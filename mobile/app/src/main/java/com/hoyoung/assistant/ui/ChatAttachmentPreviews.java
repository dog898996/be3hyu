package com.hoyoung.assistant.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.view.View;
import android.widget.ImageView;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** One private photo worker, visible-only decoding, and explicit bitmap ownership.
 * Call UI methods on the main thread. It never uses the activity's command/file IO executor.
 * Expanded results transfer ownership to the caller: clear the drawable and recycle on dismissal. */
public final class ChatAttachmentPreviews implements AutoCloseable {
    public static final int MAX_ACTIVE_THUMBNAILS=8,MAX_QUEUED_DECODES=4;
    public static final long MAX_THUMBNAIL_BYTES=12L*1024*1024;
    private final Decoder decoder;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LinkedBlockingDeque<Runnable> queue=new LinkedBlockingDeque<>(MAX_QUEUED_DECODES);
    private final ThreadPoolExecutor photos;
    private final Map<ImageView,Binding> bindings=new LinkedHashMap<>();
    private final Object pendingLock=new Object();
    private final Map<DecodeJob,AttachmentPreview.Result> pendingResults=new IdentityHashMap<>();
    private final AtomicInteger decodeStarts=new AtomicInteger();
    private final AtomicLong discardedBitmaps=new AtomicLong();
    private volatile boolean closed;
    private volatile long epoch=1,expandedSerial;
    private long nextOrder,bitmapBytes,assignedBitmaps;
    private boolean updateScheduled;
    private DecodeJob expanded;
    private final Runnable viewportTask=()->{updateScheduled=false;refreshNow();};

