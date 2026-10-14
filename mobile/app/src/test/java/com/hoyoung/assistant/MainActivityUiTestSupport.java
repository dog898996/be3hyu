package com.hoyoung.assistant;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import static org.junit.Assert.*;

/** Drive the visible drawer rather than bypassing navigation through private methods. */
public final class MainActivityUiTestSupport {
    private MainActivityUiTestSupport() { }
    public static void navigate(MainActivity activity, String destination) {
        View root = activity.findViewById(android.R.id.content);
        View menu = description(root, "메뉴 열기");
        assertNotNull("Visible menu control", menu); assertTrue(menu.performClick());
        View label = text(root, destination); assertNotNull("Drawer destination: " + destination, label);
        View target = label;
        while (!target.isClickable() && target.getParent() instanceof View) target = (View) target.getParent();
        assertTrue("Clickable drawer destination: " + destination, target.performClick());
    }
    static TextView text(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView)view).getText())) return (TextView)view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            TextView found=text(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;
        }
        return null;
    }
    static View description(View view, String value) {
        if (value.contentEquals(view.getContentDescription()==null?"":view.getContentDescription())) return view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=description(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;
        }
        return null;
    }
}
