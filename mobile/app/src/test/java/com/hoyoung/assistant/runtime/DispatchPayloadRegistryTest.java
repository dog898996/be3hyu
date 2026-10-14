package com.hoyoung.assistant.runtime;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public final class DispatchPayloadRegistryTest {
    private static JSONObject payload() throws Exception {
        return new JSONObject().put("requestId","request-42").put("text","옛 약속을 확인해 줘").put("requiredModel","gpt-6.1-sol")
                .put("requiredEffort","medium").put("requiredSpeed","fast").put("requiresNativeAuthorization",true)
                .put("attachments",new JSONArray().put(new JSONObject().put("name","photo.jpg").put("dataBase64","YWJj").put("sha256","source-hash")));
    }
    @Test public void finalTextModelsBytesAndArrayOrderAreDigestBound() throws Exception {
        JSONObject original=payload();String digest=DispatchPayloadRegistry.digest(original);
        for(String key:new String[]{"text","requiredModel","requiredEffort","requiredSpeed"}) {
            JSONObject changed=new JSONObject(original.toString()).put(key,"changed");assertNotEquals(digest,DispatchPayloadRegistry.digest(changed));
        }
        JSONObject changed=new JSONObject(original.toString());changed.getJSONArray("attachments").getJSONObject(0).put("dataBase64","YWJk");assertNotEquals(digest,DispatchPayloadRegistry.digest(changed));
        JSONObject reversed=new JSONObject().put("requiresNativeAuthorization",true).put("requiredSpeed","fast").put("requiredEffort","medium").put("requiredModel","gpt-6.1-sol")
                .put("text","옛 약속을 확인해 줘").put("requestId","request-42").put("attachments",original.getJSONArray("attachments"));
        assertEquals(digest,DispatchPayloadRegistry.digest(reversed));
    }
    @Test public void admissionIsOneUseAndRequiresExactMemoryVersion() throws Exception {
        DispatchPayloadRegistry registry=new DispatchPayloadRegistry();String digest=registry.register("request-42",payload(),"memory-revision-7",100);
        assertFalse(registry.consume("request-42",digest,"memory-revision-8",101));
        assertFalse(registry.consume("request-42","different", "memory-revision-7",101));
        assertTrue(registry.consume("request-42",digest,"memory-revision-7",102));
        assertFalse(registry.consume("request-42",digest,"memory-revision-7",103));
        assertThrows(IllegalStateException.class,()->registry.register("request-42",payload(),"memory-revision-7",104));
    }
    @Test public void expiredPermitCannotBeRevivedByAnEarlierClock() throws Exception {
        DispatchPayloadRegistry registry=new DispatchPayloadRegistry();String digest=registry.register("request-42",payload(),"memory",1000);
        assertFalse(registry.consume("request-42",digest,"memory",999));
        assertFalse(registry.consume("request-42",digest,"memory",601001));
        assertFalse(registry.consume("request-42",digest,"memory",1001));
    }
    @Test public void sameIdCannotReplaceRegisteredTextOrMemory() throws Exception {
        DispatchPayloadRegistry registry=new DispatchPayloadRegistry();registry.register("request-42",payload(),"memory",1000);
        assertThrows(IllegalStateException.class,()->registry.register("request-42",payload().put("text","다른 요청"),"memory",1001));
        assertThrows(IllegalStateException.class,()->registry.register("request-42",payload(),"different-memory",1001));
        assertThrows(IllegalArgumentException.class,()->registry.register("different-id",payload(),"memory",1001));
    }
    @Test public void distinctUnpairedUnicodeOriginalsHaveDistinctPayloadDigests() throws Exception {
        JSONObject one=payload().put("text","원문\ud800"),two=payload().put("text","원문\ud801");
        assertNotEquals(DispatchPayloadRegistry.digest(one),DispatchPayloadRegistry.digest(two));
        assertTrue(DispatchPayloadRegistry.canonical(one).contains("\\ud800"));
        assertEquals(DispatchPayloadRegistry.digest(payload().put("text","사진 \ud83d\ude80")),DispatchPayloadRegistry.digest(payload().put("text","사진 🚀")));
    }
    @Test public void streamingDigestMatchesExistingCanonicalForEveryUtf16UnitAndNestedScalars() throws Exception {
        char[] units=new char[65536];for(int i=0;i<units.length;i++)units[i]=(char)i;
        JSONObject source=payload().put("\ud801-key",new String(units)).put("array",new JSONArray()
                .put(JSONObject.NULL).put(true).put(false).put(0).put(-0.0d).put(1.25d).put(Long.MAX_VALUE)
                .put(new JSONObject().put("/","</\"\\\b\t\n\f\r\u0080\u2000\ud800\udc01\ud801")));
        assertEquals(DispatchPayloadRegistry.hash(DispatchPayloadRegistry.canonical(source)),DispatchPayloadRegistry.digest(source));
    }
    @Test public void quotingBoundariesPreserveSlashContextPairsAndUnpairedUnits() throws Exception {
        String[] values={"a".repeat(8191)+"</tail", "a".repeat(8191)+"\ud83d\ude80tail",
                "a".repeat(8191)+"\ud800\ud801tail", "a".repeat(16383)+"\udc00tail",
                "a".repeat(8191)+"\\\"\n/", ""};
        for(String value:values) {
            JSONObject source=payload().put("text",value).put("b".repeat(8191)+"</", "key-boundary");
            assertEquals(DispatchPayloadRegistry.hash(DispatchPayloadRegistry.canonical(source)),DispatchPayloadRegistry.digest(source));
        }
    }
    @Test public void nativePreparedDigestIsSchemaBoundAndCannotReopenConsumedAdmission() throws Exception {
        DispatchPayloadRegistry registry=new DispatchPayloadRegistry();
        DispatchPayloadRegistry.PreparedDigest prepared=DispatchPayloadRegistry.prepare("request-42",payload());
        String digest=registry.registerPrepared(prepared,"memory",10);
        assertEquals(DispatchPayloadRegistry.digest(payload()),digest);
        assertTrue(registry.consume("request-42",digest,"memory",11));
        assertThrows(IllegalStateException.class,()->registry.registerPrepared(prepared,"memory",12));
        assertThrows(IllegalArgumentException.class,()->DispatchPayloadRegistry.prepare("request-42",null));
        assertThrows(IllegalArgumentException.class,()->DispatchPayloadRegistry.prepare("42",new JSONObject().put("requestId",42)));
        assertThrows(IllegalArgumentException.class,()->DispatchPayloadRegistry.prepare("",new JSONObject().put("requestId","")));
        assertThrows(IllegalArgumentException.class,()->DispatchPayloadRegistry.prepare("request-42",payload().put("nativePayloadDigest","injected")));
        assertThrows(IllegalArgumentException.class,()->registry.registerPrepared(null,"memory",12));
    }
    @Test public void unsupportedAndNonFiniteValuesNeverReceiveADigest() throws Exception {
        for(Object invalid:new Object[]{new Object(),Double.NaN,Double.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}) {
            JSONObject source=new JSONObject(){@Override public Object opt(String key){return "invalid".equals(key)?invalid:super.opt(key);}};
            source.put("invalid",0);
            assertThrows(IllegalArgumentException.class,()->DispatchPayloadRegistry.digest(source));
            assertThrows(IllegalArgumentException.class,()->DispatchPayloadRegistry.canonical(source));
        }
    }
    @Test public void slowPayloadPreparationDoesNotHoldTheRegistryMonitor() throws Exception {
        DispatchPayloadRegistry registry=new DispatchPayloadRegistry();
        String existing=registry.register("request-42",payload(),"memory",0);
        CountDownLatch hashing=new CountDownLatch(1),release=new CountDownLatch(1);
        Number delayed=new Number() {
            public int intValue(){return 1;} public long longValue(){return 1;}
            public float floatValue(){return 1;} public double doubleValue(){return 1;}
            @Override public String toString(){hashing.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Hash preparation timed out");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(error);}return "1";}
        };
        JSONObject source=payload().put("requestId","second-request").put("delayed",delayed);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            Future<String> preparation=pool.submit(()->registry.register("second-request",source,"memory",1));
            assertTrue(hashing.await(2,TimeUnit.SECONDS));
            Future<Boolean> consume=pool.submit(()->registry.consume("request-42",existing,"memory",2));
            assertTrue("An existing native admission must remain responsive during hashing",consume.get(1,TimeUnit.SECONDS));
            release.countDown();assertEquals(64,preparation.get(2,TimeUnit.SECONDS).length());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test public void twentyMiBPayloadHashesInsideFortyMiBHeap() throws Exception {
        String java=new java.io.File(System.getProperty("java.home"),"bin/java").getAbsolutePath();
        Process process=new ProcessBuilder(java,"-Xmx40m","-cp",System.getProperty("java.class.path"),LargePayloadProbe.class.getName())
                .redirectErrorStream(true).start();
        boolean completed=process.waitFor(30,TimeUnit.SECONDS);
        if(!completed)process.destroyForcibly();
        String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertTrue("Bounded-heap payload hashing timed out: "+output,completed);
        assertEquals("Bounded-heap payload hashing failed: "+output,0,process.exitValue());
        assertTrue(output,output.contains("streaming-20MiB-ok"));
    }
    public static final class LargePayloadProbe {
        public static void main(String[] args) throws Exception {
            int count=20*1024*1024;
            JSONObject source=new JSONObject().put("requestId","large").put("text","A".repeat(count));
            DispatchPayloadRegistry.PreparedDigest prepared=DispatchPayloadRegistry.prepare("large",source);
            MessageDigest reference=MessageDigest.getInstance("SHA-256");
            reference.update("{\"requestId\":\"large\",\"text\":\"".getBytes(StandardCharsets.UTF_8));
            byte[] block=new byte[8192];Arrays.fill(block,(byte)'A');
            for(int remaining=count;remaining>0;remaining-=block.length)reference.update(block,0,Math.min(remaining,block.length));
            reference.update("\"}".getBytes(StandardCharsets.UTF_8));
            StringBuilder expected=new StringBuilder(64);
            for(byte b:reference.digest())expected.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            if(!expected.toString().equals(prepared.digest))throw new AssertionError("Streaming canonical bytes differed");
            DispatchPayloadRegistry registry=new DispatchPayloadRegistry();
            String actual=registry.registerPrepared(prepared,"memory",0);
            if(!registry.consume("large",actual,"memory",1))throw new AssertionError("Prepared native admission failed");
            System.out.println("streaming-20MiB-ok "+actual);
        }
    }
}
