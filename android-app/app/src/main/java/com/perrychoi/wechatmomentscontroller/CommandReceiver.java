package com.perrychoi.wechatmomentscontroller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class CommandReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !CommandIntentHandler.ACTION_COMMAND.equals(intent.getAction())) {
            return;
        }

        CommandIntentHandler.handle(context, intent, "Broadcast");
    }
}
