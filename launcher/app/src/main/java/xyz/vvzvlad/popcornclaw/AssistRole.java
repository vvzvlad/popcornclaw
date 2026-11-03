package xyz.vvzvlad.popcornclaw;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Root one-shot that re-points the ACTION_ASSIST "preferred activity" at {@link VoiceAssistActivity}, so the
 * phone remote's mic press reaches our silent capture without the system chooser.
 *
 * The owner had set this preference by hand to the old ASSIST handler (MainActivity); moving the filter to
 * VoiceAssistActivity invalidated it and left a three-way chooser (Katniss, the stock com.omarchytv.home
 * launcher, and us). pm install -r clears a package's preferred activities, so {@link #main} is run as uid 0
 * from the Makefile `app` target after every (re)install; uid 0 passes the SET_PREFERRED_APPLICATIONS check.
 *
 * The candidate set is read live (queryIntentActivities), never hardcoded, so the stored set matches the
 * resolver's set — the framework's sameSet check — on both boxes, whatever assist apps each has.
 */
public final class AssistRole {
    private static final String TAG = VoiceHelper.TAG;
    private static final String PKG = "xyz.vvzvlad.popcornclaw";

    private AssistRole() {
    }

    public static void main(String[] args) {
        VoiceHelper.exemptHiddenApi();
        try {
            setAssistPreferred();
        } catch (Throwable t) {
            Log.e(TAG, "assist: setting the ASSIST preferred failed", t);
            System.exit(1);
        }
    }

    private static void setAssistPreferred() throws Exception {
        ComponentName target = new ComponentName(PKG, PKG + ".VoiceAssistActivity");

        IBinder b = VoiceHelper.getService("package");
        if (b == null) {
            Log.e(TAG, "assist: no package service");
            System.exit(1);
            return;
        }
        Object pm = Class.forName("android.content.pm.IPackageManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, b);
        Class<?> ipm = Class.forName("android.content.pm.IPackageManager");

        Intent intent = new Intent(Intent.ACTION_ASSIST);
        intent.addCategory(Intent.CATEGORY_DEFAULT);

        // The live resolver set the framework will compare against at launch (implicit intents use DEFAULT only).
        Object slice = ipm.getMethod("queryIntentActivities", Intent.class, String.class, int.class, int.class)
                .invoke(pm, intent, null, PackageManager.MATCH_DEFAULT_ONLY, 0);
        List<ResolveInfo> ris = slice == null ? null
                : (List<ResolveInfo>) slice.getClass().getMethod("getList").invoke(slice);
        if (ris == null || ris.isEmpty()) {
            Log.e(TAG, "assist: no ACTION_ASSIST candidates");
            System.exit(1);
            return;
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
            Log.e(TAG, "assist: VoiceAssistActivity is not an ASSIST candidate (filter missing?); not touching the preferred");
            System.exit(1);
            return;
        }

        IntentFilter filter = new IntentFilter(Intent.ACTION_ASSIST);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        if (match == 0) {
            match = filter.match(Intent.ACTION_ASSIST, null, null, null,
                    Collections.singleton(Intent.CATEGORY_DEFAULT), TAG);
        }

        ipm.getMethod("replacePreferredActivity", IntentFilter.class, int.class, ComponentName[].class,
                ComponentName.class, int.class)
                .invoke(pm, filter, match, set.toArray(new ComponentName[0]), target, 0);
        Log.i(TAG, "assist: preferred set to VoiceAssistActivity among " + set.size()
                + " candidates (match=0x" + Integer.toHexString(match) + ")");
    }
}
