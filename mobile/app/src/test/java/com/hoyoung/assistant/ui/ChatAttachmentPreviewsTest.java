package com.hoyoung.assistant.ui;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.InternalFiles;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Real native bitmaps and attached/clipped Android view trees; no network/model calls. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ChatAttachmentPreviewsTest {
    private Context context;private InternalFiles files;private JSONObject metadata;
    private ActivityController<Activity> controller;private Activity activity;
    private ChatAttachmentPreviews previews;private FrameLayout root;
    @Before public void setUp()throws Exception{
        context=ApplicationProvider.getApplicationContext();deleteTree(context.getFilesDir());files=new InternalFiles(context);
        Bitmap source=Bitmap.createBitmap(900,700,Bitmap.Config.ARGB_8888);source.eraseColor(Color.BLUE);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();assertTrue(source.compress(Bitmap.CompressFormat.PNG,100,bytes));source.recycle();
        JSONObject working=files.writeFile("preview.png",new ByteArrayInputStream(bytes.toByteArray()));metadata=files.freezeAttachments(new JSONArray().put(working)).getJSONObject(0);
        controller=Robolectric.buildActivity(Activity.class).setup().visible();activity=controller.get();root=new FrameLayout(activity);activity.setContentView(root);layout();
    }
    @After public void tearDown()throws Exception{if(previews!=null)previews.close();if(controller!=null)controller.pause().stop().destroy();deleteTree(context.getFilesDir());}

    @Test public void offscreenArchiveDoesNotDecodeAndScrollingReleasesOwnedBitmap()throws Exception{
        List<Bitmap> decoded=new CopyOnWriteArrayList<>();previews=recording(decoded);
        ScrollView scroll=new ScrollView(activity);LinearLayout column=new LinearLayout(activity);column.setOrientation(LinearLayout.VERTICAL);scroll.addView(column);root.addView(scroll,new FrameLayout.LayoutParams(600,400));
        List<ImageView> images=new ArrayList<>();for(int i=0;i<80;i++){ImageView image=new ImageView(activity);column.addView(image,new LinearLayout.LayoutParams(600,180));images.add(image);previews.register(image,metadata);}
        layout();previews.updateViewport();await(()->previews.diagnostics().optLong("assignedBitmaps")>=3);
        assertEquals(3,previews.diagnostics().optInt("decodeStarts"));assertEquals(80,previews.diagnostics().optInt("registered"));
        Bitmap first=displayed(images.get(0));assertNotNull(first);assertFalse(first.isRecycled());assertNull(displayed(images.get(79)));
        scroll.scrollTo(0,79*180);previews.updateViewport();await(()->displayed(images.get(79))!=null);
        assertTrue(first.isRecycled());assertNull(displayed(images.get(0)));assertTrue(previews.diagnostics().optInt("decodeStarts")<=6);
        assertTrue(previews.diagnostics().optLong("ownedThumbnailBytes")<=ChatAttachmentPreviews.MAX_THUMBNAIL_BYTES);
    }

    @Test public void saturatedViewportRetriesWithEightBindingsAndFourQueuedJobs()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger starts=new AtomicInteger();
        previews=new ChatAttachmentPreviews(context,files,(m,edge)->{if(starts.incrementAndGet()==1){entered.countDown();awaitWorker(release);}return AttachmentPreview.loadResult(context,files,m,edge);});
        List<ImageView> images=overlap(24);previews.updateViewport();idle();assertTrue(entered.await(10,TimeUnit.SECONDS));
        JSONObject busy=previews.diagnostics();assertEquals(8,busy.optInt("activeThumbnailBindings"));assertTrue(busy.optInt("queuedDecodes")<=4);assertEquals(1,starts.get());
        release.countDown();await(()->previews.diagnostics().optLong("assignedBitmaps")==8);
        assertEquals(8,starts.get());assertEquals(8,images.stream().filter(image->displayed(image)!=null).count());
        assertTrue(previews.diagnostics().optLong("ownedThumbnailBytes")<=ChatAttachmentPreviews.MAX_THUMBNAIL_BYTES);
    }

    @Test public void detachedTreeWithRemainingParentCannotReceiveLateBitmap()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);List<Bitmap> decoded=new CopyOnWriteArrayList<>();
        previews=new ChatAttachmentPreviews(context,files,(m,edge)->{entered.countDown();awaitWorker(release);AttachmentPreview.Result result=AttachmentPreview.loadResult(context,files,m,edge);if(result.ready())decoded.add(result.bitmap);return result;});
        FrameLayout bubble=new FrameLayout(activity);ImageView old=new ImageView(activity);bubble.addView(old,new FrameLayout.LayoutParams(500,300));root.addView(bubble,new FrameLayout.LayoutParams(600,400));previews.register(old,metadata);layout();idle();assertTrue(entered.await(10,TimeUnit.SECONDS));
        root.removeView(bubble);assertNotNull(old.getParent());assertFalse(old.isAttachedToWindow());release.countDown();await(()->!decoded.isEmpty()&&decoded.get(0).isRecycled());
        assertNull(displayed(old));assertEquals(0,previews.diagnostics().optLong("assignedBitmaps"));
    }

    @Test public void replacementBindingDiscardsOldResultAndDisplaysOnlyNewOne()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);List<Bitmap> decoded=new CopyOnWriteArrayList<>();AtomicInteger starts=new AtomicInteger();
        previews=new ChatAttachmentPreviews(context,files,(m,edge)->{if(starts.incrementAndGet()==1){entered.countDown();awaitWorker(release);}AttachmentPreview.Result result=AttachmentPreview.loadResult(context,files,m,edge);if(result.ready())decoded.add(result.bitmap);return result;});
        ImageView image=overlap(1).get(0);idle();assertTrue(entered.await(10,TimeUnit.SECONDS));previews.register(image,new JSONObject(metadata.toString()).put("sourceName","new binding"));release.countDown();await(()->displayed(image)!=null);
        assertEquals(2,starts.get());assertEquals(2,decoded.size());assertTrue(decoded.get(0).isRecycled());assertSame(decoded.get(1),displayed(image));assertEquals("new binding",image.getContentDescription().toString());
    }

    @Test public void clearAndCloseCancelQueuedDecodesAndRecycleActiveLateResult()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);List<Bitmap> decoded=new CopyOnWriteArrayList<>();AtomicInteger starts=new AtomicInteger();
        // Hold a completed real PNG decode before handing it to the helper. A decoder
        // may finish even after cancellation; its already-produced bitmap must be released.
        previews=new ChatAttachmentPreviews(context,files,(m,edge)->{starts.incrementAndGet();AttachmentPreview.Result result=AttachmentPreview.loadResult(context,files,m,edge);if(result.ready())decoded.add(result.bitmap);entered.countDown();awaitWorker(release);return result;});
        List<ImageView> images=overlap(24);idle();assertTrue(entered.await(10,TimeUnit.SECONDS));previews.clear();previews.close();release.countDown();await(()->!decoded.isEmpty()&&decoded.get(0).isRecycled());
        assertEquals(1,starts.get());assertEquals(0,previews.diagnostics().optInt("registered"));assertEquals(0,previews.diagnostics().optInt("queuedDecodes"));assertEquals(0,previews.diagnostics().optLong("ownedThumbnailBytes"));for(ImageView image:images)assertNull(displayed(image));
    }

    @Test public void closeInterruptingStreamingDecoderSettlesWithoutWaitingForBitmapThatMayNotExist()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);List<AttachmentPreview.Result> results=new CopyOnWriteArrayList<>();AtomicInteger starts=new AtomicInteger();
        previews=new ChatAttachmentPreviews(context,files,(m,edge)->{starts.incrementAndGet();entered.countDown();awaitWorker(release);AttachmentPreview.Result result=AttachmentPreview.loadResult(context,files,m,edge);results.add(result);return result;});
        List<ImageView> images=overlap(24);idle();assertTrue(entered.await(10,TimeUnit.SECONDS));previews.close();release.countDown();
        await(()->results.size()==1&&previews.diagnostics().optInt("runningDecodes")==0&&previews.diagnostics().optInt("queuedDecodes")==0);
        // FileChannel may abort on the restored interrupt and return source_unavailable.
        // If a platform decoder still succeeds, the cancelled helper must recycle it.
        AttachmentPreview.Result result=results.get(0);if(result.ready())assertTrue(result.bitmap.isRecycled());else assertFalse(result.status.isEmpty());
        assertEquals(1,starts.get());assertEquals(0,previews.diagnostics().optLong("assignedBitmaps"));assertEquals(0,previews.diagnostics().optLong("ownedThumbnailBytes"));assertEquals(0,previews.diagnostics().optInt("registered"));for(ImageView image:images)assertNull(displayed(image));
    }

    @Test public void allocationBudgetRejectsOversizedBitmapWithoutKeepingIt()throws Exception{
        List<Bitmap> decoded=new CopyOnWriteArrayList<>();
        previews=new ChatAttachmentPreviews(context,files,(m,edge)->{Bitmap large=Bitmap.createBitmap(2048,2048,Bitmap.Config.ARGB_8888);decoded.add(large);return result(large);});
        ImageView image=overlap(1).get(0);await(()->!decoded.isEmpty()&&decoded.get(0).isRecycled());
        assertNull(displayed(image));assertEquals(0,previews.diagnostics().optLong("ownedThumbnailBytes"));assertEquals(0,previews.diagnostics().optLong("assignedBitmaps"));assertEquals(1,previews.diagnostics().optInt("decodeStarts"));
    }

    @Test public void clearRecyclesFinishedResultBeforeItsUiCallbackRuns()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);List<Bitmap> decoded=new CopyOnWriteArrayList<>();
        previews=new ChatAttachmentPreviews(context,files,(m,edge)->{entered.countDown();awaitWorker(release);AttachmentPreview.Result result=AttachmentPreview.loadResult(context,files,m,edge);if(result.ready())decoded.add(result.bitmap);return result;});
        ImageView image=overlap(1).get(0);assertTrue(entered.await(10,TimeUnit.SECONDS));release.countDown();
        // Leave the main looper paused after scheduling; the photo worker can finish
        // and queue its callback while this UI thread is about to replace the tree.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while((decoded.isEmpty()||previews.diagnostics().optInt("runningDecodes")!=0)&&System.nanoTime()<deadline)Thread.sleep(10);
        assertEquals(1,decoded.size());assertFalse(decoded.get(0).isRecycled());assertNull(displayed(image));
        previews.clear();assertTrue("Pending callback bitmap must be released immediately",decoded.get(0).isRecycled());
        idle();assertNull(displayed(image));assertEquals(0,previews.diagnostics().optLong("assignedBitmaps"));
    }

    @Test public void aggregateAllocationBudgetIsEnforcedAcrossVisibleBindings()throws Exception{
        List<Bitmap> decoded=new CopyOnWriteArrayList<>();
        previews=new ChatAttachmentPreviews(context,files,(m,edge)->{Bitmap large=Bitmap.createBitmap(1024,1024,Bitmap.Config.ARGB_8888);decoded.add(large);return result(large);});
        List<ImageView> images=overlap(8);await(()->previews.diagnostics().optInt("decodeStarts")==8&&previews.diagnostics().optInt("runningDecodes")==0&&previews.diagnostics().optInt("queuedDecodes")==0);
        idle();assertEquals(3,images.stream().filter(image->displayed(image)!=null).count());assertEquals(12L*1024*1024,previews.diagnostics().optLong("ownedThumbnailBytes"));
        assertEquals(5,decoded.stream().filter(Bitmap::isRecycled).count());previews.clear();for(Bitmap bitmap:decoded)assertTrue(bitmap.isRecycled());
    }

    @Test public void expandedOnlyDeliversLatestAndClearInvalidatesLateCallback()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger starts=new AtomicInteger(),firstCallbacks=new AtomicInteger();List<Bitmap> decoded=new CopyOnWriteArrayList<>(),delivered=new CopyOnWriteArrayList<>();
        previews=new ChatAttachmentPreviews(context,files,(m,edge)->{if(starts.incrementAndGet()==1){entered.countDown();awaitWorker(release);}AttachmentPreview.Result result=AttachmentPreview.loadResult(context,files,m,edge);if(result.ready())decoded.add(result.bitmap);return result;});
        previews.loadExpanded(metadata,r->firstCallbacks.incrementAndGet());assertTrue(entered.await(10,TimeUnit.SECONDS));previews.loadExpanded(metadata,r->delivered.add(r.bitmap));release.countDown();await(()->delivered.size()==1);
        assertEquals(0,firstCallbacks.get());assertEquals(2,decoded.size());assertTrue(decoded.get(0).isRecycled());assertFalse(delivered.get(0).isRecycled());
        previews.clear();assertFalse("Expanded result ownership belongs to dialog caller",delivered.get(0).isRecycled());delivered.get(0).recycle();
        CountDownLatch secondEntered=new CountDownLatch(1),secondRelease=new CountDownLatch(1);previews.close();
        previews=new ChatAttachmentPreviews(context,files,(m,edge)->{secondEntered.countDown();awaitWorker(secondRelease);AttachmentPreview.Result result=AttachmentPreview.loadResult(context,files,m,edge);if(result.ready())decoded.add(result.bitmap);return result;});
        previews.loadExpanded(metadata,r->firstCallbacks.incrementAndGet());assertTrue(secondEntered.await(10,TimeUnit.SECONDS));previews.clear();secondRelease.countDown();await(()->decoded.size()==3&&decoded.get(2).isRecycled());assertEquals(0,firstCallbacks.get());
    }

    private ChatAttachmentPreviews recording(List<Bitmap> decoded){return new ChatAttachmentPreviews(context,files,(m,edge)->{AttachmentPreview.Result result=AttachmentPreview.loadResult(context,files,m,edge);if(result.ready())decoded.add(result.bitmap);return result;});}
    private List<ImageView> overlap(int count){List<ImageView> result=new ArrayList<>();for(int i=0;i<count;i++){ImageView image=new ImageView(activity);root.addView(image,new FrameLayout.LayoutParams(500,300));previews.register(image,metadata);result.add(image);}layout();return result;}
    private void layout(){View decor=activity.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));decor.layout(0,0,800,600);idle();}
    private static Bitmap displayed(ImageView image){return image.getDrawable()instanceof BitmapDrawable?((BitmapDrawable)image.getDrawable()).getBitmap():null;}
    private static AttachmentPreview.Result result(Bitmap bitmap){try{java.lang.reflect.Constructor<AttachmentPreview.Result> constructor=AttachmentPreview.Result.class.getDeclaredConstructor(Bitmap.class,String.class,String.class);constructor.setAccessible(true);return constructor.newInstance(bitmap,"ready","");}catch(Exception failed){throw new IllegalStateException(failed);}}
    private static void awaitWorker(CountDownLatch latch){boolean interrupted=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(latch.getCount()!=0&&System.nanoTime()<deadline){try{latch.await(100,TimeUnit.MILLISECONDS);}catch(InterruptedException cancellation){interrupted=true;}}if(interrupted)Thread.currentThread().interrupt();}
    private static void idle(){Shadows.shadowOf(Looper.getMainLooper()).idle();}
    private static void await(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<deadline){idle();if(condition.getAsBoolean())return;Thread.sleep(10);}idle();assertTrue("Timed out waiting for real photo worker",condition.getAsBoolean());}
    private static void deleteTree(File root)throws Exception{if(!root.exists())return;if(Files.isSymbolicLink(root.toPath())){assertTrue(root.delete());return;}if(root.isDirectory()){File[] children=root.listFiles();assertNotNull(children);for(File child:children)deleteTree(child);}assertTrue(root.delete());}
}
