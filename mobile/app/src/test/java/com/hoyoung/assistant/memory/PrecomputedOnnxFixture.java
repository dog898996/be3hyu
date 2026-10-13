package com.hoyoung.assistant.memory;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import com.hoyoung.assistant.store.HoyoungStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/** Test-only seam backed by real public-input ONNX output, not synthetic semantic vectors.
 * Host production of the fixture is measured separately from Android runtime/tokenizer parity. */
final class PrecomputedOnnxFixture implements SemanticEncoder,AutoCloseable {
    final JSONObject manifest;private final RandomAccessFile vectors;private final Map<String,Integer> queries=new HashMap<>();
    PrecomputedOnnxFixture(String path)throws Exception{
        Path metadata=Paths.get(path);manifest=new JSONObject(new String(Files.readAllBytes(metadata),StandardCharsets.UTF_8));
        if(!"real-local-onnx-cpu-public-input-only".equals(manifest.getString("encoder"))||manifest.getInt("dimensions")!=512)throw new IllegalArgumentException("Not an actual local ONNX fixture");
        JSONArray files=manifest.getJSONArray("modelFiles");boolean verifiedTransformer=false;
        for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);if("transformer.onnx".equals(file.getString("name")))verifiedTransformer="c78c64bb0446ceb7086d2de24c2f4297e7a909eb457b4f68e5fdb6ab19f285c0".equals(file.getString("sha256"));}
        if(!verifiedTransformer)throw new IllegalArgumentException("Unexpected model weights");
        String binaryName=manifest.getString("vectorFile");if(!binaryName.matches("[A-Za-z0-9_.-]+"))throw new IllegalArgumentException("Invalid vector sidecar name");Path binary=metadata.getParent().resolve(binaryName);
        if(!sha(Files.readAllBytes(binary)).equals(manifest.getString("vectorFileSha256")))throw new IllegalArgumentException("Vector fixture hash mismatch");
        vectors=new RandomAccessFile(binary.toFile(),"r");if(vectors.length()!=manifest.getLong("vectorCount")*512*4)throw new IllegalArgumentException("Invalid vector sidecar length");
        JSONArray rows=manifest.getJSONArray("queries");for(int i=0;i<rows.length();i++){JSONObject query=rows.getJSONObject(i);queries.put(query.getString("query"),query.getInt("vectorIndex"));}
    }
    public String modelId(){return manifest.optString("modelId");}public int dimensions(){return 512;}public boolean isReady(){return true;}public boolean isNeural(){return true;}
    public float[] encode(String text)throws Exception{Integer index=queries.get(text);if(index==null)throw new IllegalArgumentException("Query was not actually measured by the host encoder");return VectorCodec.normalize(VectorCodec.decode(bytes(index),512),512);}
    synchronized byte[] bytes(int index)throws java.io.IOException{if(index<0||index>=manifest.optInt("vectorCount"))throw new IllegalArgumentException("Invalid vector index");byte[] bytes=new byte[512*4];vectors.seek((long)index*bytes.length);vectors.readFully(bytes);VectorCodec.normalize(VectorCodec.decode(bytes,512),512);return bytes;}
    int populate(HoyoungStore store,Map<String,Long> originals)throws Exception{
        JSONArray sources=manifest.getJSONArray("sources");int[] count={0};
        store.withTransaction(db->{try{for(int i=0;i<sources.length();i++){
            JSONObject measured=sources.getJSONObject(i);Long id=originals.get(measured.getString("key"));if(id==null)throw new IllegalArgumentException("Unknown measured original");JSONObject actual=store.getMessage(id);
            if(!measured.getString("bodySha256").equals(actual.getString("raw_content_hash"))||measured.getLong("totalChars")!=actual.getLong("raw_char_count"))throw new IllegalArgumentException("Measured vector did not come from this exact stored original");
            JSONArray chunks=measured.getJSONArray("chunks");for(int j=0;j<chunks.length();j++){JSONObject chunk=chunks.getJSONObject(j);ContentValues value=new ContentValues();value.put("source_type","message");value.put("source_id",id);value.put("source_hash",actual.getString("content_hash"));value.put("chunk_offset",chunk.getLong("offsetChars"));value.put("chunk_length",chunk.getInt("lengthChars"));value.put("model_id",modelId());value.put("dimension",512);value.put("encoding",VectorCodec.ENCODING);value.put("vector",bytes(chunk.getInt("vectorIndex")));value.put("updated_at",System.currentTimeMillis());db.insertOrThrow("semantic_chunks",null,value);count[0]++;}
            ContentValues p=new ContentValues();p.put("source_type","message");p.put("source_id",id);p.put("source_hash",actual.getString("content_hash"));p.put("model_id",modelId());p.put("next_offset",measured.getLong("totalChars"));p.put("complete",1);p.put("updated_at",System.currentTimeMillis());db.insertWithOnConflict("semantic_index_progress",null,p,SQLiteDatabase.CONFLICT_REPLACE);
        }}catch(Exception failure){throw new IllegalStateException(failure);}return null;});return count[0];
    }
    static String sha(byte[] bytes)throws Exception{byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder result=new StringBuilder();for(byte value:digest)result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return result.toString();}
    public void close()throws java.io.IOException{vectors.close();}
}
