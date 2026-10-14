package com.hoyoung.assistant.runtime;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Native-only accounting of displayed subscription usage and accepted requests.
 * Display percentages describe a shared account pool, never app-attributed tokens.
 * A separate database deliberately survives an ordinary memory ZIP restore.
 */
public final class UsageBudgetLedger implements AutoCloseable {
    public static final String DATABASE_NAME = "hoyoung_usage_budget_v1.db";
    public static final long OBSERVATION_FRESH_MS = 15 * 60_000L;
    public static final int RETAINED_DAYS = 32;
    private static final ZoneId DAY_ZONE = ZoneId.of("Asia/Seoul");
    private final Helper helper;

    public UsageBudgetLedger(Context context) {
        if (context == null) throw new IllegalArgumentException("Application context is required");
        helper = new Helper(context.getApplicationContext());
    }

    /** Ten percent of the displayed pool per Seoul day, or ten percent of pool/7. */
    public synchronized void configure(double targetPercent, String basis) {
        if (!finite(targetPercent) || targetPercent <= 0 || targetPercent > 100)
            throw new IllegalArgumentException("Target must be greater than zero and at most 100 percent");
        String normalized = "pool".equals(basis) ? "pool_per_day" : basis;
        if (!"pool_per_day".equals(normalized) && !"daily_share".equals(normalized))
            throw new IllegalArgumentException("Unsupported budget basis");
        SQLiteDatabase db = helper.getWritableDatabase();
        if(String.valueOf(targetPercent).equals(setting(db,"targetPercent","10.0")) && normalized.equals(setting(db,"basis","pool_per_day")))return;
        db.beginTransaction();
        try { putSetting(db,"targetPercent",String.valueOf(targetPercent)); putSetting(db,"basis",normalized); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); }
    }

    /**
     * Trusted native code supplies provider_ui_observed only after observing the
     * account's usage screen. Model JSON or owner statements use owner_reported.
     * precisionPercent is a conservative uncertainty radius of a rounded display.
     */
    public synchronized JSONObject recordObservation(JSONObject supplied, long now) {
        requireTime(now); if (supplied == null) throw new IllegalArgumentException("An observation is required");
        JSONObject observation = copy(supplied);
        String scope = required(observation,"accountScope",256), pool = required(observation,"poolId",256);
        String origin = required(observation,"origin",64);
        if ("provider_verified".equals(origin)) origin = "provider_ui_observed";
        boolean provider = "provider_ui_observed".equals(origin);
        if (!provider && !"owner_reported".equals(origin)) throw new IllegalArgumentException("Unknown observation origin");
        String evidence = observation.optString("evidenceRef","");
        if (evidence.length() > 1024 || (provider && evidence.trim().isEmpty()))
            throw new IllegalArgumentException("A provider UI observation needs an evidence reference");
        long at = integer(observation,"observedAt"), reset = integer(observation,"resetAt");
        double used = number(observation,"usedPercent"), precision = observation.has("precisionPercent") ? number(observation,"precisionPercent") : 1;
        if (at > now || reset <= at || !finite(used) || used < 0 || used > 100 || !finite(precision) || precision < 0 || precision > 10)
            throw new IllegalArgumentException("Invalid observation time, percentage, or display precision");
        SQLiteDatabase db = helper.getWritableDatabase(); db.beginTransaction();
        try {
            JSONObject previous = latest(db,scope,origin,null,Long.MAX_VALUE);
            if (previous != null && at <= previous.optLong("observedAt")) {
                boolean duplicate = at == previous.optLong("observedAt") && pool.equals(previous.optString("poolId"))
                        && reset == previous.optLong("resetAt") && used == previous.optDouble("usedPercent")
                        && precision == previous.optDouble("precisionPercent");
                db.setTransactionSuccessful();
                return object("ok",duplicate,"recorded",false,"code",duplicate ? "duplicate_observation" : "out_of_order_observation");
            }
            // The caller scopes an account to one displayed usage counter. Pool
            // identifiers may change at a real reset, but an unrelated counter
            // cannot silently replace an unexpired pool and erase its budget.
            if (provider && previous != null && !pool.equals(previous.optString("poolId"))
                    && (at < previous.optLong("resetAt") || reset <= previous.optLong("resetAt"))) {
                putSetting(db,"integrity:"+digest(scope),"unsupported_pool_identity_switch");
                db.setTransactionSuccessful();return object("ok",false,"recorded",false,"code","unsupported_pool_identity_switch");
            }
            JSONObject previousPool = latest(db,scope,origin,pool,Long.MAX_VALUE);
            if (previousPool != null) {
                long previousReset = previousPool.optLong("resetAt");
                String invalid = reset == previousReset && used < previousPool.optDouble("usedPercent") ? "decreasing_usage_without_reset"
                        : reset != previousReset && (reset < previousReset || at < previousReset) ? "unverified_pool_reset" : "";
                if (!invalid.isEmpty()) {
                    if (provider) putSetting(db,"integrity:"+digest(scope),invalid);
                    db.setTransactionSuccessful(); return object("ok",false,"recorded",false,"code",invalid);
                }
            }
            ContentValues row = new ContentValues(); row.put("account_scope",scope); row.put("pool_id",pool);
            row.put("origin",origin); row.put("observed_at",at); row.put("reset_at",reset); row.put("used_percent",used);
            row.put("precision_percent",precision); row.put("evidence_ref",evidence);
            db.insertOrThrow("observations",null,row);
            if (provider) { putSetting(db,"activeProviderScope",scope); putSetting(db,"integrity:"+digest(scope),""); }
            prune(db,now); db.setTransactionSuccessful();
            return object("ok",true,"recorded",true,"code","observation_recorded","origin",origin,
                    "accountShared",true,"appAttributed",false,"backendUsageVerified",false);
        } finally { db.endTransaction(); }
    }

    /** Staging is not evidence of a paid/accepted request. Repeated staging is idempotent. */
    public synchronized JSONObject recordStaged(String requestId,String kind,String model,String effort,String speed,int promptUtf16Chars,long now) {
        requireTime(now); validIdentifier(requestId); validLabel(kind); validLabel(model); validLabel(effort); validLabel(speed);
        if (promptUtf16Chars < 0) throw new IllegalArgumentException("Prompt character count cannot be negative");
        SQLiteDatabase db = helper.getWritableDatabase(); db.beginTransaction();
        try {
            String id = digest(requestId); JSONObject existing = request(db,id);
            if (existing != null) {
                boolean accepted=existing.optLong("acceptedAt")>0;
                if(!accepted && now>=existing.optLong("updatedAt")){
                    // Source/setting guards may rebuild an unaccepted payload.
                    // Acceptance accounts the latest native-staged characters, while
                    // an accepted request's metadata remains immutable.
                    ContentValues refresh=new ContentValues();refresh.put("kind",kind);refresh.put("model",model);refresh.put("effort",effort);refresh.put("speed",speed);
                    refresh.put("prompt_chars",promptUtf16Chars);refresh.put("updated_at",now);
                    if(!model.equals(existing.optString("model"))||!effort.equals(existing.optString("effort"))||!speed.equals(existing.optString("speed")))refresh.put("selection_json","{}");
                    db.update("requests",refresh,"request_hash=?",new String[]{id});
                }
                db.setTransactionSuccessful(); return object("ok",true,"recorded",false,"code","already_staged","accepted",accepted);
            }
            ContentValues row = new ContentValues(); row.put("request_hash",id); row.put("kind",kind); row.put("model",model);
            row.put("effort",effort); row.put("speed",speed); row.put("prompt_chars",promptUtf16Chars); row.put("staged_at",now);
            row.put("accepted_at",0); row.put("selection_json","{}"); row.put("outcome",""); row.put("updated_at",now);
            db.insertOrThrow("requests",null,row); counter(db,day(now),"staged_requests",1);
            prune(db,now); db.setTransactionSuccessful(); return object("ok",true,"recorded",true,"code","request_staged");
        } finally { db.endTransaction(); }
    }

    /** Called only on a real browser acceptance receipt, never when send() is attempted. */
    public synchronized JSONObject recordAccepted(String requestId,JSONObject actualSelection,long now) {
        requireTime(now); validIdentifier(requestId);
        SQLiteDatabase db = helper.getWritableDatabase(); db.beginTransaction();
        try {
            String id = digest(requestId); JSONObject previous = request(db,id);
            if (previous == null) { db.setTransactionSuccessful(); return object("ok",false,"recorded",false,"code","unstaged_acceptance_ignored"); }
            if (previous.optLong("acceptedAt")>0) { db.setTransactionSuccessful(); return object("ok",true,"recorded",false,"code","already_accepted"); }
            JSONObject selection = cleanSelection(actualSelection);
            if (selection.length()==0) selection = previous.optJSONObject("observedSelection");
            ContentValues update = new ContentValues(); update.put("accepted_at",now); update.put("updated_at",now);
            update.put("selection_json",selection.toString()); db.update("requests",update,"request_hash=?",new String[]{id});
            String today = day(now); counter(db,today,"accepted_requests",1); counter(db,today,"prompt_chars",previous.optInt("promptUtf16Chars"));
            if (selection.length()>0) counter(db,today,"selection_observed",1);
            if ("fast".equals(selection.optString("speed"))) counter(db,today,"fast_observed",1);
            prune(db,now); db.setTransactionSuccessful();
            return object("ok",true,"recorded",true,"code","request_accepted","backendModelAppliedVerified",false);
        } finally { db.endTransaction(); }
    }

    /** Observed UI selection remains separate from an unverified backend-applied model. */
    public synchronized JSONObject recordSelectionObserved(String requestId,JSONObject actualSelection,long now) {
        requireTime(now); validIdentifier(requestId); JSONObject selection = cleanSelection(actualSelection);
        if (selection.length()==0) throw new IllegalArgumentException("An actual model/effort/speed observation is required");
        SQLiteDatabase db = helper.getWritableDatabase(); db.beginTransaction();
        try {
            String id = digest(requestId); JSONObject previous = request(db,id);
            if (previous == null) { db.setTransactionSuccessful(); return object("ok",false,"recorded",false,"code","unknown_request"); }
            JSONObject old = previous.optJSONObject("observedSelection"); long accepted = previous.optLong("acceptedAt");
            if (accepted>0) {
                String today=day(accepted);
                if (old.length()==0) counter(db,today,"selection_observed",1);
                if (!"fast".equals(old.optString("speed")) && "fast".equals(selection.optString("speed"))) counter(db,today,"fast_observed",1);
                else if ("fast".equals(old.optString("speed")) && !"fast".equals(selection.optString("speed"))) counter(db,today,"fast_observed",-1);
            }
            ContentValues update = new ContentValues(); update.put("selection_json",selection.toString()); update.put("updated_at",now);
            db.update("requests",update,"request_hash=?",new String[]{id}); db.setTransactionSuccessful();
            return object("ok",true,"recorded",!old.toString().equals(selection.toString()),"code","selection_observed","backendModelAppliedVerified",false);
        } finally { db.endTransaction(); }
    }

    public synchronized JSONObject recordOutcome(String requestId,String status,long now) {
        requireTime(now); validIdentifier(requestId); validLabel(status);
        SQLiteDatabase db=helper.getWritableDatabase(); ContentValues update=new ContentValues(); update.put("outcome",status); update.put("updated_at",now);
        int changed=db.update("requests",update,"request_hash=?",new String[]{digest(requestId)});
        return object("ok",changed>0,"recorded",changed>0,"code",changed>0?"outcome_recorded":"unknown_request");
    }

    public synchronized JSONObject snapshot(long now) {
        requireTime(now); SQLiteDatabase db=helper.getReadableDatabase();
        double target=Double.parseDouble(setting(db,"targetPercent","10")); String basis=setting(db,"basis","pool_per_day");
        double effective="daily_share".equals(basis)?target/7d:target;
        String today=day(now), scope=setting(db,"activeProviderScope","");
        JSONObject overall=latest(db,null,null,null,now);
        if(scope.isEmpty() && overall!=null) scope=overall.optString("accountScope");
        JSONObject provider=scope.isEmpty()?null:latest(db,scope,"provider_ui_observed",null,now);
        JSONObject displayed=scope.isEmpty()?null:latest(db,scope,null,null,now);
        JSONObject result=object("day",today,"dayZone","Asia/Seoul","targetPercent",target,"effectiveTargetPercent",effective,"basis",basis,
                "accountShared",true,"appAttributed",false,"tokensMeasured",false,"backendUsageVerified",false,
                "observation",displayed==null?JSONObject.NULL:displayed,"latestProviderObservation",provider==null?JSONObject.NULL:provider,
                "verifiedObservationFresh",false,"partialDayCoverage",true,"measuredLowerPercent",JSONObject.NULL,"measuredUpperPercent",JSONObject.NULL,
                "budgetState",displayed==null?"unknown":"owner_reported_only","budgetTargetGuaranteed",false,
                "requestStatistics",statistics(db,today),"userRequestsAllowed",true,"backgroundAllowed",true,"budgetEnforcement","advisory_only","optimizationGoalOnly",true,
                "warning","실제 계정 사용량을 확인하지 못했어. 요청 수·문자 수를 토큰이나 한도 비율로 환산하지 않아.");
        if(provider==null)return result;
        long age=now-provider.optLong("observedAt"); boolean fresh=age>=0 && age<=OBSERVATION_FRESH_MS && now<provider.optLong("resetAt");
        String integrity=setting(db,"integrity:"+digest(scope),"");
        Bounds bounds=measure(db,scope,now);
        put(result,"verifiedObservationFresh",fresh); put(result,"partialDayCoverage",!bounds.fullDay);
        put(result,"measuredLowerPercent",bounds.lower);put(result,"measuredUpperPercent",bounds.upper);
        put(result,"measurementStartsAt",bounds.startsAt);put(result,"measurementEndsAt",provider.optLong("observedAt"));
        put(result,"roundingUncertaintyIncluded",true);put(result,"currentObservationLagMs",age);
        put(result,"unobservedDayUsageExcluded",!bounds.fullDay);
        String state;
        // A previously proven same-day lower bound does not disappear when the
        // usage screen grows stale, resets, or subsequently reports bad data.
        if(bounds.lower>=effective)state="target_exceeded";
        else if(bounds.upper>=effective)state="rounding_uncertain";
        else if(!integrity.isEmpty())state="observation_integrity_unknown";
        else if(now>=provider.optLong("resetAt"))state="pool_reset_pending";
        else if(!fresh)state="stale";
        else if(!bounds.fullDay)state="partial_day_unknown";
        else state="within_measured_target";
        boolean reached="target_exceeded".equals(state),rounded="rounding_uncertain".equals(state);
        put(result,"budgetState",state);put(result,"backgroundAllowed",true);
        put(result,"warning",reached?"계정 전체의 관측 사용량이 하루 최적화 목표에 도달했어. 목표는 절감 성과를 평가하는 지표이며 정기 판단과 사용자 Fast 응답은 계속 수행해."
                :rounded?"화면 반올림 오차를 포함하면 하루 최적화 목표 이상일 수 있어. 최적화 측정 정보이며 정기 판단과 사용자 Fast 응답은 계속 수행해."
                :"within_measured_target".equals(state)?"관측 구간에서는 최적화 목표 이내야. 다음 호출 비용과 화면 갱신 지연은 관측값에 아직 포함되지 않아."
                :"현재 관측만으로 하루 목표 이내라고 확인할 수 없어. 다른 Codex/Work 사용도 같은 계정 한도에 포함돼.");
        if(!integrity.isEmpty())put(result,"observationIntegrityIssue",integrity);
        return result;
    }

    /** Advisory only: a consumption optimization goal never stops useful work. */
    public synchronized JSONObject backgroundDecision(long now) {
        JSONObject status=snapshot(now);
        return object("allowed",true,"reason",status.optString("budgetState"),"userRequestsAllowed",true,"budgetEnforcement","advisory_only",
                "measuredLowerPercent",status.opt("measuredLowerPercent"),"effectiveTargetPercent",status.optDouble("effectiveTargetPercent"),
                "warning",status.optString("warning"),"guaranteedTenPercent",false);
    }

    public synchronized JSONObject requestSnapshot(String requestId) { validIdentifier(requestId); return request(helper.getReadableDatabase(),digest(requestId)); }
    @Override public synchronized void close(){helper.close();}

    private static Bounds measure(SQLiteDatabase db,String scope,long now){
        long start=dayStart(now);List<JSONObject> records=new ArrayList<>();
        try(Cursor c=db.rawQuery("SELECT * FROM observations WHERE account_scope=? AND origin='provider_ui_observed' AND observed_at>=? AND observed_at<=? ORDER BY observed_at,id",
                new String[]{scope,String.valueOf(start),String.valueOf(now)})){while(c.moveToNext())records.add(observation(c));}
        Bounds bounds=new Bounds();bounds.startsAt=records.isEmpty()?0:records.get(0).optLong("observedAt");
        if(records.isEmpty())return bounds;
        JSONObject first=records.get(0),previous=first,segmentStart=first;
        bounds.fullDay=first.optLong("observedAt")==start;
        // A known pool reset during this day supplies a zero baseline even if
        // the first screen observation arrived later. A guessed reset does not.
        JSONObject before=latest(db,scope,"provider_ui_observed",null,start-1);
        boolean knownBoundary=before!=null && before.optLong("resetAt")>=start
                && before.optLong("resetAt")<=first.optLong("observedAt") && first.optLong("resetAt")>before.optLong("resetAt");
        if(knownBoundary){segmentStart=object("usedPercent",0,"precisionPercent",0,"resetAt",first.optLong("resetAt"));bounds.fullDay=before.optLong("resetAt")==start;bounds.startsAt=before.optLong("resetAt");}
        for(int i=1;i<records.size();i++){
            JSONObject current=records.get(i);
            if(current.optLong("resetAt")!=previous.optLong("resetAt")){
                addDelta(bounds,segmentStart,previous);
                // Old-pool usage between its last screen observation and reset
                // is unknown. Never turn this gap into a claimed complete day.
                bounds.fullDay=false;
                segmentStart=object("usedPercent",0,"precisionPercent",0,"resetAt",current.optLong("resetAt"));
            }
            previous=current;
        }
        addDelta(bounds,segmentStart,previous);return bounds;
    }
    private static void addDelta(Bounds result,JSONObject first,JSONObject last){
        if(first==last)return;
        double firstLow=low(first),firstHigh=high(first),lastLow=low(last),lastHigh=high(last);
        result.lower+=Math.max(0,lastLow-firstHigh);result.upper+=Math.max(0,lastHigh-firstLow);
    }
    private static double low(JSONObject value){return Math.max(0,value.optDouble("usedPercent")-value.optDouble("precisionPercent"));}
    private static double high(JSONObject value){return Math.min(100,value.optDouble("usedPercent")+value.optDouble("precisionPercent"));}
    private static final class Bounds{double lower,upper;long startsAt;boolean fullDay;}

    private static JSONObject statistics(SQLiteDatabase db,String today){
        JSONObject result=object("stagedRequests",0,"acceptedRequests",0,"promptUtf16Chars",0,"selectionObservedAccepted",0,
                "fastSelectionObservedAccepted",0,"tokensMeasured",false,"backendModelAppliedVerified",false,"modelSelectionSource","observed_browser_ui");
        try(Cursor c=db.rawQuery("SELECT * FROM days WHERE day=?",new String[]{today})){
            if(c.moveToFirst()){put(result,"stagedRequests",c.getLong(c.getColumnIndexOrThrow("staged_requests")));put(result,"acceptedRequests",c.getLong(c.getColumnIndexOrThrow("accepted_requests")));
                put(result,"promptUtf16Chars",c.getLong(c.getColumnIndexOrThrow("prompt_chars")));put(result,"selectionObservedAccepted",c.getLong(c.getColumnIndexOrThrow("selection_observed")));
                put(result,"fastSelectionObservedAccepted",c.getLong(c.getColumnIndexOrThrow("fast_observed")));}
        }return result;
    }
    private static JSONObject request(SQLiteDatabase db,String id){
        try(Cursor c=db.rawQuery("SELECT * FROM requests WHERE request_hash=?",new String[]{id})){
            if(!c.moveToFirst())return null;
            return object("requestHash",id,"kind",c.getString(c.getColumnIndexOrThrow("kind")),"model",c.getString(c.getColumnIndexOrThrow("model")),
                    "effort",c.getString(c.getColumnIndexOrThrow("effort")),"speed",c.getString(c.getColumnIndexOrThrow("speed")),
                    "promptUtf16Chars",c.getInt(c.getColumnIndexOrThrow("prompt_chars")),"stagedAt",c.getLong(c.getColumnIndexOrThrow("staged_at")),
                    "updatedAt",c.getLong(c.getColumnIndexOrThrow("updated_at")),
                    "acceptedAt",c.getLong(c.getColumnIndexOrThrow("accepted_at")),"observedSelection",parse(c.getString(c.getColumnIndexOrThrow("selection_json"))),
                    "outcome",c.getString(c.getColumnIndexOrThrow("outcome")),"backendModelAppliedVerified",false);
        }
    }
    private static JSONObject cleanSelection(JSONObject input){
        JSONObject result=new JSONObject();if(input==null)return result;
        for(String key:new String[]{"model","effort","speed"}){Object supplied=input.opt(key);if(supplied==null||supplied==JSONObject.NULL)continue;
            if(!(supplied instanceof String))throw new IllegalArgumentException("Observed "+key+" must be text");
            String value=(String)supplied;if(!value.isEmpty()){validLabel(value);put(result,key,value);}}
        return result;
    }
    private static JSONObject latest(SQLiteDatabase db,String scope,String origin,String pool,long before){
        List<String> values=new ArrayList<>();StringBuilder where=new StringBuilder("observed_at<=?");values.add(String.valueOf(before));
        if(scope!=null){where.append(" AND account_scope=?");values.add(scope);}if(origin!=null){where.append(" AND origin=?");values.add(origin);}
        if(pool!=null){where.append(" AND pool_id=?");values.add(pool);}
        try(Cursor c=db.rawQuery("SELECT * FROM observations WHERE "+where+" ORDER BY observed_at DESC,id DESC LIMIT 1",values.toArray(new String[0]))){return c.moveToFirst()?observation(c):null;}
    }
    private static JSONObject observation(Cursor c){return object("accountScope",c.getString(c.getColumnIndexOrThrow("account_scope")),
            "poolId",c.getString(c.getColumnIndexOrThrow("pool_id")),"origin",c.getString(c.getColumnIndexOrThrow("origin")),
            "observedAt",c.getLong(c.getColumnIndexOrThrow("observed_at")),"resetAt",c.getLong(c.getColumnIndexOrThrow("reset_at")),
            "usedPercent",c.getDouble(c.getColumnIndexOrThrow("used_percent")),"precisionPercent",c.getDouble(c.getColumnIndexOrThrow("precision_percent")),
            "evidenceRef",c.getString(c.getColumnIndexOrThrow("evidence_ref")));}
    private static void counter(SQLiteDatabase db,String day,String name,long delta){
        db.execSQL("INSERT OR IGNORE INTO days(day) VALUES(?)",new Object[]{day});
        db.execSQL("UPDATE days SET "+name+"=MAX(0,"+name+"+?) WHERE day=?",new Object[]{delta,day});
    }
    private static void prune(SQLiteDatabase db,long now){
        long cut=Instant.ofEpochMilli(now).atZone(DAY_ZONE).toLocalDate().minusDays(RETAINED_DAYS).atStartOfDay(DAY_ZONE).toInstant().toEpochMilli();
        db.delete("days","day<?",new String[]{day(cut)});
        // Pending staged requests remain reconcilable; a forgotten callback can
        // never manufacture acceptance without its original staged record.
        db.delete("requests","updated_at<? AND (accepted_at>0 OR outcome<>'')",new String[]{String.valueOf(cut)});
        // Preserve one baseline per account/origin before retention cutoff.
        db.execSQL("DELETE FROM observations WHERE observed_at<? AND id NOT IN (SELECT id FROM observations o WHERE observed_at<? AND id=(SELECT id FROM observations p WHERE p.account_scope=o.account_scope AND p.origin=o.origin AND p.observed_at<? ORDER BY p.observed_at DESC,p.id DESC LIMIT 1))",new Object[]{cut,cut,cut});
    }
    private static String day(long now){return Instant.ofEpochMilli(now).atZone(DAY_ZONE).toLocalDate().toString();}
    private static long dayStart(long now){return Instant.ofEpochMilli(now).atZone(DAY_ZONE).toLocalDate().atStartOfDay(DAY_ZONE).toInstant().toEpochMilli();}
    private static void putSetting(SQLiteDatabase db,String key,String value){ContentValues row=new ContentValues();row.put("setting_key",key);row.put("value",value);db.insertWithOnConflict("settings",null,row,SQLiteDatabase.CONFLICT_REPLACE);}
    private static String setting(SQLiteDatabase db,String key,String fallback){try(Cursor c=db.rawQuery("SELECT value FROM settings WHERE setting_key=?",new String[]{key})){return c.moveToFirst()?c.getString(0):fallback;}}
    private static String digest(String text){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte value:bytes)result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return result.toString();}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static boolean finite(double value){return !Double.isNaN(value)&&!Double.isInfinite(value);}
    private static long integer(JSONObject object,String key){Object value=object.opt(key);if(!(value instanceof Number)||!finite(((Number)value).doubleValue())||((Number)value).doubleValue()!=((Number)value).longValue()||((Number)value).longValue()<0)throw new IllegalArgumentException(key+" must be a nonnegative integer");return ((Number)value).longValue();}
    private static double number(JSONObject object,String key){Object value=object.opt(key);if(!(value instanceof Number))throw new IllegalArgumentException(key+" must be numeric");return ((Number)value).doubleValue();}
    private static String required(JSONObject object,String key,int max){Object value=object.opt(key);if(!(value instanceof String)||((String)value).trim().isEmpty()||((String)value).length()>max)throw new IllegalArgumentException("Invalid "+key);return (String)value;}
    private static void validIdentifier(String value){if(value==null||value.trim().isEmpty()||value.length()>512)throw new IllegalArgumentException("Invalid request identifier");}
    private static void validLabel(String value){if(value==null||value.length()>256)throw new IllegalArgumentException("Invalid request label");}
    private static void requireTime(long now){if(now<0)throw new IllegalArgumentException("Time cannot be negative");}
    private static JSONObject copy(JSONObject input){return parse(input.toString());}
    private static JSONObject parse(String input){try{return new JSONObject(input);}catch(Exception corrupt){throw new IllegalStateException("Usage accounting metadata is unreadable",corrupt);}}
    private static JSONObject object(Object...pairs){JSONObject value=new JSONObject();for(int i=0;i<pairs.length;i+=2)put(value,String.valueOf(pairs[i]),pairs[i+1]);return value;}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value==null?JSONObject.NULL:value);}catch(Exception invalid){throw new IllegalStateException(invalid);}}
    private static final class Helper extends SQLiteOpenHelper{
        Helper(Context context){super(context,DATABASE_NAME,null,1);setWriteAheadLoggingEnabled(true);}
        @Override public void onConfigure(SQLiteDatabase db){super.onConfigure(db);db.execSQL("PRAGMA synchronous=FULL");}
        @Override public void onCreate(SQLiteDatabase db){
            db.execSQL("CREATE TABLE settings(setting_key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            db.execSQL("CREATE TABLE observations(id INTEGER PRIMARY KEY AUTOINCREMENT,account_scope TEXT NOT NULL,pool_id TEXT NOT NULL,origin TEXT NOT NULL,observed_at INTEGER NOT NULL,reset_at INTEGER NOT NULL,used_percent REAL NOT NULL,precision_percent REAL NOT NULL,evidence_ref TEXT NOT NULL)");
            db.execSQL("CREATE INDEX observations_scope_time ON observations(account_scope,origin,observed_at,id)");
            db.execSQL("CREATE TABLE requests(request_hash TEXT PRIMARY KEY,kind TEXT NOT NULL,model TEXT NOT NULL,effort TEXT NOT NULL,speed TEXT NOT NULL,prompt_chars INTEGER NOT NULL,staged_at INTEGER NOT NULL,accepted_at INTEGER NOT NULL,selection_json TEXT NOT NULL,outcome TEXT NOT NULL,updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX requests_updated ON requests(updated_at)");
            db.execSQL("CREATE TABLE days(day TEXT PRIMARY KEY,staged_requests INTEGER NOT NULL DEFAULT 0,accepted_requests INTEGER NOT NULL DEFAULT 0,prompt_chars INTEGER NOT NULL DEFAULT 0,selection_observed INTEGER NOT NULL DEFAULT 0,fast_observed INTEGER NOT NULL DEFAULT 0)");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("Unknown usage accounting schema");}
    }
}
