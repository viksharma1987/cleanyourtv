package com.gathin.tvhome;

import android.app.ActivityManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Intelligent RAM & Task Manager.
 *
 * Ensures the foreground app receives priority system resources by automatically
 * purging background user tasks when memory crosses safety thresholds, and provides
 * active process inspection for the Tasks overlay dialog.
 */
final class MemoryGuard {
    private static final String TAG = "TvHome.MemoryGuard";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    /** Free RAM threshold fraction (0.15 = 15% free RAM). */
    private static final float MIN_FREE_RATIO = 0.15f;
    /** Minimum free RAM floor in megabytes (220 MB on TVs with <= 2 GB). */
    private static final long MIN_FREE_MB = 220L;

    /** In-memory LRU tracking of user apps launched from Home or remote. */
    private static final List<String> SESSION_LAUNCHED = Collections.synchronizedList(new ArrayList<>());
    /** Epoch timestamp of the last "Kill All" action; initialized to startup time so old boot events are not shown. */
    private static volatile long lastKillTimestamp = System.currentTimeMillis();

    static void recordAppLaunched(String pkg) {
        if (pkg == null) return;
        synchronized (SESSION_LAUNCHED) {
            SESSION_LAUNCHED.remove(pkg);
            SESSION_LAUNCHED.add(0, pkg);
            while (SESSION_LAUNCHED.size() > 15) {
                SESSION_LAUNCHED.remove(SESSION_LAUNCHED.size() - 1);
            }
        }
    }

