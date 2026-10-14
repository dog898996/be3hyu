package com.hoyoung.assistant.runtime;
import android.app.Application;
import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;
import java.io.File;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,application=Application.class) @SQLiteMode(SQLiteMode.Mode.NATIVE)
public final class GoalLedgerPrivacyTest{
 private Context context;private HoyoungStore store;private GoalLedger ledger;
 @Before public void before()throws Exception{context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());context.getFilesDir().mkdirs();store=new HoyoungStore(context);ledger=new GoalLedger(store);}
 @After public void after()throws Exception{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);delete(context.getFilesDir());}
 private long owner(String root,String text)throws Exception{store.enqueueRequest(text,"user",root);store.putSetting("requestRoot:"+root,root);long id=store.addMessage("user",text,root).getLong("id");store.putSetting("requestSource:"+root,""+id);return id;}
 private void refs(String root,String type,long id,String hash)throws Exception{store.putSetting("goalSources:"+root,new JSONObject().put("version",1).put("sources",new JSONArray().put(new JSONObject().put("type",type).put("id",id).put("hash",hash))).put("memoryFingerprint","native").toString());}
 private void exclude(String type,long id,String hash)throws Exception{store.getPrivacyLedger().excludeSource(type,id,hash,"retrieval_exclude","owner exclusion");}
 private MemoryGateway.Prepared packet()throws Exception{return new MemoryGateway(store,store.getPrivacyLedger(),row->true).prepare("goal-dispatch","goal-root","user",0,"hello",null,80000);}
 private static void absent(Object object,String marker){assertFalse(object.toString(),object.toString().contains(marker));}
 @Test public void knownProviderCopyMasksProgressWhileQuestionAndUnrelatedGoalRemainAvailable()throws Exception{
  long old=store.addMessage("user","PRIVATE-GOAL-3147",null).getLong("id"),question=owner("root","handle archive"),clean=owner("clean","unrelated task");JSONObject reply=store.addMessage("assistant","PRIVATE-GOAL-3147 progress","root");store.putSetting("answerSources:"+reply.getLong("id"),new JSONObject().put("version",1).put("sources",new JSONArray().put(new JSONObject().put("type","message").put("id",old).put("hash",store.getMessage(old).getString("content_hash")))).toString());
  ledger.update("root","working",question,"root","PRIVATE-GOAL-3147 progress",1234);ledger.update("clean","working",clean,"clean","CLEAN-GOAL-AVAILABLE",5678);exclude("message",old,store.getMessage(old).getString("content_hash"));
  assertTrue(ledger.get("root").getString("progress").contains("PRIVATE-GOAL-3147"));JSONObject list=ledger.list(null);absent(list,"PRIVATE-GOAL-3147");assertTrue(list.toString().contains("CLEAN-GOAL-AVAILABLE"));assertEquals(2,list.getJSONArray("goals").length());absent(ledger.context(8000),"PRIVATE-GOAL-3147");MemoryGateway.Prepared p=packet();absent(p.context,"PRIVATE-GOAL-3147");assertTrue(p.context.contains("CLEAN-GOAL-AVAILABLE"));assertTrue(p.context.contains("blocked_source_excluded"));absent(ledger.update("root","working",question,"root","PRIVATE-GOAL-3147 again",1234),"PRIVATE-GOAL-3147");
 }
 @Test public void nativeGoalSourcesAndPhysicallyMissingOwnerRetainOpaqueConstraintsWithoutBlockingNewReply()throws Exception{
  long old=store.addMessage("user","PRIVATE-ROOT-8227",null).getLong("id"),question=owner("root","allowed question");refs("root","message",old,store.getMessage(old).getString("content_hash"));ledger.update("root","working",question,"root","PRIVATE-ROOT-8227 copied progress",7890);MemoryGateway.Prepared before=packet();
  store.putSetting("goalSources:root",new JSONObject().put("version",1).put("sources",new JSONArray()).toString());try{new MemoryGateway(store).assertFresh(before);fail("Raw provenance changes must invalidate");}catch(MemoryGateway.Blocked expected){assertEquals("memory_control_changed",expected.reason);}
  refs("root","message",old,store.getMessage(old).getString("content_hash"));String historyKey=ledger.get("root").getString("progressSourcesKey"),savedHistory=store.getSetting(historyKey,"");store.putSetting(historyKey,new JSONObject().put("version",1).put("sources",new JSONArray()).toString());absent(ledger.list(null),"PRIVATE-ROOT-8227");store.putSetting(historyKey,savedHistory);exclude("message",old,store.getMessage(old).getString("content_hash"));absent(packet().context,"PRIVATE-ROOT-8227");store.getWritableDatabase().execSQL("DELETE FROM messages WHERE id=?",new Object[]{question});absent(ledger.list(null),"PRIVATE-ROOT-8227");MemoryGateway.Prepared p=packet();assertEquals(5,p.receipts().length());assertTrue(p.context.contains("blocked_source_excluded"));assertTrue(p.context.contains("7890"));
 }
 @Test public void archivedProviderReportDoesNotReturnExcludedProgressOrRequestExcerpt()throws Exception{
  long old=store.addMessage("user","PRIVATE-ARCHIVE-5319",null).getLong("id"),question=owner("root","allowed question");refs("root","message",old,store.getMessage(old).getString("content_hash"));ledger.update("root","working",question,"root","PRIVATE-ARCHIVE-5319 working",0);long request=store.listRequests(1).getJSONObject(0).getLong("id");store.markRequest(request,"accepted","observed provider completion");ActionReceiptJournal receipts=new ActionReceiptJournal(store);JSONObject summary=receipts.completionSummary("root",name->false,r->true);store.putSetting("goalSources:root",new JSONObject().put("version",1).put("sources",new JSONArray()).put("memoryFingerprint","fresh-report").toString());JSONObject archived=ledger.archiveReportedCompletion("root","root","PRIVATE-ARCHIVE-5319 done",summary);assertTrue(archived.getBoolean("archived"));long report=archived.getJSONObject("goal").getLong("reportSourceMessageId");assertFalse(store.getSetting("answerSources:"+report,"").isEmpty());exclude("message",old,store.getMessage(old).getString("content_hash"));JSONObject list=ledger.list(new JSONObject().put("scope","reported_archive"));assertEquals(1,list.getJSONArray("goals").length());absent(list,"PRIVATE-ARCHIVE-5319");assertEquals("blocked_source_excluded",list.getJSONArray("goals").getJSONObject(0).getString("stage"));absent(packet().context,"PRIVATE-ARCHIVE-5319");
 }
 @Test public void deviceGrantRevocationRedactsGoalButCleanGoalStillWorks()throws Exception{
  JSONObject event=store.appendDeviceEvent("location","phone",new JSONObject().put("text","PRIVATE-DEVICE-GOAL-8613"),100,null);long question=owner("device","allowed device question");refs("device","device_event",event.getLong("id"),event.getString("content_hash"));ledger.setDeviceSourceVisibility(row->true);ledger.update("device","working",question,"device","PRIVATE-DEVICE-GOAL-8613 progress",1234);assertTrue(ledger.list(null).toString().contains("PRIVATE-DEVICE-GOAL-8613"));ledger.setDeviceSourceVisibility(row->false);absent(ledger.list(null),"PRIVATE-DEVICE-GOAL-8613");MemoryGateway.Prepared p=new MemoryGateway(store).prepare("device-dispatch","device-root","user",0,"hello",null,80000);absent(p.context,"PRIVATE-DEVICE-GOAL-8613");assertTrue(p.context.contains("blocked_source_excluded"));
 }
 private static void delete(File f)throws Exception{if(!f.exists())return;if(f.isDirectory()){File[] children=f.listFiles();if(children==null)throw new java.io.IOException("Unreadable fixture");for(File c:children)delete(c);}if(!f.delete())throw new java.io.IOException("Fixture cleanup failed");}
}
