package top.niunaijun.blackbox.fake.service;

import android.content.Context;

import java.lang.reflect.Method;

import black.android.app.BRIAlarmManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;


public class IAlarmManagerProxy extends BinderInvocationStub {

    public IAlarmManagerProxy() {
        super(BRServiceManager.get().getService(Context.ALARM_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIAlarmManagerStub.get().asInterface(BRServiceManager.get().getService(Context.ALARM_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.ALARM_SERVICE);
    }

    @ProxyMethod("set")
    public static class Set extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                MethodParameterUtils.replaceFirstAppPkg(args);
                return method.invoke(who, args);
            } catch (Throwable e) {
                Slog.w("IAlarmManagerProxy", "set failed, alarm dropped: " + e);
                return 0;
            }
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
