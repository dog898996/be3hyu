package com.hoyoung.assistant.memory;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import androidx.test.core.app.ApplicationProvider;
import com.hoyoung.assistant.store.HoyoungStore;
import com.hoyoung.assistant.runtime.MemoryReviewCoordinator;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in synthetic product timeline. Notes/aliases/review decisions are HAND AUTHORED,
 * not extracted by an LM. Real production SQLite/retrieval/review contracts are exercised. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=Application.class)
public final class LongitudinalMemoryProductBenchmarkTest {
    private static final int DAYS=365,DISTRACTORS_PER_DAY=84,CONTEXT_CHARS=12_000;
    private static final long BASE=LocalDate.of(2024,10,13).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    private static final String LOST_ID="HJ-2024-K7319",SHOE="275mm";
    private HoyoungStore store;private MemoryRetriever retriever;private MemoryReviewCoordinator review;
    private final Map<String,Fact> facts=new LinkedHashMap<>();private final Map<Long,JSONObject> ownerOriginals=new LinkedHashMap<>();
    private final List<JSONObject> cases=new ArrayList<>(),contexts=new ArrayList<>();private long firstSource,deviceSource;private int reviewBatches,reviewSecondPasses,notesThisSession;private boolean locationAllowed=true;

    @Test public void simulate365OwnerSessionsWithCorrectionsAndThirtyThousandDistractors()throws Exception{
        assumeTrue("Explicit longitudinal product simulation opt-in is required","1".equals(System.getenv("HOYOUNG_LONGITUDINAL")));
        Context context=ApplicationProvider.getApplicationContext();context.deleteDatabase(HoyoungStore.DATABASE_NAME);store=new HoyoungStore(context);retriever=new MemoryRetriever(store);review=new MemoryReviewCoordinator(store);
        retriever.setDeviceSourceVisibility(event->locationAllowed);long start=System.nanoTime();JSONArray checkpoints=new JSONArray();
        try{
            for(int day=1;day<=DAYS;day++){
                final int d=day;store.withTransaction(db->{try{feedDay(d);}catch(Exception failure){throw new IllegalStateException(failure);}return null;});
                reviewSession();if(day==180)locationAllowed=false;
                if(day==1||day==30||day==180||day==365)checkpoints.put(evaluate(day));
            }
            assertEquals(DAYS,ownerCount());assertEquals((long)DAYS*(DISTRACTORS_PER_DAY+1),store.messageCount());verifyAllOriginals();assertFalse(review.hasPending());
            JSONObject report=json("benchmark","synthetic-longitudinal-product-365-v1","ownerSessions",DAYS,"distractorMessages",DAYS*DISTRACTORS_PER_DAY,"messages",store.messageCount(),"memories",store.memoryCount(),"checkpointResults",checkpoints,"reviewBatches",reviewBatches,"explicitSecondPasses",reviewSecondPasses,"reviewWatermark",store.getWatermark(MemoryReviewCoordinator.WATERMARK),"allOwnerOriginalsUnchanged",true,"rawOriginalCount",ownerOriginals.size(),"databaseAndWalBytes",context.getDatabasePath(HoyoungStore.DATABASE_NAME).length()+new File(context.getDatabasePath(HoyoungStore.DATABASE_NAME).getPath()+"-wal").length(),"elapsedMillis",(System.nanoTime()-start)/1_000_000,"notesHandAuthored",true,"aliasesHandAuthored",true,"reviewAcknowledgementsHandAuthored",true,"actualYearElapsed",false,"actualLmExtractionMeasured",false,"neuralEncodingMeasured",false,"lmAnswerQualityMeasured",false,"physicalPhoneMeasured",false,"scoring","schema-aware oracle checks task constraints, current values, dated history, exact original references and context availability; not LM comprehension","cases",new JSONArray(cases));
            String directory=System.getenv("HOYOUNG_LONGITUDINAL_OUTPUT");if(directory!=null){File out=new File(directory);assertTrue(out.isDirectory()||out.mkdirs());Files.write(new File(out,"report.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));StringBuilder serialized=new StringBuilder();for(JSONObject row:contexts)serialized.append(row).append('\n');Files.write(new File(out,"contexts.jsonl").toPath(),serialized.toString().getBytes(StandardCharsets.UTF_8));}
            System.out.println("HOYOUNG_LONGITUDINAL_PRODUCT "+report);
            for(int i=0;i<checkpoints.length();i++){JSONObject checkpoint=checkpoints.getJSONObject(i);assertEquals("Product scenario regression at day "+checkpoint.getInt("day"),checkpoint.getInt("scenarios"),checkpoint.getInt("passed"));}
        }finally{store.close();context.deleteDatabase(HoyoungStore.DATABASE_NAME);}
    }

    private void feedDay(int day)throws Exception{
        notesThisSession=0;String text;
        if(day==1)text="[SYNTHETIC OWNER SESSION 2024-10-13; not real user data]\n땅콩 알레르기가 있어. 간식과 디저트 주문에서 땅콩을 피해.\n음료는 디카페인 라떼가 좋아.\n배송 주소는 성수.\n보고서는 한국어로 써.\n교수님 이메일과 업무 문서는 정중한 존댓말로 써.\n23:00부터 07:00까지 먼저 연락하지 마.\n추가 유료 비용은 0원으로 제한해.\n연락처 주소록을 다른 사람에게 보내지 마.\n급한 일은 앱 알림으로 알려줘.\n페스코 채식이라 식당 예약에 육류를 빼.\n아버지는 서예를 좋아해서 생일 선물로 붓을 골라.\n어릴 때 사진 앨범은 다락방 파란 상자에 있어.\n분실물 접수번호는 "+LOST_ID+".\n운동화 치수는 "+SHOE+".\n오로라 프로젝트는 진행 중이고 데모 마감은 2025-01-10.\n오로라 담당자는 미라이고 작업 장소는 오사카.\n회의 일정은 화요일 14:00.\n";
        else if(day==120)text="[SYNTHETIC OWNER CORRECTION] 배송 주소는 이제 마포야. 성수는 옛 주소라 현재 배송에 쓰지 마.";
        else if(day==121)text="[SYNTHETIC OWNER CORRECTION] 카페 음료 선호는 이제 보리차야. 디카페인 라떼는 옛 선호.";
        else if(day==150)text="[SYNTHETIC OWNER CORRECTION] 정기 회의는 이제 수요일 16:00이야. 화요일 14:00은 과거 일정.";
        else if(day==160)text="[SYNTHETIC OWNER REPORT] 오로라 프로젝트는 내가 완료했다고 보고한 상태야. 보관하고 자동으로 다시 실행하지 마. 마감도 더 이상 예정된 일이 아니야.";
        else if(day==180)text="[SYNTHETIC OWNER CORRECTION] 월 추가 비용 한도는 이제 15000원이야. 예전 0원 제한을 현재 한도로 쓰지 마.";
        else if(day==210)text="[SYNTHETIC OWNER ALIAS] 오로라 프로젝트를 앞으로 북극광이라고도 부를게. 담당자 미라와 작업 장소 오사카는 그대로야.";
        else if(day==240)text="[SYNTHETIC OWNER CORRECTION] 조용한 시간은 이제 00:00부터 08:00까지야. 그동안 먼저 연락하지 마.";
        else text="[SYNTHETIC OWNER SESSION "+LocalDate.of(2024,10,13).plusDays(day-1)+"] 오늘 공부했고 창문 밖 날씨는 흐림. 이번 기록에는 새로운 개인 조건이 없어.";
        long source=datedMessage("user",text,day);if(day==1){firstSource=source;
            fact("owner.allergy","땅콩","간식과 디저트 주문에서 땅콩을 피해.","간식 디저트 음식 선물 알레르기 피해야 주문",true,source,day);
            fact("owner.drink","디카페인 라떼","음료는 디카페인 라떼가 좋아.","카페 음료 마시는 drink 주문",false,source,day);
            fact("owner.home","성수","배송 주소는 성수.","집 home 배송 주소 이사",true,source,day);
            fact("owner.language","한국어","보고서는 한국어로 써.","보고서 회의 언어 language",true,source,day);
            fact("owner.tone","정중한 존댓말","교수님 이메일과 업무 문서는 정중한 존댓말로 써.","교수님 이메일 메일 말투 업무 문서",true,source,day);
            fact("owner.quiet","23:00~07:00","23:00부터 07:00까지 먼저 연락하지 마.","밤 새벽 두 시 연락 선톡 조용한 시간",true,source,day);
            fact("owner.budget","0원","추가 유료 비용은 0원으로 제한해.","구독 가입 월 결제 예산 유료 budget 비용",true,source,day);
            fact("owner.privacy","주소록 외부 전송 금지","연락처 주소록을 다른 사람에게 보내지 마.","연락처 주소록 친구 공유 전송",true,source,day);
            fact("owner.channel","앱 알림","급한 일은 앱 알림으로 알려줘.","급한 긴급 urgent 알림 연락 채널",false,source,day);
            fact("owner.food","육류 제외","페스코 채식이라 식당 예약에 육류를 빼.","고기집 식당 예약 음식 메뉴 채식 식단",true,source,day);
            fact("owner.familygift","서예 붓","아버지는 서예를 좋아해서 생일 선물로 붓을 골라.","아버지 생일 선물 취미",false,source,day);
            fact("owner.keepsake","다락방 파란 상자","어릴 때 사진 앨범은 다락방 파란 상자에 있어.","옛날 오래된 어릴 때 앨범 사진 상자 보관",false,source,day);
            fact("project.aurora.status","진행 중","오로라 프로젝트는 진행 중이고 데모 마감은 2025-01-10.","오로라 프로젝트 작업 상태 다시 실행",false,source,day);
            fact("project.aurora.deadline","2025-01-10","오로라 프로젝트는 진행 중이고 데모 마감은 2025-01-10.","오로라 데모 마감 기한",false,source,day);
            fact("project.aurora.partner","미라","오로라 담당자는 미라이고 작업 장소는 오사카.","오로라 담당자 파트너 공동 작업",false,source,day);
            fact("project.aurora.place","오사카","오로라 담당자는 미라이고 작업 장소는 오사카.","오로라 장소 위치 작업",false,source,day);
            fact("owner.meeting","화요일 14:00","회의 일정은 화요일 14:00.","정기 회의 둘째 주 약속 일정 meeting",false,source,day);
            JSONObject device=store.appendDeviceEvent("location","fixture.gps",json("text","GPS 센서관측은 과학관에서 저장됨; 현재 실시간 위치라는 뜻은 아님"),at(day),null);deviceSource=device.getLong("id");store.upsertMemoryWithSources("device.last_location","GPS 센서의 과학관 관측이며 현재 실시간 위치가 아니다.","observation",new JSONArray().put(json("type","device_event","id",deviceSource)));store.putMemoryMetadata("device.last_location",json("retrievalText","GPS 마지막 폰 위치 센서관측","evidenceState","observation","fixtureDerived",true));
        }else if(day==120)fact("owner.home","마포",text,"집 home 배송 주소 이사",true,source,day);
        else if(day==121)fact("owner.drink","보리차",text,"카페 음료 마시는 drink 주문",false,source,day);
        else if(day==150)fact("owner.meeting","수요일 16:00",text,"정기 회의 둘째 주 약속 일정 meeting",false,source,day);
        else if(day==160){fact("project.aurora.status","사용자 완료 보고; 자동 재실행 금지",text,"오로라 프로젝트 작업 상태 다시 실행 보관",false,source,day);fact("project.aurora.deadline","예정 없음; 사용자 완료 보고",text,"오로라 데모 마감 기한",false,source,day);}
        else if(day==180)fact("owner.budget","15000원",text,"구독 가입 월 결제 예산 유료 budget 비용",true,source,day);
        else if(day==210){fact("project.aurora.partner","미라",text,"북극광 오로라 담당자 파트너 공동 작업",false,source,day);fact("project.aurora.place","오사카",text,"북극광 오로라 장소 위치 작업",false,source,day);}
        else if(day==240)fact("owner.quiet","00:00~08:00",text,"밤 새벽 두 시 연락 선톡 조용한 시간",true,source,day);
        for(int i=0;i<DISTRACTORS_PER_DAY;i++){long id=datedMessage("assistant","[SYNTHETIC DISTRACTOR; not owner testimony] 일반 기사와 다른 팀의 회의 예산 선물 주소 음식 카페 프로젝트 상태 작업 보고 메모 D"+day+"-"+i,day);if((day*DISTRACTORS_PER_DAY+i)%20==0)store.upsertMemory("noise."+day+"."+i,"다른 팀 기사: 회의 예산 선물 주소 음식 카페 프로젝트 상태 작업 보고", "generated_summary",new JSONArray().put(id));}
    }

    private void fact(String key,String value,String quote,String aliases,boolean pinned,long source,int day)throws Exception{
        Fact old=facts.get(key);if(old!=null){JSONObject meta=store.getMemory(key).getJSONObject("metadata");meta.put("validTo",at(day));store.putMemoryMetadata(key,meta);}
        JSONObject raw=store.getMessage(source);assertTrue(raw.getString("text").contains(quote));store.upsertMemoryWithSources(key,value+" · "+quote,"fact",new JSONArray().put(json("type","message","id",source,"quote",quote,"offsetChars",raw.getString("text").indexOf(quote))));
        store.putMemoryMetadata(key,json("factKey",key,"value",value,"validFrom",at(day),"retrievalText",aliases,"aliases",new JSONArray().put(aliases),"pinned",pinned,"evidenceState","owner_report","extractionMethod","hand_authored_oracle","fixtureDerived",true));facts.put(key,new Fact(key,value,source,day));notesThisSession++;
    }

    /** Fixture-only timestamping before references are attached; production's own canonical
     * record-hash function recomputes the dated row, and raw content/hash never changes. */
    private long datedMessage(String role,String text,int day)throws Exception{
        JSONObject row=store.addMessage(role,text,null);long id=row.getLong("id");row.put("created_at",at(day));Method hash=HoyoungStore.class.getDeclaredMethod("messageHash",JSONObject.class);hash.setAccessible(true);String fingerprint=(String)hash.invoke(null,row);store.getWritableDatabase().execSQL("UPDATE messages SET created_at=?,content_hash=? WHERE id=?",new Object[]{at(day),fingerprint,id});
        if("user".equals(role))ownerOriginals.put(id,store.getMessage(id));return id;
    }
    private void reviewSession()throws Exception{
        while(review.hasPending()){
            JSONObject batch=review.prepareBatch(),ack=ack(batch,notesThisSession==0);reviewBatches++;
            if(!review.acknowledge(batch,ack,true,notesThisSession)){JSONObject again=review.prepareBatch();assertTrue(again.optBoolean("secondPassRequired"));JSONObject second=ack(again,true);second.put("secondPass",json("reviewed",true,"reason","Synthetic fixture hand check: this routine source has no new durable facts; not an LM review."));assertTrue(review.acknowledge(again,second,true,0));reviewBatches++;reviewSecondPasses++;}
        }
    }
    private static JSONObject ack(JSONObject batch,boolean none)throws Exception{JSONArray ranges=new JSONArray(),source=batch.getJSONArray("sourceRanges");for(int i=0;i<source.length();i++){JSONObject r=source.getJSONObject(i);ranges.put(json("messageId",r.getLong("messageId"),"offsetChars",r.getLong("offsetChars"),"nextOffsetChars",r.getLong("nextOffsetChars"),"contentHash",r.getString("contentHash")));}return json("batchId",batch.getString("batchId"),"sourceRanges",ranges,"noMemoryNeeded",none,"reason","Hand-authored synthetic oracle review, not actual LM extraction.");}

    private JSONObject evaluate(int day)throws Exception{
        List<Probe> probes=probes(day);int passes=0,currentPassed=0,currentTotal=0,sourceHits=0,sourceTotal=0,rawHits=0,rawTotal=0,absentPassed=0,absentTotal=0,planHits=0,planTotal=0;long largest=0,totalMs=0;List<Long> latencies=new ArrayList<>();
        for(Probe probe:probes){JSONObject args=json("query",probe.question,"asOf",at(day)+1,"limit",10,"maxChars",CONTEXT_CHARS);if(probe.asOf>0)args.put("asOf",probe.asOf);if(probe.entity!=null)args.put("entities",new JSONArray().put(probe.entity));long started=System.nanoTime();JSONObject result=retriever.search(args);long elapsed=(System.nanoTime()-started)/1_000_000;latencies.add(elapsed);totalMs+=elapsed;String context=result.getString("context");largest=Math.max(largest,context.length());assertTrue("Context budget exceeded",context.length()<=CONTEXT_CHARS);
            boolean success=true;JSONArray checks=new JSONArray();if(probe.absent){absentTotal++;boolean pass=!result.getBoolean("hasRelevantEvidence");success&=pass;if(pass)absentPassed++;checks.put(json("check","abstain_no_relevant_source","pass",pass));}
            for(String key:probe.keys){Fact gold=facts.get(key);currentTotal++;JSONObject found=find(result.getJSONArray("memories"),key);boolean correct=found!=null&&found.optBoolean("relevantToQuery")&&gold.value.equals(found.optJSONObject("metadata").optString("value"))&&"valid_at_requested_time".equals(found.optString("temporalStatus"));boolean available=context.contains("MEMORY "+key+" ")&&context.contains(gold.value);success&=correct&&available;if(correct&&available)currentPassed++;checks.put(json("check","current_task_constraint","key",key,"expected",gold.value,"correctCurrentState",correct,"availableInBoundedContext",available));sourceTotal++;boolean sourceFound=hasSource(result,gold.source)||memoryRefers(found,gold.source);if(sourceFound)sourceHits++;checks.put(json("check","gold_original_reference","id",gold.source,"pass",sourceFound));success&=sourceFound;
                for(int i=0;i<result.getJSONArray("history").length();i++){JSONObject historical=result.getJSONArray("history").getJSONObject(i);if(key.equals(historical.optString("key"))&&!gold.value.equals(historical.optJSONObject("metadata").optString("value")))success&=!"valid_at_requested_time".equals(historical.optString("temporalStatus"));}}
            if(probe.rawValue!=null){sourceTotal++;rawTotal++;boolean hit=hasSource(result,firstSource)&&context.contains(probe.rawValue);if(hit){sourceHits++;rawHits++;}success&=hit;checks.put(json("check","rare_early_raw_source","expected",probe.rawValue,"sourceId",firstSource,"pass",hit));}
            if(probe.historyValue!=null){boolean hit=false;JSONArray history=result.getJSONArray("history");for(int i=0;i<history.length();i++){JSONObject old=history.getJSONObject(i);hit|="owner.home".equals(old.optString("key"))&&probe.historyValue.equals(old.optJSONObject("metadata").optString("value"))&&"valid_at_requested_time".equals(old.optString("temporalStatus"));}if(day<120){JSONObject currentHome=find(result.getJSONArray("memories"),"owner.home");hit=currentHome!=null&&probe.historyValue.equals(currentHome.optJSONObject("metadata").optString("value"))&&"valid_at_requested_time".equals(currentHome.optString("temporalStatus"));}success&=hit&&context.contains(probe.historyValue);checks.put(json("check","as_of_old_state","expected",probe.historyValue,"pass",hit));}
            if(probe.device){boolean visible=result.getJSONArray("originalEvidence").toString().contains("\"type\":\"device_event\"")&&result.getJSONArray("memories").toString().contains("device.last_location");boolean expected=day<180;success&=visible==expected;if(!expected){assertFalse("Revoked GPS source must not remain visible",visible);assertFalse("Revoked GPS evidence must not answer this GPS-scoped question",result.getBoolean("hasRelevantEvidence"));}checks.put(json("check","revoked_phone_scope","expectedVisible",expected,"actualVisible",visible));}
            JSONObject plan=actionPlan(probe,result,day);if(plan!=null){planTotal++;if(plan.getBoolean("pass"))planHits++;success&=plan.getBoolean("pass");checks.put(plan);}
            if(success)passes++;JSONObject record=json("day",day,"question",probe.question,"scenario",probe.name,"passed",success,"checks",checks,"contextChars",context.length(),"latencyMillis",elapsed,"hasRelevantEvidence",result.getBoolean("hasRelevantEvidence"),"candidateLimitReached",result.getJSONObject("diagnostics").getBoolean("candidateLimitReached"));cases.add(record);contexts.add(json("day",day,"asOfEpochMillis",args.getLong("asOf"),"scenario",probe.name,"question",probe.question,"fixtureOracle",checks,"context",context));
        }
        verifyAllOriginals();java.util.Collections.sort(latencies);return json("day",day,"ownerSessions",day,"distractors",day*DISTRACTORS_PER_DAY,"scenarios",probes.size(),"passed",passes,"currentTaskConstraintsPassed",currentPassed,"currentTaskConstraintsTotal",currentTotal,"goldOriginalReferencesHit",sourceHits,"goldOriginalReferencesTotal",sourceTotal,"sourceRecall",sourceTotal==0?1:(double)sourceHits/sourceTotal,"rareEarlyRawSourceHits",rawHits,"rareEarlyRawSourceProbes",rawTotal,"absentSourceAbstentionsPassed",absentPassed,"absentSourceAbstentionsTotal",absentTotal,"hypotheticalTaskPlansPassed",planHits,"hypotheticalTaskPlansTotal",planTotal,"medianQueryMillis",latencies.get(latencies.size()/2),"p95QueryMillis",latencies.get((int)Math.ceil(latencies.size()*.95)-1),"totalQueryMillis",totalMs,"maxContextChars",largest,"boundedContext",true,"allOwnerOriginalsUnchanged",true,"newAliasSupported",day>=210);
    }

    /** Finite schema oracle, not an assistant: no payment/message/order is performed. */
    private static JSONObject actionPlan(Probe probe,JSONObject result,int day)throws Exception{
        if(probe.keys.length==0)return null;JSONObject row=find(result.getJSONArray("memories"),probe.keys[0]);if(row==null)return json("check","hypothetical_task_plan","pass",false,"actual","missing_current_constraint");String value=row.getJSONObject("metadata").optString("value"),actual,expected;
        switch(probe.name){
            case "snack_avoid":case "dessert_constraint":actual=value.contains("땅콩")?"exclude_peanut_dessert":"no_peanut_exclusion";expected="exclude_peanut_dessert";break;
            case "quiet_hours":actual="defer_02h_contact_until_"+value.substring(value.indexOf('~')+1);expected=day>=240?"defer_02h_contact_until_08:00":"defer_02h_contact_until_07:00";break;
            case "paid_subscription_limit":long ceiling=Long.parseLong(value.replaceAll("[^0-9]",""));actual=12000<=ceiling?"within_fixture_cap_only_not_payment_authorization":"over_fixture_cap_do_not_subscribe";expected=day>=180?"within_fixture_cap_only_not_payment_authorization":"over_fixture_cap_do_not_subscribe";break;
            case "contacts_privacy":actual=value.contains("금지")?"do_not_export_contacts":"export_not_prohibited_by_this_note";expected="do_not_export_contacts";break;
            case "diet_action":actual=value.contains("육류 제외")?"exclude_meat_restaurant_menu":"no_meat_exclusion";expected="exclude_meat_restaurant_menu";break;
            case "project_do_not_restart":actual=value.contains("완료 보고")?"do_not_restart_owner_reported_completion_is_not_independent_proof":"continue_existing_work_only";expected=day>=160?"do_not_restart_owner_reported_completion_is_not_independent_proof":"continue_existing_work_only";break;
            case "professional_tone":actual=value.contains("존댓말")?"use_professional_polite_email_tone":"tone_unspecified";expected="use_professional_polite_email_tone";break;
            case "current_delivery":actual="ship_to_fixture_address_"+value;expected=day>=120?"ship_to_fixture_address_마포":"ship_to_fixture_address_성수";break;
            default:return null;
        }
        return json("check","hypothetical_task_plan","oracleType","handwritten_schema_rules_not_LM","actual",actual,"expected",expected,"pass",actual.equals(expected),"executed",false);
    }

    private List<Probe> probes(int day){List<Probe> p=new ArrayList<>();p.add(new Probe("snack_avoid","간식 선물 주문에서 내가 피해야 할 알레르기 음식?","owner.allergy"));p.add(new Probe("dessert_constraint","디저트 주문을 내 알레르기에 맞춰줘","owner.allergy"));p.add(new Probe("current_drink","카페 음료는 내 선호로 주문해","owner.drink"));p.add(new Probe("current_delivery","내 집 배송 주소는 어디야?","owner.home"));p.add(Probe.history("old_delivery","2024년 처음 배송 주소가 어디였지?","성수",at(Math.min(day,30))+1));p.add(new Probe("wrong_home_premise","내 배송 주소가 강남이었나? 현재 주소 알려줘","owner.home"));p.add(new Probe("report_language","회의 보고서는 무슨 언어로 써야 해?","owner.language"));p.add(new Probe("professional_tone","교수님 이메일 말투를 내 업무 문서 선호에 맞춰줘","owner.tone"));p.add(new Probe("quiet_hours","밤 두 시에 먼저 연락해도 돼? 조용한 시간 확인해","owner.quiet"));p.add(new Probe("paid_subscription_limit","월 12000원 유료 구독 가입이 내 추가 비용 예산 안이야?","owner.budget"));p.add(new Probe("contacts_privacy","친구한테 내 연락처 주소록을 전송할까?","owner.privacy"));p.add(new Probe("urgent_channel","급한 일을 어느 알림 채널로 알려줘야 돼?","owner.channel"));p.add(new Probe("early_family_gift","아버지 생일 선물 취미는 뭐였지?","owner.familygift"));p.add(new Probe("early_keepsake","어릴 때 사진 앨범 보관한 오래된 상자는 어디?","owner.keepsake"));p.add(Probe.raw("early_raw_identifier","전에 말한 분실물 접수번호 알려줘",LOST_ID));p.add(Probe.raw("early_raw_size","처음 말한 운동화 치수는 얼마?",SHOE));p.add(new Probe("project_do_not_restart","오로라 프로젝트 상태 보고 다시 실행해도 되는지 확인해","project.aurora.status"));p.add(new Probe("project_deadline","오로라 데모 마감 기한은?","project.aurora.deadline"));p.add(new Probe("project_multihop","오로라 담당자와 작업 장소는?","project.aurora.partner","project.aurora.place"));Probe alias=day<210?Probe.absent("new_alias","북극광 담당자와 장소가 뭐야?","북극광"):new Probe("new_alias","북극광 담당자와 장소가 뭐야?","project.aurora.partner","project.aurora.place");alias.entity="북극광";p.add(alias);p.add(new Probe("corrected_meeting","정기 회의 일정은 무슨 요일 몇 시로 바뀌었지?","owner.meeting"));p.add(new Probe("diet_action","고기집 식당 예약을 내 채식 식단에 맞춰줘","owner.food"));Probe device=new Probe("phone_scope","GPS 마지막 폰 위치 알려줘");device.entity="GPS";device.device=true;if(day>=180)device.absent=true;p.add(device);p.add(Probe.absent("false_passport_premise","내 여권 번호를 말해줬다는 전제는 확인됐어?","여권"));return p;}
    private void verifyAllOriginals()throws Exception{for(Map.Entry<Long,JSONObject> entry:ownerOriginals.entrySet()){JSONObject original=entry.getValue(),now=store.getMessage(entry.getKey());assertEquals(original.getString("content_hash"),now.getString("content_hash"));assertEquals(original.getString("raw_content_hash"),now.getString("raw_content_hash"));assertEquals(original.getLong("raw_char_count"),now.getLong("raw_char_count"));assertEquals(original.getString("text"),store.readOriginalText(entry.getKey(),0,10000).getString("text"));}}
    private long ownerCount(){try(Cursor row=store.getReadableDatabase().rawQuery("SELECT count(*) FROM messages WHERE role='user'",null)){row.moveToFirst();return row.getLong(0);}}
    private static JSONObject find(JSONArray rows,String key){for(int i=0;i<rows.length();i++)if(key.equals(rows.optJSONObject(i).optString("key")))return rows.optJSONObject(i);return null;}
    private boolean hasSource(JSONObject result,long id){JSONArray rows=result.optJSONArray("originalEvidence");for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if("message".equals(row.optString("type"))&&row.optLong("id")==id&&ownerOriginals.get(id).optString("content_hash").equals(row.optString("hash")))return true;}return false;}
    private boolean memoryRefers(JSONObject memory,long id){if(memory==null)return false;JSONArray refs=memory.optJSONArray("sourceRefs");if(refs!=null)for(int i=0;i<refs.length();i++){JSONObject ref=refs.optJSONObject(i);if("message".equals(ref.optString("type"))&&ref.optLong("id")==id&&ownerOriginals.get(id).optString("content_hash").equals(ref.optString("hash")))return true;}return false;}
    private static long at(int day){return BASE+(day-1)*86_400_000L;}
    private static JSONObject json(Object...pairs)throws Exception{JSONObject out=new JSONObject();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],pairs[i+1]);return out;}
    private static final class Fact{final String key,value;final long source;final int day;Fact(String key,String value,long source,int day){this.key=key;this.value=value;this.source=source;this.day=day;}}
    private static final class Probe{final String name,question;final String[]keys;String rawValue,historyValue,entity;boolean absent,device;long asOf;Probe(String name,String question,String...keys){this.name=name;this.question=question;this.keys=keys;}static Probe raw(String name,String question,String value){Probe p=new Probe(name,question);p.rawValue=value;return p;}static Probe history(String name,String question,String value,long asOf){Probe p=new Probe(name,question);p.historyValue=value;p.asOf=asOf;return p;}static Probe absent(String name,String question,String entity){Probe p=new Probe(name,question);p.absent=true;p.entity=entity;return p;}}
}
