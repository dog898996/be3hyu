package com.hoyoung.assistant.memory;
import org.junit.Test;
import static org.junit.Assert.*;
public final class VectorCodecTest {
    @Test public void exactFloatCacheRoundTripAndCosineStayFinite(){float[] original=VectorCodec.normalize(new float[]{0.125f,-0.5f,2},3);float[] decoded=VectorCodec.decode(VectorCodec.encode(original,3),3);assertArrayEquals(original,decoded,0);assertEquals(1,VectorCodec.cosine(original,decoded),0.000001);assertEquals(-1,VectorCodec.cosine(original,new float[]{-original[0],-original[1],-original[2]}),0.000001);}
    @Test public void corruptDimensionsZeroAndNonfiniteVectorsAreRejected(){assertThrows(IllegalArgumentException.class,()->VectorCodec.normalize(new float[]{0,0},2));assertThrows(IllegalArgumentException.class,()->VectorCodec.normalize(new float[]{Float.NaN,1},2));assertThrows(IllegalArgumentException.class,()->VectorCodec.decode(new byte[5],2));assertThrows(IllegalArgumentException.class,()->VectorCodec.encode(new float[]{1},2));}
}
