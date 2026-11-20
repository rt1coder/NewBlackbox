package top.niunaijun.blackbox.core.system.notification;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.util.Log;
import android.os.Binder;
import android.os.Build;
import android.os.RemoteException;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import black.android.app.BRNotificationChannel;
import black.android.app.BRNotificationChannelGroup;
import black.android.app.BRNotificationO;
import black.android.app.NotificationChannelContext;
import black.android.app.NotificationChannelGroupContext;
import black.android.app.NotificationOContext;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.BProcessManagerService;
import top.niunaijun.blackbox.core.system.ISystemService;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


public class BNotificationManagerService extends IBNotificationManagerService.Stub implements ISystemService {
    private final static BNotificationManagerService sService = new BNotificationManagerService();
    public static final String CHANNEL_BLACK = "@black-";
    public static final String GROUP_BLACK = "@black-group-";

    private NotificationChannelManager mNotificationChannelManager;
    private final Map<String, NotificationRecord> mNotificationRecords = new HashMap<>();

    private final NotificationManager mRealNotificationManager =
            (NotificationManager) BlackBoxCore.getContext().getSystemService(Context.NOTIFICATION_SERVICE);

    public static BNotificationManagerService get() {
        return sService;
    }

    @Override
    public void systemReady() {
        mNotificationChannelManager = NotificationChannelManager.get();
    }


    private NotificationRecord getNotificationRecord(String packageName, int userId) {
        String key = packageName + "-" + userId;
        synchronized (mNotificationRecords) {
            NotificationRecord notificationRecord = mNotificationRecords.get(key);
            if (notificationRecord == null) {
                notificationRecord = new NotificationRecord();
                mNotificationRecords.put(key, notificationRecord);
            }
            return notificationRecord;
        }
    }

