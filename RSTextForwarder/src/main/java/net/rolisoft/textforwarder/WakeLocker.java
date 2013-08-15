package net.rolisoft.textforwarder;

import android.content.Context;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.Log;

public class WakeLocker {

    private static final String TAG = WakeLocker.class.getName();

    private static WakeLock _wl;
    private static int _ctr = 0;

    public static void acquire(Context context)
    {
        release();
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        _wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeLock");
        _wl.acquire();

        Log.i(TAG, "Acquired partial wakelock.");
    }

    public static void acquire(Context context, long timeout)
    {
        release();
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        _wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeLock");
        _wl.acquire(timeout);

        Log.i(TAG, "Acquired partial wakelock with " + (timeout / 1000) + " sec timeout.");
    }

    public static void release()
    {
        if (_wl != null) {
            _wl.release();
            _wl = null;
            Log.i(TAG, "Released wakelock.");
        }
    }

    public static void push(Context context)
    {
        if (_ctr <= 0 && (_wl == null || !_wl.isHeld())) {
            acquire(context);
            _ctr = 1;
            Log.i(TAG, "Wakelock counter is " + _ctr);
        } else {
            _ctr++;
            Log.i(TAG, "Increased wakelock counter to " + _ctr);
        }
    }

    public static void pushd(Context context)
    {
        push(context);
        push(context);
    }

    public static void pop()
    {
        if (_ctr <= 1 && _wl != null && _wl.isHeld()) {
            _ctr = 0;
            Log.i(TAG, "Wakelock counter is " + _ctr);
            release();
        } else {
            _ctr--;
            Log.i(TAG, "Decreased wakelock counter to " + _ctr);
        }
    }

    public static void popd()
    {
        pop();
        pop();
    }

}
