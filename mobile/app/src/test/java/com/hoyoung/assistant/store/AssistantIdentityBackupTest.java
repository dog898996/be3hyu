package com.hoyoung.assistant.store;

import android.app.Application;
import android.content.Context;
import com.hoyoung.assistant.R;
import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.runtime.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class AssistantIdentityBackupTest {
    private Context context;private HoyoungStore store;private InternalFiles files;private AssistantIdentity identity;
    @Before public void setup()throws Exception{context=RuntimeEnvironment.getApplication();reset();}
    @After public void cleanup()throws Exception{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());}
    private void reset()throws Exception{if(store!=null)store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);clear(context.getFilesDir());files=new InternalFiles(context);store=new HoyoungStore(context);identity=new AssistantIdentity(context,store,files);}
    @Test public void coldReinstallKeepsEveryPersonalSettingAndExactAvatarOriginal()throws Exception{
        AssistantRuntime runtime=new AssistantRuntime(context,store,files,new PhoneTools(context,store),Runnable::run);
        JSONObject baseline=runtime.personalSettingsBaseline(),expected=new JSONObject();
        for(java.util.Iterator<String> keys=baseline.keys();keys.hasNext();){String key=keys.next();String value=switch(key){
            case "requiredModel"->"gpt-6.1-sol";case "requiredEffort","memoryMinimumEffort"->"high";case "autonomousEffort"->"medium";
            case "requiredSpeed"->"fast";case "backgroundSpeed"->"standard";case "heartbeatSeconds"->"300";case "backupMaxGiB"->"64";
            case "budgetBasis"->"pool_per_day";case "budgetTargetPercent"->"10";case "wakeMode"->"charging";case "toolPermissions"->"{\"files.write\":false}";
            case "customInstructions"->"기억 정확도를 우선해";case "standingGoals"->"개인 목표 보존";case "toolRoundLimit","toolActionsPerTurnLimit"->"0";default->"true";};store.putSetting(key,value);expected.put(key,value);}
        JSONObject updated=identity.update("하루",picture().optString("id"),false,"default");assertTrue(updated.optBoolean("ok"));
        JSONObject current=identity.snapshot(),avatar=current.optJSONObject("avatar");String hash=avatar.optString("sha256");assertTrue(identity.acceptObservation(current.optString("revision"),hash,"흰 모자를 쓴 고양이","observed-test-request"));
        runtime.enterForegroundRouting();JSONObject session=runtime.foregroundRoutingState();runtime.selectForegroundRouting(session.optString("sessionId"),session.optLong("revision"),"gpt-6.1-sol","xhigh",false);
        java.lang.reflect.Field ledger=AssistantRuntime.class.getDeclaredField("usageBudget");ledger.setAccessible(true);((UsageBudgetLedger)ledger.get(runtime)).close();
        ByteArrayOutputStream output=new ByteArrayOutputStream();files.exportBackup(output,store);byte[] archive=output.toByteArray();reset();files.importBackup(new ByteArrayInputStream(archive),store);
        for(java.util.Iterator<String> keys=expected.keys();keys.hasNext();){String key=keys.next();assertEquals(key,expected.optString(key),store.getSetting(key,"missing"));}
        assertEquals("하루",AssistantIdentity.name(store));assertEquals("흰 모자를 쓴 고양이",identity.snapshot().optString("avatarDescription"));
        JSONObject uploaded=identity.avatarForUpload();assertEquals(hash,uploaded.optString("sha256"));assertEquals(avatar.optLong("size"),uploaded.optLong("size"));assertNotNull(AssistantIdentity.decode(files,uploaded,128));
        AssistantRuntime restored=new AssistantRuntime(context,store,files,new PhoneTools(context,store),Runnable::run);assertFalse(restored.foregroundRoutingState().optBoolean("selected"));((UsageBudgetLedger)ledger.get(restored)).close();
    }
    @Test public void identityEditsCompareRevisionAndRejectStaleDescriptionFromPreviousPhoto()throws Exception{
        JSONObject first=identity.update("하루",picture().optString("id"),false,"default").optJSONObject("identity");
        assertFalse(identity.update("옛 이름",null,false,"default").optBoolean("ok"));assertEquals("하루",identity.snapshot().optString("name"));
        String oldRevision=first.optString("revision"),hash=first.optJSONObject("avatar").optString("sha256");
        assertFalse(identity.acceptObservation(oldRevision,"0".repeat(64),"허위 이미지","r"));
        JSONObject second=identity.update("모모",null,false,oldRevision).optJSONObject("identity");
        assertFalse(identity.acceptObservation(oldRevision,hash,"이전 요청 설명","r"));assertTrue(identity.acceptObservation(second.optString("revision"),hash,"실제 이미지 설명","r"));
        assertEquals("모모",identity.snapshot().optString("name"));
    }
    @Test public void defaultIdentityAndAvatarRemainAvailableWithoutSavingOrPaidModelCalls()throws Exception{
        assertEquals("호영",identity.snapshot().optString("name"));JSONObject asset=identity.avatarForUpload();assertEquals("assistant_profile",asset.optString("purpose"));
        assertEquals("e8296967706181a2c25c3b64a87b8241b39351ff1c280e575e0dec04cca53f16",asset.optString("sha256"));
    }
    private JSONObject picture()throws Exception{try(InputStream input=context.getResources().openRawResource(R.drawable.hoyoung_profile)){return files.writeFile("test-profile.jpg",input);}}
    private static void clear(File file)throws IOException{if(!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)clear(child);}if(!file.delete())throw new IOException("Unable to clear fixture");}
}
