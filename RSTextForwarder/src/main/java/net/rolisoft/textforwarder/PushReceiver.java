package net.rolisoft.textforwarder;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.telephony.gsm.SmsManager;
import android.widget.Toast;

import com.google.android.gms.gcm.GoogleCloudMessaging;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.apache.http.util.EntityUtils;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class PushReceiver extends BroadcastReceiver {

    public enum Actions {
        cmd, ping, text, _null
    }

    public enum Commands {
        help, contact, whois, locate, _null
    }

    @Override
    public void onReceive(Context context, Intent intent)
    {
        SharedPreferences sp = context.getSharedPreferences("fwd", 0);
        if (!sp.getBoolean("forward", true) || sp.getString("reg_id", null) == null || !MainActivity.isConnectedToInternet(context)) {
            MainActivity.displayNotification(context, "Push rejected", "Forwarding is disabled.");
            return;
        }

        WakeLocker.push(context);
        GoogleCloudMessaging gcm = GoogleCloudMessaging.getInstance(context);

        try {
            String action = intent.getStringExtra("action");
            String msgType = gcm.getMessageType(intent);

            if (!msgType.contentEquals("gcm")) {
                MainActivity.displayNotification(context, "Push failed", "Received message type: " + msgType);
                return;
            }

            Actions actEn;
            try { actEn = Actions.valueOf(action); } catch (Exception ex) { actEn = Actions._null; }

            switch (actEn)
            {
                case cmd:
                    handleCmd(context, intent, sp);
                    break;

                case ping:
                    MainActivity.sendRequestAsync(context, "pingback", new ArrayList<NameValuePair>(Arrays.asList(
                        new BasicNameValuePair("gacc", sp.getString("g_acc", null)),
                        new BasicNameValuePair("time", intent.getStringExtra("time"))
                    )));
                    break;

                case text:
                    String to = intent.getStringExtra("to");
                    String body = intent.getStringExtra("body");

                    MainActivity.displayNotification(context, "Push received", "Sending SMS to " + to + ": \"" + body + "\"");
                    break;

                default:
                    MainActivity.displayNotification(context, "Push failed", "Unsupported action type: " + action);
                    String from = intent.hasExtra("_addr") ? intent.getStringExtra("_addr") : "";
                    MainActivity.sendMessageAsync(context, sp, "send", from, (!from.equals("") ? "*** " : "") + "The specified push action \"" + action + "\" is not supported. The server is probably newer than the client on your device.");
                    break;
            }
        } catch (Exception ex) {
            MainActivity.displayNotification(context, "Push failed", ex.getClass().getName() + ": " + ex.getMessage());
        } finally {
            gcm.close();
            WakeLocker.pop();
        }
    }

    private void handleCmd(final Context context, final Intent intent, final SharedPreferences sp)
    {
        WakeLocker.push(context);

        final String cmd = intent.getStringExtra("cmd");
        final String arg = intent.getStringExtra("arg");
        final String from = intent.hasExtra("_addr") ? intent.getStringExtra("_addr") : "";

        Commands cmdEn;
        try { cmdEn = Commands.valueOf(cmd); } catch (Exception ex) { cmdEn = Commands._null; }

        switch (cmdEn)
        {
            case help:
                MainActivity.sendMessageAsync(context, sp, "send", from, "List of supported commands by the client:\n/locate — Replies with the last known network and GPS locations.");
                break;

            case locate:
                locate(context, intent, sp);
                break;

            default:
                MainActivity.sendMessageAsync(context, sp, "send", from, (!from.equals("") ? "*** " : "") + "The specified command \"" + cmd + "\" is not supported. Reply \"/help server\" or \"/help device\" for the list of supported commands.");
                break;
        }

        WakeLocker.pop();
    }

    private void locate(final Context context, final Intent intent, final SharedPreferences sp)
    {
        String body;
        Location location;
        LocationManager locationManager = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);

        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                body = "Last known network location: https://maps.google.com/maps?q=" + location.getLatitude() + "," + location.getLongitude() + " accurate within " + location.getAccuracy() + " meters.";
            } else {
                body = "Network location provider is not enabled on the device currently.";
            }
        } catch (Exception ex) {
            body = "Error while retrieving network location: " + ex.getClass().getName() + ": " + ex.getMessage();
        }

        MainActivity.sendMessageAsync(context, sp, "send", body);

        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                body = "Last known GPS location: https://maps.google.com/maps?q=" + location.getLatitude() + "," + location.getLongitude() + " accurate within " + location.getAccuracy() + " meters.";
            } else {
                body = "GPS provider is not enabled on the device currently.";
            }
        } catch (Exception ex) {
            body = "Error while retrieving GPS location: " + ex.getClass().getName() + ": " + ex.getMessage();
        }

        MainActivity.sendMessageAsync(context, sp, "send", body);
    }

}
