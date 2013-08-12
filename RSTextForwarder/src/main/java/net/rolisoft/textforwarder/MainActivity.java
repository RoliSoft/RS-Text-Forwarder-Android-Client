package net.rolisoft.textforwarder;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.app.Activity;
import android.support.v4.app.NotificationCompat;
import android.util.Patterns;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class MainActivity extends Activity {

    private Menu _menu;
    private SharedPreferences _sp;
    private TextView _statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _sp = getSharedPreferences("fwd", 0);
        setContentView(R.layout.activity_main);
        _statusText = (TextView)findViewById(R.id.textView);

        refreshStatus();

        if (_sp.getString("reg_id", null) == null) {
            registerForGcmAsync();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        _menu = menu;
        getMenuInflater().inflate(R.menu.main, _menu);

        if (!_sp.getBoolean("forward", true)) {
            _menu.findItem(R.id.action_suspend).setTitle(R.string.action_resume);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_settings:
                /*Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);*/
                return true;
            case R.id.action_suspend:
                MenuItem menuItem = _menu.findItem(R.id.action_suspend);

                if (_sp.getBoolean("forward", true)) {
                    _sp.edit().putBoolean("forward", false).commit();
                    menuItem.setTitle(R.string.action_resume);
                    Toast.makeText(this, "Text forwarding suspended.", Toast.LENGTH_SHORT).show();
                } else {
                    _sp.edit().putBoolean("forward", true).commit();
                    menuItem.setTitle(R.string.action_suspend);
                    Toast.makeText(this, "Text forwarding resumed.", Toast.LENGTH_SHORT).show();
                }

                refreshStatus();
                return true;
            case R.id.action_reregister:
                registerForGcmAsync();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void refreshStatus()
    {
        if (_sp.getString("reg_id", null) == null) {
            _statusText.setText(R.string.not_registered);
        } else {
            if (_sp.getBoolean("forward", true)) {
                _statusText.setText(R.string.forwarding_enabled);
            } else {
                _statusText.setText(R.string.forwarding_disabled);
            }
        }
    }

    public void registerForGcmAsync()
    {
        AsyncTask<Void, Void, String> asyncTask = new AsyncTask<Void, Void, String>() {
            private Toast _toast;

            @Override
            protected String doInBackground(Void... voids) {
                GoogleCloudMessaging gcm = null;

                try {
                    String email = findUserEmail(MainActivity.this);
                    if (email == null) {
                        return "Couldn't find the primary Google account's email address.";
                    }

                    gcm = GoogleCloudMessaging.getInstance(MainActivity.this);

                    String regId = gcm.register("86714136760");

                    JSONObject json = sendRequest("register", new ArrayList<NameValuePair>(Arrays.asList(
                            new BasicNameValuePair("gacc", email),
                            new BasicNameValuePair("email", _sp.getString("email", email)),
                            new BasicNameValuePair("regid", regId)
                    )));

                    //String email = "rolisoft@gmail.com";
                    //String regId = "n/a";

                    if (_sp.getString("email", null) == null) {
                        _sp.edit().putString("email", email).commit();
                    }

                    _sp.edit().putString("g_acc", email).commit();
                    _sp.edit().putString("reg_id", regId).commit();
                    return "ok";
                } catch (ServerError ex) {
                    return "Server-side error occurred:\n" + ex.getClass().getName() + ": " + ex.getMessage();
                } catch (Exception ex) {
                    return "Exception during registration:\n" + ex.getClass().getName() + ": " + ex.getMessage();
                } finally {
                    if (gcm != null) {
                        gcm.close();
                    }
                }
            }

            @Override
            protected void onPreExecute() {
                _toast = Toast.makeText(MainActivity.this, "Registering for GCM...", Toast.LENGTH_LONG);
                _toast.show();
            }

            @Override
            protected void onPostExecute(String str) {
                _toast.cancel();
                refreshStatus();
                Toast.makeText(MainActivity.this, str == "ok" ? "Successfully registered for GCM." : str, str == "ok" ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            }
        };
        asyncTask.execute();
    }

    private static String readStream(InputStream is)
    {
        StringBuilder sb = new StringBuilder();
        BufferedReader rd = new BufferedReader(new InputStreamReader(is));
        String ln = "";

        try {
            while ((ln = rd.readLine()) != null) {
                sb.append(ln);
            }
        } catch (Exception ex) { }

        return sb.toString();
    }

    public static int displayNotification(Context context, String title, String body)
    {
        int id = new Random().nextInt();
        PendingIntent pi = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), 0);
        Notification notif = new Notification.Builder(context)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi)
                //.addAction(R.drawable.icon, "More", pIntent)
                .build();
        NotificationManager nm =  (NotificationManager)context.getSystemService(NOTIFICATION_SERVICE);
        nm.notify(id, notif);
        return id;
    }

    public static boolean isConnectedToInternet(Context context){
        ConnectivityManager connectivity = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivity == null) {
            return false;
        }

        NetworkInfo[] info = connectivity.getAllNetworkInfo();
        if (info == null){
            return false;
        }

        for (int i = 0; i < info.length; i++) {
            if (info[i].getState() == NetworkInfo.State.CONNECTED) {
                return true;
            }
        }

        return false;
    }

    public static String findUserEmail(Context context)
    {
        for (Account account : AccountManager.get(context).getAccountsByType("com.google")) {
            if (Patterns.EMAIL_ADDRESS.matcher(account.name).matches()) {
                return account.name;
            }
        }

        for (Account account : AccountManager.get(context).getAccounts()) {
            if (Patterns.EMAIL_ADDRESS.matcher(account.name).matches()) {
                return account.name;
            }
        }

        return null;
    }

    public static JSONObject sendRequest(String path, List<NameValuePair> postData) throws Exception
    {
        HttpPost post = new HttpPost("http://rstxtfwd.appspot.com/" + path);
        post.setEntity(new UrlEncodedFormEntity(postData, HTTP.UTF_8));

        HttpResponse resp = new DefaultHttpClient().execute(post);
        JSONObject json = new JSONObject(EntityUtils.toString(resp.getEntity()));

        if (!json.getString("res").contentEquals("ok")) {
            throw new ServerError(json.getString("err"), json);
        }

        return json;
    }

    private static JSONObject sendRequestNoThrow(Context context, String path, List<NameValuePair> postData)
    {
        WakeLocker.push(context);

        try {
            return MainActivity.sendRequest(path, postData);
        } catch (ServerError ex) {
            MainActivity.displayNotification(context, "Request to " + path + " failed", "Server error: " + ex.getClass().getName() + ": " + ex.getMessage());
            return ex.response;
        } catch (Exception ex) {
            MainActivity.displayNotification(context, "Request to " + path + " failed", "Send error: " + ex.getClass().getName() + ": " + ex.getMessage());
            return null;
        } finally {
            WakeLocker.pop();
        }
    }

    public static AsyncTask<Void, Void, JSONObject> sendRequestAsync(final Context context, final String path, final List<NameValuePair> postData)
    {
        AsyncTask<Void, Void, JSONObject> asyncTask = new AsyncTask<Void, Void, JSONObject>() {

            @Override
            protected JSONObject doInBackground(Void... voids)
            {
                return sendRequestNoThrow(context, path, postData);
            }

        };
        asyncTask.execute();
        return asyncTask;
    }

    public static AsyncTask<Void, Void, JSONObject> sendMessageAsync(Context context, SharedPreferences sp, String path, String body)
    {
        return sendMessageAsync(context, sp, path, "", body);
    }

    public static AsyncTask<Void, Void, JSONObject> sendMessageAsync(Context context, SharedPreferences sp, String path, String from, String body)
    {
        return sendRequestAsync(context, path, new ArrayList<NameValuePair>(Arrays.asList(
                new BasicNameValuePair("gacc", sp.getString("g_acc", null)),
                new BasicNameValuePair("from", from.replace("@", "/")),
                new BasicNameValuePair("body", body)
        )));
    }

}
