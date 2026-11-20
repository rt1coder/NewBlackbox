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

    public static synchronized void install(boolean guestProcess) {
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
        String pkg = null;
        if (mGuest) {
            try {
                pkg = BActivityThread.getAppPackageName();
            } catch (Throwable ignored) {
            }
        }
        EngineLog.writeCrash(mGuest ? "app crash" : "engine crash", t, e, pkg);
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
