package com.perrychoi.wechatmomentscontroller;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity {
    private static final String EXTRA_WORKFLOW = "workflow";
    private static final String EXTRA_MOMENT_TEXT = "moment_text";
    private static final String EXTRA_POINT_KEY = "point_key";
    private static final String EXTRA_MAX_PAGES = "max_pages";
    private static final String EXTRA_IMAGE_COUNT = "image_count";
    private static final String EXTRA_X = "x";
    private static final String EXTRA_Y = "y";
    private static final String WORKFLOW_ALBUM = "album";
    private static final String WORKFLOW_COMPOSE = "compose";
    private static final String WORKFLOW_COLLECT = "collect";
    private static final String WORKFLOW_CAPTURE_IMAGES = "capture_images";
    private static final String WORKFLOW_STOP = "stop";
    private static final String WORKFLOW_TAP_POINT = "tap_point";
    private static final String WORKFLOW_SET_POINT = "set_point";
    private static final String WORKFLOW_CLEAR_POINT = "clear_point";
    private static final String WORKFLOW_CLEAR_ALL_POINTS = "clear_all_points";
    private static final String WORKFLOW_CALIBRATION = "calibration";

    private TextView statusView;
    private TextView accessibilityStatusView;
    private TextView diagnosticView;
    private TextView screenshotView;
    private TextView exportView;
    private TextView logView;
    private EditText momentTextInput;
    private EditText collectPagesInput;
    private EditText postImageCountInput;

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
        note.setText("当前原型可选图并填写文字。发布前必须人工确认，避免误发。");
        note.setTextSize(14);
        note.setTextColor(Color.rgb(71, 85, 105));
        note.setPadding(0, dp(8), 0, dp(12));
        root.addView(note, matchWrap());

        statusView = new TextView(this);
        statusView.setTextSize(15);
        statusView.setTextColor(Color.rgb(30, 41, 59));
        root.addView(statusView, matchWrap());

        accessibilityStatusView = new TextView(this);
        accessibilityStatusView.setTextSize(13);
        root.addView(accessibilityStatusView, matchWrap());

        diagnosticView = new TextView(this);
        diagnosticView.setTextSize(13);
        diagnosticView.setTextColor(Color.rgb(71, 85, 105));
        root.addView(diagnosticView, matchWrap());

        screenshotView = new TextView(this);
        screenshotView.setTextSize(12);
        screenshotView.setTextColor(Color.rgb(100, 116, 139));
        screenshotView.setTextIsSelectable(true);
        root.addView(screenshotView, matchWrap());

        exportView = new TextView(this);
        exportView.setTextSize(12);
        exportView.setTextColor(Color.rgb(100, 116, 139));
        exportView.setTextIsSelectable(true);
        root.addView(exportView, matchWrap());

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

        TextView collectPagesLabel = new TextView(this);
        collectPagesLabel.setText("朋友圈采集屏数");
        collectPagesLabel.setTextSize(13);
        collectPagesLabel.setTextColor(Color.rgb(71, 85, 105));
        root.addView(collectPagesLabel, matchWrap());

        collectPagesInput = new EditText(this);
        collectPagesInput.setSingleLine(true);
        collectPagesInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        collectPagesInput.setText(String.valueOf(AutomationStore.momentCollectPages(this)));
        root.addView(collectPagesInput, matchWrap());

        TextView postImagesLabel = new TextView(this);
        postImagesLabel.setText("当前朋友圈图片/视频数量");
        postImagesLabel.setTextSize(13);
        postImagesLabel.setTextColor(Color.rgb(71, 85, 105));
        root.addView(postImagesLabel, matchWrap());

        postImageCountInput = new EditText(this);
        postImageCountInput.setSingleLine(true);
        postImageCountInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        postImageCountInput.setText(String.valueOf(AutomationStore.postImageCount(this)));
        root.addView(postImageCountInput, matchWrap());

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

        Button collect = button("读取朋友圈并保存素材");
        collect.setOnClickListener(v -> startMomentsCollect());
        root.addView(collect, matchWrap());

        Button captureImages = button("原生保存当前朋友圈图片/视频");
        captureImages.setOnClickListener(v -> startPostImageCapture());
        root.addView(captureImages, matchWrap());

        Button observe = button("只监听微信 30 秒（不操作）");
        observe.setOnClickListener(v -> startObserveOnly());
        root.addView(observe, matchWrap());

        Button openWechat = button("打开微信（不执行自动化）");
        openWechat.setOnClickListener(v -> openWechatOnly());
        root.addView(openWechat, matchWrap());

        Button pointTest = button("点位测试 / 校准");
        pointTest.setOnClickListener(v -> buildPointTestUi());
        root.addView(pointTest, matchWrap());

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

    private void buildPointTestUi() {
        int pad = dp(18);
        DisplayMetrics metrics = realMetrics();
        DeviceProfile profile = DeviceProfile.load(this, metrics);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);
        content.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("点位测试 / 校准");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(15, 23, 42));
        content.addView(title, matchWrap());

        TextView note = new TextView(this);
        note.setText("可保存现场覆盖值；服务执行时优先使用覆盖坐标，其次使用 profile 坐标。");
        note.setTextSize(14);
        note.setTextColor(Color.rgb(71, 85, 105));
        note.setPadding(0, dp(8), 0, dp(8));
        content.addView(note, matchWrap());

        TextView profileView = new TextView(this);
        profileView.setText("profile " + profile.summary() + " / " + metrics.widthPixels + "x" + metrics.heightPixels);
        profileView.setTextSize(13);
        profileView.setTextColor(Color.rgb(71, 85, 105));
        content.addView(profileView, matchWrap());

        Button openWechat = button("打开微信（不执行自动化）");
        openWechat.setOnClickListener(v -> openWechatOnly());
        content.addView(openWechat, matchWrap());

        Button clearAll = button("清除本机全部覆盖值");
        clearAll.setOnClickListener(v -> {
            PointOverrideStore.clearAllPointOverrides(this, profile, metrics);
            AutomationLogger.log(this, "已清除全部点位覆盖值: " + profile.summary());
            Toast.makeText(this, "已清除全部覆盖值", Toast.LENGTH_SHORT).show();
            buildPointTestUi();
        });
        content.addView(clearAll, matchWrap());

        for (ProfilePointCatalog.PointSpec point : ProfilePointCatalog.POINTS) {
            int[] profileXy = profile.point(point.key, point.fallbackX, point.fallbackY, metrics);
            PointOverrideStore.Coordinate effective =
                PointOverrideStore.effectivePoint(this, profile, metrics, point);
            String source = PointOverrideStore.pointSource(this, profile, metrics, point.key);

            TextView summary = new TextView(this);
            summary.setText(point.label + " (" + point.key + ")\n"
                + "profile " + profileXy[0] + "," + profileXy[1]
                + " / 当前 " + effective.x + "," + effective.y
                + " / " + source);
            summary.setTextSize(13);
            summary.setTextColor(Color.rgb(30, 41, 59));
            summary.setPadding(0, dp(10), 0, 0);
            content.addView(summary, matchWrap());

            Button edit = button("微调 / 保存覆盖值");
            edit.setOnClickListener(v -> buildPointEditorUi(point));
            content.addView(edit, matchWrap());

            Button test = button("测试当前点位");
            test.setOnClickListener(v -> startPointTapTest(point));
            content.addView(test, matchWrap());
        }

        Button back = button("返回主界面");
        back.setOnClickListener(v -> {
            buildUi();
            refresh();
        });
        content.addView(back, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    private void buildPointEditorUi(ProfilePointCatalog.PointSpec point) {
        int pad = dp(18);
        DisplayMetrics metrics = realMetrics();
        DeviceProfile profile = DeviceProfile.load(this, metrics);
        int[] profileXy = profile.point(point.key, point.fallbackX, point.fallbackY, metrics);
        PointOverrideStore.Coordinate effective =
            PointOverrideStore.effectivePoint(this, profile, metrics, point);
        String source = PointOverrideStore.pointSource(this, profile, metrics, point.key);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);
        content.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("校准: " + point.label);
        title.setTextSize(22);
        title.setTextColor(Color.rgb(15, 23, 42));
        content.addView(title, matchWrap());

        TextView meta = new TextView(this);
        meta.setText("key " + point.key
            + "\nprofile " + profileXy[0] + "," + profileXy[1]
            + "\n当前 " + effective.x + "," + effective.y
            + " / " + source
            + "\n屏幕 " + metrics.widthPixels + "x" + metrics.heightPixels);
        meta.setTextSize(13);
        meta.setTextColor(Color.rgb(71, 85, 105));
        meta.setPadding(0, dp(8), 0, dp(8));
        content.addView(meta, matchWrap());

        EditText xInput = coordinateInput(effective.x);
        EditText yInput = coordinateInput(effective.y);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.addView(xInput, weightedWrap());
        inputRow.addView(yInput, weightedWrap());
        content.addView(inputRow, matchWrap());

        addAdjustRow(content, "X", xInput, 0, Math.max(0, metrics.widthPixels - 1));
        addAdjustRow(content, "Y", yInput, 0, Math.max(0, metrics.heightPixels - 1));

        Button save = button("保存覆盖值");
        save.setOnClickListener(v -> {
            if (saveOverrideFromInputs(point, profile, metrics, xInput, yInput)) {
                buildPointEditorUi(point);
            }
        });
        content.addView(save, matchWrap());

        Button saveAndTest = button("保存并测试当前点位");
        saveAndTest.setOnClickListener(v -> {
            if (saveOverrideFromInputs(point, profile, metrics, xInput, yInput)) {
                startPointTapTest(point);
            }
        });
        content.addView(saveAndTest, matchWrap());

        Button clear = button("清除此点覆盖值");
        clear.setOnClickListener(v -> {
            PointOverrideStore.clearPointOverride(this, profile, metrics, point.key);
            AutomationLogger.log(this, "清除点位覆盖值: " + point.key + " " + point.label);
            Toast.makeText(this, "已清除覆盖值: " + point.label, Toast.LENGTH_SHORT).show();
            buildPointEditorUi(point);
        });
        content.addView(clear, matchWrap());

        Button openWechat = button("打开微信（不执行自动化）");
        openWechat.setOnClickListener(v -> openWechatOnly());
        content.addView(openWechat, matchWrap());

        Button back = button("返回点位列表");
        back.setOnClickListener(v -> buildPointTestUi());
        content.addView(back, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
    }

    private EditText coordinateInput(int value) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(String.valueOf(value));
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return input;
    }

    private void addAdjustRow(
        LinearLayout content,
        String label,
        EditText input,
        int min,
        int max
    ) {
        TextView axis = new TextView(this);
        axis.setText(label + " 微调");
        axis.setTextSize(13);
        axis.setTextColor(Color.rgb(71, 85, 105));
        axis.setPadding(0, dp(8), 0, 0);
        content.addView(axis, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int[] deltas = new int[]{-10, -1, 1, 10};
        for (int delta : deltas) {
            Button button = button(delta > 0 ? "+" + delta : String.valueOf(delta));
            button.setOnClickListener(v -> adjustInput(input, delta, min, max));
            row.addView(button, weightedWrap());
        }
        content.addView(row, matchWrap());
    }

    private boolean saveOverrideFromInputs(
        ProfilePointCatalog.PointSpec point,
        DeviceProfile profile,
        DisplayMetrics metrics,
        EditText xInput,
        EditText yInput
    ) {
        Integer x = parseCoordinate(xInput);
        Integer y = parseCoordinate(yInput);
        if (x == null || y == null) {
            Toast.makeText(this, "请输入有效坐标", Toast.LENGTH_SHORT).show();
            return false;
        }

        int safeX = clamp(x, 0, Math.max(0, metrics.widthPixels - 1));
        int safeY = clamp(y, 0, Math.max(0, metrics.heightPixels - 1));
        PointOverrideStore.setPointOverride(this, profile, metrics, point.key, safeX, safeY);
        AutomationLogger.log(this, "保存点位覆盖值: key=" + point.key
            + " label=" + point.label
            + " x=" + safeX
            + " y=" + safeY
            + " profile=" + profile.summary());
        Toast.makeText(this, "已保存: " + point.label + " " + safeX + "," + safeY, Toast.LENGTH_SHORT).show();
        return true;
    }

    private void adjustInput(EditText input, int delta, int min, int max) {
        Integer value = parseCoordinate(input);
        if (value == null) {
            value = 0;
        }
        input.setText(String.valueOf(clamp(value + delta, min, max)));
        input.setSelection(input.getText().length());
    }

    private Integer parseCoordinate(EditText input) {
        if (input == null || input.getText() == null) {
            return null;
        }

        String value = input.getText().toString().trim();
        if (value.isEmpty()) {
            return null;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int collectPagesFromInput() {
        Integer pages = parseCoordinate(collectPagesInput);
        if (pages == null) {
            return AutomationStore.defaultMomentCollectPages();
        }
        return pages;
    }

    private int postImageCountFromInput() {
        Integer count = parseCoordinate(postImageCountInput);
        if (count == null) {
            return AutomationStore.defaultPostImageCount();
        }
        return count;
    }

    private void startWechatAlbumTest() {
        if (!ensureAccessibilityEnabled()) {
            return;
        }
        AutomationLogger.log(this, "请求启动微信四步测试，请手动切到微信首页");
        AutomationStore.requestWorkflow(this, AutomationStore.COMMAND_WECHAT_ALBUM_TEST);
        Toast.makeText(this, "请手动打开微信首页，服务检测到微信后会延迟 3 秒执行", Toast.LENGTH_LONG).show();
        refresh();
    }

    private void startWechatComposeTest() {
        if (!ensureAccessibilityEnabled()) {
            return;
        }
        String text = momentTextInput == null
            ? AutomationStore.defaultMomentText()
            : momentTextInput.getText().toString();
        AutomationLogger.log(this, "请求启动完整测试，请手动切到微信首页");
        AutomationStore.requestWorkflow(this, AutomationStore.COMMAND_WECHAT_COMPOSE_TEST, text);
        Toast.makeText(this, "请手动打开微信首页；将选第一张图并填写文字，不会发表", Toast.LENGTH_LONG).show();
        refresh();
    }

    private void startMomentsCollect() {
        if (!ensureAccessibilityEnabled()) {
            return;
        }
        int pages = collectPagesFromInput();
        AutomationLogger.log(this, "请求采集朋友圈素材: pages=" + pages);
        AutomationStore.requestMomentCollection(this, pages);
        Toast.makeText(this, "请打开微信首页或朋友圈页；将保存可见截图和文字素材", Toast.LENGTH_LONG).show();
        refresh();
    }

    private void startPostImageCapture() {
        if (!ensureAccessibilityEnabled()) {
            return;
        }
        int count = postImageCountFromInput();
        AutomationLogger.log(this, "请求保存朋友圈图片: count=" + count);
        AutomationStore.requestPostImageCapture(this, count);
        Toast.makeText(this, "请点开目标朋友圈第一张图片/视频；将用微信菜单逐个保存", Toast.LENGTH_LONG).show();
        refresh();
    }

    private void startObserveOnly() {
        if (!ensureAccessibilityEnabled()) {
            return;
        }
        AutomationLogger.log(this, "请求微信监听诊断，不执行点击");
        AutomationStore.requestWorkflow(this, AutomationStore.COMMAND_WECHAT_OBSERVE_ONLY);
        Toast.makeText(this, "请打开微信并等待 30 秒，这个模式不会点击屏幕", Toast.LENGTH_LONG).show();
        refresh();
    }

    private void startPointTapTest(ProfilePointCatalog.PointSpec point) {
        if (!ensureAccessibilityEnabled()) {
            return;
        }
        AutomationLogger.log(this, "请求点位测试: " + point.key + " " + point.label);
        AutomationStore.requestPointTap(this, point.key);
        Toast.makeText(this, "请切到对应微信页面，将点击一次: " + point.label, Toast.LENGTH_LONG).show();
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
        if (WORKFLOW_STOP.equals(workflow)) {
            AutomationLogger.log(this, "ADB 请求停止并清空命令");
            AutomationStore.requestStop(this);
            AutomationStore.clearCommand(this, "ADB 已停止自动化");
            return;
        }

        if (WORKFLOW_CALIBRATION.equals(workflow)) {
            AutomationLogger.log(this, "ADB 请求打开点位测试页");
            buildPointTestUi();
            return;
        }

        if (WORKFLOW_SET_POINT.equals(workflow)) {
            handleSetPointIntent(intent);
            return;
        }

        if (WORKFLOW_CLEAR_POINT.equals(workflow)) {
            handleClearPointIntent(intent);
            return;
        }

        if (WORKFLOW_CLEAR_ALL_POINTS.equals(workflow)) {
            DisplayMetrics metrics = realMetrics();
            DeviceProfile profile = DeviceProfile.load(this, metrics);
            PointOverrideStore.clearAllPointOverrides(this, profile, metrics);
            AutomationLogger.log(this, "ADB 清除全部点位覆盖值: " + profile.summary());
            buildPointTestUi();
            return;
        }

        if (WORKFLOW_TAP_POINT.equals(workflow)) {
            String pointKey = intent.getStringExtra(EXTRA_POINT_KEY);
            AutomationLogger.log(this, "ADB 请求点位测试: " + pointKey);
            AutomationStore.requestPointTap(this, pointKey);
            return;
        }

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
            return;
        }

        if (WORKFLOW_COLLECT.equals(workflow)) {
            int pages = intent.getIntExtra(
                EXTRA_MAX_PAGES,
                AutomationStore.defaultMomentCollectPages()
            );
            if (collectPagesInput != null) {
                collectPagesInput.setText(String.valueOf(pages));
            }
            AutomationLogger.log(this, "ADB 请求采集朋友圈素材: pages=" + pages);
            AutomationStore.requestMomentCollection(this, pages);
            return;
        }

        if (WORKFLOW_CAPTURE_IMAGES.equals(workflow)) {
            int count = intent.getIntExtra(
                EXTRA_IMAGE_COUNT,
                AutomationStore.defaultPostImageCount()
            );
            if (postImageCountInput != null) {
                postImageCountInput.setText(String.valueOf(count));
            }
            AutomationLogger.log(this, "ADB 请求保存朋友圈图片: count=" + count);
            AutomationStore.requestPostImageCapture(this, count);
        }
    }

    private void handleSetPointIntent(Intent intent) {
        String pointKey = intent.getStringExtra(EXTRA_POINT_KEY);
        ProfilePointCatalog.PointSpec point = ProfilePointCatalog.find(pointKey);
        if (point == null) {
            AutomationLogger.log(this, "ADB 设置点位失败: 未知点位 " + pointKey);
            Toast.makeText(this, "未知点位: " + pointKey, Toast.LENGTH_LONG).show();
            return;
        }
        if (!intent.hasExtra(EXTRA_X) || !intent.hasExtra(EXTRA_Y)) {
            AutomationLogger.log(this, "ADB 设置点位失败: 缺少 x/y " + pointKey);
            Toast.makeText(this, "缺少 x/y", Toast.LENGTH_LONG).show();
            return;
        }

        DisplayMetrics metrics = realMetrics();
        DeviceProfile profile = DeviceProfile.load(this, metrics);
        int x = intent.getIntExtra(EXTRA_X, point.fallbackX);
        int y = intent.getIntExtra(EXTRA_Y, point.fallbackY);
        int safeX = clamp(x, 0, Math.max(0, metrics.widthPixels - 1));
        int safeY = clamp(y, 0, Math.max(0, metrics.heightPixels - 1));
        PointOverrideStore.setPointOverride(this, profile, metrics, point.key, safeX, safeY);
        AutomationLogger.log(this, "ADB 设置点位覆盖值: key=" + point.key
            + " x=" + safeX
            + " y=" + safeY
            + " profile=" + profile.summary());
        buildPointTestUi();
    }

    private void handleClearPointIntent(Intent intent) {
        String pointKey = intent.getStringExtra(EXTRA_POINT_KEY);
        ProfilePointCatalog.PointSpec point = ProfilePointCatalog.find(pointKey);
        if (point == null) {
            AutomationLogger.log(this, "ADB 清除点位失败: 未知点位 " + pointKey);
            Toast.makeText(this, "未知点位: " + pointKey, Toast.LENGTH_LONG).show();
            return;
        }

        DisplayMetrics metrics = realMetrics();
        DeviceProfile profile = DeviceProfile.load(this, metrics);
        PointOverrideStore.clearPointOverride(this, profile, metrics, point.key);
        AutomationLogger.log(this, "ADB 清除点位覆盖值: key=" + point.key
            + " profile=" + profile.summary());
        buildPointTestUi();
    }

    private void refresh() {
        if (statusView != null) {
            statusView.setText("状态: " + AutomationStore.lastStatus(this));
        }
        if (accessibilityStatusView != null) {
            boolean enabled = isAccessibilityServiceEnabled();
            accessibilityStatusView.setText(enabled
                ? "无障碍服务: 已启用"
                : "无障碍服务: 未启用，测试不会执行");
            accessibilityStatusView.setTextColor(enabled
                ? Color.rgb(22, 101, 52)
                : Color.rgb(185, 28, 28));
        }
        if (diagnosticView != null) {
            diagnosticView.setText("诊断: " + AutomationStore.lastDiagnostic(this));
        }
        if (screenshotView != null) {
            String screenshot = AutomationStore.lastScreenshotPath(this);
            screenshotView.setText(screenshot.isEmpty() ? "最近失败截图: 无" : "最近失败截图: " + screenshot);
        }
        if (exportView != null) {
            String export = AutomationStore.lastExportPath(this);
            exportView.setText(export.isEmpty() ? "最近素材导出: 无" : "最近素材导出: " + export);
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

    private LinearLayout.LayoutParams weightedWrap() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1
        );
        params.setMargins(dp(2), dp(4), dp(2), dp(4));
        return params;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private boolean ensureAccessibilityEnabled() {
        if (isAccessibilityServiceEnabled()) {
            return true;
        }

        AutomationLogger.log(this, "启动被拦截: 无障碍服务未启用");
        Toast.makeText(this, "请先启用“朋友圈自动化辅助服务”", Toast.LENGTH_LONG).show();
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        refresh();
        return false;
    }

    private boolean isAccessibilityServiceEnabled() {
        int accessibilityEnabled;
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED
            );
        } catch (Settings.SettingNotFoundException e) {
            accessibilityEnabled = 0;
        }

        if (accessibilityEnabled != 1) {
            return false;
        }

        String enabledServices = Settings.Secure.getString(
            getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabledServices == null) {
            return false;
        }

        String component = new ComponentName(this, WechatAutomationService.class).flattenToString();
        return enabledServices.toLowerCase(Locale.US).contains(component.toLowerCase(Locale.US));
    }

    private String deviceSummary() {
        DisplayMetrics metrics = realMetrics();
        DeviceProfile profile = DeviceProfile.load(this, metrics);
        return "设备: " + Build.MANUFACTURER + " " + Build.MODEL
            + " / Android " + Build.VERSION.RELEASE
            + " / " + metrics.widthPixels + "x" + metrics.heightPixels
            + " @" + metrics.densityDpi + "dpi"
            + " / profile " + profile.summary();
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics realMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        return metrics;
    }
}
