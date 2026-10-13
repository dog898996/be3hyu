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
    @Test public void downloadsDoNotAcceptCookieDestinationLookalikes() {
        assertTrue(WorkingDownload.allowed(Uri.parse("https://chatgpt.com/backend-api/files/example")));
        assertTrue(WorkingDownload.allowed(Uri.parse("https://files.oaiusercontent.com/file")));
        for (String bad : new String[]{"http://files.oaiusercontent.com/file", "https://files.oaiusercontent.com.example.com/file", "https://chatgpt.com:8443/file", "blob:https://chatgpt.com/private", "content://private", "https://example.com/file"}) {
            assertFalse(bad, WorkingDownload.allowed(Uri.parse(bad)));
        }
    }
}
