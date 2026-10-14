package com.hoyoung.assistant.store;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.hoyoung.assistant.R;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Current identity is an authoritative setting, independent of old conversation memories. */
public final class AssistantIdentity {
    public static final String KEY = "assistant.identity";
    public static final String DEFAULT_NAME = "호영";
    private final Context context;
    private final HoyoungStore store;
    private final InternalFiles files;

    public AssistantIdentity(Context context, HoyoungStore store, InternalFiles files) {
        this.context=context.getApplicationContext(); this.store=store; this.files=files;
    }
    public static String name(HoyoungStore store) {
        try { return new JSONObject(store.getSetting(KEY,"{}")).optString("name",DEFAULT_NAME); }
        catch (org.json.JSONException bad) { return DEFAULT_NAME; }
    }
    public JSONObject snapshot() {
        JSONObject value;
        try { value=new JSONObject(store.getSetting(KEY,"{}")); }
        catch(org.json.JSONException invalid) { throw new IllegalStateException("Identity setting is invalid",invalid); }
        if (!value.has("name")) put(value,"name",DEFAULT_NAME);
        if (!value.has("revision")) put(value,"revision","default");
        if (!value.has("avatar")) {
            put(value,"avatar",json("bundled",true,"resource","hoyoung_profile"));
            put(value,"avatarDescription","흰색과 연한 주황색 털의 고양이가 흰색 adidas 모자를 쓴 사진");
            put(value,"avatarDescriptionSource","bundled_owner_selected_image");
        }
        return value;
    }
    public JSONObject update(String name,String avatarFileId,boolean defaultAvatar,String expectedRevision) throws IOException {
        if (name!=null && (name.trim().isEmpty() || name.length()>80 || name.indexOf('\n')>=0 || name.indexOf('\r')>=0))
            throw new IllegalArgumentException("이름은 한 줄, 1~80자로 입력해 줘.");
        if (defaultAvatar && avatarFileId!=null) throw new IllegalArgumentException("Choose one avatar source");
        JSONObject avatar=null;
        if (avatarFileId!=null) {
            avatar=files.freezeAttachments(new JSONArray().put(avatarFileId)).optJSONObject(0);
            // The avatar renderer and Work image path require the preserved image MIME.
            // Do not rewrite an immutable original's provider metadata to make it appear usable.
            if(!avatar.optString("mime",avatar.optString("mimeType","")).startsWith("image/"))
                throw new IOException("이미지 형식으로 확인된 파일을 선택해 줘. 현재 파일의 MIME으로는 프로필 사진을 표시할 수 없어.");
            Bitmap checked=decode(files,avatar,128);
            if (checked==null) throw new IOException("선택한 파일을 프로필 이미지로 읽을 수 없어.");
            checked.recycle();
        }
        final JSONObject selected=avatar;
        return store.withTransaction(db->{
            JSONObject current=snapshot();
            if(expectedRevision!=null&&!expectedRevision.equals(current.optString("revision")))
                return json("ok",false,"code","identity_changed","message","다른 화면이나 대화에서 프로필이 바뀌었어. 최신 프로필을 확인하고 다시 저장해 줘.");
            JSONObject previous=copy(current);
            if(name!=null)put(current,"name",name.trim());
            if(selected!=null){put(current,"avatar",selected);current.remove("avatarDescription");current.remove("avatarDescriptionSource");current.remove("avatarDescriptionReceipt");}
            if(defaultAvatar){put(current,"avatar",json("bundled",true,"resource","hoyoung_profile"));put(current,"avatarDescription","흰색과 연한 주황색 털의 고양이가 흰색 adidas 모자를 쓴 사진");put(current,"avatarDescriptionSource","bundled_owner_selected_image");current.remove("avatarDescriptionReceipt");}
            put(current,"revision",UUID.randomUUID().toString()); put(current,"changedAt",System.currentTimeMillis());
            store.putSetting(KEY,current.toString());
            store.recordEvent("assistant_identity_changed",json("previous",previous,"current",current,"currentSettingOverridesHistoricalNames",true));
            return json("ok",true,"identity",current,"message","이름과 프로필 설정을 저장했어. 이후 판단은 새 정체성을 기준으로 해.");
        });
    }
    /** Returns an immutable file usable by the ordinary verified Work attachment path. */
    // Resources.openRawResource explicitly supports file-backed drawables. Read the JPEG
    // bytes rather than re-encoding a Drawable, so upload and backup keep the original hash.
    @android.annotation.SuppressLint("ResourceType")
    public JSONObject avatarForUpload() throws IOException {
        JSONObject current=snapshot(),avatar=current.optJSONObject("avatar");
        if(avatar.optBoolean("bundled")) {
            try(InputStream source=context.getResources().openRawResource(R.drawable.hoyoung_profile)) {
                JSONObject working=files.writeFile("assistant-profile/default.jpg",source);
                avatar=files.freezeAttachments(new JSONArray().put(working)).optJSONObject(0);
            }
        } else avatar=files.freezeAttachments(new JSONArray().put(avatar)).optJSONObject(0);
        put(avatar,"assistantIdentityRevision",current.optString("revision"));
        put(avatar,"purpose","assistant_profile"); return avatar;
    }
    /** Called only after this request's actual attachment receipt and response have been checked. */
    public boolean acceptObservation(String revision,String sha,String description,String requestId) {
        if(description==null||description.trim().isEmpty()||description.length()>4000)return false;
        return store.withTransaction(db->{
            JSONObject current=snapshot(),avatar=current.optJSONObject("avatar");
            if(!current.optString("revision").equals(revision)||avatar.optBoolean("bundled")||!avatar.optString("sha256").equals(sha))return false;
            put(current,"avatarDescription",description);put(current,"avatarDescriptionSource","model_observation_of_uploaded_image");
            put(current,"avatarDescriptionReceipt",json("requestId",requestId,"sha256",sha,"uploadObserved",true));
            store.putSetting(KEY,current.toString());return true;
        });
    }
    public static Bitmap decode(InternalFiles files,JSONObject avatar,int limit)throws IOException {
        try(InternalFiles.VerifiedImageSource source=files.openVerifiedImage(avatar)) {
            BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeStream(source.stream(),null,bounds); source.rewind();
            if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("프로필 사진 형식을 읽을 수 없어.");
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;
            while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>limit && options.inSampleSize<(1<<29))options.inSampleSize*=2;
            Bitmap bitmap=BitmapFactory.decodeStream(source.stream(),null,options);
            try{source.verifyUnchanged();return bitmap;}catch(IOException changed){if(bitmap!=null)bitmap.recycle();throw changed;}
        }
    }
    private static JSONObject copy(JSONObject value){try{return new JSONObject(value.toString());}catch(org.json.JSONException bad){throw new IllegalStateException(bad);}}
    private static JSONObject json(Object...pairs){JSONObject value=new JSONObject();for(int i=0;i<pairs.length;i+=2)put(value,(String)pairs[i],pairs[i+1]);return value;}
    private static void put(JSONObject value,String key,Object item){try{value.put(key,item);}catch(org.json.JSONException bad){throw new IllegalStateException(bad);}}
}
