package net.rolisoft.textforwarder;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

public class MmsReceiver extends BroadcastReceiver {

    private final String TAG = this.toString();

    @Override
    public void onReceive(final Context context, Intent intent)
    {
        Log.i(TAG, "Received MMS broadcast...");

        final SharedPreferences sp = context.getSharedPreferences("fwd", 0);
        if (!sp.getBoolean("forward", true) || !sp.getBoolean("forward_mms", true) || !MainActivity.isConnectedToInternet(context)) {
            Log.w(TAG, "Forwarding disabled or no internet connection.");
            return;
        }

        WakeLocker.pushd(context);

        try {
            List<TextMessage> messages = getMessagesFrom(context, intent);

            for (TextMessage msg : messages) {
                MainActivity.sendMessageAsync(context, sp, "send", msg.from, msg.body);
            }
        } catch (Exception ex) {
            Log.e(TAG, "Error while extracting messages from intent.", ex);
            MainActivity.displayNotification(context, "Request to send failed", "Local error: " + ex.getClass().getName() + ": " + ex.getMessage());
        } finally {
            WakeLocker.popd();
        }

        setResultCode(Activity.RESULT_OK);
    }

    public static List<TextMessage> getMessagesFrom(Context context, Intent intent)
    {
        Bundle bundle = intent.getExtras();
        List<TextMessage> messages = new ArrayList<TextMessage>();

        if (bundle == null || !bundle.containsKey("data")) {
            return messages;
        }

        byte[] data = bundle.getByteArray("data");

        if (data == null || data.length == 0) {
            return messages;
        }

        try {
            String buffer = new String(bundle.getByteArray("data"));

            for (int i = 0; i < 10; i++) {
                try { Thread.sleep(1000); } catch (Exception ex) { }

                Cursor cur = context.getContentResolver().query(Uri.parse("content://mms/inbox"), null, "m_type in (132,128)", null, "date DESC");
                if (cur == null) {
                    continue;
                }

                try {
                    if (cur.getCount() == 0) {
                        continue;
                    }

                    cur.moveToFirst();
                    int cnt = 0;

                    do {
                        int id = cur.getInt(cur.getColumnIndex("_id"));
                        String mid = cur.getString(cur.getColumnIndex("m_id"));

                        if (!buffer.contains(mid)) {
                            continue;
                        }

                        String subj = cur.getString(cur.getColumnIndex("sub"));
                        String body = "";
                        String from = getMmsAddr(context, id);
                        long date = Long.parseLong(cur.getString(cur.getColumnIndex("date")));

                        Cursor cprt = context.getContentResolver().query(Uri.parse("content://mms/part"), null, "mid = " + id, null, null);
                        try {
                            if (cprt.moveToFirst()) {
                                do {
                                    String pid = cprt.getString(cprt.getColumnIndex("_id"));
                                    String type = cprt.getString(cprt.getColumnIndex("ct"));
                                    if ("text/plain".equals(type)) {
                                        String dat = cprt.getString(cprt.getColumnIndex("_data"));
                                        if (dat != null) {
                                            body += getMmsText(context, pid);
                                        } else {
                                            body += cprt.getString(cprt.getColumnIndex("text"));
                                        }
                                    } else if ("image/jpeg".equals(type) || "image/bmp".equals(type) || "image/gif".equals(type) || "image/jpg".equals(type) || "image/png".equals(type)) {
                                        body += "\n[image]\n";
                                    }
                                } while (cprt.moveToNext());
                            }
                        } finally {
                            if (cprt != null) {
                                cprt.close();
                            }
                        }

                        String sender;
                        Contact contact = ContactTools.findContact(context, from, true);
                        if (contact != null) {
                            sender = ContactTools.createXmppAddrAutoSelCheck(context, contact);
                        } else {
                            if (ContactTools.isPhoneNumber(from)) {
                                sender = ContactTools.cleanNumber(from);
                            } else {
                                sender = ContactTools.createSlug(from);
                            }
                        }

                        messages.add(new TextMessage(sender, date, subj + (body.length() != 0 ? "\n" + body : "")));
                        return messages;
                    } while (cur.moveToNext() && ++cnt < 10);
                } finally {
                    cur.close();
                }
            }
        } catch (Exception ex) {
            return messages;
        }

        return messages;
    }

    private static String getMmsText(Context context, String id)
    {
        InputStream is = null;
        StringBuilder sb = new StringBuilder();

        try {
            is = context.getContentResolver().openInputStream(Uri.parse("content://mms/part/" + id));
            if (is == null) {
                return sb.toString();
            }

            InputStreamReader isr = new InputStreamReader(is, "UTF-8");
            BufferedReader reader = new BufferedReader(isr);
            String temp = reader.readLine();
            while (temp != null) {
                sb.append(temp);
                temp = reader.readLine();
            }
        } catch (IOException e) {
        } finally {
            if (is != null) {
                try { is.close(); } catch (IOException e) { }
            }
        }

        return sb.toString();
    }

    private static String getMmsAddr(Context context, int id)
    {
        String address = "";
        String val;

        Cursor cur = context.getContentResolver().query(Uri.parse("content://mms/" + id + "/addr"), new String[] { "address" }, "type=137 AND msg_id=" + id, null, null);

        if (cur == null) {
            return address;
        }

        try {
            if (cur.moveToFirst()) {
                do {
                    val = cur.getString(cur.getColumnIndex("address"));
                    if (val != null) {
                        address = val;
                        break;
                    }
                } while (cur.moveToNext());
            }
        } finally {
            cur.close();
        }

        // return address.replaceAll("[^0-9]", "");
        return address;
    }

}
