package top.niunaijun.blackbox.core.engine;

import android.os.Process;
import android.util.Log;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;

public final class EngineCrash implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "MocgramEngine";
    private static EngineCrash sInstalled;

    private final Thread.UncaughtExceptionHandler mNext;
    private final boolean mGuest;

    private EngineCrash(Thread.UncaughtExceptionHandler next, boolean guest) {
        mNext = next;
        mGuest = guest;
    }

    private static volatile Throwable sLastReported;
    private static boolean sPreInstalled;

    private static void report(Thread t, Throwable e, boolean guest) {
        if (e == null || e == sLastReported) return;
        sLastReported = e;
        String pkg = null;
        if (guest) {
            try {
                pkg = BActivityThread.getAppPackageName();
            } catch (Throwable ignored) {
            }
        }
        EngineLog.writeCrash(guest ? "app crash" : "engine crash", t, e, pkg);
    }

    private static void installPreHandler(final boolean guest) {
        if (sPreInstalled) return;
        try {
            java.lang.reflect.Method get = Thread.class.getDeclaredMethod("getUncaughtExceptionPreHandler");
            java.lang.reflect.Method set = Thread.class.getDeclaredMethod("setUncaughtExceptionPreHandler", Thread.UncaughtExceptionHandler.class);
            final Thread.UncaughtExceptionHandler prev = (Thread.UncaughtExceptionHandler) get.invoke(null);
            set.invoke(null, new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        if (!top.niunaijun.blackbox.utils.SimpleCrashFix.isRecoverable(t, e)) report(t, e, guest);
                    } catch (Throwable ignored) {
                    }
                    if (prev != null) prev.uncaughtException(t, e);
                }
            });
            sPreInstalled = true;
        } catch (Throwable e) {
            Log.w(TAG, "pre-handler not available: " + e);
        }
    }

    public static synchronized void install(boolean guestProcess) {
        installPreHandler(guestProcess);
        Thread.UncaughtExceptionHandler cur = Thread.getDefaultUncaughtExceptionHandler();
        if (cur instanceof EngineCrash) {
            if (((EngineCrash) cur).mGuest == guestProcess) return;
            cur = ((EngineCrash) cur).mNext;
        }
        sInstalled = new EngineCrash(cur, guestProcess);
        Thread.setDefaultUncaughtExceptionHandler(sInstalled);
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        if (top.niunaijun.blackbox.utils.SimpleCrashFix.isRecoverable(t, e)) {
            EngineLog.error(TAG, "recovered from a crash on background thread " + t.getName(), e);
            return;
        }
        report(t, e, mGuest);
        String pkg = null;
        if (mGuest) {
            try {
                pkg = BActivityThread.getAppPackageName();
            } catch (Throwable ignored) {
            }
        }
        try {
            Thread.UncaughtExceptionHandler user = BlackBoxCore.get().getExceptionHandler();
            if (user != null) user.uncaughtException(t, e);
        } catch (Throwable ignored) {
        }
        if (mGuest) {
            Log.e(TAG, "app " + pkg + " crashed; closing only its process", e);
            Process.killProcess(Process.myPid());
            System.exit(10);
            return;
        }
        if (mNext != null && mNext != this) {
            mNext.uncaughtException(t, e);
        } else {
            Process.killProcess(Process.myPid());
            System.exit(10);
        }
    }
}
