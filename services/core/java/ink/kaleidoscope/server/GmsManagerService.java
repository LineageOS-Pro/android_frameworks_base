/*
 * Copyright (C) 2022 Project Kaleidoscope
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ink.kaleidoscope.server;

import static android.os.Process.THREAD_PRIORITY_DEFAULT;
import static android.provider.Settings.Secure.GMS_ENABLED;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.IUserManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;

import com.android.server.ServiceThread;
import com.android.server.SystemService;

import java.lang.Boolean;
import java.lang.IllegalArgumentException;
import java.lang.Integer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * GMS运行时开关服务
 * 允许用户在运行时开启/关闭Google Mobile Services
 * 当关闭时, GMS组件会从PackageManager中隐藏, 且应用会被禁用
 */
public final class GmsManagerService extends SystemService {

    // GMS相关包名列表
    private static final String[] GMS_PACKAGES =
    {
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gms.policy_sidecar_aps",
        "com.google.android.gsf",
        "com.google.android.syncadapters.calendar",
        "com.google.android.syncadapters.contacts",
        "com.google.android.projection.gearhead",
        "com.google.android.apps.wellbeing",
    };

    private static final String TAG = "GmsManagerService";

    // 每个用户缓存的GMS启用状态
    private static HashMap<Integer, Boolean> sCachedSettings = new HashMap<>();

    private final Context mContext;
    private final IPackageManager mPM;
    private final IUserManager mUM;
    private final ContentResolver mResolver;
    private final String mOpPackageName;

    private ServiceThread mWorker;
    private Handler mHandler;
    private HashMap<Integer, SettingsObserver> mObservers;
    private boolean mInitialized;

    /**
     * 检查指定包名是否应被隐藏
     */
    public static boolean shouldHide(int userId, String packageName) {
        if (packageName == null)
            return false;

        Boolean enabled = sCachedSettings.get(userId);
        if (enabled == null)
            return false;

        return !enabled.booleanValue() &&
                Arrays.stream(GMS_PACKAGES).anyMatch(packageName::equals);
    }

    /**
     * 从已安装包列表中过滤掉GMS包
     */
    public static ParceledListSlice<PackageInfo> recreatePackageList(
                            int userId, ParceledListSlice<PackageInfo> list) {
        Boolean enabled = sCachedSettings.get(userId);
        if (enabled == null || enabled.booleanValue())
            return list;

        List<PackageInfo> oldList = list.getList();
        ArrayList<PackageInfo> newList = new ArrayList<>();
        for (PackageInfo info : oldList) {
            if (info.packageName != null &&
                    Arrays.stream(GMS_PACKAGES).anyMatch(info.packageName::equals))
                continue;
            newList.add(info);
        }

        return new ParceledListSlice<>(newList);
    }

    /**
     * 从已安装应用列表中过滤掉GMS应用
     */
    public static List<ApplicationInfo> recreateApplicationList(
                            int userId, List<ApplicationInfo> list) {
        Boolean enabled = sCachedSettings.get(userId);
        if (enabled == null || enabled.booleanValue())
            return list;

        ArrayList<ApplicationInfo> newList = new ArrayList<>();
        for (ApplicationInfo info : list) {
            if (info.packageName != null &&
                    Arrays.stream(GMS_PACKAGES).anyMatch(info.packageName::equals))
                continue;
            newList.add(info);
        }

        return newList;
    }

    /**
     * 更新指定用户的GMS状态
     */
    private void updateStateForUser(int userId) {
        boolean enabled = Settings.Secure.getIntForUser(mResolver, GMS_ENABLED, 0, userId) == 1;
        sCachedSettings.put(userId, enabled);

        try {
            for (String packageName : GMS_PACKAGES) {
                try {
                    if (enabled) {
                        mPM.setApplicationEnabledSetting(packageName,
                                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                                0, userId, mOpPackageName);
                    } else {
                        mPM.setApplicationEnabledSetting(packageName,
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                0, userId, mOpPackageName);
                    }
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * 初始化指定用户的监听器
     */
    private void initForUser(int userId) {
        if (userId < 0)
            return;

        SettingsObserver observer = new SettingsObserver(mHandler, userId);
        mResolver.registerContentObserver(
                Settings.Secure.getUriFor(GMS_ENABLED), false, observer, userId);
        mObservers.put(userId, observer);

        updateStateForUser(userId);
    }

    /**
     * 清理指定用户的监听器
     */
    private void deInitForUser(int userId) {
        if (userId < 0)
            return;

        SettingsObserver observer = mObservers.get(userId);
        if (observer == null)
            return;

        mResolver.unregisterContentObserver(observer);
        mObservers.remove(userId);
        sCachedSettings.remove(userId);
    }

    /**
     * 初始化服务: 为所有现有用户注册监听器
     */
    private void init() {
        if (mInitialized)
            return;

        mInitialized = true;

        try {
            for (UserInfo user : mUM.getUsers(false)) {
                initForUser(user.id);
            }
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiver(new UserReceiver(), filter,
                android.Manifest.permission.MANAGE_USERS, mHandler);
    }

    @Override
    public void onStart() {
        mWorker = new ServiceThread(TAG, THREAD_PRIORITY_DEFAULT, false);
        mWorker.start();
        mHandler = new Handler(mWorker.getLooper());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_ACTIVITY_MANAGER_READY) {
            mHandler.post(this::init);
        }
    }

    public GmsManagerService(Context context) {
        super(context);
        mContext = context;
        mResolver = context.getContentResolver();
        mPM = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        mUM = IUserManager.Stub.asInterface(ServiceManager.getService(Context.USER_SERVICE));
        mOpPackageName = context.getOpPackageName();
        mObservers = new HashMap<>();
    }

    /**
     * 用户添加/移除广播接收器
     */
    private final class UserReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, -1);

            if (Intent.ACTION_USER_ADDED.equals(intent.getAction()))
                initForUser(userId);
            else
                deInitForUser(userId);
        }
    }

    /**
     * Settings内容观察器
     */
    private final class SettingsObserver extends ContentObserver {
        private int mUserId;

        public SettingsObserver(Handler handler, int userId) {
            super(handler);
            mUserId = userId;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateStateForUser(mUserId);
        }
    }
}