    interface Decoder {AttachmentPreview.Result load(JSONObject metadata,int edge);}
    public ChatAttachmentPreviews(Context context,InternalFiles files){
        this(context,files,(metadata,edge)->AttachmentPreview.loadResult(context.getApplicationContext(),files,metadata,edge));
    }
    ChatAttachmentPreviews(Context context,InternalFiles files,Decoder decoder){
        requireUi();if(context==null||files==null||decoder==null)throw new IllegalArgumentException("Photo preview dependencies are missing");
        this.decoder=decoder;
        photos=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,queue,task->{Thread worker=new Thread(()->{Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);task.run();},"hoyoung-photo-preview");worker.setDaemon(true);return worker;});
        photos.prestartCoreThread();
    }
    public void register(ImageView image,JSONObject metadata){
        requireUi();if(closed)return;if(image==null||metadata==null)throw new IllegalArgumentException("Missing image binding");
        Binding previous=bindings.remove(image);if(previous!=null)release(previous);
        JSONObject frozen;try{frozen=new JSONObject(metadata.toString());}catch(Exception malformed){throw new IllegalArgumentException("Invalid image metadata",malformed);}
        Binding binding=new Binding(image,frozen,epoch,nextOrder++);bindings.put(image,binding);
        image.addOnAttachStateChangeListener(binding.attachListener);image.addOnLayoutChangeListener(binding.layoutListener);
        image.setImageDrawable(new StatusDrawable("사진",image));image.setContentDescription(binding.name);
        updateViewport();
    }
    /** Coalesces scroll/layout updates; only clipped, attached image rectangles are eligible. */
    public void updateViewport(){requireUi();if(!closed&&!updateScheduled){updateScheduled=true;main.post(viewportTask);}}
    private void refreshNow(){
        if(closed)return;
        List<Binding> visible=new ArrayList<>();Rect rectangle=new Rect(),window=new Rect();
        for(Binding binding:bindings.values()){
            ImageView view=binding.image.get();
            if(isVisible(view,rectangle)){view.getWindowVisibleDisplayFrame(window);binding.priority=Math.abs(rectangle.centerY()-window.centerY());visible.add(binding);}
        }
        visible.sort(Comparator.comparingInt((Binding b)->b.priority).thenComparingLong(b->b.order));
        Map<Binding,Boolean> selected=new IdentityHashMap<>();for(int i=0;i<Math.min(MAX_ACTIVE_THUMBNAILS,visible.size());i++)selected.put(visible.get(i),true);
        for(Binding binding:bindings.values()){
            boolean wanted=selected.containsKey(binding);binding.wanted=wanted;
            if(!wanted){cancel(binding);releaseBitmap(binding);}
        }
        for(int i=0;i<Math.min(MAX_ACTIVE_THUMBNAILS,visible.size());i++){
            Binding binding=visible.get(i);
            if(binding.bitmap==null&&binding.job==null&&!binding.failed){
                DecodeJob job=new DecodeJob(binding,epoch);binding.job=job;
                ImageView image=binding.image.get();if(image!=null)image.setImageDrawable(new StatusDrawable("불러오는 중",image));
                if(!enqueue(job,false))binding.job=null;
            }
        }
    }
    /** Expanded requests get queue priority, while an active decode finishes normally.
     * Only the latest request in this render session can call back. The caller owns a ready bitmap. */
    public void loadExpanded(JSONObject metadata,Consumer<AttachmentPreview.Result> callback){
        requireUi();if(closed)return;if(metadata==null||callback==null)throw new IllegalArgumentException("Missing expanded preview request");
        if(expanded!=null){expanded.cancelled=true;queue.remove(expanded);}
        JSONObject frozen;try{frozen=new JSONObject(metadata.toString());}catch(Exception invalid){throw new IllegalArgumentException(invalid);}
        DecodeJob job=new DecodeJob(frozen,callback,epoch,++expandedSerial);expanded=job;
        if(!enqueue(job,true)){expanded=null;throw new IllegalStateException("Photo preview worker is closed");}
    }
    private boolean enqueue(DecodeJob job,boolean priority){
        if(closed||photos.isShutdown())return false;
        if(priority){
            if(queue.offerFirst(job))return true;
            Runnable evicted=queue.pollLast();if(evicted instanceof DecodeJob){DecodeJob old=(DecodeJob)evicted;old.cancelled=true;if(old.binding!=null&&old.binding.job==old)old.binding.job=null;}
            return queue.offerFirst(job);
        }
        try{photos.execute(job);return true;}catch(RejectedExecutionException saturated){return false;}
    }
    private void complete(DecodeJob job,AttachmentPreview.Result result){
        requireUi();synchronized(pendingLock){pendingResults.remove(job);}
        if(job.binding!=null){
            Binding binding=job.binding;if(binding.job==job)binding.job=null;
            ImageView image=binding.image.get();
            boolean live=!closed&&!job.cancelled&&job.epoch==epoch&&binding.epoch==epoch&&bindings.get(image)==binding&&binding.wanted&&isVisible(image,new Rect());
            if(!live){discard(result);}
            else if(result==null){binding.failed=true;image.setImageDrawable(new StatusDrawable("사진 확인 필요",image));}
            else if(!result.ready()||result.bitmap.isRecycled()){
                binding.failed=true;image.setImageDrawable(new StatusDrawable("사진 확인 필요",image));image.setContentDescription(binding.name+" · "+result.detail);discard(result);
            }else{
                long bytes=allocation(result.bitmap);
                if(bytes>MAX_THUMBNAIL_BYTES||bitmapBytes+bytes>MAX_THUMBNAIL_BYTES){binding.failed=true;image.setImageDrawable(new StatusDrawable("사진 확인 필요",image));discard(result);}
                else{releaseBitmap(binding);binding.bitmap=result.bitmap;binding.bytes=bytes;bitmapBytes+=bytes;assignedBitmaps++;image.setImageBitmap(binding.bitmap);image.setContentDescription(binding.name+(result.detail.isEmpty()?"":" · "+result.detail));}
            }
        }else{
            if(expanded==job)expanded=null;
            boolean live=!closed&&!job.cancelled&&job.epoch==epoch&&job.expandedNumber==expandedSerial;
            if(!live)discard(result);
            else if(result!=null){try{job.callback.accept(result);}catch(RuntimeException uiFailure){discard(result);}}
        }
        updateViewport();
    }
    private void cancel(Binding binding){DecodeJob job=binding.job;if(job!=null){job.cancelled=true;queue.remove(job);binding.job=null;}}
    private void releaseBitmap(Binding binding){
        Bitmap bitmap=binding.bitmap;if(bitmap==null)return;ImageView image=binding.image.get();if(image!=null)image.setImageDrawable(new StatusDrawable("사진",image));
        binding.bitmap=null;bitmapBytes=Math.max(0,bitmapBytes-binding.bytes);binding.bytes=0;if(!bitmap.isRecycled())bitmap.recycle();
    }
    private void release(Binding binding){
        binding.wanted=false;cancel(binding);releaseBitmap(binding);ImageView view=binding.image.get();if(view!=null){view.removeOnAttachStateChangeListener(binding.attachListener);view.removeOnLayoutChangeListener(binding.layoutListener);}
    }
    /** Call before replacing the message tree, changing tabs, or destroying the activity. */
    public void clear(){
        requireUi();epoch++;main.removeCallbacks(viewportTask);updateScheduled=false;
        for(Binding binding:bindings.values())release(binding);bindings.clear();
        if(expanded!=null){expanded.cancelled=true;queue.remove(expanded);expanded=null;}expandedSerial++;
        for(Runnable task:queue.toArray(new Runnable[0]))if(task instanceof DecodeJob){((DecodeJob)task).cancelled=true;queue.remove(task);}
        synchronized(pendingLock){for(AttachmentPreview.Result result:pendingResults.values())discard(result);pendingResults.clear();}
    }
    public void close(){requireUi();if(!closed){closed=true;clear();photos.shutdownNow();}}
    public JSONObject diagnostics(){
        requireUi();int wanted=0;for(Binding b:bindings.values())if(b.wanted)wanted++;
        JSONObject d=new JSONObject();put(d,"registered",bindings.size());put(d,"activeThumbnailBindings",wanted);put(d,"ownedThumbnailBytes",bitmapBytes);put(d,"queuedDecodes",queue.size());put(d,"runningDecodes",photos.getActiveCount());put(d,"decodeStarts",decodeStarts.get());put(d,"assignedBitmaps",assignedBitmaps);put(d,"discardedBitmaps",discardedBitmaps.get());put(d,"closed",closed);return d;
    }
    private void discard(AttachmentPreview.Result result){if(result!=null&&result.bitmap!=null&&!result.bitmap.isRecycled()){result.bitmap.recycle();discardedBitmaps.incrementAndGet();}}
    private static long allocation(Bitmap bitmap){try{return bitmap.getAllocationByteCount();}catch(RuntimeException unavailable){return (long)bitmap.getRowBytes()*bitmap.getHeight();}}
    private static boolean isVisible(ImageView image,Rect rectangle){return image!=null&&image.isAttachedToWindow()&&image.isShown()&&image.getAlpha()>0&&image.getGlobalVisibleRect(rectangle)&&rectangle.width()>0&&rectangle.height()>0;}
    private static void requireUi(){if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Photo bindings belong to the UI thread");}
    private static void put(JSONObject json,String key,Object value){try{json.put(key,value);}catch(Exception impossible){throw new IllegalStateException(impossible);}}

    private final class Binding{
        final WeakReference<ImageView> image;final JSONObject metadata;final String name;final long epoch,order;
        volatile boolean wanted;boolean failed;int priority;Bitmap bitmap;long bytes;DecodeJob job;
        final View.OnAttachStateChangeListener attachListener=new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View view){updateViewport();}public void onViewDetachedFromWindow(View view){wanted=false;cancel(Binding.this);releaseBitmap(Binding.this);updateViewport();}};
        final View.OnLayoutChangeListener layoutListener=(v,l,t,r,b,ol,ot,or,ob)->updateViewport();
        Binding(ImageView image,JSONObject metadata,long epoch,long order){this.image=new WeakReference<>(image);this.metadata=metadata;this.epoch=epoch;this.order=order;name=metadata.optString("sourceName",metadata.optString("name","사진"));}
    }
    private final class DecodeJob implements Runnable{
        final Binding binding;final JSONObject metadata;final Consumer<AttachmentPreview.Result> callback;final long epoch,expandedNumber;volatile boolean cancelled;
        DecodeJob(Binding binding,long epoch){this.binding=binding;this.metadata=binding.metadata;this.callback=null;this.epoch=epoch;expandedNumber=0;}
        DecodeJob(JSONObject metadata,Consumer<AttachmentPreview.Result> callback,long epoch,long expandedNumber){this.binding=null;this.metadata=metadata;this.callback=callback;this.epoch=epoch;this.expandedNumber=expandedNumber;}
        boolean needed(){return !closed&&!cancelled&&epoch==ChatAttachmentPreviews.this.epoch&&(binding==null?expandedNumber==expandedSerial:binding.wanted);}
        public void run(){
            AttachmentPreview.Result result=null;
            if(needed()){decodeStarts.incrementAndGet();try{result=decoder.load(metadata,binding==null?AttachmentPreview.EXPANDED_SIZE:AttachmentPreview.THUMBNAIL_SIZE);}catch(RuntimeException|OutOfMemoryError unavailable){/* Explicit UI placeholder; source archive is unchanged. */}}
            synchronized(pendingLock){if(!needed()){discard(result);result=null;}else if(result!=null)pendingResults.put(this,result);}
            final AttachmentPreview.Result completed=result;
            if(!main.post(()->complete(this,completed))){synchronized(pendingLock){pendingResults.remove(this);discard(completed);}}
        }
    }
    private static final class StatusDrawable extends Drawable{
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final String text;
        StatusDrawable(String text,ImageView image){this.text=text;paint.setTextSize(12*image.getResources().getDisplayMetrics().scaledDensity);paint.setTextAlign(Paint.Align.CENTER);}
        public void draw(Canvas canvas){Rect r=getBounds();paint.setColor(Color.rgb(234,238,245));canvas.drawRect(r,paint);paint.setColor(Color.rgb(92,108,130));canvas.drawText(text,r.centerX(),r.centerY()-(paint.ascent()+paint.descent())/2,paint);}
        public void setAlpha(int alpha){paint.setAlpha(alpha);}public void setColorFilter(android.graphics.ColorFilter filter){paint.setColorFilter(filter);}public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}
    }
}
