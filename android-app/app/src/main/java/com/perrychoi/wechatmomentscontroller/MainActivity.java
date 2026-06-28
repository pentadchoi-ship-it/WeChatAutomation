package com.perrychoi.wechatmomentscontroller;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String EXTRA_WORKFLOW = "workflow";
    private static final String EXTRA_MOMENT_TEXT = "moment_text";
    private static final String WORKFLOW_ALBUM = "album";
    private static final String WORKFLOW_COMPOSE = "compose";

    private TextView statusView;
    private TextView logView;
    private EditText momentTextInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleIntent(getIntent());
        refresh();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void buildUi() {
        int pad = dp(18);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("朋友圈自动化 MVP");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(15, 23, 42));
        root.addView(title, matchWrap());

        TextView note = new TextView(this);
        note.setText("当前原型只执行到“从手机相册选择”。发布前必须人工确认，避免误发。");
        note.setTextSize(14);
        note.setTextColor(Color.rgb(71, 85, 105));
        note.setPadding(0, dp(8), 0, dp(12));
        root.addView(note, matchWrap());

        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setTextColor(Color.rgb(30, 41, 59));
        root.addView(statusView, matchWrap());

        TextView device = new TextView(this);
        device.setText(deviceSummary());
        device.setTextSize(13);
        device.setTextColor(Color.rgb(71, 85, 105));
        device.setPadding(0, 0, 0, dp(8));
        root.addView(device, matchWrap());

        TextView inputLabel = new TextView(this);
        inputLabel.setText("朋友圈文字");
        inputLabel.setTextSize(13);
        inputLabel.setTextColor(Color.rgb(71, 85, 105));
        root.addView(inputLabel, matchWrap());

        momentTextInput = new EditText(this);
        momentTextInput.setMinLines(2);
        momentTextInput.setMaxLines(4);
        momentTextInput.setText(AutomationStore.momentText(this));
        momentTextInput.setHint(AutomationStore.defaultMomentText());
        root.addView(momentTextInput, matchWrap());

        Button accessibility = button("打开无障碍设置");
        accessibility.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        );
        root.addView(accessibility, matchWrap());

        Button start = button("从微信首页开始四步测试");
        start.setOnClickListener(v -> startWechatAlbumTest());
        root.addView(start, matchWrap());

        Button compose = button("完整测试：选图并填写文字");
        compose.setOnClickListener(v -> startWechatComposeTest());
        root.addView(compose, matchWrap());

        Button observe = button("只监听微信 30 秒（不操作）");
        observe.setOnClickListener(v -> startObserveOnly());
        root.addView(observe, matchWrap());

        Button openWechat = button("打开微信（不执行自动化）");
        openWechat.setOnClickListener(v -> openWechatOnly());
        root.addView(openWechat, matchWrap());

        Button stop = button("停止自动化");
        stop.setOnClickListener(v -> {
            AutomationStore.requestStop(this);
            AutomationLogger.log(this, "用户请求停止");
            refresh();
        });
        root.addView(stop, matchWrap());

        Button refresh = button("刷新日志");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh, matchWrap());

        Button clear = button("清空日志");
        clear.setOnClickListener(v -> {
            AutomationLogger.clear(this);
            refresh();
        });
        root.addView(clear, matchWrap());

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setTextColor(Color.rgb(15, 23, 42));
        logView.setTextIsSelectable(true);
        logView.setPadding(0, dp(12), 0, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1
        ));

        setContentView(root);
    }

    private void startWechatAlbumTest() {
        AutomationLogger.log(this, "请求启动微信四步测试，请手动切到微信首页");
        AutomationStore.requestWorkflow(this, AutomationStore.COMMAND_WECHAT_ALBUM_TEST);
        Toast.makeText(this, "请手动打开微信首页，服务检测到微信后会延迟 3 秒执行", Toast.LENGTH_LONG).show();
        refresh();
    }

    private void startWechatComposeTest() {
        String text = momentTextInput == null
            ? AutomationStore.defaultMomentText()
            : momentTextInput.getText().toString();
        AutomationLogger.log(this, "请求启动完整测试，请手动切到微信首页");
        AutomationStore.requestWorkflow(this, AutomationStore.COMMAND_WECHAT_COMPOSE_TEST, text);
        Toast.makeText(this, "请手动打开微信首页；将选第一张图并填写文字，不会发表", Toast.LENGTH_LONG).show();
        refresh();
    }

    private void startObserveOnly() {
        AutomationLogger.log(this, "请求微信监听诊断，不执行点击");
        AutomationStore.requestWorkflow(this, AutomationStore.COMMAND_WECHAT_OBSERVE_ONLY);
        Toast.makeText(this, "请打开微信并等待 30 秒，这个模式不会点击屏幕", Toast.LENGTH_LONG).show();
        refresh();
    }

    private void openWechatOnly() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
        if (launch == null) {
            Toast.makeText(this, "没有找到微信 com.tencent.mm", Toast.LENGTH_LONG).show();
            AutomationLogger.log(this, "启动失败: 未找到微信");
            refresh();
            return;
        }

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
        Toast.makeText(this, "已打开微信；未请求自动化", Toast.LENGTH_LONG).show();
        refresh();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        String workflow = intent.getStringExtra(EXTRA_WORKFLOW);
        if (WORKFLOW_ALBUM.equals(workflow)) {
            AutomationLogger.log(this, "ADB 请求启动四步测试");
            AutomationStore.requestWorkflow(this, AutomationStore.COMMAND_WECHAT_ALBUM_TEST);
            return;
        }

        if (WORKFLOW_COMPOSE.equals(workflow)) {
            String text = intent.getStringExtra(EXTRA_MOMENT_TEXT);
            if (momentTextInput != null && text != null && !text.trim().isEmpty()) {
                momentTextInput.setText(text.trim());
            }
            String momentText = momentTextInput == null
                ? text
                : momentTextInput.getText().toString();
            AutomationLogger.log(this, "ADB 请求启动完整测试");
            AutomationStore.requestWorkflow(this, AutomationStore.COMMAND_WECHAT_COMPOSE_TEST, momentText);
        }
    }

    private void refresh() {
        if (statusView != null) {
            statusView.setText("状态: " + AutomationStore.lastStatus(this));
        }
        if (logView != null) {
            String logs = AutomationLogger.lines(this);
            logView.setText(logs.isEmpty() ? "暂无日志" : logs);
        }
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(4), 0, dp(4));
        return params;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private String deviceSummary() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        return "设备: " + Build.MANUFACTURER + " " + Build.MODEL
            + " / Android " + Build.VERSION.RELEASE
            + " / " + metrics.widthPixels + "x" + metrics.heightPixels
            + " @" + metrics.densityDpi + "dpi";
    }
}
