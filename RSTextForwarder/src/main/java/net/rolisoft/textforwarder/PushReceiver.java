package net.rolisoft.textforwarder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;
import android.telephony.NeighboringCellInfo;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;

import com.google.android.gms.gcm.GoogleCloudMessaging;

import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PushReceiver extends BroadcastReceiver {

    public enum Actions {
        cmd, ping, text, _null
    }

    public enum Commands {
        help, contact, whois, locate, device, track, _null
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
                MainActivity.sendMessageAsync(context, sp, "send", from, "List of supported commands by the client:\n/device — Gets device information.\n/locate — Gets the last known network and GPS locations.\n/track [start|stop|status*|provider|exploit] — Starts or stops tracking the device with the best provider.");
                break;

            case device:
                device(context, intent, sp);
                break;

            case locate:
                locate(context, intent, sp);
                break;

            case track:
                track(context, intent, sp, arg);
                break;

            default:
                MainActivity.sendMessageAsync(context, sp, "send", from, (!from.equals("") ? "*** " : "") + "The specified command \"" + cmd + "\" is not supported. Reply \"/help server\" or \"/help device\" for the list of supported commands.");
                break;
        }

        WakeLocker.pop();
    }

    private void device(final Context context, final Intent intent, final SharedPreferences sp)
    {
        StringBuilder sb = new StringBuilder();
        TelephonyManager tm = (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        GsmCellLocation loc = (GsmCellLocation)tm.getCellLocation();

        sb.append("Device info:\n");
        sb.append("Cell ID: " + loc.getCid() + "\n");
        sb.append("LAC: " + loc.getLac() + "\n");
        sb.append("Device ID: " + tm.getDeviceId() + "\n");
        sb.append("Phone Number: " + tm.getLine1Number() + "\n");
        sb.append("Software Version: " + tm.getDeviceSoftwareVersion() + "\n");
        sb.append("Network Operator: " + tm.getNetworkOperatorName() + "\n");
        sb.append("Network Country Code: " + tm.getNetworkCountryIso() + "\n");
        sb.append("Network Code: " + tm.getNetworkOperator() + "\n");
        sb.append("SIM Operator Name: " + tm.getSimOperatorName() + "\n");
        sb.append("SIM Op. Country Code: " + tm.getSimCountryIso() + "\n");
        sb.append("SIM Operator Code: " + tm.getSimOperator() + "\n");
        sb.append("SIM Serial No.: " + tm.getSimSerialNumber() + "\n");
        sb.append("Subscriber ID: " + tm.getSubscriberId() + "\n");
        sb.append("Network Type: " + getNetworkTypeString(tm.getNetworkType()) + "\n");
        sb.append("Phone Type: " + getPhoneTypeString(tm.getPhoneType()) + "\n");

        List<NeighboringCellInfo> cellinfo = tm.getNeighboringCellInfo();

        if (null != cellinfo) {
            sb.append("Neighboring Cells:\n");

            for (NeighboringCellInfo info : cellinfo) {
                sb.append("CellID: " + info.getCid() + ", RSSI: " + info.getRssi() + "\n");
            }
        }

        MainActivity.sendMessageAsync(context, sp, "send", sb.toString().trim());
    }

    private static LocationManager _lm;
    private static LocationListener _ll;
    private static long _lu = 0;
    private static String _lp = "";

    private void locate(final Context context, final Intent intent, final SharedPreferences sp)
    {
        if (_lm == null) {
            _lm = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
        }

        String body;
        Location location;

        try {
            if (_lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                location = _lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                body = "Last known network location: https://maps.google.com/maps?q=" + location.getLatitude() + "," + location.getLongitude() + " accurate within " + location.getAccuracy() + " meters.";
            } else {
                body = "Network location provider is not enabled on the device currently.";
            }
        } catch (Exception ex) {
            body = "Error while retrieving network location: " + ex.getClass().getName() + ": " + ex.getMessage();
        }

        MainActivity.sendMessageAsync(context, sp, "send", body);

        try {
            if (_lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                location = _lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                body = "Last known GPS location: https://maps.google.com/maps?q=" + location.getLatitude() + "," + location.getLongitude() + " accurate within " + location.getAccuracy() + " meters.";
            } else {
                body = "GPS provider is not enabled on the device currently.";
            }
        } catch (Exception ex) {
            body = "Error while retrieving GPS location: " + ex.getClass().getName() + ": " + ex.getMessage();
        }

        MainActivity.sendMessageAsync(context, sp, "send", body);
    }

    private void track(final Context context, final Intent intent, final SharedPreferences sp, final String arg)
    {
        if (arg.contentEquals("stop")) {
            if (_lm != null && _ll != null) {
                try { _lm.removeUpdates(_ll); } catch (Exception ex) { }
                _ll = null;
                _lu = 0;
                _lp = "";
                MainActivity.sendMessageAsync(context, sp, "send", "Stopped location tracking.");
                WakeLocker.pop();
            } else {
                MainActivity.sendMessageAsync(context, sp, "send", "Location tracking is not running.");
            }
            return;
        } else if (arg.contentEquals("start")) {
            if (_lm != null && _ll != null) {
                MainActivity.sendMessageAsync(context, sp, "send", "Location tracking is already running with provider " + _lp + ".");
                return;
            }
        } else if (!arg.contentEquals("provider") && !arg.contentEquals("exploit")) {
            if (_lm != null && _ll != null) {
                DecimalFormat df = new DecimalFormat("0.000");
                MainActivity.sendMessageAsync(context, sp, "send", "Location tracking is running with provider " + _lp + ". " + (_lu == 0 ? "However, no location updates have occurred as of yet." : "Last location update was sent " + df.format((double)(System.currentTimeMillis() - _lu) / 1000.0) + " seconds ago."));
            } else {
                MainActivity.sendMessageAsync(context, sp, "send", "Location tracking is not running.");
            }
            return;
        }

        if (_lm == null) {
            _lm = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
        }

        if (arg.contentEquals("provider")) {
            try {
                String provider = getBestProvider();
                MainActivity.sendMessageAsync(context, sp, "send", "The currently available best location provider is " + provider + ".");
            } catch (Exception ex) {
                MainActivity.sendMessageAsync(context, sp, "send", "Error while retrieving best provider: " + ex.getClass().getName() + ": " + ex.getMessage());
            }
            return;
        } else if (arg.contentEquals("exploit")) { // TODO: turn on GPS with root with https://github.com/sms2000/GPSToggler
            try {
                if (!_lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    turnGpsOn(context);
                }
                MainActivity.sendMessageAsync(context, sp, "send", "Ran exploit, waiting for 5 seconds...");
                WakeLocker.push(context);

                AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {

                    @Override
                    protected Void doInBackground(Void... params) {
                        try { Thread.sleep(5000); } catch (Exception ex) { }

                        try {
                            String provider = getBestProvider();
                            if (provider != null && provider.contentEquals(LocationManager.GPS_PROVIDER)) {
                                MainActivity.sendMessageAsync(context, sp, "send", "The GPS provider has been turned on successfully via exploit.");
                            } else {
                                MainActivity.sendMessageAsync(context, sp, "send", "Failed to turn on GPS provider via exploit.");
                            }
                        } catch (Exception ex) {
                            MainActivity.sendMessageAsync(context, sp, "send", "Error while re-checking best provider: " + ex.getClass().getName() + ": " + ex.getMessage());
                        }

                        WakeLocker.pop();
                        return null;
                    }

                };
                asyncTask.execute();
            } catch (Exception ex) {
                MainActivity.sendMessageAsync(context, sp, "send", "Error while trying to activate GPS using an exploit: " + ex.getClass().getName() + ": " + ex.getMessage());
            }
            return;
        }

        if (_ll == null) {
            _ll = new LocationListener() {

                public void onProviderDisabled(final String provider)
                {
                    MainActivity.sendMessageAsync(context, sp, "send", "The " + provider + " location provider has been disabled. Suspending location updates...");
                }

                public void onProviderEnabled(final String provider)
                {
                    MainActivity.sendMessageAsync(context, sp, "send", "The " + provider + " location provider has been enabled. Resuming location updates...");
                }

                public void onStatusChanged(final String provider, final int status, final Bundle extras)
                {
                    MainActivity.sendMessageAsync(context, sp, "send", "Status of " + provider + " location provider has changed to " + getLocationProviderStatusString(status) + ".");
                }

                public void onLocationChanged(final Location location)
                {
                    String body;

                    try {
                        _lu = System.currentTimeMillis();
                        body = "Location update received from " + location.getProvider() + ": https://maps.google.com/maps?q=" + location.getLatitude() + "," + location.getLongitude() + " accurate within " + location.getAccuracy() + " meters.";
                    } catch (Exception ex) {
                        body = "Error while retrieving location update: " + ex.getClass().getName() + ": " + ex.getMessage();
                    }

                    MainActivity.sendMessageAsync(context, sp, "send", body);
                }

            };
        }

        try {
            String provider = getBestProvider();
            _lp = provider;
            MainActivity.sendMessageAsync(context, sp, "send", "Starting tracking with best available location provider " + provider + "...");
            _lm.requestLocationUpdates(provider, 500, 0, _ll);
            WakeLocker.push(context);
        } catch (Exception ex) {
            try { _lm.removeUpdates(_ll); } catch (Exception ez) { }
            _ll = null;

            MainActivity.sendMessageAsync(context, sp, "send", "Error while managing location manager: " + ex.getClass().getName() + ": " + ex.getMessage());
        }
    }

    private String getBestProvider()
    {
        if (_lm == null) {
            return null;
        }

        final Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE);
        criteria.setPowerRequirement(Criteria.POWER_HIGH);
        criteria.setAltitudeRequired(false);
        criteria.setBearingRequired(false);
        criteria.setSpeedRequired(false);
        criteria.setCostAllowed(true);

        final String bestProvider = _lm.getBestProvider(criteria, true);

        if (bestProvider != null && bestProvider.length() > 0)
        {
            return bestProvider;
        }
        else
        {
            final List<String> providers = _lm.getProviders(true);

            for (final String provider : providers)
            {
                return provider;
            }
        }

        return null;
    }

    private void turnGpsOn(Context context)
    {
        String provider = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.LOCATION_PROVIDERS_ALLOWED);

        if (!provider.contains("gps")) {
            final Intent poke = new Intent();
            poke.setClassName("com.android.settings", "com.android.settings.widget.SettingsAppWidgetProvider");
            poke.addCategory(Intent.CATEGORY_ALTERNATIVE);
            poke.setData(Uri.parse("3"));
            context.sendBroadcast(poke);
        }
    }

    private void turnGpsOff(Context context)
    {
        String provider = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.LOCATION_PROVIDERS_ALLOWED);

        if (provider.contains("gps")) {
            final Intent poke = new Intent();
            poke.setClassName("com.android.settings", "com.android.settings.widget.SettingsAppWidgetProvider");
            poke.addCategory(Intent.CATEGORY_ALTERNATIVE);
            poke.setData(Uri.parse("3"));
            context.sendBroadcast(poke);
        }
    }

    private String getNetworkTypeString(int type)
    {
        switch (type) {
            case 7: return "1xRTT";
            case 4: return "CDMA";
            case 2: return "EDGE";
            case 14: return "eHRPD";
            case 5: return "EVDO rev. 0";
            case 6: return "EVDO rev. A";
            case 12: return "EVDO rev. B";
            case 1: return "GPRS";
            case 8: return "HSDPA";
            case 10: return "HSPA";
            case 15: return "HSPA+";
            case 9: return "HSUPA";
            case 11: return "iDen";
            case 13: return "LTE";
            case 3: return "UMTS";
            default: return "Unknown";
        }
    }

    private String getPhoneTypeString(int type)
    {
        switch (type)
        {
            case TelephonyManager.PHONE_TYPE_GSM: return "GSM";
            case TelephonyManager.PHONE_TYPE_CDMA: return "CDMA";
            case TelephonyManager.PHONE_TYPE_SIP: return "VoIP/SIP";
            default: return "Unknown";
        }
    }

    private String getLocationProviderStatusString(int type)
    {
        switch (type)
        {
            case LocationProvider.AVAILABLE: return "available";
            case LocationProvider.OUT_OF_SERVICE: return "out of service";
            case LocationProvider.TEMPORARILY_UNAVAILABLE: return "temporarily unavailable";
            default: return "unknown";
        }
    }

}
