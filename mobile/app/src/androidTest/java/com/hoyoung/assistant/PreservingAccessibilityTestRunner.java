package com.hoyoung.assistant;

import android.app.UiAutomation;
import androidx.test.runner.AndroidJUnitRunner;

/** Test harness only: Android's ordinary UiAutomation connection suppresses
 * enabled accessibility services. Preserve their real Binder connections for
 * every caller, including UiAutomator and default getUiAutomation() calls.
 * This neither grants accessibility nor substitutes a service or screenshot. */
public final class PreservingAccessibilityTestRunner extends AndroidJUnitRunner {
    @Override public UiAutomation getUiAutomation() {
        return getUiAutomation(0);
    }

    @Override public UiAutomation getUiAutomation(int flags) {
        return super.getUiAutomation(flags | UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    }
}
