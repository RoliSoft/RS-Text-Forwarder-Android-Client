package net.rolisoft.textforwarder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.StrictMode;
import android.telephony.SmsMessage;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent)
    {
        final SharedPreferences sp = context.getSharedPreferences("fwd", 0);
        if (!sp.getBoolean("forward", true) || !sp.getBoolean("forward_sms", true) || !MainActivity.isConnectedToInternet(context)) {
            return;
        }

        WakeLocker.push(context);

        try {
            List<TextMessage> messages = getMessagesFrom(context, intent);

            for (TextMessage msg : messages) {
                MainActivity.sendMessageAsync(context, sp, "send", msg.from, msg.body);
            }
        } catch (Exception ex) {
            MainActivity.displayNotification(context, "Request to send failed", "Local error: " + ex.getClass().getName() + ": " + ex.getMessage());
        } finally {
            WakeLocker.pop();
        }
    }

    public static List<TextMessage> getMessagesFrom(Context context, Intent intent)
    {
        Bundle bundle = intent.getExtras();
        List<TextMessage> messages = new ArrayList<TextMessage>();
        Map<String, Integer> msgidx = new HashMap<String, Integer>();

        if (bundle == null || !bundle.containsKey("pdus")) {
            return messages;
        }

        Object[] pdu = (Object[])bundle.get("pdus");

        if (pdu == null || pdu.length == 0) {
            return messages;
        }

        for (int i = 0; i < pdu.length; i++) {
            SmsMessage sms = SmsMessage.createFromPdu((byte[])pdu[i]);
            String from = sms.getOriginatingAddress();
            String body = sms.getMessageBody();

            if (msgidx.containsKey(from)) {
                messages.get(msgidx.get(from)).append(body);
            } else {
                msgidx.put(from, messages.size());
                messages.add(new TextMessage(from, (double)sms.getTimestampMillis() / 1000.0, body));
            }
        }

        return messages;
    }

}
