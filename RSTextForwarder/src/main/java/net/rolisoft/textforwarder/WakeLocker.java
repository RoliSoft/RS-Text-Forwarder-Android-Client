package net.rolisoft.textforwarder;

import android.content.Context;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;

public abstract class WakeLocker {

    private static WakeLock _wl;
    private static int _ctr = 0;

    public static void acquire(Context context)
    {
        release();
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        _wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeLock");
        _wl.acquire();
    }

    public static void acquire(Context context, long timeout)
    {
        release();
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

    public static void push(Context context)
    {
        if (_ctr <= 0 && (_wl == null || !_wl.isHeld())) {
            acquire(context);
            _ctr = 1;
        } else {
            _ctr++;
        }
    }

    public static void pop()
    {
        if (_ctr <= 0 && _wl != null && _wl.isHeld())
        {
            _ctr = 0;
            release();
        } else {
            _ctr--;
        }
    }

}
