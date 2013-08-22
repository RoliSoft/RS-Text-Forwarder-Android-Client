package net.rolisoft.textforwarder;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;

public class BackgroundIntentService extends IntentService {

    public static final int SMS_RECEIVED  = 0x0A;
    public static final int MMS_RECEIVED  = 0x0B;
    public static final int CALL_RECEIVED = 0x0C;
    public static final int PUSH_RECEIVED = 0x0D;

    public static void start(Context context, Intent intent, int action)
    {
        Intent svc = new Intent(context, BackgroundIntentService.class);
        svc.setAction(Integer.toString(action));
        svc.putExtras(intent.getExtras());
        context.startService(svc);
    }

    public BackgroundIntentService() {
        super("BackgroundIntentService");
    }

    @Override
    protected void onHandleIntent(Intent intent)
    {
        int action = Integer.valueOf(intent.getAction());

        switch (action) {
            case SMS_RECEIVED:  SmsReceiver.handle(this, intent);  break;
            case MMS_RECEIVED:  MmsReceiver.handle(this, intent);  break;
            case CALL_RECEIVED: CallReceiver.handle(this, intent); break;
            case PUSH_RECEIVED: PushReceiver.handle(this, intent); break;
        }
    }

}