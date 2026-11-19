package top.niunaijun.blackbox.core;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import top.niunaijun.blackbox.BlackBoxCore;

import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.TrieTree;


@SuppressLint("SdCardPath")
public class IOCore {
    public static final String TAG = "IOCore";
    private static final String LOG = "BlackBoxIO";

    private static final IOCore sIOCore = new IOCore();
    private static final TrieTree mTrieTree = new TrieTree();
    private static final TrieTree sBlackTree = new TrieTree();
    private final Map<String, String> mRedirectMap = new LinkedHashMap<>();

    private static final Map<String, Map<String, String>> sCachePackageRedirect = new HashMap<>();

    public static IOCore get() {
        return sIOCore;
    }

    
    public void addRedirect(String origPath, String redirectPath) {
        if (TextUtils.isEmpty(origPath) || TextUtils.isEmpty(redirectPath) || mRedirectMap.get(origPath) != null)
            return;
        
        mTrieTree.add(origPath);
        mRedirectMap.put(origPath, redirectPath);
        File redirectFile = new File(redirectPath);
        if (!redirectFile.exists()) {
            FileUtils.mkdirs(redirectPath);
        }
        NativeCore.addIORule(origPath, redirectPath);
    }

    public void addBlackRedirect(String path) {
        if (TextUtils.isEmpty(path))
            return;
        sBlackTree.add(path);
    }

    public String redirectPath(String path) {
        if (TextUtils.isEmpty(path))
            return path;
        if (path.contains("/blackbox/")) {
            return path;
        }
        String search = sBlackTree.search(path);
        if (!TextUtils.isEmpty(search))
            return search;

        
        String key = mTrieTree.search(path);
        if (!TextUtils.isEmpty(key))
            path = path.replace(key, Objects.requireNonNull(mRedirectMap.get(key)));

        return path;
    }

