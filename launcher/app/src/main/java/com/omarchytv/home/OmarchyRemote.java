package com.omarchytv.home;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Optional "OmarchyTV remote" accessibility service: lets a paired PC press Back (PROTOCOL.md, Remote keys).
 * It listens to no events and reads no window content; it only performs GLOBAL_ACTION_BACK on request.
 * Enable in Settings > Accessibility, or over adb:
 *   adb shell settings put secure enabled_accessibility_services com.omarchytv.home/.OmarchyRemote
 *   adb shell settings put secure accessibility_enabled 1
 */
public class OmarchyRemote extends AccessibilityService {

    private static volatile OmarchyRemote instance;

    static boolean enabled() {
        return instance != null;
    }

    static boolean back() {
        OmarchyRemote s = instance;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        OmarchyLink.statusChanged();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        OmarchyLink.statusChanged();
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