    /** Kills a single background user app and removes it from tracking. */
    static void killPackage(Context context, String pkg) {
        if (context == null || pkg == null) return;
        synchronized (SESSION_LAUNCHED) {
            SESSION_LAUNCHED.remove(pkg);
        }
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            try {
                am.killBackgroundProcesses(pkg);
                Log.d(TAG, "Killed single app: " + pkg);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to kill single app " + pkg + ": " + t.getMessage());
            }
        }
    }

    static final class AppCard {
        final String packageName;
        final String label;
        final Drawable icon;
        final int pid;

        AppCard(String packageName, String label, Drawable icon, int pid) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
            this.pid = pid;
        }
    }

    private MemoryGuard() {}

    /**
     * Executes asynchronously in parallel with app launch so the foreground launch
     * is not delayed by even 1 millisecond. Proactively purges idle background apps
     * leaving the launching app exempt.
     */
    static void ensureHeadroomAsync(Context context, String exemptPackage) {
        if (context == null) return;
        Context appCtx = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                purgeBackgroundUserApps(appCtx, exemptPackage);
            } catch (Throwable t) {
                Log.w(TAG, "Headroom check failed: " + t.getMessage());
            }
        });
    }

    /** Returns true if available RAM is below the safety threshold. */
    static boolean isMemoryTight(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return false;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        if (mi.lowMemory) return true;

        long freeMb = mi.availMem / (1024L * 1024L);
        long totalMb = mi.totalMem / (1024L * 1024L);
        if (totalMb <= 0) return false;

        float freeRatio = (float) freeMb / (float) totalMb;
        return freeRatio < MIN_FREE_RATIO || freeMb < MIN_FREE_MB;
    }

    /** Returns current RAM usage string, e.g. "1.3 / 2.0 GB RAM". */
    static String getRamSummary(Context context) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return "";
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        double totalGb = (double) mi.totalMem / (1024.0 * 1024.0 * 1024.0);
        double usedGb = (double) (mi.totalMem - mi.availMem) / (1024.0 * 1024.0 * 1024.0);
        return String.format(Locale.US, "%.1f / %.1f GB RAM", usedGb, totalGb);
    }

    /**
     * Discovers currently running user apps (launchable activities, excluding system core).
     */
    static List<AppCard> getRunningUserApps(Context context) {
        List<AppCard> result = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        if (pm == null) return result;

        Set<String> launchables = getLaunchablePackages(pm);
        Set<String> seen = new HashSet<>();
        String myPkg = context.getPackageName();

        // 1. Session launched apps (immediate 0ms latency from Home or Remote)
        synchronized (SESSION_LAUNCHED) {
            for (String pkg : SESSION_LAUNCHED) {
                if (pkg == null || pkg.equals(myPkg) || seen.contains(pkg)) continue;
                if (!launchables.contains(pkg)) continue;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    CharSequence label = pm.getApplicationLabel(ai);
                    Drawable icon = pm.getApplicationIcon(ai);
                    result.add(new AppCard(pkg, label != null ? label.toString() : pkg, icon, 0));
                    seen.add(pkg);
                } catch (PackageManager.NameNotFoundException ignored) {
                }
            }
        }

        // 2. Query UsageEvents for external launches (voice, hardware remote keys) since last kill
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm != null && result.size() < 12) {
            long now = System.currentTimeMillis();
            long since = lastKillTimestamp > 0 ? lastKillTimestamp : (now - 1000L * 60L * 30L);
            if (now > since) {
                try {
                    android.app.usage.UsageEvents events = usm.queryEvents(since, now);
                    if (events != null) {
                        android.app.usage.UsageEvents.Event event = new android.app.usage.UsageEvents.Event();
                        List<String> eventPkgs = new ArrayList<>();
                        while (events.hasNextEvent()) {
                            events.getNextEvent(event);
                            if (event.getEventType() == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                                String pkg = event.getPackageName();
                                if (pkg != null && !pkg.equals(myPkg) && launchables.contains(pkg)) {
                                    eventPkgs.remove(pkg);
                                    eventPkgs.add(0, pkg);
                                }
                            }
                        }
                        for (String pkg : eventPkgs) {
                            if (seen.contains(pkg)) continue;
                            try {
                                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                                CharSequence label = pm.getApplicationLabel(ai);
                                Drawable icon = pm.getApplicationIcon(ai);
                                result.add(new AppCard(pkg, label != null ? label.toString() : pkg, icon, 0));
                                seen.add(pkg);
                                if (result.size() >= 12) break;
                            } catch (PackageManager.NameNotFoundException ignored) {
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        return result;
    }

    /**
     * Terminates all background user apps using ActivityManager.killBackgroundProcesses.
     * Returns count of terminated packages.
     */
    static int killAllBackgroundUserApps(Context context) {
        lastKillTimestamp = System.currentTimeMillis();
        synchronized (SESSION_LAUNCHED) {
            SESSION_LAUNCHED.clear();
        }
        return purgeBackgroundUserApps(context, null);
    }

    private static int purgeBackgroundUserApps(Context context, String exemptPkg) {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        PackageManager pm = context.getPackageManager();
        if (am == null || pm == null) return 0;

        List<AppCard> running = getRunningUserApps(context);
        Set<String> killed = new HashSet<>();
        String myPkg = context.getPackageName();

        for (AppCard card : running) {
            String pkg = card.packageName;
            if (pkg == null || pkg.equals(myPkg) || pkg.equals(exemptPkg) || killed.contains(pkg)) {
                continue;
            }
            try {
                am.killBackgroundProcesses(pkg);
                killed.add(pkg);
                Log.d(TAG, "Killed background user app: " + pkg);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to kill " + pkg + ": " + t.getMessage());
            }
        }

        // Also terminate background processes of known heavy media/streaming apps if idle
        Set<String> launchables = getLaunchablePackages(pm);
        for (String pkg : launchables) {
            if (pkg == null || pkg.equals(myPkg) || pkg.equals(exemptPkg) || killed.contains(pkg)) {
                continue;
            }
            if (isHeavyMediaPackage(pkg)) {
                try {
                    am.killBackgroundProcesses(pkg);
                    killed.add(pkg);
                    Log.d(TAG, "Killed heavy background media app: " + pkg);
                } catch (Throwable ignored) {
                }
            }
        }

        return killed.size();
    }

    private static boolean isHeavyMediaPackage(String pkg) {
        if (pkg == null) return false;
        return pkg.equals("com.netflix.ninja")
                || pkg.equals("in.startv.hotstar")
                || pkg.equals("com.amazon.amazonvideo.livingroom")
                || pkg.equals("com.google.android.youtube.tv")
                || pkg.equals("com.jio.media.ondemand")
                || pkg.equals("com.graymatrix.did")
                || pkg.equals("com.sonyliv")
                || pkg.equals("org.videolan.vlc")
                || pkg.equals("org.xbmc.kodi")
                || pkg.equals("com.plexapp.android")
                || pkg.equals("com.apple.atve.androidtv.appletv")
                || pkg.equals("com.disney.disneyplus")
                || pkg.equals("com.spotify.tv.android");
    }

    private static Set<String> getLaunchablePackages(PackageManager pm) {
        Set<String> pkgs = new HashSet<>();
        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> tvApps = pm.queryIntentActivities(leanback, 0);
        if (tvApps != null) {
            for (ResolveInfo r : tvApps) {
                if (r.activityInfo != null) pkgs.add(r.activityInfo.packageName);
            }
        }

        Intent standard = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> normalApps = pm.queryIntentActivities(standard, 0);
        if (normalApps != null) {
            for (ResolveInfo r : normalApps) {
                if (r.activityInfo != null) pkgs.add(r.activityInfo.packageName);
            }
        }
        return pkgs;
    }
}

