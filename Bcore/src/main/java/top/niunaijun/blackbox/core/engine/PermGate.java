package top.niunaijun.blackbox.core.engine;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;

public final class PermGate {
    private PermGate() {
    }

    private static final String TAG = "MocgramPerm";
    public static final String ACTION_REQUEST = "android.content.pm.action.REQUEST_PERMISSIONS";
    private static final String EXTRA_NAMES = "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES";
    private static final String EXTRA_RESULTS = "android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS";
    private static final long PENDING_MS = 5 * 60 * 1000L;
    public static final String STORAGE_ASKED = "mocgram.STORAGE_ASKED";
    public static final String REAL_STORAGE = "mocgram.REAL_STORAGE";
    private static final Map<String, Integer> sTarget = new HashMap<String, Integer>();

    public static boolean isStorage(String p) {
        if (p == null) return false;
        return p.equals("android.permission.READ_EXTERNAL_STORAGE") || p.equals("android.permission.WRITE_EXTERNAL_STORAGE")
                || p.startsWith("android.permission.READ_MEDIA_") || p.equals("android.permission.ACCESS_MEDIA_LOCATION")
                || p.equals("android.permission.MANAGE_EXTERNAL_STORAGE");
    }

    private static int targetSdk(String pkg, int user) {
        synchronized (sTarget) {
            Integer t = sTarget.get(pkg);
            if (t != null) return t;
        }
        int t = 30;
        try {
            android.content.pm.ApplicationInfo ai = BlackBoxCore.getBPackageManager().getApplicationInfo(pkg, 0, user);
            if (ai != null) t = ai.targetSdkVersion;
        } catch (Throwable ignored) {
        }
        synchronized (sTarget) {
            sTarget.put(pkg, t);
        }
        return t;
    }

    public static int storageCheck() {
        String pkg = guestPkg();
        if (pkg == null) return PackageManager.PERMISSION_GRANTED;
        int user = userId();
        if (targetSdk(pkg, user) < 23) return PackageManager.PERMISSION_GRANTED;
        return granted(pkg, user).contains(STORAGE_ASKED) ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
    }

    public static boolean hasRealStorage(String pkg, int userId) {
        return granted(pkg, userId).contains(REAL_STORAGE);
    }

    private static final Map<String, Boolean> sDangerous = new HashMap<String, Boolean>();
    private static final Map<String, Long> sPending = new HashMap<String, Long>();
    private static final Map<String, Set<String>> sCache = new HashMap<String, Set<String>>();
    private static final Map<String, Long> sCacheTime = new HashMap<String, Long>();

    public static File dir() {
        Context c = BlackBoxCore.getContext();
        if (c == null) return null;
        File d = new File(c.getFilesDir(), "mocgram_engine/perms");
        d.mkdirs();
        return d;
    }

    private static File file(String pkg, int userId) {
        File d = dir();
        return d == null ? null : new File(d, userId + "_" + pkg + ".txt");
    }

