package xyz.vvzvlad.popcornclaw;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.speech.RecognizerIntent;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Root one-shot that re-points two "preferred activities" at our voice entry points: ACTION_ASSIST at
 * {@link VoiceAssistActivity}, so the phone remote's mic press reaches our silent capture without the system
 * chooser, and android.speech.action.WEB_SEARCH at {@link VoiceSearchActivity}, so the Bluetooth voice remote's
 * mic key (KEYCODE_VOICE_ASSIST) asks the agent instead of Google search (katniss).
 *
 * The owner had set the ASSIST preference by hand to the old ASSIST handler (MainActivity); moving the filter to
 * VoiceAssistActivity invalidated it and left a three-way chooser (Katniss, the stock com.omarchytv.home
 * launcher, and us). The WEB_SEARCH preference was picked as "always" for katniss in the chooser. pm install -r
 * clears a package's preferred activities, so {@link #main} is run as uid 0 from the Makefile `app` target
 * after every (re)install; uid 0 passes the SET_PREFERRED_APPLICATIONS check. Both are set even if one fails;
 * the exit code is 1 if either did.
 *
 * The candidate set is read live (queryIntentActivities), never hardcoded, so the stored set matches the
 * resolver's set — the framework's sameSet check — on both boxes, whatever assist and search apps each has.
 */
public final class AssistRole {
    private static final String TAG = VoiceHelper.TAG;
    private static final String PKG = "xyz.vvzvlad.popcornclaw";

    private AssistRole() {
    }

    public static void main(String[] args) {
        VoiceHelper.exemptHiddenApi();
        boolean assist = setPreferred(Intent.ACTION_ASSIST, "VoiceAssistActivity");
        boolean search = setPreferred(RecognizerIntent.ACTION_WEB_SEARCH, "VoiceSearchActivity");
        if (!assist || !search) System.exit(1);
    }

    /** Re-points the {@code action} preferred at our activity {@code cls}; false if it was not set. */
    private static boolean setPreferred(String action, String cls) {
        try {
            return replacePreferred(action, cls);
        } catch (Throwable t) {
            Log.e(TAG, "assist: setting the " + action + " preferred failed", t);
            return false;
        }
    }

    private static boolean replacePreferred(String action, String cls) throws Exception {
        ComponentName target = new ComponentName(PKG, PKG + "." + cls);

        IBinder b = VoiceHelper.getService("package");
        if (b == null) {
            Log.e(TAG, "assist: no package service");
            return false;
        }
        Object pm = Class.forName("android.content.pm.IPackageManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, b);
        Class<?> ipm = Class.forName("android.content.pm.IPackageManager");

        Intent intent = new Intent(action);
        intent.addCategory(Intent.CATEGORY_DEFAULT);

        // The live resolver set the framework will compare against at launch (implicit intents use DEFAULT only).
        Object slice = ipm.getMethod("queryIntentActivities", Intent.class, String.class, int.class, int.class)
                .invoke(pm, intent, null, PackageManager.MATCH_DEFAULT_ONLY, 0);
        List<ResolveInfo> ris = slice == null ? null
                : (List<ResolveInfo>) slice.getClass().getMethod("getList").invoke(slice);
        if (ris == null || ris.isEmpty()) {
            Log.e(TAG, "assist: no " + action + " candidates");
            return false;
        }

        List<ComponentName> set = new ArrayList<>();
        int match = 0;
        boolean hasTarget = false;
        for (ResolveInfo ri : ris) {
            ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            set.add(cn);
            if (cn.equals(target)) {
                hasTarget = true;
                match = ri.match;
            }
        }
        if (!hasTarget) {
            Log.e(TAG, "assist: " + cls + " is not an " + action + " candidate (filter missing?); not touching the preferred");
            return false;
        }

        IntentFilter filter = new IntentFilter(action);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        if (match == 0) {
            match = filter.match(action, null, null, null,
                    Collections.singleton(Intent.CATEGORY_DEFAULT), TAG);
        }

        ipm.getMethod("replacePreferredActivity", IntentFilter.class, int.class, ComponentName[].class,
                ComponentName.class, int.class)
                .invoke(pm, filter, match, set.toArray(new ComponentName[0]), target, 0);
        Log.i(TAG, "assist: " + action + " preferred set to " + cls + " among " + set.size()
                + " candidates (match=0x" + Integer.toHexString(match) + ")");
        return true;
    }
}