    private void removeNotificationRecord(String packageName, int userId) {
        String key = packageName + "-" + userId;
        synchronized (mNotificationRecords) {
            mNotificationRecords.remove(key);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public NotificationChannel getNotificationChannel(String channelId, int userId) throws RemoteException {
        int callingPid = getCallingPid();
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(callingPid);
        if (processByPid == null)
            return null;
        NotificationRecord notificationRecord = getNotificationRecord(processByPid.getPackageName(), userId);
        synchronized (notificationRecord.mNotificationChannels) {
            return notificationRecord.mNotificationChannels.get(channelId);
        }
    }

    @Override
    public List<NotificationChannel> getNotificationChannels(String packageName, int userId) throws RemoteException {
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        synchronized (notificationRecord.mNotificationChannels) {
            return new ArrayList<>(notificationRecord.mNotificationChannels.values());
        }
    }

    @Override
    public List<NotificationChannelGroup> getNotificationChannelGroups(String packageName, int userId) throws RemoteException {
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        synchronized (notificationRecord.mNotificationChannelGroups) {
            return new ArrayList<>(notificationRecord.mNotificationChannelGroups.values());
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public void createNotificationChannel(NotificationChannel notificationChannel, int userId) {
        int callingPid = getCallingPid();
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(callingPid);
        if (processByPid == null)
            return;
        handleNotificationChannel(notificationChannel, userId);
        mRealNotificationManager.createNotificationChannel(notificationChannel);

        resetNotificationChannel(notificationChannel);
        NotificationRecord notificationRecord = getNotificationRecord(processByPid.getPackageName(), userId);
        synchronized (notificationRecord.mNotificationChannels) {
            notificationRecord.mNotificationChannels.put(notificationChannel.getId(), notificationChannel);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public void deleteNotificationChannel(String channelId, int userId) {
        int callingPid = getCallingPid();
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(callingPid);
        if (processByPid == null)
            return;
        NotificationRecord notificationRecord = getNotificationRecord(processByPid.getPackageName(), userId);
        synchronized (notificationRecord.mNotificationChannels) {
            NotificationChannel remove = notificationRecord.mNotificationChannels.remove(channelId);
            if (remove != null) {
                String blackChannelId = getBlackChannelId(remove.getId(), userId);
                mRealNotificationManager.deleteNotificationChannel(blackChannelId);
            }
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public void createNotificationChannelGroup(NotificationChannelGroup notificationChannelGroup, int userId) {
        int callingPid = getCallingPid();
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(callingPid);
        if (processByPid == null)
            return;
        handleNotificationGroup(notificationChannelGroup, userId);
        mRealNotificationManager.createNotificationChannelGroup(notificationChannelGroup);

        resetNotificationGroup(notificationChannelGroup);
        NotificationRecord notificationRecord = getNotificationRecord(processByPid.getPackageName(), userId);
        synchronized (notificationRecord.mNotificationChannelGroups) {
            notificationRecord.mNotificationChannelGroups.put(notificationChannelGroup.getId(), notificationChannelGroup);
        }
    }

    @Override
    @TargetApi(Build.VERSION_CODES.O)
    public void deleteNotificationChannelGroup(String groupId, int userId) {
        int callingPid = getCallingPid();
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(callingPid);
        if (processByPid == null)
            return;
        NotificationRecord notificationRecord = getNotificationRecord(processByPid.getPackageName(), userId);
        synchronized (notificationRecord.mNotificationChannelGroups) {
            NotificationChannelGroup remove = notificationRecord.mNotificationChannelGroups.remove(groupId);
            if (remove != null) {
                String blackGroupId = getBlackGroupId(remove.getId(), userId);
                mRealNotificationManager.deleteNotificationChannelGroup(blackGroupId);
            }
        }
    }

    @Override
    public void enqueueNotificationWithTag(int id, String tag, Notification notification, int userId) {
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        if (processByPid == null)
            return;
        int notificationId = getNotificationId(userId, id, processByPid.getPackageName());

        if (BuildCompat.isOreo()) {
            NotificationOContext notificationOContext = BRNotificationO.get(notification);
            
            if (notificationOContext._check_mChannelId() != null) {
                String blackChannelId = getBlackChannelId(notificationOContext.mChannelId(), userId);
                notificationOContext._set_mChannelId(blackChannelId);
            }
            
            if (notificationOContext._check_mGroupKey() != null) {
                String blackGroupId = getBlackGroupId(notificationOContext.mGroupKey(), userId);
                notificationOContext._set_mGroupKey(blackGroupId);
            }
        }
        NotificationRecord notificationRecord = getNotificationRecord(processByPid.getPackageName(), userId);
        synchronized (notificationRecord.mIds) {
            notificationRecord.mIds.add(notificationId);
        }
        decorate(notification, processByPid.getPackageName(), userId);
        mRealNotificationManager.notify(notificationId, notification);
    }

    private final Map<String, CharSequence> mLabels = new HashMap<>();

    private void decorate(Notification n, String pkg, int userId) {
        if (n == null || pkg == null) return;
        try {
            ApplicationInfo ai = BPackageManagerService.get().getApplicationInfo(pkg, 0, userId);
            if (ai == null) return;
            PackageManager pm = BlackBoxCore.getContext().getPackageManager();
            String key = pkg + "@" + userId;
            CharSequence label;
            synchronized (mLabels) {
                label = mLabels.get(key);
            }
            if (label == null) {
                try {
                    label = ai.loadLabel(pm);
                } catch (Throwable ignored) {
                }
                if (label == null) label = pkg;
                synchronized (mLabels) {
                    mLabels.put(key, label);
                }
            }
            if (n.extras != null && n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT) == null
                    && !pkg.contentEquals(label)) {
                n.extras.putCharSequence(Notification.EXTRA_SUB_TEXT, label);
            }
            if (Build.VERSION.SDK_INT >= 28) fixSmallIcon(n, pkg, ai, pm);
        } catch (Throwable e) {
            Log.w("BlackBoxNotify", "decorate notification failed for " + pkg + ": " + e);
        }
    }

    @TargetApi(28)
    private void fixSmallIcon(Notification n, String pkg, ApplicationInfo ai, PackageManager pm) throws Exception {
        Icon icon = n.getSmallIcon();
        if (icon == null || icon.getType() != Icon.TYPE_RESOURCE) return;
        int resId = icon.getResId();
        if ((resId >>> 24) == 0x01) return;
        if (!pkg.equals(icon.getResPackage())) return;
        Resources res = pm.getResourcesForApplication(ai);
        Drawable d = res.getDrawable(resId, null);
        if (d == null) return;
        int w = Math.max(1, d.getIntrinsicWidth()), h = Math.max(1, d.getIntrinsicHeight());
        if (w > 256 || h > 256) {
            w = 96;
            h = 96;
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        Field f = Notification.class.getDeclaredField("mSmallIcon");
        f.setAccessible(true);
        f.set(n, Icon.createWithBitmap(bmp));
    }

    @Override
    public void cancelNotificationWithTag(int id, String tag, int userId) throws RemoteException {
        ProcessRecord processByPid = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        if (processByPid == null)
            return;
        int notificationId = getNotificationId(userId, id, processByPid.getPackageName());
        mRealNotificationManager.cancel(notificationId);

        NotificationRecord notificationRecord = getNotificationRecord(processByPid.getPackageName(), userId);
        synchronized (notificationRecord.mIds) {
            notificationRecord.mIds.remove(notificationId);
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void handleNotificationChannel(NotificationChannel notificationChannel, int userId) {
        NotificationChannelContext channelContext = BRNotificationChannel.get(notificationChannel);
        String channelId = channelContext.mId();
        String blackChannelId = getBlackChannelId(channelId, userId);
        channelContext._set_mId(blackChannelId);

        notificationChannel.setGroup(getBlackGroupId(notificationChannel.getGroup(), userId));
    }

    private void resetNotificationChannel(NotificationChannel notificationChannel) {
        NotificationChannelContext channelContext = BRNotificationChannel.get(notificationChannel);
        String channelId = channelContext.mId();
        String realChannelId = getRealChannelId(channelId);
        channelContext._set_mId(realChannelId);
    }

    private void handleNotificationGroup(NotificationChannelGroup notificationChannelGroup, int userId) {
        NotificationChannelGroupContext groupContext = BRNotificationChannelGroup.get(notificationChannelGroup);
        String groupId = groupContext.mId();
        String blackGroupId = getBlackGroupId(groupId, userId);
        groupContext._set_mId(blackGroupId);

        List<NotificationChannel> notificationChannels = groupContext.mChannels();
        if (notificationChannels != null) {
            for (NotificationChannel notificationChannel : notificationChannels) {
                createNotificationChannel(notificationChannel, userId);
            }
        }
    }

    private void resetNotificationGroup(NotificationChannelGroup notificationChannelGroup) {
        NotificationChannelGroupContext groupContext = BRNotificationChannelGroup.get(notificationChannelGroup);
        String groupId = groupContext.mId();
        String realGroupId = getRealGroupId(groupId);
        groupContext._set_mId(realGroupId);

        List<NotificationChannel> notificationChannels = groupContext.mChannels();
        if (notificationChannels != null) {
            for (NotificationChannel notificationChannel : notificationChannels) {
                resetNotificationChannel(notificationChannel);
            }
        }
    }

    @SuppressLint("NewApi")
    public void deletePackageNotification(String packageName, int userId) {
        NotificationRecord notificationRecord = getNotificationRecord(packageName, userId);
        if (BuildCompat.isOreo()) {
            for (NotificationChannelGroup value : notificationRecord.mNotificationChannelGroups.values()) {
                String blackGroupId = getBlackGroupId(value.getId(), userId);
                mRealNotificationManager.deleteNotificationChannelGroup(blackGroupId);
            }
            for (NotificationChannel value : notificationRecord.mNotificationChannels.values()) {
                String blackChannelId = getBlackChannelId(value.getId(), userId);
                mRealNotificationManager.deleteNotificationChannel(blackChannelId);
            }
        }
        for (Integer id : notificationRecord.mIds) {
            mRealNotificationManager.cancel(id);
        }
        removeNotificationRecord(packageName, userId);
    }

    private String getBlackChannelId(String channelId, int userId) {
        if (channelId == null || channelId.contains(CHANNEL_BLACK)) {
            return channelId;
        }
        return channelId + CHANNEL_BLACK + userId;
    }

    private String getRealChannelId(String channelId) {
        if (channelId == null || !channelId.contains(CHANNEL_BLACK)) {
            return channelId;
        }
        return channelId.split(CHANNEL_BLACK)[0];
    }

    private String getBlackGroupId(String groupId, int userId) {
        if (groupId == null || groupId.contains(GROUP_BLACK))
            return groupId;
        return groupId + GROUP_BLACK + userId;
    }

    private String getRealGroupId(String groupId) {
        if (groupId == null || !groupId.contains(GROUP_BLACK))
            return groupId;
        return groupId.split(GROUP_BLACK)[0];
    }

    public static int getNotificationId(int userId, int notificationId, String packageName) {
        return (packageName + userId + notificationId).hashCode();
    }
}
