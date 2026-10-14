package com.hoyoung.assistant.web;

import android.net.Uri;
import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class OriginBoundaryTest {
    @Test public void nativeMessagesOnlyAcceptExactTlsOrigin() {
        assertTrue(WorkWebController.isChatGptOrigin(Uri.parse("https://chatgpt.com/?temporary-chat=true")));
        assertTrue(WorkWebController.isChatGptOrigin(Uri.parse("https://chatgpt.com:443/c/task")));
        for (String bad : new String[]{"http://chatgpt.com", "https://chatgpt.com:8443", "https://chatgpt.com.example.com", "https://example.com/chatgpt.com", "file:///data/user/0/private", "javascript:alert(1)"}) {
            assertFalse(bad, WorkWebController.isChatGptOrigin(Uri.parse(bad)));
        }
    }
    @Test public void ordinaryDownloadsAllowAnyValidHttpOrHttpsHostWithoutExpandingNativeBridgeIdentity() {
        for (String valid : new String[]{"https://chatgpt.com/backend-api/files/example", "https://files.oaiusercontent.com/file",
                "http://files.oaiusercontent.com/file", "https://files.oaiusercontent.com.example.com/file", "https://chatgpt.com:8443/file",
                "https://example.com/file", "http://192.0.2.1:8080/file"}) assertTrue(valid, WorkingDownload.allowed(Uri.parse(valid)));
        for (String bad : new String[]{"blob:https://chatgpt.com/private", "content://private", "file:///tmp/file", "javascript:alert(1)", "https:///file", "https://example.com:0/file", "https://example.com:65536/file"}) {
            assertFalse(bad, WorkingDownload.allowed(Uri.parse(bad)));
        }
    }
}
