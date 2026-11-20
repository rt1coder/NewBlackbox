package top.niunaijun.blackbox.fake.service;

import android.os.IBinder;

import java.util.ArrayList;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;

public class IRoleManagerProxy extends BinderInvocationStub {
    private static final String SERVICE = "role";

    public IRoleManagerProxy() {
        super(BRServiceManager.get().getService(SERVICE));
    }

    @Override
    protected Object getWho() {
        try {
            IBinder b = BRServiceManager.get().getService(SERVICE);
            if (b == null) return null;
            return Class.forName("android.app.role.IRoleManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, b);
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(SERVICE);
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("isRoleHeld", false));
        addMethodHook(new ValueMethodProxy("getRoleHolders", new ArrayList<String>()));
        addMethodHook(new ValueMethodProxy("getRoleHoldersAsUser", new ArrayList<String>()));
        addMethodHook(new ValueMethodProxy("getDefaultSmsPackage", null));
        addMethodHook(new ValueMethodProxy("getBrowserRoleHolder", null));
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}
