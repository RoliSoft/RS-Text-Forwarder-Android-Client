package net.rolisoft.textforwarder;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.NeighboringCellInfo;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;

import com.google.android.gms.gcm.GoogleCloudMessaging;

import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;

import java.io.InputStream;
import java.text.DecimalFormat;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PushReceiver extends BroadcastReceiver {

    private final String TAG = this.toString();

    public enum Actions {
        cmd, ping, text, chat, _null
    }

    public enum Commands {
        help, contact, whois, locate, device, track, apps, cmd, _null
    }

    @Override
    public void onReceive(Context context, Intent intent)
    {
        Log.i(TAG, "Received push notification...");

        SharedPreferences sp = context.getSharedPreferences("fwd", 0);
        if (!sp.getBoolean("forward", true) || sp.getString("reg_id", null) == null || !MainActivity.isConnectedToInternet(context)) {
            Log.w(TAG, "Push rejected because forwarding is disabled or GCM ID is unknown.");
            return;
        }

        WakeLocker.pushd(context);
        GoogleCloudMessaging gcm = GoogleCloudMessaging.getInstance(context);

        try {
            String action = intent.getStringExtra("action");
            String msgType = gcm.getMessageType(intent);

            if (!msgType.contentEquals("gcm")) {
                Log.w(TAG, "Push rejected because message type '" + msgType + "' is not supported.");
                MainActivity.displayNotification(context, "Push failed", "Received message type: " + msgType);
                return;
            }

            Log.i(TAG, "Push message requests action '" + action + "'.");

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
                        new BasicNameValuePair("time", intent.getStringExtra("time")),
                        new BasicNameValuePair("from", intent.hasExtra("_addr") ? intent.getStringExtra("_addr") : "")
                    )));
                    break;

                case text:
                    String to = intent.getStringExtra("to");
                    String body = intent.getStringExtra("body");

                    MainActivity.displayNotification(context, "Push received", "Sending SMS to " + to + ": \"" + body + "\"");
                    break;

                case chat:
                    chat(context, intent, sp);
                    break;

                default:
                    MainActivity.displayNotification(context, "Push failed", "Unsupported action type: " + action);
                    String from = intent.hasExtra("_addr") ? intent.getStringExtra("_addr") : "";
                    MainActivity.sendMessageAsync(context, sp, "send", from, (!from.equals("") ? "*** " : "") + "The specified push action \"" + action + "\" is not supported. The server is probably newer than the client on your device.");
                    break;
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error while processing push notification.", ex);
            MainActivity.displayNotification(context, "Push failed", ex.getClass().getName() + ": " + ex.getMessage());
        } finally {
            gcm.close();
            WakeLocker.popd();
        }

        setResultCode(Activity.RESULT_OK);
    }

    private void handleCmd(final Context context, final Intent intent, final SharedPreferences sp)
    {
        WakeLocker.push(context);

        final String cmd = intent.getStringExtra("cmd");
        final String arg = intent.getStringExtra("arg");
        final String from = intent.hasExtra("_addr") ? intent.getStringExtra("_addr") : "";

        Log.i(TAG, "Command handler called with command '" + cmd + "'.");

        Commands cmdEn;
        try { cmdEn = Commands.valueOf(cmd); } catch (Exception ex) { cmdEn = Commands._null; }

        switch (cmdEn)
        {
            case help:
                MainActivity.sendMessageAsync(context, sp, "send", from, "List of supported commands by the client:\n" +
                        "/contact *name*[/*index*] — Search through your address book.\n" +
                        "/whois — When in dedicated window, reveals the destination contact." +
                        "/device [info*|cpu] — Gets device information or current/min/max CPU speed.\n" +
                        "/apps [list [all|sys|user*]|run *app*|running*|ps] — Lists the currently installed/running applications or starts one if specified." +
                        "/cmd *cmd* — Runs a command on your device." +
                        "/locate — Gets the last known network and GPS locations.\n" +
                        "/track [start|stop|status*|provider|exploit] — Starts or stops tracking the device with the best provider.");
                break;

            case device:
                device(context, intent, sp, arg);
                break;

            case locate:
                locate(context, intent, sp);
                break;

            case track:
                track(context, intent, sp, arg);
                break;

            case contact:
                contact(context, intent, sp, arg);
                break;

            case apps:
                apps(context, intent, sp, arg);
                break;

            case cmd:
                cmd(context, intent, sp, arg);
                break;

            case whois:
                if (!from.contentEquals("")) {
                    Tuple<String, String> contact = resolveXmppAddr(context, from);
                    if (contact != null) {
                        MainActivity.sendMessageAsync(context, sp, "send", from, "*** All messages sent to this address will be forwarded to " + contact.x + " (" + contact.y + ")");
                    } else {
                        MainActivity.sendMessageAsync(context, sp, "send", from, "*** Messages sent to this address will be discarded, because the contact name or phone number could not be resolved.");
                    }
                } else {
                    MainActivity.sendMessageAsync(context, sp, "send", from, "The specified command is not valid in this context.");
                }
                break;

            default:
                MainActivity.sendMessageAsync(context, sp, "send", from, (!from.contentEquals("") ? "*** " : "") + "The specified command \"" + cmd + "\" is not supported. Reply \"/help server\" or \"/help device\" for the list of supported commands.");
                break;
        }

        WakeLocker.pop();
    }

    private void chat(final Context context, final Intent intent, final SharedPreferences sp)
    {
        String with = intent.getStringExtra("with");
        String needle = with.toLowerCase().trim();
        int sel = -1;
        if (needle.contains("/")) {
            String[] mc = needle.split("/(?!.*/)", 2);
            if (mc.length > 1) {
                try {
                    sel = Integer.parseInt(mc[1].trim());
                    needle = mc[0].trim();
                } catch (Exception ex) { }
            }
        }

        Tuple<String, String> contact, ctry;

        try {
            INameCompare cmp = new INameCompare() {

                @Override
                public boolean compare(String a, String b)
                {
                    return Normalizer.normalize(a.toLowerCase(), Normalizer.Form.NFKD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase().contains(b);
                }

            };
            contact = resolveName(context, cmp, Normalizer.normalize(needle, Normalizer.Form.NFKD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase(), sel);
        } catch (Exception ex) {
            MainActivity.sendMessageAsync(context, sp, "send", "Error while reading contacts: " + ex.getClass().getName() + ": " + ex.getMessage());
            return;
        }

        if (contact == null) {
            MainActivity.sendMessageAsync(context, sp, "send", "Couldn't resolve the specified name to a phone number.");
            return;
        }

        String sname = Normalizer.normalize(contact.x.toLowerCase(), Normalizer.Form.NFKD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase().replaceAll("([\"']|^([^a-z]+)|([^a-z]+)$)", "").replaceAll("[^a-z0-9]", ".").replaceAll("\\.{2,}", ".");

        if (sel != -1) {
            sname += "-" + sel;
        }

        ctry = resolveXmppAddr(context, sname);
        if (!ctry.y.contentEquals(contact.y)) {
            sname = contact.y.replaceAll("[^0-9]", "");
        }

        MainActivity.sendMessageAsync(context, sp, "send", sname, "*** All messages sent to this address will be forwarded to " + contact.x + " (" + contact.y + ")");
    }

    private Tuple<String, String> resolveXmppAddr(Context context, String addr)
    {
        String needle = addr;
        int sel = -1;
        if (needle.contains("-")) {
            String[] mc = needle.split("/(?!.*\\-)", 2);
            if (mc.length > 1) {
                try {
                    sel = Integer.parseInt(mc[1].trim());
                    needle = mc[0].trim();
                } catch (Exception ex) { }
            }
        }

        INameCompare cmp = new INameCompare() {

            @Override
            public boolean compare(String a, String b)
            {
                return Normalizer.normalize(a.toLowerCase(), Normalizer.Form.NFKD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase().replaceAll("([\"']|^([^a-z]+)|([^a-z]+)$)", "").replaceAll("[^a-z0-9]", ".").replaceAll("\\.{2,}", ".").contains(b);
            }

        };

        return resolveName(context, cmp, Normalizer.normalize(addr, Normalizer.Form.NFKD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase(), sel);
    }

    private void device(final Context context, final Intent intent, final SharedPreferences sp, final String arg)
    {
        StringBuilder sb = new StringBuilder();

        if (arg.contentEquals("cpu")) {
            int cur, min, max;
            DecimalFormat dfg = new DecimalFormat("0.00 GHz");
            DecimalFormat dfm = new DecimalFormat("0 MHz");

            sb.append("Current CPU speed: ");

            try {
                cur = Integer.parseInt(MainActivity.readFile("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq")) / 1000;
                if (cur >= 1000) {
                    sb.append(dfg.format((float)cur / 1000.0));
                } else {
                    sb.append(dfm.format(cur));
                }
            } catch (Exception ex) {
                Log.e(TAG, "Error while reading CPU speed.", ex);
            }

            sb.append(" [");

            try {
                min = Integer.parseInt(MainActivity.readFile("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq")) / 1000;
                if (min >= 1000) {
                    sb.append(dfg.format((float)min / 1000.0));
                } else {
                    sb.append(dfm.format(min));
                }
            } catch (Exception ex) {
                Log.e(TAG, "Error while reading CPU speed.", ex);
            }

            sb.append(", ");

            try {
                max = Integer.parseInt(MainActivity.readFile("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")) / 1000;
                if (max >= 1000) {
                    sb.append(dfg.format((float)max / 1000.0));
                } else {
                    sb.append(dfm.format(max));
                }
            } catch (Exception ex) {
                Log.e(TAG, "Error while reading CPU speed.", ex);
            }

            sb.append("]");
        } else if (arg.length() == 0 || arg.contentEquals("info")) {
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
                    sb.append(" - CellID: " + info.getCid() + ", RSSI: " + info.getRssi() + ", Type: " + getNetworkTypeString(info.getNetworkType()) + "\n");
                }
            }
        } else {
            sb.append("The specified subcommand is not valid for /device.");
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
        if (arg.length() > 0 && !arg.contentEquals("start") && !arg.contentEquals("stop") && !arg.contentEquals("provider") && !arg.contentEquals("exploit") && !arg.contentEquals("status")) {
            MainActivity.sendMessageAsync(context, sp, "send", "The specified subcommand is not valid for /track.");
            return;
        }

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

    private void apps(final Context context, final Intent intent, final SharedPreferences sp, final String arg)
    {
        StringBuilder sb = new StringBuilder();

        if (arg.length() == 0 || arg.contentEquals("running")) {
            ActivityManager actvityManager = (ActivityManager)context.getSystemService(Activity.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> procInfos = actvityManager.getRunningAppProcesses();

            sb.append("List of running applications:\n");

            for (ActivityManager.RunningAppProcessInfo app : procInfos) {
                sb.append(" - " + app.pid + "# ");

                if (app.pkgList.length > 0) {
                    if (app.pkgList[0].contentEquals(app.processName)) {
                        sb.append(app.processName);
                    } else {
                        sb.append(app.pkgList[0] + " / " + app.processName);
                    }
                } else {
                    sb.append(app.processName);
                }

                if (app.importanceReasonComponent != null) {
                    String cls = app.importanceReasonComponent.getClassName();
                    if (cls != null && !cls.contentEquals(app.processName)) {
                        sb.append(" / " + cls);
                    }
                }

                sb.append("\n");
            }
        } else if (arg.contentEquals("ps")) {
            try {
                sb.append(MainActivity.runAndRead("/system/bin/ps"));
            } catch (Exception ex) {
                sb.append("Error while running '/system/bin/ps': " + ex.getClass().getName() + ": " + ex.getMessage());
            }
        } else if (arg.contentEquals("list") || arg.contentEquals("list all") || arg.contentEquals("list user") || arg.contentEquals("list sys")) {
            PackageManager packageManager = context.getPackageManager();
            List<PackageInfo> apps = packageManager.getInstalledPackages(0);

            sb.append("List of installed applications:\n");

            boolean all = arg.contentEquals("list all");
            boolean sys = all || arg.contentEquals("list sys");
            boolean usr = all || arg.contentEquals("list user") || arg.contentEquals("list");
            for (PackageInfo pkg : apps) {
                if ((pkg.applicationInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) {
                    if (!sys) {
                        continue;
                    }
                } else {
                    if (!usr) {
                        continue;
                    }
                }

                sb.append(" - " + pkg.applicationInfo.loadLabel(packageManager) + " " + pkg.versionName + " [" + pkg.packageName + " " + pkg.versionCode + "]" + "\n");
            }
        } else if (arg.startsWith("run ")) {
            String query = arg.split(" ", 2)[1].toLowerCase();

            PackageManager packageManager = context.getPackageManager();
            List<PackageInfo> apps = packageManager.getInstalledPackages(0);

            String ps = null, pn = null;

            for (PackageInfo pkg : apps) {
                ps = pkg.applicationInfo.loadLabel(packageManager).toString();
                if (ps.toLowerCase().contains(query)) {
                    pn = pkg.packageName;
                    break;
                }
            }

            if (pn == null) {
                sb.append("No package names found matching " + query + ".");
            } else {
                sb.append("Launching " + ps + " (" + pn + ") on your device...");

                Intent it = packageManager.getLaunchIntentForPackage(pn);
                context.startActivity(it);
            }
        } else {
            sb.append("The specified subcommand is not valid for /apps.");
        }

        MainActivity.sendMessageAsync(context, sp, "send", sb.toString().trim());
    }

    private void cmd(final Context context, final Intent intent, final SharedPreferences sp, final String arg)
    {
        StringBuilder sb = new StringBuilder();

        if (arg.length() == 0) {
            sb.append("No command specified to run.");
        } else {
            try {
                sb.append(MainActivity.runAndRead(arg));
            } catch (Exception ex) {
                sb.append("Error while running '" + arg + "': " + ex.getClass().getName() + ": " + ex.getMessage());
            }
        }

        MainActivity.sendMessageAsync(context, sp, "send", sb.toString().trim());
    }

    private void contact(final Context context, final Intent intent, final SharedPreferences sp, final String arg)
    {
        StringBuilder sb = new StringBuilder();

        if (arg == null || arg.length() == 0 || arg.trim().length() == 0) {
            MainActivity.sendMessageAsync(context, sp, "send", "You need to specify a name argument which will be used to search your contacts for a name or phone number match.");
            return;
        }

        String needle = arg.toLowerCase().trim();
        int sel = -1;
        if (needle.contains("/")) {
            String[] mc = needle.split("/(?!.*/)", 2);
            if (mc.length > 1) {
                try {
                    sel = Integer.parseInt(mc[1].trim());
                    needle = mc[0].trim();
                } catch (Exception ex) { }
            }
        }

        needle = Normalizer.normalize(needle, Normalizer.Form.NFKD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase();

        List<Contact> contacts;

        try {
            contacts = getContacts(context);
        } catch (Exception ex) {
            MainActivity.sendMessageAsync(context, sp, "send", "Error while reading contacts: " + ex.getClass().getName() + ": " + ex.getMessage());
            return;
        }

        sb.append("Search for contacts matching " + needle.trim() + ":\n");
        String number = null;
        int j = 0;
        for (Contact contact : contacts) {
            int idx;
            if ((idx = Normalizer.normalize(contact.name.toLowerCase(), Normalizer.Form.NFKD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase().indexOf(needle)) != -1) {
                j++;

                sb.append(j + ". " + contact.name.substring(0, idx) + "[" + contact.name.substring(idx, idx + needle.length()) + "]" + contact.name.substring(idx + needle.length()) + "\n");

                if (sel == -1) {
                    if (number == null) {
                        number = contact.preferred.number;
                    }

                    sb.append((contact.preferred.isDefault ? "Marked as default" : "First mobile or only") + " number: " + contact.preferred.number + "; ");
                } else if (sel > 0 && sel <= contact.numbers.size()) {
                    if (number == null) {
                        number = contact.numbers.get(sel - 1).number;
                    }

                    sb.append("Number behind specified index: " + contact.numbers.get(sel - 1).number + "; ");
                } else {
                    sb.append("Specified index is out of bounds; ");
                }

                if (contact.numbers.size() == 1) {
                    sb.append("This is the only number.\n");
                } else {
                    sb.append("All associated numbers:\n");

                    int i = 0;
                    for (Contact.Number numObj : contact.numbers) {
                        i++;
                        sb.append(" - " + i + ". " + numObj.number + "; " + numObj.type + (numObj.isDefault ? "; marked as default" : "") + "\n");
                    }
                }

                if (j >= 10) {
                    sb.append("Interrupting search: too many results.\n");
                    break;
                }
            }

            for (Contact.Number numObj : contact.numbers) {
                if ((idx = numObj.number.indexOf(needle)) != -1) {
                    j++;

                    if (number == null) {
                        number = numObj.number;
                    }

                    sb.append(j + ". " + contact.name + ":\nMatching number: " + numObj.number.substring(0, idx) + "[" + numObj.number.substring(idx, idx + needle.length()) + "]" + numObj.number.substring(idx + needle.length()) + "\n");

                    if (j >= 10) {
                        sb.append("Interrupting search: too many results.\n");
                        break;
                    }
                }
            }

            if (j >= 10) {
                break;
            }
        }

        if (j == 0) {
            sb.setLength(0);
            sb.append("No contacts found matching " + arg.trim() + ".");
        } else if (number != null) {
        }

        // test standalone search
        INameCompare cmp = new INameCompare() {

            @Override
            public boolean compare(String a, String b)
            {
                return Normalizer.normalize(a.toLowerCase(), Normalizer.Form.NFKD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "").toLowerCase().contains(b);
            }

        };
        Tuple<String, String> number2 = resolveName(context, cmp, needle, sel);

        if ((number == null && number2 == null) || number.contentEquals(number2.y)) {
            if (number2 != null) {
                sb.append("When specified to other commands, this name resolves to: " + number2.y);
            }
        } else {
            sb.append("When specified to other commands, this name resolves to: " + number2.y + " [BUT should resolve to " + number + ", probably. contact()/resolveName() results differ.]");
        }

        MainActivity.sendMessageAsync(context, sp, "send", sb.toString().trim());
    }

    private interface INameCompare {

        boolean compare(String a, String b);

    }

    private Tuple<String, String> resolveName(Context context, INameCompare cmp, String query, int sel)
    {
        Cursor cur = context.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);

        if (cur == null) {
            return null;
        }

        try {
            while (cur.moveToNext()) {
                String id = cur.getString(cur.getColumnIndex(ContactsContract.Contacts._ID));
                String name = cur.getString(cur.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));

                if (Integer.parseInt(cur.getString(cur.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER))) > 0) {
                    Cursor pCur = context.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", new String[] { id }, null);

                    if (pCur == null) {
                        continue;
                    }

                    Contact.Number preferred = null;
                    List<Contact.Number> numbers = new ArrayList<Contact.Number>();

                    try {
                        while (pCur.moveToNext()) {
                            String number = pCur.getString(pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER));
                            if (number == null || number.contentEquals("")) {
                                number = pCur.getString(pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)).replaceAll("[^0-9\\+\\*#]", "");
                            }

                            int type = Integer.parseInt(pCur.getString(pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)));
                            boolean isDef = Integer.parseInt(pCur.getString(pCur.getColumnIndex(ContactsContract.Data.IS_SUPER_PRIMARY))) > 0;
                            boolean stop = false;

                            if (numbers.size() >= 1) {
                                for (Contact.Number num2 : numbers) {
                                    if (num2.number.contentEquals(number)) {
                                        if (isDef) {
                                            num2.isDefault = true;
                                            preferred = num2;
                                        }

                                        stop = true;
                                    }
                                }
                            }

                            if (stop) {
                                continue;
                            }

                            Contact.Number numObj = new Contact.Number(number, "", isDef);
                            numbers.add(numObj);

                            if (isDef) {
                                preferred = numObj;
                            } else if (preferred == null && type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                                preferred = numObj;
                            }
                        }

                        if (preferred == null) {
                            preferred = numbers.get(0);
                        }
                    } finally {
                        pCur.close();
                    }

                    if (cmp.compare(name, query)) {
                        if (sel == -1) {
                            return new Tuple(name, preferred.number);
                        } else if (sel > 0 && sel <= numbers.size()) {
                            return new Tuple(name, numbers.get(sel - 1).number);
                        }
                    }

                    for (Contact.Number numObj : numbers) {
                        if (cmp.compare(numObj.number, query)) {
                            return new Tuple(name, numObj.number);
                        }
                    }
                }
            }
        } finally {
            cur.close();
        }

        return null;
    }

    private List<Contact> getContacts(Context context)
    {
        List<Contact> contacts = new ArrayList<Contact>();

        Cursor cur = context.getContentResolver().query(ContactsContract.Contacts.CONTENT_URI, null, null, null, null);

        if (cur == null) {
            return contacts;
        }

        try {
            while (cur.moveToNext()) {
                String id = cur.getString(cur.getColumnIndex(ContactsContract.Contacts._ID));
                String key = cur.getString(cur.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY));
                String name = cur.getString(cur.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME));

                if (Integer.parseInt(cur.getString(cur.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER))) > 0) {
                    Contact contact = new Contact(key, name);
                    contacts.add(contact);

                    Cursor pCur = context.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?", new String[] { id }, null);

                    if (pCur == null) {
                        continue;
                    }

                    try {
                        while (pCur.moveToNext()) {
                            String number = pCur.getString(pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER));
                            if (number == null || number.contentEquals("")) {
                                number = pCur.getString(pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)).replaceAll("[^0-9\\+\\*#]", "");
                            }

                            int type = Integer.parseInt(pCur.getString(pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)));
                            String typeStr = (String)ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.getResources(), type, pCur.getString(pCur.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LABEL)));
                            boolean isDef = Integer.parseInt(pCur.getString(pCur.getColumnIndex(ContactsContract.Data.IS_SUPER_PRIMARY))) > 0;
                            boolean stop = false;

                            if (contact.numbers.size() >= 1) {
                                for (Contact.Number num2 : contact.numbers) {
                                    if (num2.number.contentEquals(number)) {
                                        if (isDef) {
                                            num2.isDefault = true;
                                            contact.preferred = num2;
                                        }

                                        stop = true;
                                    }
                                }
                            }

                            if (stop) {
                                continue;
                            }

                            Contact.Number numObj = contact.addNumber(number, typeStr, isDef);

                            if (isDef) {
                                contact.preferred = numObj;
                            } else if (contact.preferred == null && type == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                                contact.preferred = numObj;
                            }
                        }

                        if (contact.numbers.size() == 0) {
                            contacts.remove(contact);
                        } else if (contact.preferred == null) {
                            contact.preferred = contact.numbers.get(0);
                        }
                    } finally {
                        pCur.close();
                    }
                }
            }
        } finally {
            cur.close();
        }

        return contacts;
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
