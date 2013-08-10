package net.rolisoft.textforwarder;

import android.content.Context;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

public abstract class WakeLocker {

    private static WakeLock _wl;

    public static void acquire(Context context, long timeout)
    {
        if (_wl != null) {
            _wl.release();
        }

        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        _wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeLock");
        _wl.acquire(timeout);
    }

    public static void release()
    {
        if (_wl != null) {
            _wl.release();
            _wl = null;
        }
    }
}