    public File redirectPath(File path) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr));
    }

    public String redirectPath(String path, Map<String, String> rule) {
        if (TextUtils.isEmpty(path))
            return path;

        
        String key = mTrieTree.search(path);
        if (!TextUtils.isEmpty(key))
            path = path.replace(key, Objects.requireNonNull(rule.get(key)));

        return path;
    }

    public File redirectPath(File path, Map<String, String> rule) {
        if (path == null)
            return null;
        String pathStr = path.getAbsolutePath();
        return new File(redirectPath(pathStr, rule));
    }

    

    public void enableRedirect(Context context) {
        Map<String, String> rule = new LinkedHashMap<>();
        Set<String> blackRule = new HashSet<>();
        String packageName = context.getPackageName();

        try {
            ApplicationInfo packageInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName, PackageManager.GET_META_DATA, BlackBoxCore.getUserId());
            int systemUserId = BlackBoxCore.getHostUserId();
            rule.put(String.format("/data/data/%s/lib", packageName), packageInfo.nativeLibraryDir);
            rule.put(String.format("/data/user/%d/%s/lib", systemUserId, packageName), packageInfo.nativeLibraryDir);

            rule.put(String.format("/data/data/%s", packageName), packageInfo.dataDir);
            rule.put(String.format("/data/user/%d/%s", systemUserId, packageName), packageInfo.dataDir);

            
            File profilesRoot = new File(BEnvironment.getVirtualRoot(), "profiles");
            FileUtils.mkdirs(profilesRoot.getAbsolutePath());
            
            rule.put("/data/misc/profiles", profilesRoot.getAbsolutePath());

            File profilesCurDir = new File(profilesRoot, String.format("cur/%d/%s", BlackBoxCore.getUserId(), packageName));
            File profilesRefDir = new File(profilesRoot, String.format("ref/%d/%s", BlackBoxCore.getUserId(), packageName));
            FileUtils.mkdirs(profilesCurDir.getAbsolutePath());
            FileUtils.mkdirs(profilesRefDir.getAbsolutePath());
            rule.put(String.format("/data/misc/profiles/cur/%d/%s", BlackBoxCore.getUserId(), packageName), profilesCurDir.getAbsolutePath());
            rule.put(String.format("/data/misc/profiles/ref/%d/%s", BlackBoxCore.getUserId(), packageName), profilesRefDir.getAbsolutePath());

            File external = externalUserRoot(BlackBoxCore.getUserId());
            String[] sub = {"Android/data/" + packageName + "/files", "Android/data/" + packageName + "/cache",
                    "Android/obb/" + packageName, "Download", "Documents", "DCIM", "Movies", "Music"};
            for (String d : sub) new File(external, d).mkdirs();
            Log.w(LOG, "external redirect for " + packageName + " -> " + external
                    + " filesDirReady=" + new File(external, sub[0]).isDirectory());
            rule.put("/sdcard", external.getAbsolutePath());
            rule.put(String.format("/storage/emulated/%d", systemUserId), external.getAbsolutePath());
            rule.put(String.format("/mnt/user/%d/primary", systemUserId), external.getAbsolutePath());
            blackRule.add("/sdcard/Pictures");
            blackRule.add(String.format("/storage/emulated/%d/Pictures", systemUserId));
            if (BlackBoxCore.get().isHideRoot()) {
                hideRoot(rule);
            }
            proc(rule);
        } catch (Throwable e) {
            Log.e(LOG, "enableRedirect failed for " + packageName, e);
        }
        for (String key : rule.keySet()) {
            get().addRedirect(key, rule.get(key));
        }
        try {
            Object os = Class.forName("libcore.io.Libcore").getField("os").get(null);
            Log.w(LOG, "rules=" + rule.size() + " libcoreOs=" + (os == null ? "null" : os.getClass().getName()));
        } catch (Throwable e) {
            Log.w(LOG, "rules=" + rule.size() + " libcoreOs=? " + e);
        }
        for (String s : blackRule) {
            get().addBlackRedirect(s);
        }
        NativeCore.enableIO();
    }

    private static File externalUserRoot(int userId) {
        String rel = "storage/emulated/" + userId;
        File base = BEnvironment.getExternalVirtualRoot();
        if (base == null) {
            try {
                base = BlackBoxCore.getContext().getExternalFilesDir("blackbox");
            } catch (Throwable ignored) {
            }
        }
        if (base == null) {
            base = new File(Environment.getExternalStorageDirectory(),
                    "Android/data/" + BlackBoxCore.getHostPkg() + "/files/blackbox");
        }
        File ext = new File(base, rel);
        if (writable(ext)) return ext;
        File internal = new File(BEnvironment.getVirtualRoot(), "external/" + rel);
        Log.w(LOG, "external root not writable (" + ext + "), using internal " + internal);
        writable(internal);
        return internal;
    }

    private static boolean writable(File dir) {
        try {
            dir.mkdirs();
            if (!dir.isDirectory()) return false;
            File t = new File(dir, ".bb_w");
            new java.io.FileOutputStream(t).close();
            t.delete();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private void hideRoot(Map<String, String> rule) {
        rule.put("/system/app/Superuser.apk", "/system/app/Superuser.apk-fake");
        rule.put("/sbin/su", "/sbin/su-fake");
        rule.put("/system/bin/su", "/system/bin/su-fake");
        rule.put("/system/xbin/su", "/system/xbin/su-fake");
        rule.put("/data/local/xbin/su", "/data/local/xbin/su-fake");
        rule.put("/data/local/bin/su", "/data/local/bin/su-fake");
        rule.put("/system/sd/xbin/su", "/system/sd/xbin/su-fake");
        rule.put("/system/bin/failsafe/su", "/system/bin/failsafe/su-fake");
        rule.put("/data/local/su", "/data/local/su-fake");
        rule.put("/su/bin/su", "/su/bin/su-fake");
    }

    private void proc(Map<String, String> rule) {
        int appPid = BlackBoxCore.getAppPid();
        int pid = Process.myPid();
        String selfProc = "/proc/self/";
        String proc = "/proc/" + pid + "/";

        String cmdline = new File(BEnvironment.getProcDir(appPid), "cmdline").getAbsolutePath();
        rule.put(proc + "cmdline", cmdline);
        rule.put(selfProc + "cmdline", cmdline);
    }
}
