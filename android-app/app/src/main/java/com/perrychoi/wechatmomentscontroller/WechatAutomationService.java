package com.perrychoi.wechatmomentscontroller;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

public class WechatAutomationService extends AccessibilityService {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final int PROFILE_WIDTH = 1080;
    private static final int PROFILE_HEIGHT = 2340;
    private static final long START_DELAY_MS = 3000;
    private static final long OBSERVE_DURATION_MS = 30000;
    private static final long COMMAND_POLL_MS = 1000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences.OnSharedPreferenceChangeListener commandListener =
        (prefs, key) -> scheduleCommandCheck("命令变化");

    private boolean workflowRunning = false;
    private boolean observeRunning = false;
    private boolean commandCheckScheduled = false;
    private long lastWaitingLogMs = 0;
    private String workflowCompletionStatus = "流程完成";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AutomationStore.registerListener(this, commandListener);
        AutomationLogger.log(this, "辅助服务已连接: " + deviceSummary());
        scheduleCommandCheck("服务连接");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return;
        }

        if (!WECHAT_PACKAGE.contentEquals(event.getPackageName())) {
            return;
        }

        scheduleCommandCheck("微信事件");
    }

    @Override
    public void onInterrupt() {
        AutomationLogger.log(this, "辅助服务被系统中断");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        AutomationStore.unregisterListener(this, commandListener);
        handler.removeCallbacksAndMessages(null);
        return super.onUnbind(intent);
    }

    private void scheduleCommandCheck(String reason) {
        if (commandCheckScheduled) {
            return;
        }

        commandCheckScheduled = true;
        handler.postDelayed(() -> {
            commandCheckScheduled = false;
            checkCommand(reason);
        }, 100);
    }

    private void checkCommand(String reason) {
        String command = AutomationStore.command(this);
        if (AutomationStore.COMMAND_NONE.equals(command)) {
            return;
        }

        if (AutomationStore.stopRequested(this)) {
            finishWorkflow("用户停止");
            return;
        }

        if (!isWechatActive()) {
            logWaitingForWechat(command, reason);
            handler.postDelayed(() -> scheduleCommandCheck("等待微信前台"), COMMAND_POLL_MS);
            return;
        }

        maybeStartObserveOnly();
        maybeStartWorkflow();
    }

    private void maybeStartWorkflow() {
        if (workflowRunning) {
            return;
        }

        String command = AutomationStore.command(this);
        List<Step> steps;
        String startName;
        if (AutomationStore.COMMAND_WECHAT_ALBUM_TEST.equals(command)) {
            steps = wechatAlbumSteps();
            startName = "四步测试";
            workflowCompletionStatus = "四步测试完成，已停在相册选择流程后";
        } else if (AutomationStore.COMMAND_WECHAT_COMPOSE_TEST.equals(command)) {
            steps = wechatComposeSteps();
            startName = "完整测试";
            workflowCompletionStatus = "已选择图片并填写文字，停在发表前";
        } else {
            return;
        }

        workflowRunning = true;
        AutomationLogger.log(this, "检测到微信，" + (START_DELAY_MS / 1000) + " 秒后开始" + startName);
        handler.postDelayed(() -> {
            if (!AutomationStore.hasCommand(this, command)) {
                workflowRunning = false;
                AutomationLogger.log(this, startName + "取消: 命令已变化");
                return;
            }

            if (!isWechatActive()) {
                workflowRunning = false;
                AutomationLogger.log(this, startName + "等待: 微信不在前台");
                scheduleCommandCheck("启动延迟后等待微信");
                return;
            }

            AutomationLogger.log(this, "开始执行" + startName);
            runStep(steps, 0, 0);
        }, START_DELAY_MS);
    }

    private void maybeStartObserveOnly() {
        if (observeRunning) {
            return;
        }

        String command = AutomationStore.command(this);
        if (!AutomationStore.COMMAND_WECHAT_OBSERVE_ONLY.equals(command)) {
            return;
        }

        observeRunning = true;
        AutomationLogger.log(this, "微信监听诊断开始: 仅确认前台进入微信，不读取节点，不点击");
        handler.postDelayed(() -> {
            observeRunning = false;
            if (AutomationStore.hasCommand(this, AutomationStore.COMMAND_WECHAT_OBSERVE_ONLY)) {
                AutomationStore.clearCommand(this, "监听诊断完成，未执行点击");
                AutomationLogger.log(this, "微信监听诊断完成");
            }
        }, OBSERVE_DURATION_MS);
    }

    private void logWaitingForWechat(String command, String reason) {
        long now = System.currentTimeMillis();
        if (now - lastWaitingLogMs < 5000) {
            return;
        }

        lastWaitingLogMs = now;
        AutomationLogger.log(this, "等待微信前台: command=" + command + " reason=" + reason);
    }

    private List<Step> wechatAlbumSteps() {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(
            "点击“发现”",
            new String[]{"发现"},
            710,
            2246,
            1500
        ));
        steps.add(new Step(
            "点击“朋友圈”",
            new String[]{"朋友圈"},
            215,
            290,
            2500
        ));
        steps.add(new Step(
            "点击右上角摄像头按钮",
            new String[]{"相机", "拍摄", "拍照", "拍照分享", "Camera"},
            990,
            185,
            1500
        ));
        steps.add(new Step(
            "点击“从手机相册选择”",
            new String[]{"从手机相册选择", "从相册选择", "手机相册"},
            840,
            2080,
            1500
        ));
        return steps;
    }

    private List<Step> wechatComposeSteps() {
        List<Step> steps = wechatAlbumSteps();
        steps.add(new Step(
            "选择第一张图片",
            new String[]{"选择", "图片"},
            215,
            293,
            800
        ));
        steps.add(new Step(
            "点击“完成”",
            new String[]{"完成"},
            940,
            2248,
            2200
        ));
        steps.add(Step.text(
            "填写朋友圈文字",
            new String[]{"这一刻的想法", "说点什么", "文本", "输入"},
            245,
            337,
            150,
            265,
            1500
        ));
        return steps;
    }

    private void runStep(List<Step> steps, int index, int retry) {
        if (AutomationStore.stopRequested(this)) {
            finishWorkflow("用户停止");
            return;
        }

        if (!isWechatActive()) {
            AutomationLogger.log(this, "流程暂停: 微信不在前台");
            workflowRunning = false;
            scheduleCommandCheck("流程中等待微信");
            return;
        }

        if (index >= steps.size()) {
            finishWorkflow(workflowCompletionStatus);
            return;
        }

        Step step = steps.get(index);
        if (step.isText()) {
            runTextStep(steps, index, retry, step);
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            retryOrFallback(steps, index, retry, step, "当前窗口为空");
            return;
        }

        if (isDegenerateRoot(root)) {
            retryOrFallback(steps, index, 4, step, "节点树为空");
            return;
        }

        AccessibilityNodeInfo node = findByLabels(root, step.labels);
        if (node != null && clickNode(node)) {
            AutomationLogger.log(this, "节点点击成功: " + step.name);
            handler.postDelayed(() -> runStep(steps, index + 1, 0), step.afterMs);
            return;
        }

        retryOrFallback(steps, index, retry, step, "未找到可点击节点");
    }

    private void retryOrFallback(List<Step> steps, int index, int retry, Step step, String reason) {
        if (retry < 4) {
            AutomationLogger.log(this, step.name + " 等待重试: " + reason + " #" + (retry + 1));
            handler.postDelayed(() -> runStep(steps, index, retry + 1), 500);
            return;
        }

        int[] xy = scaleFromProfile(step.fallbackX, step.fallbackY);
        AutomationLogger.log(this, "使用坐标兜底: " + step.name
            + " baseline=" + step.fallbackX + "," + step.fallbackY
            + " scaled=" + xy[0] + "," + xy[1]
            + " metrics=" + metricsSummary());
        tap(xy[0], xy[1]);
        handler.postDelayed(() -> runStep(steps, index + 1, 0), step.afterMs);
    }

    private void runTextStep(List<Step> steps, int index, int retry, Step step) {
        String text = AutomationStore.momentText(this);
        setClipboard(text);

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && !isDegenerateRoot(root)) {
            AccessibilityNodeInfo target = findEditableOrLabel(root, step.labels);
            if (target != null && setNodeText(target, text)) {
                AutomationLogger.log(this, "文字填写成功: " + step.name);
                handler.postDelayed(() -> runStep(steps, index + 1, 0), step.afterMs);
                return;
            }
        }

        int[] textXy = scaleFromProfile(step.fallbackX, step.fallbackY);
        int[] pasteXy = scaleFromProfile(step.pasteX, step.pasteY);
        AutomationLogger.log(this, "使用剪贴板粘贴文字: " + step.name
            + " textTap=" + textXy[0] + "," + textXy[1]
            + " pasteTap=" + pasteXy[0] + "," + pasteXy[1]);
        tap(textXy[0], textXy[1]);
        handler.postDelayed(() -> longPress(textXy[0], textXy[1]), 450);
        handler.postDelayed(() -> tap(pasteXy[0], pasteXy[1]), 1200);
        handler.postDelayed(() -> runStep(steps, index + 1, 0), step.afterMs + 1400);
    }

    private void finishWorkflow(String status) {
        workflowRunning = false;
        AutomationLogger.log(this, "流程结束: " + status);
        AutomationStore.clearCommand(this, status);
    }

    private AccessibilityNodeInfo findByLabels(AccessibilityNodeInfo root, String[] labels) {
        if (root == null) {
            return null;
        }

        CharSequence text = root.getText();
        CharSequence desc = root.getContentDescription();
        for (String label : labels) {
            if (contains(text, label) || contains(desc, label)) {
                return root;
            }
        }

        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            AccessibilityNodeInfo found = findByLabels(child, labels);
            if (found != null) {
                return found;
            }
        }

        return null;
    }

    private AccessibilityNodeInfo findEditableOrLabel(AccessibilityNodeInfo root, String[] labels) {
        if (root == null) {
            return null;
        }

        if (root.isEditable()) {
            return root;
        }

        AccessibilityNodeInfo labeled = findByLabels(root, labels);
        if (labeled != null) {
            return labeled;
        }

        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            AccessibilityNodeInfo found = findEditableOrLabel(child, labels);
            if (found != null) {
                return found;
            }
        }

        return null;
    }

    private boolean contains(CharSequence value, String needle) {
        return value != null && value.toString().contains(needle);
    }

    private boolean isDegenerateRoot(AccessibilityNodeInfo root) {
        return root.getChildCount() == 0
            && root.getText() == null
            && root.getContentDescription() == null;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            current = current.getParent();
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (!bounds.isEmpty()) {
            return tap(bounds.centerX(), bounds.centerY());
        }

        return false;
    }

    private boolean tap(int x, int y) {
        return gestureAt(x, y, 80);
    }

    private boolean longPress(int x, int y) {
        return gestureAt(x, y, 650);
    }

    private boolean gestureAt(int x, int y, long durationMs) {
        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, durationMs);

        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(stroke)
            .build();

        return dispatchGesture(gesture, null, null);
    }

    private boolean setNodeText(AccessibilityNodeInfo node, String text) {
        Bundle args = new Bundle();
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            text
        );
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private void setClipboard(String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("moments_text", text));
        }
    }

    private int[] scaleFromProfile(int x, int y) {
        DisplayMetrics metrics = realMetrics();
        int scaledX = Math.round((x / (float) (PROFILE_WIDTH - 1)) * (metrics.widthPixels - 1));
        int scaledY = Math.round((y / (float) (PROFILE_HEIGHT - 1)) * (metrics.heightPixels - 1));
        return new int[]{scaledX, scaledY};
    }

    private boolean isWechatActive() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            return false;
        }

        return WECHAT_PACKAGE.contentEquals(root.getPackageName());
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics realMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            wm.getDefaultDisplay().getRealMetrics(metrics);
        } else {
            metrics.setTo(getResources().getDisplayMetrics());
        }
        return metrics;
    }

    private String deviceSummary() {
        return Build.MANUFACTURER + " " + Build.MODEL
            + " Android " + Build.VERSION.RELEASE
            + " " + metricsSummary();
    }

    private String metricsSummary() {
        DisplayMetrics metrics = realMetrics();
        return metrics.widthPixels + "x" + metrics.heightPixels + "@" + metrics.densityDpi + "dpi";
    }

    private static final class Step {
        final String name;
        final String[] labels;
        final int fallbackX;
        final int fallbackY;
        final int pasteX;
        final int pasteY;
        final long afterMs;
        final boolean text;

        Step(String name, String[] labels, int fallbackX, int fallbackY, long afterMs) {
            this.name = name;
            this.labels = labels;
            this.fallbackX = fallbackX;
            this.fallbackY = fallbackY;
            this.pasteX = 0;
            this.pasteY = 0;
            this.afterMs = afterMs;
            this.text = false;
        }

        static Step text(
            String name,
            String[] labels,
            int fallbackX,
            int fallbackY,
            int pasteX,
            int pasteY,
            long afterMs
        ) {
            return new Step(name, labels, fallbackX, fallbackY, pasteX, pasteY, afterMs);
        }

        private Step(
            String name,
            String[] labels,
            int fallbackX,
            int fallbackY,
            int pasteX,
            int pasteY,
            long afterMs
        ) {
            this.name = name;
            this.labels = labels;
            this.fallbackX = fallbackX;
            this.fallbackY = fallbackY;
            this.pasteX = pasteX;
            this.pasteY = pasteY;
            this.afterMs = afterMs;
            this.text = true;
        }

        boolean isText() {
            return text;
        }
    }
}
