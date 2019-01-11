package com.ryuunoakaihitomi.rebootmenu.util.hook;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemService;
import android.util.Log;

import com.ryuunoakaihitomi.rebootmenu.IRMPowerActionService;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * IRMPowerActionService AIDL的具体实现
 * Created by ZQY on 2019/1/3.
 */

class RMPowerActionService extends IRMPowerActionService.Stub {

    static final String TAG = "RMPowerActionService";

    @SuppressLint("StaticFieldLeak")
    private static Context mContext;
    private PackageManager mPackageManager;
    private PowerManager mPowerManager;
    //API比aidl稳定，但是导包是个问题，现在暂时使用反射将就下
    private Method goToSleep, rebootSafeMode, shutdown;

    RMPowerActionService(Context context) {
        Log.d(TAG, "Constructor: " + context);
        mContext = context;
    }

    @Override
    public void lockScreen() {
        Log.i(TAG, "lockScreen");
        injectSystemThread(() -> {
            try {
                goToSleep.invoke(mPowerManager, SystemClock.uptimeMillis());
            } catch (Throwable throwable) {
                Log.e(TAG, "run: ", throwable);
            }
        });
    }

    @Override
    public void reboot(String reason) {
        Log.i(TAG, "reboot: reason=" + reason);
        injectSystemThread(() -> mPowerManager.reboot(reason));
    }

    @TargetApi(Build.VERSION_CODES.N)
    @Override
    public void safeMode() {
        Log.i(TAG, "safeMode");
        injectSystemThread(() -> {
            try {
                rebootSafeMode.invoke(mPowerManager);
            } catch (Throwable throwable) {
                Log.e(TAG, "run: ", throwable);
            }
        });
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    @Override
    public void shutdown() {
        Log.i(TAG, "shutdown");
        injectSystemThread(() -> {
            try {
                shutdown.invoke(mPowerManager, false, null, false);
            } catch (IllegalAccessException | InvocationTargetException e) {
                Log.e(TAG, "run: ", e);
            }
        });
    }

    @Override
    public void hotReboot() {
        Log.i(TAG, "hotReboot");
        injectSystemThread(() -> {
            //throw new IllegalStateException("Hot Reboot Request");    /*在此抛出异常也会导致热重启*/
            SystemService.restart("zygote");
        });
    }

    //测试服务状态
    @Override
    public void ping() {
        Log.i(TAG, "ping: " + XposedUtils.varArgsToString(mPowerManager, mPackageManager, mContext, Process.myUid(), getCallingPid(), getCallingUid()));
        injectSystemThread(() ->
                Log.i(TAG, "ping: " + XposedUtils.varArgsToString(pingBinder(), getCallingPid(), getCallingUid())));
    }

    //插入到系统线程才有系统权限
    private void injectSystemThread(Runnable r) {
        new Handler(mContext.getMainLooper()).post(r);
    }

    //所有的系统服务都已经初始化完成
    @SuppressWarnings({"JavaReflectionMemberAccess", "unchecked"})
    @TargetApi(Build.VERSION_CODES.N)
    void allServicesInitialised() {
        Log.d(TAG, "allServicesInitialised");
        mPowerManager = mContext.getSystemService(PowerManager.class);
        //PackageManager无法用以下方法从system_server的context中获取
        //mPackageManager = mContext.getSystemService(PackageManager.class);
        mPackageManager = mContext.getPackageManager();
        Class pmCls = PowerManager.class;
        try {

            /*
             * Forces the device to go to sleep.
             * <p>
             * Overrides all the wake locks that are held.
             * This is what happens when the power key is pressed to turn off the screen.
             * </p><p>
             * Requires the {@link android.Manifest.permission#DEVICE_POWER} permission.
             * </p>
             *
             * @param time The time when the request to go to sleep was issued, in the
             * {@link SystemClock#uptimeMillis()} time base.  This timestamp is used to correctly
             * order the go to sleep request with other power management functions.  It should be set
             * to the timestamp of the input event that caused the request to go to sleep.
             *
             * @see #userActivity
             * @see #wakeUp
             *
             * @removed Requires signature permission.
             */
            goToSleep = pmCls.getMethod("goToSleep", long.class);

            /*
             * Reboot the device. Will not return if the reboot is successful.
             * <p>
             * Requires the {@link android.Manifest.permission#REBOOT} permission.
             * </p>
             * @hide
             */
            rebootSafeMode = pmCls.getMethod("rebootSafeMode");

            /*
             * Turn off the device.
             *
             * @param confirm If true, shows a shutdown confirmation dialog.
             * @param reason code to pass to android_reboot() (e.g. "userrequested"), or null.
             * @param wait If true, this call waits for the shutdown to complete and does not return.
             *
             * @hide
             */
            shutdown = pmCls.getMethod("shutdown", boolean.class, String.class, boolean.class);
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "allServicesInitialised: ", e);
        }
        Log.d(TAG, "allServicesInitialised: Methods:" + XposedUtils.varArgsToString(goToSleep, rebootSafeMode, shutdown));
    }


    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        ensureInvokePermission();
        return super.onTransact(code, data, reply, flags);
    }

    /**
     * 权限检查
     */
    private void ensureInvokePermission() {
        if (mPackageManager == null)
            throw new IllegalStateException("mPackageManager not initialized");
        String[] packages = mPackageManager.getPackagesForUid(Binder.getCallingUid());
        if (packages != null && packages.length > 0) {
            for (String pn : packages) {
                Log.d(TAG, "ensureInvokePermission: pn=" + pn);
                if (!pn.startsWith("com.ryuunoakaihitomi.rebootmenu"))
                    throw new SecurityException("Permission Denied!");
            }
        } else
            throw new NullPointerException("packages is null");
    }
}