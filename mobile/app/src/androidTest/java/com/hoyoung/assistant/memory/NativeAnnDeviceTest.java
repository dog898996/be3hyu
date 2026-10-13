package com.hoyoung.assistant.memory;

import android.content.Context;
import android.util.Log;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.Random;
import static org.junit.Assert.*;

/** Actual packaged Android JNI execution; host timing and model quality are separate evidence. */
@RunWith(AndroidJUnit4.class)
public final class NativeAnnDeviceTest {
    @Test public void packagedJniSupportsExactRerankFilteringAndVerifiedPersistence()throws Exception{
        assertTrue("This fixture runs only on the CI AOSP emulator",android.os.Build.HARDWARE.contains("ranchu")||android.os.Build.HARDWARE.contains("goldfish"));
        assertTrue("Packaged JNI must load on both supported Android ABIs",NativeAnnIndex.isAvailable());
        Context context=ApplicationProvider.getApplicationContext();File directory=new File(context.getCacheDir(),"ann-device-proof-"+System.nanoTime());
        int count=256,dimension=512;float[] vectors=new float[count*dimension];long[] labels=new long[count];Random random=new Random(44129);
        for(int i=0;i<count;i++){labels[i]=i+1;for(int d=0;d<dimension;d++)vectors[i*dimension+d]=(float)random.nextGaussian();}
        float[] query=java.util.Arrays.copyOfRange(vectors,117*dimension,118*dimension);String fingerprint="a".repeat(64);
        try(NativeAnnIndex graph=new NativeAnnIndex("android-jni-512d-proof-v1",dimension,count)){
            long started=System.nanoTime();graph.addBatch(labels,vectors);assertEquals(count,graph.count());
            NativeAnnIndex.SearchResult hits=graph.search(query,20,512);assertEquals(118,hits.labels[0]);assertTrue(hits.similarities[0]>.9999f);
            graph.setExcludedLabels(new long[]{118});NativeAnnIndex.SearchResult denied=graph.search(query,20,512);assertEquals(20,denied.labels.length);for(long label:denied.labels)assertNotEquals(118,label);
            graph.setExcludedLabels(null);JSONObject manifest=graph.saveVerified(directory,fingerprint);
            try(NativeAnnIndex restored=new NativeAnnIndex("android-jni-512d-proof-v1",dimension,1)){
                assertFalse(restored.loadVerified(directory,"b".repeat(64)));assertTrue(restored.loadVerified(directory,fingerprint));
                NativeAnnIndex.SearchResult replay=restored.search(query,20,512);assertArrayEquals(hits.labels,replay.labels);assertArrayEquals(hits.similarities,replay.similarities,0);
            }
            float[] bad=query.clone();bad[0]=Float.NaN;assertThrows(IllegalStateException.class,()->graph.addBatch(new long[]{1000},bad));assertEquals(count,graph.count());
            File cache=new File(directory,manifest.getString("file"));try(RandomAccessFile corrupt=new RandomAccessFile(cache,"rw")){corrupt.seek(64);int value=corrupt.read();corrupt.seek(64);corrupt.write(value^1);}
            try(NativeAnnIndex restored=new NativeAnnIndex("android-jni-512d-proof-v1",dimension,1)){assertThrows(java.io.IOException.class,()->restored.loadVerified(directory,fingerprint));}
            JSONObject proof=new JSONObject();proof.put("actualAndroidJniExecuted",true);proof.put("vectors",count);proof.put("dimensions",dimension);proof.put("querySelfTop1",true);proof.put("excludedLabelAbsent",true);proof.put("shaCacheRoundTrip",true);proof.put("corruptCacheRejected",true);proof.put("nonfiniteVectorRejected",true);proof.put("elapsedMillis",(System.nanoTime()-started)/1_000_000);proof.put("fullNeuralQualityMeasured",false);proof.put("realUserPhoneMeasured",false);Log.i("HoyoungDeviceProof","HOYOUNG_REAL_ANDROID_ANN "+proof);
        }finally{remove(directory);}
    }
    private static void remove(File file){File[] children=file.listFiles();if(children!=null)for(File child:children)remove(child);file.delete();}
}
