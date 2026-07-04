package com.perrychoi.wechatmomentscontroller;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;

public class CommandActivity extends Activity {
    private boolean pendingClipboardRead = false;

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

    @Override
    protected void onResume() {
        super.onResume();
        if (!pendingClipboardRead) {
            return;
        }
        pendingClipboardRead = false;
        getWindow().getDecorView().postDelayed(this::readClipboardAndFinish, 200);
    }

    private void handleAndFinish(Intent intent) {
        if (intent != null && AutomationStore.ACTION_READ_CLIPBOARD.equals(intent.getAction())) {
            pendingClipboardRead = true;
            return;
        }

        CommandIntentHandler.handle(this, intent, "CommandActivity");
        finish();
        overridePendingTransition(0, 0);
    }

    private void readClipboardAndFinish() {
        String text = readClipboardText();
        AutomationStore.setNativeCopyText(this, text);
        AutomationStore.bumpAutomationWakeTick(this);
        AutomationLogger.log(this, "CommandActivity 前台读取剪贴板: chars=" + text.length());
        finish();
        overridePendingTransition(0, 0);
    }

    private String readClipboardText() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return "";
            }

            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() <= 0 || clip.getItemAt(0) == null) {
                return "";
            }

            CharSequence text = clip.getItemAt(0).coerceToText(this);
            return text == null ? "" : text.toString();
        } catch (RuntimeException e) {
            AutomationLogger.log(this, "CommandActivity 读取剪贴板失败: "
                + e.getClass().getSimpleName() + " " + e.getMessage());
            return "";
        }
    }
}
