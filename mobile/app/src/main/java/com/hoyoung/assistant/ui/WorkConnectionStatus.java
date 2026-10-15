package com.hoyoung.assistant.ui;

import org.json.JSONObject;

/** Short connection status for the toolbar, with full diagnostics available on demand. */
public final class WorkConnectionStatus {
    private WorkConnectionStatus() {}
    public static String hint(String code) {
        return switch (code) {
            case "authentication_required" -> "아래 Work 화면에서 ChatGPT에 로그인해 줘.";
            case "authentication_unverified" -> "계정 표시를 아직 확인하지 못했어. 로그인 실패라는 뜻은 아니야. ‘연결’을 눌러 다시 확인하고, 계속 막히면 웹 왼쪽 메뉴를 열어 계정이 보이게 해 줘.";
            case "connection_preparing" -> "접힌 메뉴에서 계정 표시와 Work 선택을 확인하고 있어.";
            case "work_unverified", "work_control_unverified" -> "아래 웹의 Chat / Work에서 Work를 선택해 줘.";
            case "unowned_conversation" -> "앱이 만든 대화인지 확인되지 않았어. ‘새 Work 대화’로 시작해 줘. 개인 대화는 자동으로 지우지 않아.";
            case "conversation_cleanup_pending" -> "이전 호영 대화의 삭제를 확인 중이야. 확인되면 새 Work 대화를 열어.";
            case "conversation_cleanup_unconfirmed", "conversation_cleanup_control_unverified", "conversation_cleanup_confirmation_unverified", "conversation_cleanup_wrong_target" -> "이전 대화의 자동 삭제를 확인하지 못했어. 아래 웹에서 직접 삭제한 뒤 ‘삭제 확인 복구’를 눌러 줘.";
            case "conversation_journal_unreadable", "conversation_ownership_unresolved" -> "이전 대화의 소유·접수 기록 확인이 필요해. 삭제하거나 새 작업을 보내지 않고 대기 중이야.";
            case "model_unverified", "effort_unverified", "speed_unverified", "preference_selection_unverified", "preference_option_unavailable", "preference_selection_uncertain" -> "웹 입력창의 모델 버튼을 열고 모델·추론 강도·Fast 상태를 확인해 줘. 앱이 읽지 못하면 아래 진단을 복사해 줘.";
            case "composer_unverified" -> "Work의 새 메시지 입력창을 찾지 못했어. 열린 메뉴를 닫고 입력창이 보이게 해 줘.";
            case "page_load_failed", "page_http_403", "page_http_429" -> "웹 페이지를 불러오지 못했어. 네트워크나 웹에 표시된 인증·한도 안내를 확인해 줘.";
            case "ready" -> "연결 준비가 됐어. 대화 탭의 전송 대기 메시지는 순서대로 처리해.";
            case "busy" -> "Work의 진행 중인 응답을 기다리고 있어.";
            case "page_loading" -> "Work 페이지를 불러오는 중이야.";
            default -> "아래 웹 상태를 확인한 뒤 ‘연결’을 눌러 줘. 계속 막히면 ⋯에서 ‘연결 진단 복사’를 보내 줘.";
        };
    }
    public static String compact(String code, JSONObject web) {
        JSONObject value = web == null ? new JSONObject() : web;
        return switch (code) {
            case "ready" -> modelLabel(display(value,"model")) + " · " + effortLabel(display(value,"effort")) + " · " + speedLabel(display(value,"speed"));
            case "authentication_required" -> "아래 ChatGPT에서 로그인해 줘";
            case "authentication_unverified" -> "계정 표시 확인 필요 · 연결을 눌러 줘";
            case "connection_preparing" -> "계정과 Work 확인 중…";
            case "page_loading" -> "ChatGPT를 여는 중…";
            case "work_unverified", "work_control_unverified" -> "Work 선택 필요 · 연결을 눌러 줘";
            case "speed_unverified" -> "Fast 상태 확인 필요 · 연결을 눌러 줘";
            case "model_unverified", "effort_unverified", "preference_selection_pending" -> "모델·속도 확인 중…";
            case "conversation_cleanup_pending" -> "이전 대화 정리 중…";
            case "conversation_cleanup_unconfirmed", "conversation_cleanup_control_unverified", "conversation_cleanup_confirmation_unverified", "conversation_cleanup_wrong_target", "conversation_journal_unreadable", "conversation_ownership_unresolved" -> "대화 기록 확인 필요 · ⋯에서 복구";
            case "busy" -> "작업 중 · 추가 지시는 대화에서";
            case "quota_exhausted" -> "사용 한도 대기 · 메시지는 저장돼";
            default -> "연결 확인 필요 · ⋯에서 상세 보기";
        };
    }
    private static String modelLabel(String model) { return switch(model) {case "gpt-6.1-sol" -> "6.1 Sol"; case "gpt-6-sol" -> "6 Sol"; case "gpt-6-astra" -> "Astra"; default -> model;}; }
    private static String effortLabel(String effort) { return effort.isEmpty() ? effort : Character.toUpperCase(effort.charAt(0)) + effort.substring(1); }
    private static String speedLabel(String speed) { return switch(speed) {case "fast" -> "Fast"; case "standard" -> "Standard"; default -> speed;}; }
    public static String describe(String code, JSONObject web) {
        JSONObject value=web==null?new JSONObject():web;
        return "로그인 " + flag(value,"authenticated") + " · Work " + flag(value,"workSelected") + " · 일반 대화"
            + "\n" + display(value,"model") + " · " + display(value,"effort") + " · " + display(value,"speed")
            + "\n" + hint(code) + "\n상태: " + (code.isEmpty()?"확인 중":code)
            + (value.isNull("selectionStatus")?"":" · "+value.optString("selectionStatus"));
    }
    private static String flag(JSONObject value,String key){return value.optBoolean(key)?"✓":"미확인";}
    private static String display(JSONObject value,String key){return value.isNull(key)||value.optString(key).isEmpty()?"미확인":value.optString(key);}
}
