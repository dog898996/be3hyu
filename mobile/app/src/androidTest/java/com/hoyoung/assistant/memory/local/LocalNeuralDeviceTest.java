package com.hoyoung.assistant.memory.local;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Executes the real Android ONNX JNI path with immutable public multilingual weights. */
@RunWith(AndroidJUnit4.class)
public final class LocalNeuralDeviceTest {
    @Test public void actualNeuralEncoderLoadsVerifiedWeightsAndMeasuresMultilingualEmbeddings() throws Exception {
        assertTrue("No physical-device fixture mutation", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ConnectivityManager connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        assertNull("Neural inference must be tested with actual offline Android connectivity", connectivity.getActiveNetwork());
        String path = InstrumentationRegistry.getArguments().getString("fixtureModelPath");
        assertNotNull("The real public model fixture is mandatory, never silently skipped", path);
        assertEquals("/data/local/tmp/hoyoung-semantic-model", path);
        File target = new File(context.getFilesDir(), "models/sentence-memory-v1");
        if (!target.isDirectory()) assertTrue(target.mkdirs());
        for (String name : new String[]{"transformer.onnx", "dense.safetensors", "vocab.txt"}) {
            File source = new File(path, name); assertTrue(source.isFile());
            Files.copy(source.toPath(), new File(target, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        long started = SystemClock.elapsedRealtime();
        LocalSemanticEncoder encoder = ((HoyoungApplication) context.getApplicationContext()).getSemanticEncoder();
        {
            encoder.prepareAsync();
            long deadline = started + 30_000;
            while (!encoder.isReady() && SystemClock.elapsedRealtime() < deadline) {
                JSONObject state = encoder.state();
                if ("failed".equals(state.optString("phase"))) fail("Native model load failed: " + state.optString("code"));
                SystemClock.sleep(100);
            }
            assertTrue("Actual Android neural encoder must finish preparation", encoder.isReady());
            assertTrue(encoder.isNeural()); assertEquals(512, encoder.dimensions());
            long readyAt = SystemClock.elapsedRealtime();
            float[] original = encoder.encode("나는 딸기 알레르기가 있어.");
            float[] paraphrase = encoder.encode("딸기를 먹으면 알레르기 반응이 나.");
            float[] english = encoder.encode("I am allergic to strawberries.");
            float[] unrelated = encoder.encode("A rocket launched into orbit.");
            for (float[] vector : new float[][]{original, paraphrase, english, unrelated}) {
                assertEquals(512, vector.length); double norm = 0;
                for (float value : vector) { assertTrue(Float.isFinite(value)); norm += value * (double) value; }
                assertEquals(1.0, norm, 0.002);
            }
            double ko = cosine(original, paraphrase), en = cosine(original, english), other = cosine(original, unrelated);
            assertTrue("Different sentences must produce different real neural vectors", other < 0.999);
            assertTrue("Korean paraphrase must rank above the unrelated public fixture", ko > other);
            assertTrue("English translation must rank above the unrelated public fixture", en > other);
            // Actual pinned Python ONNX/Rust reference values from validate-semantic-host.py.
            // This tolerance accommodates architecture float math; it is not a recall-accuracy threshold.
            assertEquals("Android Korean embedding must agree with the real host reference", 0.6992020606994629, ko, 0.01);
            assertEquals("Android cross-language embedding must agree with the real host reference", 0.815122127532959, en, 0.01);
            assertEquals("Android unrelated embedding must agree with the real host reference", 0.09281639754772186, other, 0.01);
            JSONObject metrics = new JSONObject().put("modelId", encoder.modelId()).put("dimensions", 512)
                    .put("readyMillis", readyAt - started).put("fourEmbeddingsMillis", SystemClock.elapsedRealtime() - readyAt)
                    .put("koreanParaphraseCosine", ko).put("koreanEnglishCosine", en).put("unrelatedCosine", other)
                    .put("applicationOwnedEncoder", true)
                    .put("actualHostReferenceCompared", true)
                    .put("physicalPhoneBenchmark", false).put("textSentToService", false);
            System.out.println("HOYOUNG_REAL_ANDROID_NN " + metrics);
            android.util.Log.i("HoyoungDeviceProof", "HOYOUNG_REAL_ANDROID_NN " + metrics);
        }
    }
    private static double cosine(float[] left, float[] right) {
        double value = 0; for (int index = 0; index < left.length; index++) value += left[index] * (double) right[index]; return value;
    }
}
