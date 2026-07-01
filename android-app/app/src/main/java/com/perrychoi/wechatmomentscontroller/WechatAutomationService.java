package com.perrychoi.wechatmomentscontroller;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class WechatAutomationService extends AccessibilityService {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final long START_DELAY_MS = 3000;
    private static final long OBSERVE_DURATION_MS = 30000;
    private static final long COMMAND_POLL_MS = 1000;
    private static final long WAIT_FOR_WECHAT_TIMEOUT_MS = 45000;
    private static final long RECENT_WECHAT_EVENT_ACTIVE_MS = 10000;
    private static final long MOMENTS_COLLECT_SCROLL_DELAY_MS = 1600;
    private static final long POST_IMAGE_SWIPE_DELAY_MS = 1100;
    private static final int MOMENTS_COLLECT_MAX_NODES = 220;
    private static final float[][] POST_MEDIA_TAP_CANDIDATES = new float[][]{
        {0.40f, 0.55f},
        {0.40f, 0.42f},
        {0.40f, 0.68f},
        {0.66f, 0.55f},
        {0.40f, 0.80f}
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable commandPollRunnable = new Runnable() {
        @Override
        public void run() {
            checkCommand("周期检查");
            handler.postDelayed(this, COMMAND_POLL_MS);
        }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener commandListener =
        (prefs, key) -> scheduleCommandCheck("命令变化");

    private boolean workflowRunning = false;
    private boolean observeRunning = false;
    private boolean commandCheckScheduled = false;
    private long lastWaitingLogMs = 0;
    private long waitingForWechatSinceMs = 0;
    private String waitingCommand = AutomationStore.COMMAND_NONE;
    private String workflowCompletionStatus = "流程完成";
    private Stage stage = Stage.IDLE;
    private DeviceProfile deviceProfile = null;
    private String lastWechatEventClassName = "";
    private String lastWechatWindowClassName = "";
    private long lastWechatEventMs = 0;
    private File momentsCollectionDir = null;
    private int momentsCollectionPages = 0;
    private File postImageCaptureDir = null;
    private int postImageCaptureCount = 0;
    private boolean postImageAssumeViewer = false;
    private boolean currentNativeSaveLikelyVideo = false;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AutomationStore.registerListener(this, commandListener);
        AutomationLogger.log(this, "辅助服务已连接: " + deviceSummary());
        transition(Stage.IDLE, "服务已连接");
        startCommandPolling();
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

        if (event.getClassName() != null) {
            lastWechatEventClassName = event.getClassName().toString();
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                lastWechatWindowClassName = lastWechatEventClassName;
            }
        }
        lastWechatEventMs = System.currentTimeMillis();

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

    private void startCommandPolling() {
        handler.removeCallbacks(commandPollRunnable);
        handler.postDelayed(commandPollRunnable, COMMAND_POLL_MS);
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
            waitingForWechatSinceMs = 0;
            waitingCommand = AutomationStore.COMMAND_NONE;
            return;
        }

        if (AutomationStore.stopRequested(this)) {
            finishWorkflow("用户停止");
            return;
        }

        if (!isWechatActive()) {
            long now = System.currentTimeMillis();
            if (!command.equals(waitingCommand)) {
                waitingCommand = command;
                waitingForWechatSinceMs = now;
            } else if (waitingForWechatSinceMs > 0
                && now - waitingForWechatSinceMs > WAIT_FOR_WECHAT_TIMEOUT_MS) {
                failWorkflow("等待微信前台超时: command=" + command
                    + " " + wechatActiveSummary());
                return;
            }

            AutomationStore.setDiagnostic(this, "WAITING_FOR_WECHAT: " + command);
            logWaitingForWechat(command, reason);
            handler.postDelayed(() -> scheduleCommandCheck("等待微信前台"), COMMAND_POLL_MS);
            return;
        }

        waitingForWechatSinceMs = 0;
        waitingCommand = AutomationStore.COMMAND_NONE;
        if (workflowRunning) {
            maybeResumePostImageCaptureFromViewer(reason);
            return;
        }
        maybeStartObserveOnly();
        maybeStartPointTapTest();
        maybeStartPostImageCapture();
        maybeStartMomentCollection();
        maybeStartWorkflow();
    }

    private void maybeStartPointTapTest() {
        if (workflowRunning) {
            return;
        }

        String command = AutomationStore.command(this);
        if (!AutomationStore.COMMAND_POINT_TAP_TEST.equals(command)) {
            return;
        }

        String pointKey = AutomationStore.pointKey(this);
        ProfilePointCatalog.PointSpec point = ProfilePointCatalog.find(pointKey);
        if (point == null) {
            failWorkflow("未知点位: " + pointKey);
            return;
        }

        if (!runPreflight(command, "点位测试")) {
            return;
        }

        workflowRunning = true;
        transition(Stage.START_DELAY, "检测到微信，1 秒后测试点位: " + point.label);
        handler.postDelayed(() -> {
            if (!AutomationStore.hasCommand(this, command)) {
                workflowRunning = false;
                transition(Stage.IDLE, "点位测试取消: 命令已变化");
                return;
            }
            if (!isWechatActive()) {
                workflowRunning = false;
                failWorkflow("点位测试启动失败: 微信不在前台 " + wechatActiveSummary());
                return;
            }

            ResolvedPoint resolved = resolvedProfilePoint(point.key, point.fallbackX, point.fallbackY);
            transition(Stage.RUNNING, "点位测试: " + point.label + " " + resolved.x + "," + resolved.y);
            AutomationLogger.log(this, "点位测试点击: key=" + point.key
                + " label=" + point.label
                + " x=" + resolved.x
                + " y=" + resolved.y
                + " source=" + resolved.source
                + " profile=" + resolved.profileSummary);
            if (!tap(resolved.x, resolved.y)) {
                failWorkflow("点位测试点击失败: " + point.label);
                return;
            }
            finishWorkflow("点位测试完成: " + point.label + " " + resolved.x + "," + resolved.y);
        }, 1000);
    }

    private void maybeStartPostImageCapture() {
        if (workflowRunning) {
            return;
        }

        String command = AutomationStore.command(this);
        if (!AutomationStore.COMMAND_WECHAT_POST_IMAGE_CAPTURE.equals(command)) {
            return;
        }

        if (!runPreflight(command, "朋友圈图片保存")) {
            return;
        }

        postImageCaptureCount = AutomationStore.postImageCount(this);
        postImageAssumeViewer = AutomationStore.postImageAssumeViewer(this);
        postImageCaptureDir = null;
        currentNativeSaveLikelyVideo = false;
        workflowCompletionStatus = "朋友圈图片保存完成";
        workflowRunning = true;
        transition(Stage.START_DELAY, "检测到微信，" + (START_DELAY_MS / 1000)
            + " 秒后原生保存 " + postImageCaptureCount + " 个图片/视频");
        handler.postDelayed(() -> {
            if (!AutomationStore.hasCommand(this, command)) {
                workflowRunning = false;
                transition(Stage.IDLE, "朋友圈图片保存取消: 命令已变化");
                return;
            }
            if (postImageCaptureDir != null) {
                AutomationLogger.log(this, "朋友圈原生保存已由恢复钩子启动，跳过延迟启动");
                return;
            }
            if (!isWechatActive()) {
                workflowRunning = false;
                failWorkflow("朋友圈图片保存启动失败: 微信不在前台 "
                    + wechatActiveSummary());
                return;
            }
            if (!isPostImageViewer()) {
                if (postImageAssumeViewer) {
                    AutomationLogger.log(this, "命令指定当前页为图片/视频浏览器，跳过类名校验继续保存");
                    startPostImageCaptureAfterViewerCheck(true);
                    return;
                }
                boolean looksLikeMomentsList = isMomentsListForNativeSave();
                if (looksLikeMomentsList || shouldTryOpenMediaFromCurrentWechatSurface()) {
                    transition(Stage.RUNNING, looksLikeMomentsList
                        ? "从朋友圈列表点开当前可见第一张媒体"
                        : "当前微信页面节点不完整，尝试按候选坐标点开媒体");
                    openLatestVisiblePostMedia(0);
                    return;
                }
                workflowRunning = false;
                failWorkflow("请先在微信里点开目标朋友圈的第一张图片/视频，再启动保存");
                return;
            }

            startPostImageCaptureAfterViewerCheck(false);
        }, START_DELAY_MS);
    }

    private void maybeResumePostImageCaptureFromViewer(String reason) {
        if (!AutomationStore.hasCommand(this, AutomationStore.COMMAND_WECHAT_POST_IMAGE_CAPTURE)) {
            return;
        }
        if (postImageCaptureDir != null) {
            return;
        }
        if (!isPostImageViewer()) {
            return;
        }

        AutomationLogger.log(this, "检测到已进入微信图片/视频浏览器，恢复原生保存: reason=" + reason);
        startPostImageCaptureAfterViewerCheck(false);
    }

    private void startPostImageCaptureAfterViewerCheck() {
        startPostImageCaptureAfterViewerCheck(false);
    }

    private void startPostImageCaptureAfterViewerCheck(boolean allowUnverifiedViewer) {
        if (!workflowRunning) {
            return;
        }
        if (AutomationStore.stopRequested(this)) {
            finishWorkflow("用户停止");
            return;
        }
        if (!isWechatActive()) {
            failWorkflow("朋友圈图片保存启动失败: 微信不在前台 "
                + wechatActiveSummary());
            return;
        }
        if (!allowUnverifiedViewer && !isPostImageViewer()) {
            failWorkflow("未进入微信图片/视频浏览器，可能点到了广告落地页或普通链接");
            return;
        }
        if (allowUnverifiedViewer && !isPostImageViewer()) {
            AutomationLogger.log(this, "未能从无障碍类名确认浏览器，按 assume_viewer 继续: "
                + currentWechatClassHints());
        }

        try {
            preparePostImageCaptureSession(postImageCaptureCount);
        } catch (IOException | JSONException e) {
            failWorkflow("创建朋友圈图片目录失败: " + e.getClass().getSimpleName()
                + " " + e.getMessage());
            return;
        }

        transition(Stage.RUNNING, "开始原生保存朋友圈图片/视频");
        capturePostImage(0);
    }

    private void maybeStartMomentCollection() {
        if (workflowRunning) {
            return;
        }

        String command = AutomationStore.command(this);
        if (!AutomationStore.COMMAND_WECHAT_MOMENTS_COLLECT.equals(command)) {
            return;
        }

        if (Build.VERSION.SDK_INT < 30) {
            failWorkflow("朋友圈素材采集需要 Android 11+ 截图能力");
            return;
        }

        if (!runPreflight(command, "朋友圈素材采集")) {
            return;
        }

        momentsCollectionPages = AutomationStore.momentCollectPages(this);
        workflowCompletionStatus = "朋友圈素材采集完成";
        workflowRunning = true;
        transition(Stage.START_DELAY, "检测到微信，" + (START_DELAY_MS / 1000)
            + " 秒后开始采集 " + momentsCollectionPages + " 屏");
        handler.postDelayed(() -> {
            if (!AutomationStore.hasCommand(this, command)) {
                workflowRunning = false;
                transition(Stage.IDLE, "朋友圈素材采集取消: 命令已变化");
                return;
            }
            if (!isWechatActive()) {
                workflowRunning = false;
                failWorkflow("朋友圈素材采集启动失败: 微信不在前台 "
                    + wechatActiveSummary());
                return;
            }

            try {
                prepareMomentsCollectionSession(momentsCollectionPages);
            } catch (IOException | JSONException e) {
                failWorkflow("创建朋友圈素材目录失败: " + e.getClass().getSimpleName()
                    + " " + e.getMessage());
                return;
            }

            transition(Stage.RUNNING, "开始采集朋友圈素材");
            navigateForMomentCollection(0);
        }, START_DELAY_MS);
    }

    private void maybeStartWorkflow() {
        if (workflowRunning) {
            return;
        }

        String command = AutomationStore.command(this);
        WorkflowPlan plan;
        if (AutomationStore.COMMAND_WECHAT_ALBUM_TEST.equals(command)) {
            plan = WorkflowPlan.album(wechatAlbumSteps());
        } else if (AutomationStore.COMMAND_WECHAT_COMPOSE_TEST.equals(command)) {
            plan = WorkflowPlan.compose(wechatComposeSteps());
        } else {
            return;
        }
        workflowCompletionStatus = plan.completionStatus;

        if (!runPreflight(command, plan.startName)) {
            return;
        }

        workflowRunning = true;
        transition(Stage.START_DELAY, "检测到微信，" + (START_DELAY_MS / 1000) + " 秒后开始" + plan.startName);
        handler.postDelayed(() -> {
            if (!AutomationStore.hasCommand(this, command)) {
                workflowRunning = false;
                transition(Stage.IDLE, plan.startName + "取消: 命令已变化");
                return;
            }

            if (!isWechatActive()) {
                workflowRunning = false;
                failWorkflow(plan.startName + "启动失败: 微信不在前台 "
                    + wechatActiveSummary());
                return;
            }

            transition(Stage.RUNNING, "开始执行" + plan.startName);
            startWorkflowAtCurrentPage(plan);
        }, START_DELAY_MS);
    }

    private boolean runPreflight(String command, String startName) {
        transition(Stage.PREFLIGHT, "预检: " + startName);

        List<String> failures = new ArrayList<>();
        DisplayMetrics metrics = realMetrics();
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            failures.add("屏幕尺寸无效");
        }
        if (!isWechatActive()) {
            failures.add("微信不在前台 " + wechatActiveSummary());
        }
        if (AutomationStore.COMMAND_WECHAT_COMPOSE_TEST.equals(command) && !wechatHasMediaPermission()) {
            failures.add("微信缺少图片/视频完整访问权限");
        }

        if (!failures.isEmpty()) {
            failWorkflow("预检失败: " + join(failures, "；"));
            return false;
        }

        deviceProfile = DeviceProfile.load(this, metrics);
        AutomationLogger.log(this, "预检通过: " + startName
            + " device=" + deviceSummary()
            + " profile=" + deviceProfile.summary()
            + " command=" + command);
        return true;
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
        AutomationLogger.log(this, "等待微信前台: command=" + command
            + " reason=" + reason
            + " active=" + wechatActiveSummary());
    }

    private List<Step> wechatAlbumSteps() {
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(
            "点击“发现”",
            new String[]{"发现"},
            "discover_tab",
            new PageHint[]{PageHint.WECHAT_HOME, PageHint.DISCOVER},
            710,
            2246,
            1500
        ));
        steps.add(new Step(
            "点击“朋友圈”",
            new String[]{"朋友圈"},
            "moments_entry",
            PageHint.DISCOVER,
            215,
            290,
            2500
        ));
        steps.add(new Step(
            "点击右上角摄像头按钮",
            new String[]{"相机", "拍摄", "拍照", "拍照分享", "Camera"},
            "moments_camera",
            PageHint.MOMENTS,
            990,
            185,
            1500
        ));
        steps.add(new Step(
            "点击“从手机相册选择”",
            new String[]{"从手机相册选择", "从相册选择", "手机相册"},
            "choose_from_album",
            PageHint.CAMERA_MENU,
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
            "first_photo_check",
            PageHint.ALBUM,
            215,
            293,
            800
        ));
        steps.add(new Step(
            "点击“完成”",
            new String[]{"完成"},
            "album_done",
            PageHint.ALBUM,
            940,
            2248,
            2200
        ));
        steps.add(Step.text(
            "填写朋友圈文字",
            new String[]{"这一刻的想法", "说点什么", "文本", "输入"},
            "compose_text",
            "compose_paste",
            PageHint.COMPOSE,
            245,
            337,
            150,
            265,
            1500
        ));
        return steps;
    }

    private void preparePostImageCaptureSession(int imageCount) throws IOException, JSONException {
        File baseDir = new File(getFilesDir(), "moments_native_saves");
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IOException("无法创建目录 " + baseDir.getAbsolutePath());
        }

        String timestamp = timestampForFile();
        File sessionDir = new File(baseDir, "native_save_" + timestamp);
        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            throw new IOException("无法创建目录 " + sessionDir.getAbsolutePath());
        }

        postImageCaptureDir = sessionDir;
        AutomationStore.setLastExportPath(this, sessionDir.getAbsolutePath());

        JSONObject session = new JSONObject();
        session.put("type", "session");
        session.put("captureMode", "wechat_native_save_menu");
        session.put("createdAt", timestamp);
        session.put("packageName", WECHAT_PACKAGE);
        session.put("mediaCount", imageCount);
        session.put("device", deviceSummary());
        session.put("metrics", metricsSummary());
        appendPostImageRecord(session);

        AutomationLogger.log(this, "朋友圈原生保存记录目录已创建: " + sessionDir.getAbsolutePath());
    }

    private void capturePostImage(int imageIndex) {
        if (!workflowRunning) {
            return;
        }
        if (AutomationStore.stopRequested(this)) {
            finishWorkflow("用户停止");
            return;
        }
        if (!isWechatActive()) {
            failWorkflow("朋友圈图片保存中断: 微信不在前台 " + wechatActiveSummary());
            return;
        }
        if (!postImageAssumeViewer && !isPostImageViewer()) {
            failWorkflow("朋友圈图片/视频保存中断: 当前不在微信图片/视频浏览器");
            return;
        }
        if (postImageCaptureDir == null) {
            failWorkflow("朋友圈原生保存记录目录未初始化");
            return;
        }

        transition(Stage.RUNNING, "原生保存第 " + (imageIndex + 1)
            + "/" + postImageCaptureCount + " 个图片/视频");
        currentNativeSaveLikelyVideo = currentWechatClassHints().contains("video");
        if (!longPressCenterMedia()) {
            failWorkflow("长按当前图片/视频失败");
            return;
        }

        handler.postDelayed(() -> clickNativeSaveMenu(imageIndex, 0), 900);
    }

    private void clickNativeSaveMenu(int imageIndex, int retry) {
        if (!workflowRunning) {
            return;
        }
        if (AutomationStore.stopRequested(this)) {
            finishWorkflow("用户停止");
            return;
        }
        if (!isWechatActive()) {
            failWorkflow("朋友圈图片保存中断: 微信不在前台 " + wechatActiveSummary());
            return;
        }

        String[] labels = new String[]{
            "保存图片", "保存视频", "保存到手机", "保存到相册", "保存"
        };
        AccessibilityNodeInfo node = findByLabelsInWindows(labels);
        if (node != null && clickNode(node)) {
            appendPostImageActionRecord(imageIndex, safeString(node.getText()), "node");
            AutomationLogger.log(this, "微信原生保存菜单点击成功: index="
                + (imageIndex + 1) + "/" + postImageCaptureCount);
            handler.postDelayed(() -> afterNativeSaveClicked(imageIndex), 1200);
            return;
        }

        if (retry < 3) {
            AutomationLogger.log(this, "等待微信保存菜单: index="
                + (imageIndex + 1) + " retry=" + retry);
            handler.postDelayed(() -> clickNativeSaveMenu(imageIndex, retry + 1), 450);
            return;
        }

        if (tapNativeSaveFallback()) {
            appendPostImageActionRecord(imageIndex, "fallback_tap", "coordinate");
            AutomationLogger.log(this, "微信原生保存菜单坐标兜底: index="
                + (imageIndex + 1) + "/" + postImageCaptureCount);
            handler.postDelayed(() -> afterNativeSaveClicked(imageIndex), 1200);
            return;
        }

        failWorkflow("未找到微信保存图片/视频菜单");
    }

    private void afterNativeSaveClicked(int imageIndex) {
        if (!workflowRunning) {
            return;
        }
        if (imageIndex + 1 >= postImageCaptureCount) {
            finishWorkflow("朋友圈图片/视频原生保存完成: " + postImageCaptureDir.getAbsolutePath());
            return;
        }
        if (!swipeNextPostImage()) {
            failWorkflow("朋友圈图片/视频切换下一张失败");
            return;
        }
        handler.postDelayed(
            () -> capturePostImage(imageIndex + 1),
            POST_IMAGE_SWIPE_DELAY_MS
        );
    }

    private void appendPostImageActionRecord(int imageIndex, String actionLabel, String source) {
        AccessibilityNodeInfo root = getRootInActiveWindow();

        JSONObject record = new JSONObject();
        try {
            record.put("type", "native_save_action");
            record.put("capturedAt", timestampForFile());
            record.put("mediaIndex", imageIndex);
            record.put("mediaNumber", imageIndex + 1);
            record.put("actionLabel", actionLabel);
            record.put("source", source);
            record.put("root", rootSummary(root));
            record.put("eventClass", lastWechatEventClassName);
            record.put("windowClass", lastWechatWindowClassName);
            appendPostImageRecord(record);
        } catch (IOException | JSONException e) {
            AutomationLogger.log(this, "朋友圈原生保存记录失败: "
                + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void appendPostImageRecord(JSONObject record) throws IOException {
        if (postImageCaptureDir == null) {
            throw new IOException("朋友圈图片目录未初始化");
        }

        File manifest = new File(postImageCaptureDir, "manifest.jsonl");
        try (OutputStreamWriter writer = new OutputStreamWriter(
            new FileOutputStream(manifest, true),
            StandardCharsets.UTF_8
        )) {
            writer.write(record.toString());
            writer.write("\n");
        }
    }

    private boolean isPostImageViewer() {
        String classes = currentWechatClassHints();
        return classes.contains("snsbrowse")
            || classes.contains("snsimage")
            || classes.contains("snsonlinevideo")
            || classes.contains("imagegallery")
            || classes.contains("gallery")
            || classes.contains("video");
    }

    private boolean isMomentsListForNativeSave() {
        String recentClasses = currentWechatClassHints();
        if (recentClasses.contains("snstimeline")
            || recentClasses.contains("improvesnstimeline")
            || recentClasses.contains("recyclerview")) {
            return true;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        PageDetection detection = new PageDetection(detectPage(root), "accessibility", rootSummary(root));
        if (detection.page == PageHint.MOMENTS || looksLikeMomentsSurface(root, detection)) {
            return true;
        }
        if (root == null || root.getPackageName() == null
            || !WECHAT_PACKAGE.contentEquals(root.getPackageName())) {
            return false;
        }

        String classes = ((root.getClassName() == null ? "" : root.getClassName().toString())
            + " " + lastWechatEventClassName
            + " " + lastWechatWindowClassName).toLowerCase(Locale.US);
        return classes.contains("recyclerview");
    }

    private void openLatestVisiblePostMedia(int candidateIndex) {
        if (!workflowRunning) {
            return;
        }
        if (AutomationStore.stopRequested(this)) {
            finishWorkflow("用户停止");
            return;
        }
        if (!isWechatActive()) {
            failWorkflow("点开朋友圈媒体失败: 微信不在前台 " + wechatActiveSummary());
            return;
        }
        if (isPostImageViewer()) {
            startPostImageCaptureAfterViewerCheck();
            return;
        }
        if (candidateIndex >= POST_MEDIA_TAP_CANDIDATES.length) {
            failWorkflow("未能从当前朋友圈列表点开图片/视频，请手动点开第一张后再启动保存");
            return;
        }

        DisplayMetrics metrics = realMetrics();
        float[] candidate = POST_MEDIA_TAP_CANDIDATES[candidateIndex];
        int x = Math.round(metrics.widthPixels * candidate[0]);
        int y = Math.round(metrics.heightPixels * candidate[1]);
        AutomationLogger.log(this, "点开朋友圈可见媒体候选: "
            + (candidateIndex + 1) + "/" + POST_MEDIA_TAP_CANDIDATES.length
            + " x=" + x + " y=" + y
            + " metrics=" + metricsSummary());
        if (!tap(x, y)) {
            failWorkflow("点开朋友圈媒体候选点击失败");
            return;
        }

        handler.postDelayed(() -> afterPostMediaCandidateTap(candidateIndex), 1700);
    }

    private void afterPostMediaCandidateTap(int candidateIndex) {
        if (!workflowRunning) {
            return;
        }
        if (AutomationStore.stopRequested(this)) {
            finishWorkflow("用户停止");
            return;
        }
        if (!isWechatActive()) {
            failWorkflow("点开朋友圈媒体失败: 微信不在前台 " + wechatActiveSummary());
            return;
        }
        if (isPostImageViewer()) {
            startPostImageCaptureAfterViewerCheck();
            return;
        }

        if (shouldBackAfterFailedMediaTap()) {
            AutomationLogger.log(this, "媒体候选可能进入非浏览器页面，先返回再试下一个: "
                + currentWechatClassHints());
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
                failWorkflow("点开朋友圈媒体后返回失败");
                return;
            }
            handler.postDelayed(
                () -> openLatestVisiblePostMedia(candidateIndex + 1),
                1100
            );
            return;
        }

        AutomationLogger.log(this, "媒体候选未进入浏览器，继续尝试下一个: "
            + (candidateIndex + 1)
            + " hints=" + currentWechatClassHints());
        openLatestVisiblePostMedia(candidateIndex + 1);
    }

    private boolean shouldTryOpenMediaFromCurrentWechatSurface() {
        return isWechatActive();
    }

    private boolean shouldBackAfterFailedMediaTap() {
        String classes = currentWechatClassHints();
        return classes.contains("landingpage")
            || classes.contains("dynamiccanvas")
            || classes.contains("webview")
            || classes.contains("snsuser")
            || classes.contains("contactinfo");
    }

    private String currentWechatClassHints() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        StringBuilder classes = new StringBuilder();
        if (root != null && root.getClassName() != null) {
            classes.append(root.getClassName()).append(' ');
        }
        classes.append(lastWechatEventClassName).append(' ');
        classes.append(lastWechatWindowClassName).append(' ');

        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                if (window != null && window.getTitle() != null) {
                    classes.append(window.getTitle()).append(' ');
                }
                AccessibilityNodeInfo windowRoot = window == null ? null : window.getRoot();
                if (windowRoot != null && windowRoot.getClassName() != null) {
                    classes.append(windowRoot.getClassName()).append(' ');
                }
            }
        }
        return classes.toString().toLowerCase(Locale.US);
    }

    private boolean longPressCenterMedia() {
        DisplayMetrics metrics = realMetrics();
        int x = Math.round(metrics.widthPixels * 0.50f);
        int y = Math.round(metrics.heightPixels * 0.50f);
        return longPress(x, y);
    }

    private AccessibilityNodeInfo findByLabelsInWindows(String[] labels) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo node = findByLabels(root, labels);
        if (node != null) {
            return node;
        }

        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) {
            return null;
        }
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo windowRoot = window == null ? null : window.getRoot();
            node = findByLabels(windowRoot, labels);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    private boolean tapNativeSaveFallback() {
        DisplayMetrics metrics = realMetrics();
        String classes = currentWechatClassHints();
        boolean likelyVideo = currentNativeSaveLikelyVideo || classes.contains("video");
        float xRatio = 0.10f;
        float yRatio = 0.83f;
        int x = Math.round(metrics.widthPixels * xRatio);
        int y = Math.round(metrics.heightPixels * yRatio);
        AutomationLogger.log(this, "微信保存菜单坐标兜底点: x=" + x
            + " y=" + y
            + " likelyVideo=" + likelyVideo
            + " hints=" + classes);
        return tap(x, y);
    }

    private boolean swipeNextPostImage() {
        DisplayMetrics metrics = realMetrics();
        int y = Math.round(metrics.heightPixels * 0.50f);
        int x1 = Math.round(metrics.widthPixels * 0.82f);
        int x2 = Math.round(metrics.widthPixels * 0.18f);
        return swipe(x1, y, x2, y, 450);
    }

    private void prepareMomentsCollectionSession(int pages) throws IOException, JSONException {
        File baseDir = new File(getFilesDir(), "moments_exports");
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IOException("无法创建目录 " + baseDir.getAbsolutePath());
        }

        String timestamp = timestampForFile();
        File sessionDir = new File(baseDir, "moments_" + timestamp);
        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            throw new IOException("无法创建目录 " + sessionDir.getAbsolutePath());
        }

        momentsCollectionDir = sessionDir;
        AutomationStore.setLastExportPath(this, sessionDir.getAbsolutePath());

        JSONObject session = new JSONObject();
        session.put("type", "session");
        session.put("createdAt", timestamp);
        session.put("packageName", WECHAT_PACKAGE);
        session.put("maxPages", pages);
        session.put("device", deviceSummary());
        session.put("metrics", metricsSummary());
        appendMomentsRecord(session);

        AutomationLogger.log(this, "朋友圈素材目录已创建: " + sessionDir.getAbsolutePath());
    }

    private void navigateForMomentCollection(int retry) {
        if (!workflowRunning) {
            return;
        }
        if (AutomationStore.stopRequested(this)) {
            finishWorkflow("用户停止");
            return;
        }
        if (!isWechatActive()) {
            failWorkflow("朋友圈素材采集中断: 微信不在前台 " + wechatActiveSummary());
            return;
        }
        if (retry > 8) {
            failWorkflow("无法定位到朋友圈页面");
            return;
        }

        detectCurrentPage("朋友圈素材采集定位", true, retry == 0, detection -> {
            if (!workflowRunning) {
                return;
            }

            AccessibilityNodeInfo root = getRootInActiveWindow();
            AutomationLogger.log(this, "朋友圈素材采集定位: page=" + detection.page
                + " source=" + detection.source
                + " retry=" + retry
                + " detail=" + detection.detail);

            if (detection.page == PageHint.MOMENTS || looksLikeMomentsSurface(root, detection)) {
                collectMomentPage(0);
                return;
            }

            if (detection.page == PageHint.SNS_DETAIL
                || detection.page == PageHint.CAMERA_MENU
                || detection.page == PageHint.ALBUM
                || detection.page == PageHint.COMPOSE) {
                AutomationLogger.log(this, "采集定位恢复: 当前页面需返回 page=" + detection.page);
                if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
                    failWorkflow("采集定位返回失败: " + detection.page);
                    return;
                }
                handler.postDelayed(() -> navigateForMomentCollection(retry + 1), 1200);
                return;
            }

            Step step = null;
            boolean allowFallback = true;
            if (detection.page == PageHint.WECHAT_HOME) {
                step = momentsCollectDiscoverStep();
            } else if (detection.page == PageHint.DISCOVER) {
                step = momentsCollectEntryStep();
            } else if (detection.page == PageHint.UNKNOWN) {
                if (clickStepTarget(momentsCollectEntryStep(), false)) {
                    handler.postDelayed(() -> navigateForMomentCollection(retry + 1), 1500);
                    return;
                }
                step = momentsCollectDiscoverStep();
                allowFallback = false;
            }

            if (step == null) {
                failWorkflow("采集定位失败: page=" + detection.page + " " + detection.detail);
                return;
            }

            if (!clickStepTarget(step, allowFallback)) {
                failWorkflow("采集定位点击失败: " + step.name);
                return;
            }
            handler.postDelayed(() -> navigateForMomentCollection(retry + 1), step.afterMs);
        });
    }

    private Step momentsCollectDiscoverStep() {
        return new Step(
            "点击“发现”",
            new String[]{"发现"},
            "discover_tab",
            new PageHint[]{PageHint.WECHAT_HOME, PageHint.UNKNOWN},
            710,
            2246,
            1500
        );
    }

    private Step momentsCollectEntryStep() {
        return new Step(
            "点击“朋友圈”",
            new String[]{"朋友圈"},
            "moments_entry",
            new PageHint[]{PageHint.DISCOVER, PageHint.UNKNOWN},
            215,
            290,
            2500
        );
    }

    private boolean looksLikeMomentsSurface(AccessibilityNodeInfo root, PageDetection detection) {
        if (root == null || root.getPackageName() == null
            || !WECHAT_PACKAGE.contentEquals(root.getPackageName())) {
            return false;
        }
        if (detection.page == PageHint.MOMENTS) {
            return true;
        }

        String classes = ((root.getClassName() == null ? "" : root.getClassName().toString())
            + " " + lastWechatEventClassName
            + " " + lastWechatWindowClassName).toLowerCase(Locale.US);
        if (classes.contains("snstimeline") || classes.contains("snsuser")) {
            return true;
        }

        return hasAnyLabel(root, new String[]{"朋友圈"})
            && !hasAllLabels(root, new String[]{"微信", "通讯录", "发现", "我"})
            && !hasAnyLabel(root, new String[]{"视频号", "扫一扫", "搜一搜", "看一看"});
    }

    private boolean clickStepTarget(Step step, boolean allowFallback) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && !isDegenerateRoot(root)) {
            AccessibilityNodeInfo node = findByLabels(root, step.labels);
            if (node != null && clickNode(node)) {
                AutomationLogger.log(this, "采集定位节点点击成功: " + step.name);
                return true;
            }
        }

        if (!allowFallback) {
            AutomationLogger.log(this, "采集定位未找到节点: " + step.name
                + " root=" + rootSummary(root));
            return false;
        }

        ResolvedPoint resolved = resolvedProfilePoint(step.pointKey, step.fallbackX, step.fallbackY);
        AutomationLogger.log(this, "采集定位使用坐标兜底: " + step.name
            + " point=" + step.pointKey
            + " x=" + resolved.x
            + " y=" + resolved.y
            + " source=" + resolved.source
            + " profile=" + resolved.profileSummary);
        return tap(resolved.x, resolved.y);
    }

    private void collectMomentPage(int pageIndex) {
        if (!workflowRunning) {
            return;
        }
        if (AutomationStore.stopRequested(this)) {
            finishWorkflow("用户停止");
            return;
        }
        if (!isWechatActive()) {
            failWorkflow("朋友圈素材采集中断: 微信不在前台 " + wechatActiveSummary());
            return;
        }
        if (momentsCollectionDir == null) {
            failWorkflow("朋友圈素材目录未初始化");
            return;
        }

        transition(Stage.RUNNING, "采集朋友圈第 " + (pageIndex + 1)
            + "/" + momentsCollectionPages + " 屏");
        captureScreenshotBitmap("朋友圈素材采集 page=" + pageIndex, new ScreenshotBitmapCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                try {
                    File screenshot = saveMomentBitmap(bitmap, pageIndex);
                    appendMomentPageRecord(pageIndex, screenshot);
                    AutomationLogger.log(WechatAutomationService.this,
                        "朋友圈素材已保存: page=" + (pageIndex + 1)
                            + "/" + momentsCollectionPages
                            + " screenshot=" + screenshot.getAbsolutePath());
                } catch (IOException | JSONException | RuntimeException e) {
                    failWorkflow("保存朋友圈素材失败: " + e.getClass().getSimpleName()
                        + " " + e.getMessage());
                    return;
                } finally {
                    bitmap.recycle();
                }

                if (!workflowRunning) {
                    return;
                }
                if (pageIndex + 1 >= momentsCollectionPages) {
                    finishWorkflow("朋友圈素材采集完成: " + momentsCollectionDir.getAbsolutePath());
                    return;
                }
                if (!swipeUpFeed()) {
                    failWorkflow("朋友圈素材采集滚动失败");
                    return;
                }
                handler.postDelayed(
                    () -> collectMomentPage(pageIndex + 1),
                    MOMENTS_COLLECT_SCROLL_DELAY_MS
                );
            }

            @Override
            public void onFailure(String error) {
                failWorkflow("朋友圈素材截图失败: " + error);
            }
        });
    }

    private File saveMomentBitmap(Bitmap bitmap, int pageIndex) throws IOException {
        File file = new File(
            momentsCollectionDir,
            "screen_" + String.format(Locale.US, "%03d", pageIndex + 1)
                + "_" + timestampForFile() + ".png"
        );
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        return file;
    }

    private void appendMomentPageRecord(int pageIndex, File screenshot)
        throws IOException, JSONException {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        PageHint page = detectPage(root);

        JSONObject record = new JSONObject();
        record.put("type", "page");
        record.put("capturedAt", timestampForFile());
        record.put("pageIndex", pageIndex);
        record.put("pageNumber", pageIndex + 1);
        record.put("pageHint", page.name());
        record.put("root", rootSummary(root));
        record.put("eventClass", lastWechatEventClassName);
        record.put("windowClass", lastWechatWindowClassName);
        record.put("screenshot", screenshot.getAbsolutePath());
        record.put("screenshotFile", screenshot.getName());

        JSONArray nodes = new JSONArray();
        collectVisibleNodes(root, nodes, 0, new int[]{0});
        record.put("nodeCount", nodes.length());
        record.put("nodeExtractStatus", nodeExtractStatus(root, nodes.length()));
        record.put("nodes", nodes);
        appendMomentsRecord(record);
    }

    private void appendMomentsRecord(JSONObject record) throws IOException {
        if (momentsCollectionDir == null) {
            throw new IOException("朋友圈素材目录未初始化");
        }

        File manifest = new File(momentsCollectionDir, "manifest.jsonl");
        try (OutputStreamWriter writer = new OutputStreamWriter(
            new FileOutputStream(manifest, true),
            StandardCharsets.UTF_8
        )) {
            writer.write(record.toString());
            writer.write("\n");
        }
    }

    private void collectVisibleNodes(
        AccessibilityNodeInfo node,
        JSONArray nodes,
        int depth,
        int[] count
    ) throws JSONException {
        if (node == null || count[0] >= MOMENTS_COLLECT_MAX_NODES) {
            return;
        }

        try {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            String text = safeString(node.getText());
            String desc = safeString(node.getContentDescription());
            String className = safeString(node.getClassName());
            String lowerClass = className.toLowerCase(Locale.US);
            boolean hasText = !text.isEmpty() || !desc.isEmpty();
            boolean mediaLike = lowerClass.contains("image")
                || lowerClass.contains("video")
                || lowerClass.contains("texture")
                || lowerClass.contains("surface")
                || lowerClass.contains("gallery");
            boolean interesting = node.isVisibleToUser()
                && !bounds.isEmpty()
                && (hasText || node.isClickable() || node.isScrollable() || mediaLike);

            if (interesting) {
                JSONObject item = new JSONObject();
                item.put("index", count[0]);
                item.put("depth", depth);
                item.put("className", className);
                item.put("text", text);
                item.put("description", desc);
                item.put("viewId", safeString(node.getViewIdResourceName()));
                item.put("clickable", node.isClickable());
                item.put("scrollable", node.isScrollable());
                item.put("enabled", node.isEnabled());
                item.put("selected", node.isSelected());
                item.put("bounds", boundsJson(bounds));
                nodes.put(item);
                count[0]++;
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                if (count[0] >= MOMENTS_COLLECT_MAX_NODES) {
                    return;
                }
                AccessibilityNodeInfo child = node.getChild(i);
                collectVisibleNodes(child, nodes, depth + 1, count);
            }
        } catch (RuntimeException e) {
            JSONObject item = new JSONObject();
            item.put("index", count[0]);
            item.put("depth", depth);
            item.put("error", e.getClass().getSimpleName());
            nodes.put(item);
            count[0]++;
        }
    }

    private JSONObject boundsJson(Rect bounds) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("left", bounds.left);
        json.put("top", bounds.top);
        json.put("right", bounds.right);
        json.put("bottom", bounds.bottom);
        json.put("width", bounds.width());
        json.put("height", bounds.height());
        return json;
    }

    private String nodeExtractStatus(AccessibilityNodeInfo root, int nodeCount) {
        if (nodeCount > 0) {
            return "ok";
        }
        if (root == null) {
            return "empty_root";
        }
        if (isDegenerateRoot(root)) {
            return "degenerate_root";
        }
        return "no_visible_nodes";
    }

    private boolean swipeUpFeed() {
        DisplayMetrics metrics = realMetrics();
        int x = Math.round(metrics.widthPixels * 0.50f);
        int y1 = Math.round(metrics.heightPixels * 0.78f);
        int y2 = Math.round(metrics.heightPixels * 0.25f);
        return swipe(x, y1, x, y2, 520);
    }

    private void startWorkflowAtCurrentPage(WorkflowPlan plan) {
        detectCurrentPage("启动定位: " + plan.startName, true, true, detection -> {
            int startIndex = plan.indexForPage(detection.page);
            if (startIndex < 0) {
                startIndex = 0;
            }

            if (startIndex >= plan.steps.size()) {
                AutomationLogger.log(this, "状态机启动定位: page=" + detection.page
                    + " source=" + detection.source
                    + " 已到达目标页，直接完成");
                finishWorkflow(plan.completionStatus);
                return;
            }

            AutomationLogger.log(this, "状态机启动定位: page=" + detection.page
                + " source=" + detection.source
                + " detail=" + detection.detail
                + " startStep=" + (startIndex + 1) + "/" + plan.steps.size());
            runStep(plan, startIndex, 0);
        });
    }

    private void runStep(WorkflowPlan plan, int index, int retry) {
        if (AutomationStore.stopRequested(this)) {
            finishWorkflow("用户停止");
            return;
        }

        if (!isWechatActive()) {
            failWorkflow("流程中断: 微信不在前台 " + wechatActiveSummary());
            return;
        }

        if (index >= plan.steps.size()) {
            finishWorkflow(workflowCompletionStatus);
            return;
        }

        Step step = plan.steps.get(index);
        transition(Stage.RUNNING, "步骤 " + (index + 1) + "/" + plan.steps.size() + ": " + step.name);
        detectCurrentPage("步骤识别: " + step.name, false, false,
            detection -> runStepAfterDetection(plan, index, retry, detection));
    }

    private void runStepAfterDetection(WorkflowPlan plan, int index, int retry, PageDetection detection) {
        if (!workflowRunning) {
            return;
        }
        if (AutomationStore.stopRequested(this)) {
            finishWorkflow("用户停止");
            return;
        }
        if (!isWechatActive()) {
            failWorkflow("流程中断: 微信不在前台 " + wechatActiveSummary());
            return;
        }
        if (index >= plan.steps.size()) {
            finishWorkflow(workflowCompletionStatus);
            return;
        }

        Step step = plan.steps.get(index);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (detection.page == PageHint.SNS_DETAIL) {
            if (retry >= 3) {
                failWorkflow("无法从朋友圈详情页恢复: " + detection.detail);
                return;
            }
            AutomationLogger.log(this, "状态机恢复: 当前在朋友圈详情页，先返回再重新定位"
                + " source=" + detection.source
                + " detail=" + detection.detail);
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
                failWorkflow("从朋友圈详情页返回失败: " + detection.detail);
                return;
            }
            scheduleRunStep(plan, index, retry + 1, 1200, "详情页返回后重试");
            return;
        }

        if (detection.page != PageHint.UNKNOWN && !step.acceptsPage(detection.page)) {
            int adjustedIndex = plan.indexForPage(detection.page);
            if (adjustedIndex >= plan.steps.size()) {
                AutomationLogger.log(this, "状态机恢复: page=" + detection.page
                    + " 已到达目标页，结束 " + plan.startName);
                finishWorkflow(plan.completionStatus);
                return;
            }
            if (adjustedIndex >= 0 && adjustedIndex != index) {
                AutomationLogger.log(this, "状态机恢复: page=" + detection.page
                    + " source=" + detection.source
                    + " step " + (index + 1) + " -> " + (adjustedIndex + 1));
                runStep(plan, adjustedIndex, 0);
                return;
            }
        }

        if (!validatePage(step, detection, root)) {
            return;
        }

        if (step.isText()) {
            runTextStep(plan, index, retry, step, root);
            return;
        }

        if (root == null) {
            retryOrFallback(plan, index, retry, step, "当前窗口为空");
            return;
        }

        if (isDegenerateRoot(root)) {
            retryOrFallback(plan, index, 4, step, "节点树为空");
            return;
        }

        AccessibilityNodeInfo node = findByLabels(root, step.labels);
        if (node != null && clickNode(node)) {
            AutomationLogger.log(this, "节点点击成功: " + step.name);
            scheduleRunStep(plan, index + 1, 0, step.afterMs, "节点点击后: " + step.name);
            return;
        }

        retryOrFallback(plan, index, retry, step, "未找到可点击节点");
    }

    private void retryOrFallback(WorkflowPlan plan, int index, int retry, Step step, String reason) {
        if (retry < 4) {
            AutomationLogger.log(this, step.name + " 等待重试: " + reason + " #" + (retry + 1));
            scheduleRunStep(plan, index, retry + 1, 500, "重试: " + step.name);
            return;
        }

        ResolvedPoint resolved = resolvedProfilePoint(step.pointKey, step.fallbackX, step.fallbackY);
        AutomationLogger.log(this, "使用坐标兜底: " + step.name
            + " point=" + step.pointKey
            + " baseline=" + step.fallbackX + "," + step.fallbackY
            + " scaled=" + resolved.x + "," + resolved.y
            + " source=" + resolved.source
            + " profile=" + resolved.profileSummary
            + " metrics=" + metricsSummary());
        if (!tap(resolved.x, resolved.y)) {
            failWorkflow("坐标点击失败: " + step.name);
            return;
        }
        scheduleRunStep(plan, index + 1, 0, step.afterMs, "坐标点击后: " + step.name);
    }

    private void runTextStep(WorkflowPlan plan, int index, int retry, Step step, AccessibilityNodeInfo root) {
        String text = AutomationStore.momentText(this);
        setClipboard(text);

        if (root != null && !isDegenerateRoot(root)) {
            AccessibilityNodeInfo target = findEditableOrLabel(root, step.labels);
            if (target != null && setNodeText(target, text)) {
                AutomationLogger.log(this, "文字填写成功: " + step.name);
                scheduleRunStep(plan, index + 1, 0, step.afterMs, "文字节点填写后: " + step.name);
                return;
            }
        }

        ResolvedPoint textPoint = resolvedProfilePoint(step.pointKey, step.fallbackX, step.fallbackY);
        ResolvedPoint pastePoint = resolvedProfilePoint(step.pastePointKey, step.pasteX, step.pasteY);
        AutomationLogger.log(this, "使用剪贴板粘贴文字: " + step.name
            + " textTap=" + textPoint.x + "," + textPoint.y
            + " textSource=" + textPoint.source
            + " pasteTap=" + pastePoint.x + "," + pastePoint.y
            + " pasteSource=" + pastePoint.source
            + " profile=" + textPoint.profileSummary);
        if (!tap(textPoint.x, textPoint.y)) {
            failWorkflow("文字输入区域点击失败");
            return;
        }
        handler.postDelayed(() -> longPress(textPoint.x, textPoint.y), 450);
        handler.postDelayed(() -> tap(pastePoint.x, pastePoint.y), 1200);
        scheduleRunStep(plan, index + 1, 0, step.afterMs + 1400, "剪贴板粘贴后: " + step.name);
    }

    private void scheduleRunStep(WorkflowPlan plan, int index, int retry, long delayMs, String reason) {
        AutomationLogger.log(this, "排定下一步: " + reason
            + " next=" + (index + 1) + "/" + plan.steps.size()
            + " retry=" + retry
            + " delayMs=" + delayMs);
        handler.postDelayed(() -> runStep(plan, index, retry), delayMs);
    }

    private boolean validatePage(Step step, PageDetection detection, AccessibilityNodeInfo root) {
        if (detection.page == PageHint.UNKNOWN) {
            AutomationLogger.log(this, "页面识别未知，继续执行: step=" + step.name
                + " expected=" + step.expectedPagesSummary()
                + " source=" + detection.source
                + " root=" + rootSummary(root));
            return true;
        }

        if (step.acceptsPage(detection.page)) {
            AutomationLogger.log(this, "页面识别通过: step=" + step.name
                + " page=" + detection.page
                + " source=" + detection.source
                + " expected=" + step.expectedPagesSummary());
            return true;
        }

        failWorkflow("页面不匹配: step=" + step.name
            + " expected=" + step.expectedPagesSummary()
            + " actual=" + detection.page
            + " source=" + detection.source
            + " root=" + rootSummary(root));
        return false;
    }

    private void detectCurrentPage(
        String reason,
        boolean allowScreenshot,
        boolean saveSnapshot,
        PageDetectionCallback callback
    ) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        PageHint page = detectPage(root);
        if (page != PageHint.UNKNOWN || !allowScreenshot) {
            PageDetection detection = new PageDetection(
                page,
                page == PageHint.UNKNOWN ? "unknown" : "accessibility",
                rootSummary(root)
            );
            if (page != PageHint.UNKNOWN && saveSnapshot && Build.VERSION.SDK_INT >= 30) {
                capturePageSnapshot(reason, page, detection, callback);
                return;
            }
            callback.onDetected(detection);
            return;
        }

        if (Build.VERSION.SDK_INT < 30) {
            callback.onDetected(new PageDetection(PageHint.UNKNOWN, "screenshot_unsupported", rootSummary(root)));
            return;
        }

        captureScreenshotBitmap(reason, new ScreenshotBitmapCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                try {
                    PageHint screenshotPage = detectPageFromScreenshot(bitmap);
                    String detail = "bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight();
                    if (saveSnapshot) {
                        try {
                            String path = saveBitmap(
                                bitmap,
                                "page_screenshots",
                                "page_",
                                reason + " page=" + screenshotPage,
                                "页面诊断截图",
                                false
                            );
                            if (!path.isEmpty()) {
                                detail += " path=" + path;
                            }
                        } catch (IOException e) {
                            detail += " saveError=" + e.getClass().getSimpleName();
                        }
                    }
                    callback.onDetected(new PageDetection(screenshotPage, "screenshot", detail));
                } finally {
                    bitmap.recycle();
                }
            }

            @Override
            public void onFailure(String error) {
                callback.onDetected(new PageDetection(PageHint.UNKNOWN, "screenshot_failed", error));
            }
        });
    }

    private void capturePageSnapshot(
        String reason,
        PageHint page,
        PageDetection baseDetection,
        PageDetectionCallback callback
    ) {
        captureScreenshotBitmap(reason, new ScreenshotBitmapCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                try {
                    String detail = baseDetection.detail
                        + " bitmap=" + bitmap.getWidth() + "x" + bitmap.getHeight();
                    try {
                        String path = saveBitmap(
                            bitmap,
                            "page_screenshots",
                            "page_",
                            reason + " page=" + page,
                            "页面诊断截图",
                            false
                        );
                        if (!path.isEmpty()) {
                            detail += " path=" + path;
                        }
                    } catch (IOException e) {
                        detail += " saveError=" + e.getClass().getSimpleName();
                    }
                    callback.onDetected(new PageDetection(page, baseDetection.source + "+screenshot", detail));
                } finally {
                    bitmap.recycle();
                }
            }

            @Override
            public void onFailure(String error) {
                callback.onDetected(new PageDetection(page, baseDetection.source, baseDetection.detail
                    + " snapshotError=" + error));
            }
        });
    }

    private void finishWorkflow(String status) {
        workflowRunning = false;
        transition(Stage.COMPLETED, status);
        AutomationLogger.log(this, "流程结束: " + status);
        AutomationStore.clearCommand(this, status);
    }

    private void failWorkflow(String reason) {
        workflowRunning = false;
        observeRunning = false;
        String status = "失败: " + reason;
        transition(Stage.FAILED, reason);
        AutomationLogger.log(this, status);
        captureFailureScreenshot(reason);
        AutomationStore.clearCommand(this, status);
    }

    private void transition(Stage nextStage, String detail) {
        stage = nextStage;
        String diagnostic = nextStage.name() + ": " + detail;
        AutomationStore.setDiagnostic(this, diagnostic);
        AutomationLogger.log(this, "状态: " + diagnostic);
    }

    private boolean wechatHasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return hasWechatPermission(Manifest.permission.READ_MEDIA_IMAGES)
                && hasWechatPermission(Manifest.permission.READ_MEDIA_VIDEO);
        }

        return hasWechatPermission(Manifest.permission.READ_EXTERNAL_STORAGE);
    }

    private boolean hasWechatPermission(String permission) {
        return getPackageManager().checkPermission(permission, WECHAT_PACKAGE)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void captureFailureScreenshot(String reason) {
        if (Build.VERSION.SDK_INT < 30) {
            AutomationLogger.log(this, "失败截图跳过: 系统版本不支持");
            return;
        }

        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, command -> handler.post(command), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    saveScreenshotResult(result, reason);
                }

                @Override
                public void onFailure(int errorCode) {
                    AutomationLogger.log(WechatAutomationService.this,
                        "失败截图失败: errorCode=" + errorCode + " reason=" + reason);
                }
            });
        } catch (RuntimeException e) {
            AutomationLogger.log(this, "失败截图异常: " + e.getClass().getSimpleName()
                + " " + e.getMessage());
        }
    }

    private void captureScreenshotBitmap(String reason, ScreenshotBitmapCallback callback) {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, command -> handler.post(command), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    Bitmap hardwareBitmap = null;
                    Bitmap bitmap = null;
                    try {
                        hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hardwareBitmap == null) {
                            callback.onFailure("bitmap 为空: " + reason);
                            return;
                        }
                        bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
                        callback.onSuccess(bitmap);
                    } catch (RuntimeException e) {
                        if (bitmap != null) {
                            bitmap.recycle();
                        }
                        callback.onFailure(e.getClass().getSimpleName() + " " + e.getMessage());
                    } finally {
                        if (buffer != null) {
                            buffer.close();
                        }
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    callback.onFailure("errorCode=" + errorCode + " reason=" + reason);
                }
            });
        } catch (RuntimeException e) {
            callback.onFailure(e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void saveScreenshotResult(ScreenshotResult result, String reason) {
        HardwareBuffer buffer = result.getHardwareBuffer();
        Bitmap hardwareBitmap = null;
        Bitmap bitmap = null;
        try {
            hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (hardwareBitmap == null) {
                AutomationLogger.log(this, "失败截图保存失败: bitmap 为空");
                return;
            }

            bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
            File dir = new File(getFilesDir(), "failure_screenshots");
            if (!dir.exists() && !dir.mkdirs()) {
                AutomationLogger.log(this, "失败截图保存失败: 无法创建目录 " + dir.getAbsolutePath());
                return;
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File file = new File(dir, "failure_" + timestamp + ".png");
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }

            AutomationStore.setLastScreenshotPath(this, file.getAbsolutePath());
            AutomationLogger.log(this, "失败截图已保存: " + file.getAbsolutePath()
                + " reason=" + reason);
        } catch (IOException | RuntimeException e) {
            AutomationLogger.log(this, "失败截图保存异常: " + e.getClass().getSimpleName()
                + " " + e.getMessage());
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
            if (buffer != null) {
                buffer.close();
            }
        }
    }

    private String saveBitmap(
        Bitmap bitmap,
        String dirName,
        String prefix,
        String reason,
        String label,
        boolean updateLastScreenshot
    ) throws IOException {
        File dir = new File(getFilesDir(), dirName);
        if (!dir.exists() && !dir.mkdirs()) {
            AutomationLogger.log(this, label + "保存失败: 无法创建目录 " + dir.getAbsolutePath());
            return "";
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(dir, prefix + timestamp + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }

        if (updateLastScreenshot) {
            AutomationStore.setLastScreenshotPath(this, file.getAbsolutePath());
        }
        AutomationLogger.log(this, label + "已保存: " + file.getAbsolutePath()
            + " reason=" + reason);
        return file.getAbsolutePath();
    }

    private String timestampForFile() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private String join(List<String> values, String delimiter) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(delimiter);
            }
            builder.append(values.get(i));
        }
        return builder.toString();
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

    private String safeString(CharSequence value) {
        return value == null ? "" : value.toString();
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

    private boolean swipe(int x1, int y1, int x2, int y2, long durationMs) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);

        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(path, 0, durationMs);

        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(stroke)
            .build();

        return dispatchGesture(gesture, null, null);
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

    private ResolvedPoint resolvedProfilePoint(String key, int fallbackX, int fallbackY) {
        DisplayMetrics metrics = realMetrics();
        DeviceProfile profile = activeProfile(metrics);
        PointOverrideStore.Coordinate override =
            PointOverrideStore.pointOverride(this, profile, metrics, key);
        if (override != null) {
            return new ResolvedPoint(override.x, override.y, "override", profile.summary());
        }

        int[] xy = profile.point(key, fallbackX, fallbackY, metrics);
        return new ResolvedPoint(xy[0], xy[1], "profile", profile.summary());
    }

    private DeviceProfile activeProfile(DisplayMetrics metrics) {
        if (deviceProfile == null) {
            deviceProfile = DeviceProfile.load(this, metrics);
        }
        return deviceProfile;
    }

    private PageHint detectPage(AccessibilityNodeInfo root) {
        if (root == null || root.getPackageName() == null
            || !WECHAT_PACKAGE.contentEquals(root.getPackageName())) {
            return PageHint.UNKNOWN;
        }

        if (!isDegenerateRoot(root)) {
            if (hasAnyLabel(root, new String[]{
                "发表", "所在位置", "提醒谁看", "谁可以看", "这一刻的想法", "说点什么"
            })) {
                return PageHint.COMPOSE;
            }
            if (hasAnyLabel(root, new String[]{"从手机相册选择", "从相册选择", "手机相册"})) {
                return PageHint.CAMERA_MENU;
            }
            if (hasAnyLabel(root, new String[]{"完成"})
                && hasAnyLabel(root, new String[]{"预览", "图片和视频", "所有图片", "相册"})) {
                return PageHint.ALBUM;
            }
            if (hasAnyLabel(root, new String[]{"朋友圈"})
                && hasAnyLabel(root, new String[]{"视频号", "扫一扫", "搜一搜", "看一看", "直播", "小程序"})) {
                return PageHint.DISCOVER;
            }
            if (hasAnyLabel(root, new String[]{"朋友圈"})
                && hasAnyLabel(root, new String[]{"相机", "拍摄", "拍照分享", "Camera"})) {
                return PageHint.MOMENTS;
            }
            if (hasAllLabels(root, new String[]{"微信", "通讯录", "发现", "我"})) {
                return PageHint.WECHAT_HOME;
            }
        }

        String classes = ((root.getClassName() == null ? "" : root.getClassName().toString())
            + " " + lastWechatEventClassName
            + " " + lastWechatWindowClassName).toLowerCase(Locale.US);
        return detectPageFromClassNames(classes);
    }

    private PageHint detectPageFromClassNames(String classes) {
        if (classes.contains("snsupload")) {
            return PageHint.COMPOSE;
        }
        if (classes.contains("album") || classes.contains("gallery")) {
            return PageHint.ALBUM;
        }
        if (classes.contains("dialog")) {
            return PageHint.CAMERA_MENU;
        }
        if (classes.contains("snsbrowse")
            || classes.contains("wsfold")
            || classes.contains("snscommentdetail")) {
            return PageHint.SNS_DETAIL;
        }
        if (classes.contains("snstimeline") || classes.contains("snsuser")) {
            return PageHint.MOMENTS;
        }

        return PageHint.UNKNOWN;
    }

    private PageHint detectPageFromScreenshot(Bitmap bitmap) {
        if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return PageHint.UNKNOWN;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float topRightGreen = greenRatio(
            bitmap,
            Math.round(width * 0.80f),
            Math.round(height * 0.045f),
            Math.round(width * 0.99f),
            Math.round(height * 0.12f)
        );
        if (topRightGreen > 0.035f) {
            return PageHint.COMPOSE;
        }

        float bottomRightGreen = greenRatio(
            bitmap,
            Math.round(width * 0.78f),
            Math.round(height * 0.89f),
            Math.round(width * 0.99f),
            Math.round(height * 0.985f)
        );
        if (bottomRightGreen > 0.035f) {
            return PageHint.ALBUM;
        }

        return PageHint.UNKNOWN;
    }

    private float greenRatio(Bitmap bitmap, int left, int top, int right, int bottom) {
        int safeLeft = Math.max(0, Math.min(left, bitmap.getWidth() - 1));
        int safeTop = Math.max(0, Math.min(top, bitmap.getHeight() - 1));
        int safeRight = Math.max(safeLeft + 1, Math.min(right, bitmap.getWidth()));
        int safeBottom = Math.max(safeTop + 1, Math.min(bottom, bitmap.getHeight()));
        int stride = Math.max(2, Math.min(safeRight - safeLeft, safeBottom - safeTop) / 40);

        int total = 0;
        int green = 0;
        for (int y = safeTop; y < safeBottom; y += stride) {
            for (int x = safeLeft; x < safeRight; x += stride) {
                int pixel = bitmap.getPixel(x, y);
                int red = (pixel >> 16) & 0xff;
                int valueGreen = (pixel >> 8) & 0xff;
                int blue = pixel & 0xff;
                if (valueGreen >= 115
                    && valueGreen > red * 1.25f
                    && valueGreen > blue * 1.15f
                    && red < 145) {
                    green++;
                }
                total++;
            }
        }

        return total == 0 ? 0f : green / (float) total;
    }

    private boolean hasAnyLabel(AccessibilityNodeInfo root, String[] labels) {
        return findByLabels(root, labels) != null;
    }

    private boolean hasAllLabels(AccessibilityNodeInfo root, String[] labels) {
        for (String label : labels) {
            if (!hasAnyLabel(root, new String[]{label})) {
                return false;
            }
        }
        return true;
    }

    private String rootSummary(AccessibilityNodeInfo root) {
        if (root == null) {
            return "null";
        }

        return "pkg=" + root.getPackageName()
            + " class=" + root.getClassName()
            + " children=" + root.getChildCount()
            + " eventClass=" + lastWechatEventClassName
            + " windowClass=" + lastWechatWindowClassName;
    }

    private boolean isWechatActive() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && WECHAT_PACKAGE.contentEquals(root.getPackageName())) {
            return true;
        }

        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo windowRoot = window == null ? null : window.getRoot();
                if (windowRoot != null && WECHAT_PACKAGE.contentEquals(windowRoot.getPackageName())) {
                    return true;
                }
            }
        }

        return hasRecentWechatEvent();
    }

    private boolean hasRecentWechatEvent() {
        return lastWechatEventMs > 0
            && System.currentTimeMillis() - lastWechatEventMs <= RECENT_WECHAT_EVENT_ACTIVE_MS;
    }

    private String wechatActiveSummary() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String rootPackage = root == null || root.getPackageName() == null
            ? "null"
            : root.getPackageName().toString();
        long eventAge = lastWechatEventMs <= 0
            ? -1
            : System.currentTimeMillis() - lastWechatEventMs;
        int wechatWindows = 0;
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo windowRoot = window == null ? null : window.getRoot();
                if (windowRoot != null && WECHAT_PACKAGE.contentEquals(windowRoot.getPackageName())) {
                    wechatWindows++;
                }
            }
        }

        return "rootPkg=" + rootPackage
            + " wechatWindows=" + wechatWindows
            + " recentEventMs=" + eventAge
            + " eventClass=" + lastWechatEventClassName
            + " windowClass=" + lastWechatWindowClassName;
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

    private enum Stage {
        IDLE,
        WAITING_FOR_WECHAT,
        PREFLIGHT,
        START_DELAY,
        RUNNING,
        COMPLETED,
        FAILED
    }

    private enum PageHint {
        UNKNOWN,
        WECHAT_HOME,
        DISCOVER,
        MOMENTS,
        CAMERA_MENU,
        ALBUM,
        COMPOSE,
        SNS_DETAIL
    }

    private interface PageDetectionCallback {
        void onDetected(PageDetection detection);
    }

    private interface ScreenshotBitmapCallback {
        void onSuccess(Bitmap bitmap);

        void onFailure(String error);
    }

    private static final class ResolvedPoint {
        final int x;
        final int y;
        final String source;
        final String profileSummary;

        ResolvedPoint(int x, int y, String source, String profileSummary) {
            this.x = x;
            this.y = y;
            this.source = source;
            this.profileSummary = profileSummary;
        }
    }

    private static final class PageDetection {
        final PageHint page;
        final String source;
        final String detail;

        PageDetection(PageHint page, String source, String detail) {
            this.page = page;
            this.source = source;
            this.detail = detail;
        }
    }

    private static final class WorkflowPlan {
        final String startName;
        final String completionStatus;
        final List<Step> steps;
        final boolean composeWorkflow;

        private WorkflowPlan(
            String startName,
            String completionStatus,
            List<Step> steps,
            boolean composeWorkflow
        ) {
            this.startName = startName;
            this.completionStatus = completionStatus;
            this.steps = steps;
            this.composeWorkflow = composeWorkflow;
        }

        static WorkflowPlan album(List<Step> steps) {
            return new WorkflowPlan(
                "四步测试",
                "四步测试完成，已停在相册选择流程后",
                steps,
                false
            );
        }

        static WorkflowPlan compose(List<Step> steps) {
            return new WorkflowPlan(
                "完整测试",
                "已选择图片并填写文字，停在发表前",
                steps,
                true
            );
        }

        int indexForPage(PageHint page) {
            switch (page) {
                case WECHAT_HOME:
                    return 0;
                case DISCOVER:
                    return 1;
                case MOMENTS:
                    return 2;
                case CAMERA_MENU:
                    return 3;
                case ALBUM:
                    return composeWorkflow ? 4 : steps.size();
                case COMPOSE:
                    return composeWorkflow ? 6 : steps.size();
                case SNS_DETAIL:
                    return -1;
                case UNKNOWN:
                default:
                    return -1;
            }
        }
    }

    private static final class Step {
        final String name;
        final String[] labels;
        final String pointKey;
        final String pastePointKey;
        final PageHint[] acceptedPages;
        final int fallbackX;
        final int fallbackY;
        final int pasteX;
        final int pasteY;
        final long afterMs;
        final boolean text;

        Step(
            String name,
            String[] labels,
            String pointKey,
            PageHint expectedPage,
            int fallbackX,
            int fallbackY,
            long afterMs
        ) {
            this(name, labels, pointKey, new PageHint[]{expectedPage}, fallbackX, fallbackY, afterMs);
        }

        Step(
            String name,
            String[] labels,
            String pointKey,
            PageHint[] acceptedPages,
            int fallbackX,
            int fallbackY,
            long afterMs
        ) {
            this.name = name;
            this.labels = labels;
            this.pointKey = pointKey;
            this.pastePointKey = "";
            this.acceptedPages = acceptedPages;
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
            String pointKey,
            String pastePointKey,
            PageHint expectedPage,
            int fallbackX,
            int fallbackY,
            int pasteX,
            int pasteY,
            long afterMs
        ) {
            return new Step(
                name,
                labels,
                pointKey,
                pastePointKey,
                new PageHint[]{expectedPage},
                fallbackX,
                fallbackY,
                pasteX,
                pasteY,
                afterMs
            );
        }

        private Step(
            String name,
            String[] labels,
            String pointKey,
            String pastePointKey,
            PageHint[] acceptedPages,
            int fallbackX,
            int fallbackY,
            int pasteX,
            int pasteY,
            long afterMs
        ) {
            this.name = name;
            this.labels = labels;
            this.pointKey = pointKey;
            this.pastePointKey = pastePointKey;
            this.acceptedPages = acceptedPages;
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

        boolean acceptsPage(PageHint page) {
            if (page == PageHint.UNKNOWN || acceptedPages == null || acceptedPages.length == 0) {
                return true;
            }

            for (PageHint acceptedPage : acceptedPages) {
                if (acceptedPage == page) {
                    return true;
                }
            }
            return false;
        }

        String expectedPagesSummary() {
            if (acceptedPages == null || acceptedPages.length == 0) {
                return "ANY";
            }

            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < acceptedPages.length; i++) {
                if (i > 0) {
                    builder.append("|");
                }
                builder.append(acceptedPages[i].name());
            }
            return builder.toString();
        }
    }
}
