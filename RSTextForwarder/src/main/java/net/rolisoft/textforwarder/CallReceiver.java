package net.rolisoft.textforwarder;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;

public class CallReceiver extends BroadcastReceiver {

    private final String TAG = this.toString();

    @Override
    public void onReceive(final Context context, Intent intent)
    {
        Log.i(TAG, "Received phone state broadcast...");

        final SharedPreferences sp = context.getSharedPreferences("fwd", 0);
        if (!sp.getBoolean("forward", true) || !sp.getBoolean("forward_call", true) || !MainActivity.isConnectedToInternet(context)) {
            Log.w(TAG, "Forwarding disabled or no internet connection.");
            return;
        }

        WakeLocker.pushd(context);

        try {
            Bundle bundle = intent.getExtras();
            if (bundle == null) {
                return;
            }

            String state = bundle.getString(TelephonyManager.EXTRA_STATE);
            if (!state.contentEquals(TelephonyManager.EXTRA_STATE_RINGING)) {
                return;
            }

            String from = bundle.getString(TelephonyManager.EXTRA_INCOMING_NUMBER);
            Contact contact = ContactTools.findContact(context, from, true);
            StringBuilder sb = new StringBuilder();

            sb.append("Incoming call from ");

            if (contact != null) {
                sb.append(contact.name + " via " + ContactTools.formatNumber(context, contact.selected.number));
            } else {
                sb.append(ContactTools.formatNumber(context, from));
            }

            sb.append("...");

            MainActivity.sendMessageAsync(context, sp, "send", sb.toString());
        } catch (Exception ex) {
            Log.e(TAG, "Error while extracting phone state from intent.", ex);
            MainActivity.displayNotification(context, "Request to send failed", "Local error: " + ex.getClass().getName() + ": " + ex.getMessage());
        } finally {
            WakeLocker.popd();
        }

        setResultCode(Activity.RESULT_OK);
    }

}
