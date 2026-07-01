package com.perrychoi.wechatmomentscontroller;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class CommandActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleAndFinish(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleAndFinish(intent);
    }

    private void handleAndFinish(Intent intent) {
        CommandIntentHandler.handle(this, intent, "CommandActivity");
        finish();
        overridePendingTransition(0, 0);
    }
}
