package com.hoyoung.assistant.ui;

import static org.junit.Assert.*;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public final class PrivateFormStateTest {
    private final Context context=RuntimeEnvironment.getApplication();

    @Test public void sameSavedStateCanRestoreEveryCharacterMoreThanOnce() throws Exception {
        String text="미저장 메모 🙂\n".repeat(40_000);
        JSONObject forms=new JSONObject().put("account",new JSONObject().put("baseline",text).put("edited",text+"끝"));
        String token=PrivateFormState.write(context,forms);
        assertTrue(token.matches("[0-9a-f]{32}"));
        assertEquals(forms.toString(),PrivateFormState.read(context,token).toString());
        assertEquals(forms.toString(),PrivateFormState.read(context,token).toString());
        assertTrue(file(token).isFile());
        PrivateFormState.delete(context,token);
        assertFalse(file(token).exists());
    }

    @Test public void cleanupRetainsAnOldReferencedTaskAndRecentSnapshots() throws Exception {
        String active=PrivateFormState.write(context,new JSONObject().put("active",true));
        String abandoned=PrivateFormState.write(context,new JSONObject().put("abandoned",true));
        String recent=PrivateFormState.write(context,new JSONObject().put("recent",true));
        long old=System.currentTimeMillis()-8L*24*60*60*1000;
        assertTrue(file(active).setLastModified(old));assertTrue(file(abandoned).setLastModified(old));
        PrivateFormState.prune(context,active);
        assertTrue(file(active).isFile());assertFalse(file(abandoned).exists());assertTrue(file(recent).isFile());
    }

    @Test public void corruptStateAndPathTokensFailWithoutDeletingOtherPrivateFiles() throws Exception {
        String token=PrivateFormState.write(context,new JSONObject().put("value","saved edit"));
        Files.write(file(token).toPath(),"incomplete json".getBytes(StandardCharsets.UTF_8));
        try{PrivateFormState.read(context,token);fail("Corruption must be reported");}catch(IOException expected){}
        assertTrue("Failed reads retain the file for later diagnosis",file(token).isFile());
        File unrelated=new File(context.getNoBackupFilesDir(),"unrelated-original");
        Files.write(unrelated.toPath(),"retained original".getBytes(StandardCharsets.UTF_8));
        try{PrivateFormState.read(context,"../unrelated-original");fail("Path tokens are not accepted");}catch(IOException expected){}
        PrivateFormState.delete(context,"../unrelated-original");
        assertEquals("retained original",new String(Files.readAllBytes(unrelated.toPath()),StandardCharsets.UTF_8));
    }

    @Test public void accountCleanupRemovesOnlyItsCopiesAndBackupCleanupRemovesEverySnapshot() throws Exception {
        String removed=PrivateFormState.write(context,account("removed-account","synthetic removed password"));
        String retained=PrivateFormState.write(context,account("retained-account","synthetic retained password"));
        String settings=PrivateFormState.write(context,new JSONObject().put("settings",new JSONObject().put("edit","unsaved instructions")));
        PrivateFormState.deleteAccountSnapshots(context,"removed-account");
        assertFalse(file(removed).exists());assertTrue(file(retained).isFile());assertTrue(file(settings).isFile());
        assertEquals("synthetic retained password",PrivateFormState.read(context,retained).getJSONObject("account").getJSONObject("record").getString("password"));
        PrivateFormState.deleteAll(context);
        assertFalse(file(retained).exists());assertFalse(file(settings).exists());
    }

    private JSONObject account(String id,String password) throws Exception {
        return new JSONObject().put("account",new JSONObject().put("record",new JSONObject().put("accountId",id).put("password",password)));
    }

    private File file(String token){return new File(new File(context.getNoBackupFilesDir(),"private-form-state"),token+".json");}
}
