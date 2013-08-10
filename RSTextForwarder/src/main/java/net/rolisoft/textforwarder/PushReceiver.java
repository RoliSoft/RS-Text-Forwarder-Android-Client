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

    @Override
    public void onReceive(Context context, Intent intent)
    {
        SharedPreferences sp = context.getSharedPreferences("fwd", 0);
        if (!sp.getBoolean("forward", true) || sp.getString("reg_id", null) == null || !MainActivity.isConnectedToInternet(context)) {
            MainActivity.displayNotification(context, "Push rejected", "Forwarding is disabled.");
            return;
        }

        WakeLocker.acquire(context, 60000);
        GoogleCloudMessaging gcm = GoogleCloudMessaging.getInstance(context);
        boolean async = false;

        try {
            String action = intent.getStringExtra("action");
            String msgType = gcm.getMessageType(intent);

            if (!msgType.contentEquals("gcm")) {
                MainActivity.displayNotification(context, "Push failed", "Received message type: " + msgType);
                return;
            }

            if (action.contentEquals("text")) {
                String to = intent.getStringExtra("to");
                String body = intent.getStringExtra("body");

                MainActivity.displayNotification(context, "Push received", "Sending SMS to " + to + ": \"" + body + "\"");
            } else if (action.contentEquals("ping")) {
                pingBack(context, intent, sp);
                async = true;
            } else if (action.contentEquals("locate")) {
                locate(context, intent, sp);
                async = true;
            } else {
                MainActivity.displayNotification(context, "Push failed", "Unsupported action type: " + action);
            }
        } catch (Exception ex) {
            MainActivity.displayNotification(context, "Push failed", ex.toString());
        } finally {
            gcm.close();

            if (!async) {
                WakeLocker.release();
            }
        }

        setResultCode(Activity.RESULT_OK);
    }

    private void pingBack(final Context context, final Intent intent, final SharedPreferences sp)
    {
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {

            @Override
            protected Void doInBackground(Void... voids) {
                try {
                    JSONObject json = MainActivity.sendRequest("pingback", new ArrayList<NameValuePair>(Arrays.asList(
                            new BasicNameValuePair("gacc", sp.getString("g_acc", null)),
                            new BasicNameValuePair("time", intent.getStringExtra("time"))
                    )));
                } catch (ServerError ex) {
                    MainActivity.displayNotification(context, "Pingback failed", "Server error: " + ex.toString());
                } catch (Exception ex) {
                    MainActivity.displayNotification(context, "Pingback failed", "Send error: " + ex.toString());
                }

                return null;
            }

            @Override
            protected void onPostExecute(Void voids) {
                WakeLocker.release();
            }

        };

        asyncTask.execute();
    }

    private void locate(final Context context, final Intent intent, final SharedPreferences sp)
    {
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {

            @Override
            protected Void doInBackground(Void... voids) {
                String body = null;
                Location location = null;
                LocationManager locationManager = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);

                try {
                    if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                        body = "Last known network location: https://maps.google.com/maps?q=" + location.getLatitude() + "," + location.getLongitude() + " accurate within " + location.getAccuracy() + " meters.";
                    } else {
                        body = "Network location provider is not enabled on the device currently.";
                    }

                    try {
                        JSONObject json = MainActivity.sendRequest("send", new ArrayList<NameValuePair>(Arrays.asList(
                                new BasicNameValuePair("gacc", sp.getString("g_acc", null)),
                                new BasicNameValuePair("body", body)
                        )));
                    } catch (ServerError ex) {
                        MainActivity.displayNotification(context, "Request failed", "Server error: " + ex.toString());
                    } catch (Exception ex) {
                        MainActivity.displayNotification(context, "Request failed", "Send error: " + ex.toString());
                    }
                } catch (Exception ex) { }

                try {
                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                        body = "Last known GPS location: https://maps.google.com/maps?q=" + location.getLatitude() + "," + location.getLongitude() + " accurate within " + location.getAccuracy() + " meters.";
                    } else {
                        body = "GPS provider is not enabled on the device currently.";
                    }

                    try {
                        JSONObject json = MainActivity.sendRequest("send", new ArrayList<NameValuePair>(Arrays.asList(
                                new BasicNameValuePair("gacc", sp.getString("g_acc", null)),
                                new BasicNameValuePair("body", body)
                        )));
                    } catch (ServerError ex) {
                        MainActivity.displayNotification(context, "Request failed", "Server error: " + ex.toString());
                    } catch (Exception ex) {
                        MainActivity.displayNotification(context, "Request failed", "Send error: " + ex.toString());
                    }
                } catch (Exception ex) { }

                // TODO: get location updates
                return null;
            }

            @Override
            protected void onPostExecute(Void voids) {
                WakeLocker.release();
            }

        };

        asyncTask.execute();
    }

}
