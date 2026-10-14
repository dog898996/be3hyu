package com.hoyoung.assistant.ui;

import org.json.JSONObject;

/** The connection blocker remains visible rather than being collapsed into a generic label. */
public final class WorkConnectionStatus {
    private WorkConnectionStatus() {}
    public static String hint(String code) {
        return switch (code) {
            case "authentication_required" -> "아래 Work 화면에서 ChatGPT에 로그인해 줘.";
            case "authentication_unverified" -> "로그인 표시를 읽지 못했어. 웹 왼쪽 메뉴를 열어 계정이 보이게 한 뒤 ‘연결 확인’을 눌러 줘.";
            case "work_unverified", "work_control_unverified" -> "아래 웹의 Chat / Work에서 Work를 선택해 줘.";
            case "temporary_unverified", "temporary_control_unverified" -> "임시 채팅을 확인하지 못했어. ‘새 임시 대화’를 누르고 웹에서도 임시 채팅이 켜졌는지 확인해 줘.";
            case "model_unverified", "effort_unverified", "speed_unverified", "preference_selection_unverified", "preference_option_unavailable", "preference_selection_uncertain" -> "웹 입력창의 모델 버튼을 열고 모델·추론 강도·Fast 상태를 확인해 줘. 앱이 읽지 못하면 아래 진단을 복사해 줘.";
            case "composer_unverified" -> "Work의 새 메시지 입력창을 찾지 못했어. 열린 메뉴를 닫고 입력창이 보이게 해 줘.";
            case "page_load_failed", "page_http_403", "page_http_429" -> "웹 페이지를 불러오지 못했어. 네트워크나 웹에 표시된 인증·한도 안내를 확인해 줘.";
            case "ready" -> "연결 준비가 됐어. 대화 탭의 전송 대기 메시지는 순서대로 처리해.";
            case "busy" -> "Work의 진행 중인 응답을 기다리고 있어.";
            case "page_loading" -> "Work 페이지를 불러오는 중이야.";
            default -> "아래 웹 상태를 확인한 뒤 ‘연결 확인’을 눌러 줘. 계속 막히면 ‘연결 진단 복사’를 보내 줘.";
        };
    }
    public static String describe(String code, JSONObject web) {
        JSONObject value=web==null?new JSONObject():web;
        return "로그인 " + flag(value,"authenticated") + " · Work " + flag(value,"workSelected") + " · 임시 채팅 " + flag(value,"temporary")
            + "\n" + display(value,"model") + " · " + display(value,"effort") + " · " + display(value,"speed")
            + "\n" + hint(code) + "\n상태: " + (code.isEmpty()?"확인 중":code)
            + (value.isNull("selectionStatus")?"":" · "+value.optString("selectionStatus"));
    }
    private static String flag(JSONObject value,String key){return value.optBoolean(key)?"✓":"미확인";}
    private static String display(JSONObject value,String key){return value.isNull(key)||value.optString(key).isEmpty()?"미확인":value.optString(key);}
}
