package com.hoyoung.assistant;

import static org.junit.Assert.*;
import android.Manifest;
import android.os.Looper;
import android.widget.TextView;
import com.hoyoung.assistant.phone.PermissionTestGrants;
import com.hoyoung.assistant.runtime.HoyoungApplication;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,application=HoyoungApplication.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class PermissionReturnReviewTest {
    @Test public void returningAfterPermissionGrantRestoresARealTab() throws Exception {
        HoyoungApplication app=(HoyoungApplication)RuntimeEnvironment.getApplication();
        app.getStore().putSetting("autonomy.enabled","false");
        app.getStore().putSetting("memoryReviewEnabled","false");
        PermissionTestGrants.allowAll(app);
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS);
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup().visible();
        MainActivity activity=controller.get();
        try {
            Field tab=MainActivity.class.getDeclaredField("tab");tab.setAccessible(true);
            Field title=MainActivity.class.getDeclaredField("screenTitle");title.setAccessible(true);
            Field composer=MainActivity.class.getDeclaredField("composer");composer.setAccessible(true);
            System.out.println("blockedTab="+tab.get(activity));
            assertEquals("권한 설정",tab.get(activity));
            controller.pause();PermissionTestGrants.allowAll(app);controller.resume();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            System.out.println("grantedTab="+tab.get(activity));
            System.out.println("grantedTitle="+((TextView)title.get(activity)).getText());
            System.out.println("chatComposerExists="+(composer.get(activity)!=null));
            assertEquals("Permission onboarding is not a normal tab; restored chat must use the chat identity", "대화",tab.get(activity));
        } finally {
            controller.pause().stop().destroy();
        }
    }
}
