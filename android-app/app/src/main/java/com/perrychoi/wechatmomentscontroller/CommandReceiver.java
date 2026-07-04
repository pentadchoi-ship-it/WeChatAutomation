package com.perrychoi.wechatmomentscontroller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class CommandReceiver extends BroadcastReceiver {
    static final String ACTION_AUTOMATION_WAKE =
        "com.perrychoi.wechatmomentscontroller.AUTOMATION_WAKE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        if (ACTION_AUTOMATION_WAKE.equals(intent.getAction())) {
            AutomationLogger.log(context, "内部唤醒自动化检查: "
                + intent.getStringExtra("reason"));
            AutomationStore.bumpAutomationWakeTick(context);
            return;
        }

        if (CommandIntentHandler.ACTION_COMMAND.equals(intent.getAction())) {
            CommandIntentHandler.handle(context, intent, "Broadcast");
        }
    }
}