    public static synchronized Set<String> granted(String pkg, int userId) {
        File f = file(pkg, userId);
        if (f == null) return new LinkedHashSet<String>();
        String key = userId + "_" + pkg;
        Long t = sCacheTime.get(key);
        long mod = f.exists() ? f.lastModified() : -1;
        Set<String> cached = sCache.get(key);
        if (cached != null && t != null && t == mod) return cached;
        Set<String> out = new LinkedHashSet<String>();
        if (f.exists()) {
            try {
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.length() > 0) out.add(line);
                }
                r.close();
            } catch (Throwable e) {
                Log.w(TAG, "read " + f + ": " + e);
            }
        }
        sCache.put(key, out);
        sCacheTime.put(key, mod);
        return out;
    }

    private static synchronized void save(String pkg, int userId, Set<String> set) {
        File f = file(pkg, userId);
        if (f == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (String s : set) sb.append(s).append('\n');
            FileOutputStream out = new FileOutputStream(f);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
        } catch (Throwable e) {
            Log.w(TAG, "write " + f + ": " + e);
        }
        sCache.remove(userId + "_" + pkg);
    }

    public static synchronized void grant(String pkg, int userId, List<String> perms) {
        Set<String> s = new LinkedHashSet<String>(granted(pkg, userId));
        if (s.addAll(perms)) save(pkg, userId, s);
    }

    public static synchronized void revoke(String pkg, int userId, String perm) {
        Set<String> s = new LinkedHashSet<String>(granted(pkg, userId));
        if (perm == null) s.clear();
        else s.remove(perm);
        save(pkg, userId, s);
    }

    public static synchronized void forget(String pkg, int userId) {
        File f = file(pkg, userId);
        if (f != null) f.delete();
        sCache.remove(userId + "_" + pkg);
        sCacheTime.remove(userId + "_" + pkg);
    }

    public static boolean isDangerous(String perm) {
        if (perm == null) return false;
        synchronized (sDangerous) {
            Boolean b = sDangerous.get(perm);
            if (b != null) return b;
        }
        boolean d = false;
        try {
            PermissionInfo pi = BlackBoxCore.getContext().getPackageManager().getPermissionInfo(perm, 0);
            d = (pi.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS;
        } catch (Throwable ignored) {
        }
        synchronized (sDangerous) {
            sDangerous.put(perm, d);
        }
        return d;
    }

    private static String guestPkg() {
        try {
            return BActivityThread.getAppPackageName();
        } catch (Throwable e) {
            return null;
        }
    }

    private static int userId() {
        try {
            return BActivityThread.getUserId();
        } catch (Throwable e) {
            return 0;
        }
    }

    public static int hostCheck(String perm) {
        try {
            return BlackBoxCore.getContext().checkPermission(perm, Process.myPid(), BlackBoxCore.getHostUid());
        } catch (Throwable e) {
            return PackageManager.PERMISSION_DENIED;
        }
    }

    public static int filter(String perm, int real) {
        if (real != PackageManager.PERMISSION_GRANTED) return real;
        String pkg = guestPkg();
        if (pkg == null || !isDangerous(perm)) return real;
        int user = userId();
        if (granted(pkg, user).contains(perm)) return real;
        synchronized (sPending) {
            Long at = sPending.get(perm);
            if (at != null && SystemClock.elapsedRealtime() - at < PENDING_MS) {
                sPending.remove(perm);
                List<String> one = new ArrayList<String>();
                one.add(perm);
                grant(pkg, user, one);
                return real;
            }
        }
        return PackageManager.PERMISSION_DENIED;
    }

    public static boolean intercept(final Context ctx, final IBinder token, final String who, Intent intent, final int requestCode,
                                    final Runnable toSystem) {
        if (intent == null || !ACTION_REQUEST.equals(intent.getAction()) || !(ctx instanceof Activity)) return false;
        final String[] perms = intent.getStringArrayExtra(EXTRA_NAMES);
        final String pkg = guestPkg();
        if (perms == null || pkg == null) return false;
        final int user = userId();
        Set<String> have = granted(pkg, user);
        boolean wantsStorage = false;
        for (String p : perms) if (isStorage(p)) wantsStorage = true;
        if (wantsStorage && !have.contains(STORAGE_ASKED)) {
            final Activity act = (Activity) ctx;
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    showStorageDialog(act, token, who, requestCode, perms, pkg, user, toSystem);
                }
            });
            return true;
        }
        boolean needSystem = false;
        final List<String> ask = new ArrayList<String>();
        for (String p : perms) {
            if (isStorage(p) || !isDangerous(p)) continue;
            if (hostCheck(p) != PackageManager.PERMISSION_GRANTED) needSystem = true;
            else if (!have.contains(p) && !ask.contains(p)) ask.add(p);
        }
        if (needSystem) {
            long now = SystemClock.elapsedRealtime();
            synchronized (sPending) {
                for (String p : perms) if (isDangerous(p)) sPending.put(p, now);
            }
            return false;
        }
        final Activity a = (Activity) ctx;
        if (ask.isEmpty()) {
            deliver(a, token, who, requestCode, perms, pkg, user);
            return true;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                showDialog(a, token, who, requestCode, perms, ask, pkg, user);
            }
        });
        return true;
    }

    private static void showStorageDialog(final Activity a, final IBinder token, final String who, final int requestCode,
                                          final String[] perms, final String pkg, final int user, final Runnable toSystem) {
        boolean ar = Locale.getDefault().getLanguage().startsWith("ar");
        CharSequence app = pkg;
        try {
            app = a.getApplicationInfo().loadLabel(a.getPackageManager());
        } catch (Throwable ignored) {
        }
        String msg = ar
                ? "يطلب «" + app + "» الوصول إلى الملفات.\n\n• ملفاتي: يرى الصور والفيديو والموسيقى والتنزيلات الموجودة على جهازك.\n• مساحة خاصة: يرى ملفاته فقط، وهذا الأكثر أماناً."
                : "\"" + app + "\" wants to access files.\n\n• My files: sees the photos, videos, music and downloads on your device.\n• Private space: sees only its own files. This is the safest choice.";
        try {
            new AlertDialog.Builder(a)
                    .setMessage(msg)
                    .setCancelable(false)
                    .setPositiveButton(ar ? "ملفاتي" : "My files", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            List<String> g = new ArrayList<String>();
                            g.add(STORAGE_ASKED);
                            g.add(REAL_STORAGE);
                            grant(pkg, user, g);
                            try {
                                top.niunaijun.blackbox.core.IOCore.get().allowRealMedia();
                            } catch (Throwable e) {
                                Log.w(TAG, "allowRealMedia: " + e);
                            }
                            if (hostCheck("android.permission.READ_EXTERNAL_STORAGE") != PackageManager.PERMISSION_GRANTED && toSystem != null) {
                                toSystem.run();
                            } else {
                                deliver(a, token, who, requestCode, perms, pkg, user);
                            }
                        }
                    })
                    .setNegativeButton(ar ? "مساحة خاصة" : "Private space", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            List<String> g = new ArrayList<String>();
                            g.add(STORAGE_ASKED);
                            grant(pkg, user, g);
                            deliver(a, token, who, requestCode, perms, pkg, user);
                        }
                    })
                    .show();
        } catch (Throwable e) {
            Log.w(TAG, "storage dialog failed: " + e);
            List<String> g = new ArrayList<String>();
            g.add(STORAGE_ASKED);
            grant(pkg, user, g);
            deliver(a, token, who, requestCode, perms, pkg, user);
        }
    }

    private static void showDialog(final Activity a, final IBinder token, final String who, final int requestCode,
                                   final String[] perms, final List<String> ask, final String pkg, final int user) {
        boolean ar = Locale.getDefault().getLanguage().startsWith("ar");
        PackageManager pm = BlackBoxCore.getContext().getPackageManager();
        CharSequence app = pkg;
        try {
            app = a.getApplicationInfo().loadLabel(a.getPackageManager());
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ar ? "السماح لتطبيق «" + app + "» بـ:" : "Allow \"" + app + "\" to use:");
        for (String p : ask) {
            CharSequence label = p;
            try {
                label = pm.getPermissionInfo(p, 0).loadLabel(pm);
            } catch (Throwable ignored) {
            }
            sb.append("\n• ").append(label);
        }
        try {
            new AlertDialog.Builder(a)
                    .setMessage(sb.toString())
                    .setCancelable(false)
                    .setPositiveButton(ar ? "سماح" : "Allow", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            grant(pkg, user, ask);
                            deliver(a, token, who, requestCode, perms, pkg, user);
                        }
                    })
                    .setNegativeButton(ar ? "رفض" : "Deny", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            deliver(a, token, who, requestCode, perms, pkg, user);
                        }
                    })
                    .show();
        } catch (Throwable e) {
            Log.w(TAG, "permission dialog failed: " + e);
            deliver(a, token, who, requestCode, perms, pkg, user);
        }
    }

    private static void deliver(Activity a, IBinder token, String who, int requestCode, String[] perms, String pkg, int user) {
        Set<String> have = granted(pkg, user);
        int[] results = new int[perms.length];
        for (int i = 0; i < perms.length; i++) {
            if (isStorage(perms[i])) {
                results[i] = have.contains(STORAGE_ASKED) ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
                continue;
            }
            int real = hostCheck(perms[i]);
            if (real == PackageManager.PERMISSION_GRANTED && isDangerous(perms[i]) && !have.contains(perms[i])) {
                real = PackageManager.PERMISSION_DENIED;
            }
            results[i] = real;
        }
        Intent data = new Intent(ACTION_REQUEST);
        data.putExtra(EXTRA_NAMES, perms);
        data.putExtra(EXTRA_RESULTS, results);
        try {
            Object at = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null);
            Method m = at.getClass().getMethod("sendActivityResult", IBinder.class, String.class, int.class, int.class, Intent.class);
            m.invoke(at, token, who, requestCode, Activity.RESULT_OK, data);
            return;
        } catch (Throwable e) {
            Log.w(TAG, "sendActivityResult failed, trying direct dispatch: " + e);
        }
        try {
            Method m = Activity.class.getDeclaredMethod("dispatchRequestPermissionsResult", int.class, Intent.class);
            m.setAccessible(true);
            m.invoke(a, requestCode, data);
        } catch (Throwable e) {
            Log.e(TAG, "could not deliver permission result", e);
        }
    }
}
