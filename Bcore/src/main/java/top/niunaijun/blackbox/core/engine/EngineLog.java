package top.niunaijun.blackbox.core.engine;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import top.niunaijun.blackbox.BlackBoxCore;

public final class EngineLog {
    private EngineLog() {
    }

    private static final String TAG = "MocgramEngine";
    private static final long MAX_LOG = 512 * 1024;
    private static final ThreadLocal<Boolean> sBusy = new ThreadLocal<Boolean>();

    public static File root() {
        Context c = null;
        try {
            c = BlackBoxCore.getContext();
        } catch (Throwable ignored) {
        }
        if (c == null) return null;
        File dir = new File(c.getFilesDir(), "mocgram_engine/" + MocgramEngine.tag());
        dir.mkdirs();
        return dir;
    }

    public static File crashDir() {
        File r = root();
        if (r == null) return null;
        File d = new File(r, "crashes");
        d.mkdirs();
        return d;
    }

    public static void error(String tag, String msg, Throwable tr) {
        if (Boolean.TRUE.equals(sBusy.get())) return;
        sBusy.set(Boolean.TRUE);
        try {
            File r = root();
            if (r == null) return;
            File f = new File(r, "errors.log");
            if (f.length() > MAX_LOG) {
                File old = new File(r, "errors.1.log");
                old.delete();
                f.renameTo(old);
            }
            StringBuilder sb = new StringBuilder();
            sb.append(now()).append(' ').append(Process.myPid()).append(' ').append(processName())
                    .append(" E/").append(tag).append(": ").append(msg).append('\n');
            if (tr != null) sb.append(stack(tr)).append('\n');
            synchronized (EngineLog.class) {
                FileOutputStream out = new FileOutputStream(f, true);
                out.write(sb.toString().getBytes("UTF-8"));
                out.close();
            }
        } catch (Throwable ignored) {
        } finally {
            sBusy.set(Boolean.FALSE);
        }
    }

    public static File writeCrash(String kind, Thread thread, Throwable tr, String guestPkg) {
        try {
            File dir = crashDir();
            if (dir == null) return null;
            String proc = processName();
            String who = guestPkg != null ? guestPkg : proc;
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File f = new File(dir, stamp + "_" + safe(who) + ".txt");
            StringBuilder sb = new StringBuilder();
            sb.append(MocgramEngine.full()).append('\n');
            sb.append("Time: ").append(now()).append('\n');
            sb.append("Kind: ").append(kind).append('\n');
            sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(" · Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("Process: ").append(proc).append(" pid=").append(Process.myPid()).append('\n');
            if (guestPkg != null) sb.append("App: ").append(guestPkg).append(appVersion(guestPkg)).append('\n');
            sb.append("Thread: ").append(thread != null ? thread.getName() : "?").append('\n');
            sb.append("\n--- stack ---\n").append(stack(tr)).append('\n');
            sb.append("\n--- recent log of this process ---\n").append(recentLog());
            FileOutputStream out = new FileOutputStream(f);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
            prune(dir, 200);
            Log.e(TAG, "crash report saved: " + f);
            return f;
        } catch (Throwable e) {
            Log.e(TAG, "could not save crash report", e);
            return null;
        }
    }

    private static String appVersion(String pkg) {
        try {
            android.content.pm.PackageInfo pi = BlackBoxCore.getBPackageManager().getPackageInfo(pkg, 0, BlackBoxCore.getUserId());
            if (pi != null) return " " + pi.versionName + " (" + pi.versionCode + ")";
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String recentLog() {
        StringBuilder sb = new StringBuilder();
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "time", "-t", "300",
                    "--pid=" + Process.myPid()});
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
        } catch (Throwable e) {
            sb.append("(log unavailable: ").append(e).append(")\n");
        }
        return sb.toString();
    }

    private static void prune(File dir, int keep) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= keep) return;
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        for (int i = 0; i < files.length - keep; i++) files[i].delete();
    }

    public static String processName() {
        try {
            if (Build.VERSION.SDK_INT >= 28) return android.app.Application.getProcessName();
        } catch (Throwable ignored) {
        }
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/self/cmdline"));
            String s = r.readLine();
            r.close();
            if (s != null) return s.trim().replace("\u0000", "");
        } catch (Throwable ignored) {
        }
        return "?";
    }

    static String stack(Throwable tr) {
        if (tr == null) return "";
        StringWriter sw = new StringWriter();
        tr.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
