package com.hoyoung.assistant.ui;

import android.content.Context;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import com.hoyoung.assistant.R;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import com.hoyoung.assistant.store.AssistantIdentity;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Originals stay unchanged. All visible avatars share bounded decoded images. */
public final class HoyoungAvatarView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader gradient;
    private static Bitmap profile;
    private Bitmap photo;
    private BitmapShader profileShader;
    private final Matrix crop=new Matrix();
    private String desiredKey="",initial="호";
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static final ExecutorService DECODER=Executors.newSingleThreadExecutor();
    private static final android.util.LruCache<String,Bitmap> CACHE=new android.util.LruCache<>(4);
    private static final HashMap<String,ArrayList<WeakReference<HoyoungAvatarView>>> LOADING=new HashMap<>();

    public HoyoungAvatarView(Context context) {
        super(context);refreshIdentity();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setMinimumWidth(ChatPalette.dp(context,42));setMinimumHeight(ChatPalette.dp(context,42));
    }
    public void refreshIdentity() {
        Context context=getContext().getApplicationContext();
        if(!(context instanceof HoyoungApplication app)||!app.isStartupReady()) {
            setContentDescription("호영 프로필 사진");setPhoto(profilePhoto(context));return;
        }
        JSONObject identity=app.getRuntime().assistantIdentity(),avatar=identity.optJSONObject("avatar");
        String name=identity.optString("name",AssistantIdentity.DEFAULT_NAME);
        initial=name.isEmpty()?"호":name.substring(0,name.offsetByCodePoints(0,1));setContentDescription(name+" 프로필 사진");
        String key=avatar.optBoolean("bundled")?"bundled":app.getFilesDir().getAbsolutePath()+":"+avatar.optString("sha256");
        if(key.equals(desiredKey)){invalidate();return;}desiredKey=key;
        if(avatar.optBoolean("bundled")){setPhoto(profilePhoto(context));return;}
        Bitmap cached=CACHE.get(key);if(cached!=null){setPhoto(cached);return;}
        // Do not briefly show the old person's photo after a rename/photo replacement.
        setPhoto(null);
        ArrayList<WeakReference<HoyoungAvatarView>> waiting=LOADING.get(key);
        if(waiting!=null){waiting.add(new WeakReference<>(this));return;}
        waiting=new ArrayList<>();waiting.add(new WeakReference<>(this));LOADING.put(key,waiting);
        DECODER.execute(()->{
            Bitmap decoded=null;try{decoded=AttachmentPreview.load(context,app.getFiles(),avatar,512);}catch(Exception unavailable){/* Explicit initials while a missing/corrupt original is unavailable. */}
            final Bitmap result=decoded;
            MAIN.post(()->{
                ArrayList<WeakReference<HoyoungAvatarView>> views=LOADING.remove(key);
                if(result!=null)CACHE.put(key,result);
                if(views!=null)for(WeakReference<HoyoungAvatarView> reference:views){HoyoungAvatarView view=reference.get();if(view!=null&&key.equals(view.desiredKey))view.setPhoto(result);}
            });
        });
    }
    private void setPhoto(Bitmap image){photo=image;profileShader=image==null?null:new BitmapShader(image,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);invalidate();}
    private static synchronized Bitmap profilePhoto(Context context){
        if(profile!=null&&!profile.isRecycled())return profile;
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;bounds.inScaled=false;
        BitmapFactory.decodeResource(context.getResources(),R.drawable.hoyoung_profile,bounds);
        BitmapFactory.Options decode=new BitmapFactory.Options();decode.inScaled=false;
        int sample=1;while(Math.max(bounds.outWidth,bounds.outHeight)/sample>512)sample*=2;decode.inSampleSize=sample;
        try{profile=BitmapFactory.decodeResource(context.getResources(),R.drawable.hoyoung_profile,decode);}catch(RuntimeException unavailable){profile=null;}
        return profile;
    }
    @Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec){setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(),widthMeasureSpec),resolveSize(getSuggestedMinimumHeight(),heightMeasureSpec));}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);gradient=new LinearGradient(0,0,w,h,ChatPalette.ACCENT,ChatPalette.ACCENT_END,Shader.TileMode.CLAMP);}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float size=Math.min(getWidth(),getHeight()),cx=getWidth()/2f,cy=getHeight()/2f;
        if(profileShader!=null&&photo!=null){float scale=Math.max(size/photo.getWidth(),size/photo.getHeight());crop.reset();crop.setScale(scale,scale);crop.postTranslate(cx-photo.getWidth()*scale/2f,cy-photo.getHeight()*scale/2f);profileShader.setLocalMatrix(crop);paint.setShader(profileShader);paint.setAlpha(255);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(cx,cy,size/2f,paint);paint.setShader(null);return;}
        paint.setShader(gradient);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(cx,cy,size/2f,paint);paint.setShader(null);
        paint.setColor(Color.WHITE);paint.setAlpha(255);paint.setTextSize(size*.43f);paint.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(initial,cx,cy-(paint.ascent()+paint.descent())/2f,paint);
    }
}
