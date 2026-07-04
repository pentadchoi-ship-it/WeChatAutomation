package com.perrychoi.wechatmomentscontroller;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
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
import android.os.SystemClock;
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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class WechatAutomationService extends AccessibilityService {
    private static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final long START_DELAY_MS = 3000;
    private static final long OBSERVE_DURATION_MS = 30000;
    private static final long COMMAND_POLL_MS = 1000;
    private static final long POST_MEDIA_OPEN_ASSUME_VIEWER_MS = 1500;
    private static final long POST_MEDIA_OPEN_STALE_ASSUME_VIEWER_MS = 5000;
    private static final long POST_MEDIA_OPEN_ACTIVITY_WAKE_MS = 4500;
    private static final long WAIT_FOR_WECHAT_TIMEOUT_MS = 45000;
    private static final long RECENT_WECHAT_EVENT_ACTIVE_MS = 10000;
    private static final long MOMENTS_COLLECT_SCROLL_DELAY_MS = 1600;
    private static final long POST_IMAGE_SWIPE_DELAY_MS = 1100;
    private static final int POST_IMAGE_SAME_FINGERPRINT_MAX_DISTANCE = 4;
    private static final int MOMENTS_COLLECT_MAX_NODES = 220;
    private static final int POST_CONTEXT_MAX_TEXT_NODES = 80;
    private static final long POST_CONTEXT_COPY_RETRY_DELAY_MS = 700;
    private static final long POST_CONTEXT_COPY_CLIPBOARD_DELAY_MS = 500;
    private static final float POST_TEXT_LONG_PRESS_X_RATIO = 0.52f;
    private static final float POST_TEXT_LONG_PRESS_Y_RATIO = 0.53f;
    private static final float POST_FULL_TEXT_LONG_PRESS_X_RATIO = 0.52f;
    private static final float POST_FULL_TEXT_LONG_PRESS_Y_RATIO = 0.22f;
    private static final int NATIVE_COPY_NONE = 0;
    private static final int NATIVE_COPY_WAIT_INITIAL_RESULT = 1;
    private static final int NATIVE_COPY_LONG_PRESS_FULL_TEXT = 2;
    private static final int NATIVE_COPY_WAIT_COPY_MENU = 3;
    private static final int NATIVE_COPY_READ_CLIPBOARD = 4;
    private static final int NATIVE_COPY_RETURN_TO_LIST = 5;
    private static final int NATIVE_COPY_WAIT_FOREGROUND_CLIPBOARD = 6;
    private static final int REQUEST_AUTOMATION_WAKE = 1001;
    private static final int REQUEST_ASSUME_VIEWER_WAKE = 1002;
    private static final String ACTION_ASSUME_VIEWER_WAKE =
        "com.perrychoi.wechatmomentscontroller.ASSUME_VIEWER_WAKE";
    private static final float[][] POST_MEDIA_TAP_CANDIDATES = new float[][]{
        {0.31f, 0.63f},
        {0.50f, 0.63f},
        {0.69f, 0.63f},
        {0.31f, 0.72f},
        {0.50f, 0.72f},
        {0.69f, 0.72f},
        {0.31f, 0.82f},
        {0.50f, 0.82f},
        {0.69f, 0.82f}
    };
    private static final float[][] POST_TEXT_LONG_PRESS_CANDIDATES = new float[][]{
        {POST_TEXT_LONG_PRESS_X_RATIO, POST_TEXT_LONG_PRESS_Y_RATIO},
        {0.46f, 0.43f},
        {0.52f, 0.36f},
        {0.36f, 0.48f},
        {0.60f, 0.48f}
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
        (prefs, key) -> {
            boolean wake = AutomationStore.isAutomationWakeKey(key);
            scheduleCommandCheck(wake ? "内部唤醒" : "命令变化", wake);
        };

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
    private boolean postContextCaptureText = true;
    private boolean currentNativeSaveLikelyVideo = false;
    private JSONObject pendingPostContextRecord = null;
    private String pendingPostContextScreenshotPath = "";
    private int nativeCopyPhase = NATIVE_COPY_NONE;
    private int nativeCopyAttempts = 0;
    private long nativeCopyLastActionMs = 0;
    private String nativeCopySentinel = "";
    private String nativeCopySource = "";

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
        if (event == null) {
            return;
        }

        boolean fromWechat = event.getPackageName() != null
            && WECHAT_PACKAGE.contentEquals(event.getPackageName());
        boolean windowsChanged = event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED;
        boolean canUsePackageLessWindowEvent = windowsChanged
            && AutomationStore.hasCommand(this, AutomationStore.COMMAND_WECHAT_POST_IMAGE_CAPTURE)
            && isWechatActive();
        if (!fromWechat && !canUsePackageLessWindowEvent) {
            return;
        }

        if (event.getClassName() != null) {
            lastWechatEventClassName = event.getClassName().toString();
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                lastWechatWindowClassName = lastWechatEventClassName;
            }
        }
        lastWechatEventMs = System.currentTimeMillis();

        if (AutomationStore.hasCommand(this, AutomationStore.COMMAND_WECHAT_POST_IMAGE_CAPTURE)
            && (workflowRunning || AutomationStore.nativeCopyPhase(this) != NATIVE_COPY_NONE)
            && (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            checkCommand("微信事件即时");
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

    private void startCommandPolling() {
        handler.removeCallbacks(commandPollRunnable);
        handler.postDelayed(commandPollRunnable, COMMAND_POLL_MS);
    }

    private void scheduleCommandCheck(String reason) {
        scheduleCommandCheck(reason, false);
    }

    private void scheduleCommandCheck(String reason, boolean force) {
        if (commandCheckScheduled && !force) {
            return;
        }

        commandCheckScheduled = true;
        handler.postDelayed(() -> {
            commandCheckScheduled = false;
            checkCommand(reason);
        }, 100);
    }

    private void scheduleNativeCopyCheck(String reason, long delayMs) {
        handler.postDelayed(() -> checkCommand(reason), delayMs);
    }

    private void scheduleAutomationWake(String reason, long delayMs) {
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }

        Intent intent = new Intent(this, CommandReceiver.class)
            .setAction(CommandReceiver.ACTION_AUTOMATION_WAKE)
            .setPackage(getPackageName())
            .putExtra("reason", reason == null ? "" : reason);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_AUTOMATION_WAKE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        long triggerAtMs = SystemClock.elapsedRealtime() + Math.max(250L, delayMs);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                );
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMs,
                    pendingIntent
                );
            }
        } catch (RuntimeException e) {
            AutomationLogger.log(this, "安排自动化自唤醒失败: "
                + e.getClass().getSimpleName() + " " + e.getMessage()
                + " reason=" + reason);
        }
    }

    private void scheduleAssumeViewerActivityWake(int candidateIndex, long startedMs, long delayMs) {
        scheduleAssumeViewerActivityThreadWake(candidateIndex, startedMs, delayMs);
        AutomationLogger.log(this, "已安排图片浏览器接力线程唤醒: candidate="
            + candidateIndex + " startedMs=" + startedMs + " delayMs=" + delayMs);
    }

    private void scheduleAssumeViewerActivityThreadWake(
        int candidateIndex,
        long startedMs,
        long delayMs
    ) {
        Thread thread = new Thread(() -> {
            SystemClock.sleep(Math.max(1000L, delayMs));
            if (!AutomationStore.hasCommand(this, AutomationStore.COMMAND_WECHAT_POST_IMAGE_CAPTURE)) {
                return;
            }
            if (!AutomationStore.postMediaOpenPending(this)
                || AutomationStore.postMediaOpenCandidate(this) != candidateIndex
                || AutomationStore.postMediaOpenStartedMs(this) != startedMs
                || postImageCaptureDir != null) {
                return;
            }

            try {
                AutomationLogger.log(this, "线程触发图片浏览器接力 Activity: candidate="
                    + candidateIndex + " startedMs=" + startedMs);
                startActivity(assumeViewerWakeIntent(candidateIndex));
            } catch (RuntimeException e) {
                AutomationLogger.log(this, "线程触发图片浏览器接力 Activity 失败: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            }
        }, "MomentsAssumeViewerWake");
        thread.start();
    }

    private void cancelAssumeViewerActivityWake() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);
        Intent intent = assumeViewerWakeIntent(-1);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_ASSUME_VIEWER_WAKE,
            intent,
            PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
        );
        if (pendingIntent == null) {
            return;
        }
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent);
        }
        pendingIntent.cancel();
    }

    private Intent assumeViewerWakeIntent(int candidateIndex) {
        return new Intent(this, CommandActivity.class)
            .setAction(ACTION_ASSUME_VIEWER_WAKE)
            .setPackage(getPackageName())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra("workflow", "capture_images")
            .putExtra("assume_viewer", true)
            .putExtra("image_count", Math.max(1, postImageCaptureCount))
            .putExtra("capture_text", postContextCaptureText)
            .putExtra("candidate", candidateIndex);
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
        if (AutomationStore.COMMAND_WECHAT_POST_IMAGE_CAPTURE.equals(command)
            && restoreNativeCopyStateIfNeeded()) {
            workflowRunning = true;
            postImageCaptureCount = AutomationStore.postImageCount(this);
            postImageAssumeViewer = AutomationStore.postImageAssumeViewer(this);
            postContextCaptureText = AutomationStore.postContextCaptureText(this);
            if (maybeAdvanceNativeCopyPostContext(reason)) {
                return;
            }
        }
        if (workflowRunning) {
            if (AutomationStore.COMMAND_WECHAT_POST_IMAGE_CAPTURE.equals(command)) {
                postImageCaptureCount = AutomationStore.postImageCount(this);
                postImageAssumeViewer = AutomationStore.postImageAssumeViewer(this);
                postContextCaptureText = AutomationStore.postContextCaptureText(this);
                if (postImageAssumeViewer && postImageCaptureDir == null) {
                    AutomationStore.clearPostMediaOpenPending(this);
                    cancelAssumeViewerActivityWake();
                    AutomationLogger.log(this, "收到 assume_viewer 接力命令，直接启动原生保存: reason="
                        + reason + " hints=" + currentWechatClassHints());
                    startPostImageCaptureAfterViewerCheck(true);
                    return;
                }
            }
            if (maybeAdvanceNativeCopyPostContext(reason)) {
                return;
            }
            if (maybeResumePostImageCaptureAfterMediaOpen(reason)) {
                return;
            }
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
        postContextCaptureText = AutomationStore.postContextCaptureText(this);
        postImageCaptureDir = null;
        currentNativeSaveLikelyVideo = false;
        pendingPostContextRecord = null;
        pendingPostContextScreenshotPath = "";
        AutomationStore.clearPostMediaOpenPending(this);
        resetNativeCopyState();
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
                    String captureSource = looksLikeMomentsList
                        ? "moments_list"
                        : "current_wechat_surface";
                    transition(Stage.RUNNING, looksLikeMomentsList
                        ? "从朋友圈列表点开当前可见第一张媒体"
                        : "当前微信页面节点不完整，尝试按候选坐标点开媒体");
                    capturePostContextThenOpenMedia(captureSource);
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

    private boolean maybeResumePostImageCaptureAfterMediaOpen(String reason) {
        if (!AutomationStore.hasCommand(this, AutomationStore.COMMAND_WECHAT_POST_IMAGE_CAPTURE)) {
            return false;
        }
        if (postImageCaptureDir != null || !AutomationStore.postMediaOpenPending(this)) {
            return false;
        }
        if (AutomationStore.stopRequested(this)) {
            finishWorkflow("用户停止");
            return true;
        }
        if (!isWechatActive()) {
            return false;
        }

        long startedMs = AutomationStore.postMediaOpenStartedMs(this);
        long elapsedMs = startedMs <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() - startedMs;
        if (elapsedMs < POST_MEDIA_OPEN_ASSUME_VIEWER_MS) {
            long delayMs = Math.max(250, POST_MEDIA_OPEN_ASSUME_VIEWER_MS - elapsedMs);
            handler.postDelayed(
                () -> scheduleCommandCheck("等待朋友圈媒体打开"),
                delayMs
            );
            scheduleAutomationWake("等待朋友圈媒体打开", delayMs + 250);
            return true;
        }

        String hints = currentWechatClassHints();
        if (shouldBackAfterFailedMediaTap()) {
            int candidate = AutomationStore.postMediaOpenCandidate(this);
            AutomationLogger.log(this, "媒体候选进入非图片浏览器页面，返回并尝试下一个: "
                + "candidate=" + candidate
                + " reason=" + reason
                + " hints=" + hints);
            AutomationStore.clearPostMediaOpenPending(this);
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
                failWorkflow("点开朋友圈媒体后返回失败");
                return true;
            }
            int nextCandidate = candidate + 1;
            handler.postDelayed(() -> openLatestVisiblePostMedia(nextCandidate), 900);
            return true;
        }
        if (!canAutoAssumeViewerAfterMediaTap(hints)) {
            if (elapsedMs >= POST_MEDIA_OPEN_STALE_ASSUME_VIEWER_MS) {
                int candidate = AutomationStore.postMediaOpenCandidate(this);
                AutomationLogger.log(this, "媒体打开接力已滞后，按图片/视频浏览器继续: "
                    + "candidate=" + candidate
                    + " elapsedMs=" + elapsedMs
                    + " reason=" + reason
                    + " hints=" + hints);
                AutomationStore.clearPostMediaOpenPending(this);
                postImageAssumeViewer = true;
                startPostImageCaptureAfterViewerCheck(true);
                return true;
            }

            AutomationLogger.log(this, "媒体打开接力等待: 当前仍像列表/非浏览器页面"
                + " candidate=" + AutomationStore.postMediaOpenCandidate(this)
                + " elapsedMs=" + elapsedMs
                + " reason=" + reason
                + " hints=" + hints);
            handler.postDelayed(() -> scheduleCommandCheck("等待媒体候选回调"), 600);
            scheduleAutomationWake("等待媒体候选回调", 800);
            return false;
        }

        int candidate = AutomationStore.postMediaOpenCandidate(this);
        AutomationLogger.log(this, "媒体打开后自动接力原生保存: candidate="
            + candidate
            + " elapsedMs=" + elapsedMs
            + " reason=" + reason
            + " hints=" + hints);
        AutomationStore.clearPostMediaOpenPending(this);
        postImageAssumeViewer = true;
        startPostImageCaptureAfterViewerCheck(true);
        return true;
    }

    private boolean canAutoAssumeViewerAfterMediaTap(String hints) {
        String classes = hints == null ? "" : hints.toLowerCase(Locale.US);
        if (classes.contains("contactinfo")
            || classes.contains("launcherui")
            || classes.contains("snstimeline")
            || classes.contains("improvesnstimeline")
            || classes.contains("snssingletextview")
            || classes.contains("snsuser")
            || classes.contains("webview")
            || classes.contains("landingpage")
            || classes.contains("dynamiccanvas")
            || classes.contains("朋友圈")
            || classes.contains("全文")) {
            return false;
        }
        return true;
    }

    private void startPostImageCaptureAfterViewerCheck() {
        startPostImageCaptureAfterViewerCheck(false);
    }

    private void startPostImageCaptureAfterViewerCheck(boolean allowUnverifiedViewer) {
        if (!workflowRunning) {
            return;
        }
        if (postImageCaptureDir != null) {
            AutomationLogger.log(this, "朋友圈原生保存 session 已启动，忽略重复启动请求");
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
        if (isCurrentPostVideoViewer()) {
            currentNativeSaveLikelyVideo = true;
            if (postImageCaptureCount != 1) {
                AutomationLogger.log(this, "检测到朋友圈视频浏览器，本次按单视频保存: requested="
                    + postImageCaptureCount);
                postImageCaptureCount = 1;
            }
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
        AutomationStore.clearPostMediaOpenPending(this);
        cancelAssumeViewerActivityWake();
        AutomationStore.setLastExportPath(this, sessionDir.getAbsolutePath());

        JSONObject session = new JSONObject();
        session.put("type", "session");
        session.put("captureMode", "wechat_native_save_menu");
        session.put("createdAt", timestamp);
        session.put("packageName", WECHAT_PACKAGE);
        session.put("mediaCount", imageCount);
        session.put("captureTextRequested", postContextCaptureText);
        session.put("device", deviceSummary());
        session.put("metrics", metricsSummary());
        appendPostImageRecord(session);
        appendPostContextRecordForSession();

        AutomationLogger.log(this, "朋友圈原生保存记录目录已创建: " + sessionDir.getAbsolutePath());
    }

    private void appendPostContextRecordForSession() {
        JSONObject record = pendingPostContextRecord;
        if (record == null) {
            String nativeCopyText = AutomationStore.nativeCopyText(this);
            if (!nativeCopyText.isEmpty()) {
                try {
                    record = buildUnavailablePostContextRecord(
                        "moments_list",
                        "native_copy_recovered",
                        "restored_from_prefs"
                    );
                    record.put("body", nativeCopyText);
                    record.put("bodyCharCount", nativeCopyText.length());
                    record.put("nativeCopyAttempted", true);
                    record.put("nativeCopyStatus", "copied");
                    record.put("nativeCopyCharCount", nativeCopyText.length());
                    record.put("hasFullTextLink", true);
                } catch (JSONException e) {
                    AutomationLogger.log(this, "恢复复制文字上下文失败: "
                        + e.getClass().getSimpleName() + " " + e.getMessage());
                }
            }
        }
        if (record == null) {
            try {
                record = buildUnavailablePostContextRecord(
                    postImageAssumeViewer ? "viewer_assumed" : "viewer",
                    "not_available_from_viewer",
                    "workflow_started_after_media_viewer_opened"
                );
            } catch (JSONException e) {
                AutomationLogger.log(this, "创建朋友圈文字上下文占位记录失败: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
                return;
            }
        }

        try {
            attachPostContextScreenshotToSession(record);
            appendPostImageRecord(record);
        } catch (IOException e) {
            AutomationLogger.log(this, "写入朋友圈文字上下文失败: "
                + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            pendingPostContextRecord = null;
        }
    }

    private void capturePendingPostContextBeforeOpeningMedia(String captureSource) {
        if (pendingPostContextRecord != null) {
            return;
        }

        try {
            pendingPostContextRecord = buildPostContextRecordFromCurrentSurface(captureSource);
            AutomationLogger.log(this, "已记录朋友圈文字上下文: source=" + captureSource
                + " status=" + pendingPostContextRecord.optString("extractionStatus")
                + " nodes=" + pendingPostContextRecord.optInt("nodeCount")
                + " textCandidates=" + pendingPostContextRecord.optInt("candidateTextCount")
                + " bodyChars=" + pendingPostContextRecord.optInt("bodyCharCount"));
        } catch (JSONException e) {
            AutomationLogger.log(this, "记录朋友圈文字上下文失败，继续保存媒体: "
                + e.getClass().getSimpleName() + " " + e.getMessage());
            try {
                pendingPostContextRecord = buildUnavailablePostContextRecord(
                    captureSource,
                    "capture_error",
                    e.getClass().getSimpleName() + ": " + e.getMessage()
                );
            } catch (JSONException ignored) {
                pendingPostContextRecord = null;
            }
        }
    }

    private void capturePostContextThenOpenMedia(String captureSource) {
        capturePendingPostContextBeforeOpeningMedia(captureSource);
        capturePostContextScreenshotBeforeMedia(captureSource, () -> {
            if (!workflowRunning) {
                return;
            }
            continueAfterPostContextSnapshot(captureSource);
        });
    }

    private void continueAfterPostContextSnapshot(String captureSource) {
        if (hasUsefulPostContext(pendingPostContextRecord)
            || !postContextCaptureText
            || !"moments_list".equals(captureSource)) {
            openLatestVisiblePostMedia(0);
            return;
        }

        if (!tryStartNativeCopyPostContext(captureSource)) {
            openLatestVisiblePostMedia(0);
        }
    }

    private void capturePostContextScreenshotBeforeMedia(String captureSource, Runnable continuation) {
        if (Build.VERSION.SDK_INT < 30) {
            updatePostContextScreenshotStatus("unsupported", "android_below_30", "");
            continuation.run();
            return;
        }

        captureScreenshotBitmap("朋友圈上下文截图 source=" + captureSource, new ScreenshotBitmapCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                try {
                    pendingPostContextScreenshotPath = saveBitmap(
                        bitmap,
                        "moments_context_screenshots",
                        "context_",
                        "朋友圈上下文 source=" + captureSource,
                        "朋友圈上下文截图",
                        false
                    );
                    updatePostContextScreenshotStatus(
                        pendingPostContextScreenshotPath.isEmpty() ? "failed" : "saved",
                        "",
                        pendingPostContextScreenshotPath
                    );
                } catch (IOException | RuntimeException e) {
                    updatePostContextScreenshotStatus(
                        "failed",
                        e.getClass().getSimpleName() + ": " + e.getMessage(),
                        ""
                    );
                } finally {
                    bitmap.recycle();
                    continuation.run();
                }
            }

            @Override
            public void onFailure(String error) {
                updatePostContextScreenshotStatus("failed", error, "");
                continuation.run();
            }
        });
    }

    private void updatePostContextScreenshotStatus(String status, String error, String path) {
        try {
            JSONObject record = pendingPostContextRecord;
            if (record == null) {
                record = buildUnavailablePostContextRecord(
                    "unknown",
                    "context_screenshot_only",
                    "post_context_record_missing"
                );
            }
            record.put("contextScreenshotStatus", status);
            if (path != null && !path.isEmpty()) {
                record.put("contextScreenshot", path);
                record.put("contextScreenshotFile", new File(path).getName());
            }
            if (error != null && !error.isEmpty()) {
                record.put("contextScreenshotError", error);
            }
            pendingPostContextRecord = record;
        } catch (JSONException e) {
            AutomationLogger.log(this, "记录朋友圈上下文截图状态失败: "
                + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void attachPostContextScreenshotToSession(JSONObject record) throws IOException {
        if (record == null
            || postImageCaptureDir == null
            || pendingPostContextScreenshotPath == null
            || pendingPostContextScreenshotPath.isEmpty()) {
            return;
        }

        File source = new File(pendingPostContextScreenshotPath);
        if (!source.exists()) {
            try {
                record.put("contextScreenshotStatus", "missing_before_session_copy");
                record.put("contextScreenshot", pendingPostContextScreenshotPath);
            } catch (JSONException e) {
                AutomationLogger.log(this, "记录上下文截图缺失失败: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            }
            return;
        }

        File dest = new File(postImageCaptureDir, source.getName());
        Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        try {
            record.put("contextScreenshotStatus", "saved");
            record.put("contextScreenshot", dest.getAbsolutePath());
            record.put("contextScreenshotFile", dest.getName());
            record.put("contextScreenshotOriginal", source.getAbsolutePath());
        } catch (JSONException e) {
            AutomationLogger.log(this, "记录上下文截图 session 路径失败: "
                + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private boolean hasUsefulPostContext(JSONObject record) {
        return record != null
            && record.optInt("bodyCharCount") > 0;
    }

    private boolean tryStartNativeCopyPostContext(String captureSource) {
        if (!workflowRunning || !isWechatActive()) {
            return false;
        }

        String sentinel = "moments_native_copy_" + timestampForFile();
        setClipboard(sentinel);
        transition(Stage.RUNNING, "尝试通过微信复制读取朋友圈文字");
        nativeCopyPhase = NATIVE_COPY_WAIT_INITIAL_RESULT;
        nativeCopyAttempts = 0;
        nativeCopyLastActionMs = System.currentTimeMillis();
        nativeCopySentinel = sentinel;
        nativeCopySource = captureSource;
        persistNativeCopyState();
        startNativeCopyWatchdog(sentinel);
        if (!longPressPostTextCandidate(0)) {
            AutomationLogger.log(this, "长按朋友圈正文失败，跳过复制读取");
            markNativeCopyUnavailable(captureSource, "initial_long_press_failed", false, "candidate=0");
            resetNativeCopyState();
            return false;
        }

        scheduleNativeCopyCheck("复制朋友圈文字", COMMAND_POLL_MS);
        return true;
    }

    private void startNativeCopyWatchdog(String sentinel) {
        Thread thread = new Thread(() -> {
            for (int i = 0; i < 30; i++) {
                SystemClock.sleep(700);
                if (!AutomationStore.hasCommand(this, AutomationStore.COMMAND_WECHAT_POST_IMAGE_CAPTURE)
                    || AutomationStore.nativeCopyPhase(this) == NATIVE_COPY_NONE
                    || !sentinel.equals(AutomationStore.nativeCopySentinel(this))) {
                    return;
                }
                handler.post(() -> checkCommand("朋友圈文字复制 watchdog"));
            }
        }, "MomentsNativeCopyWatchdog");
        thread.start();
    }

    private boolean maybeAdvanceNativeCopyPostContext(String reason) {
        if (nativeCopyPhase == NATIVE_COPY_NONE) {
            return false;
        }
        if (postImageCaptureDir != null) {
            resetNativeCopyState();
            return false;
        }

        long now = System.currentTimeMillis();
        if (shouldBackAfterFailedMediaTap()) {
            AutomationLogger.log(this, "文字复制进入非目标页面，返回后继续媒体保存: "
                + currentWechatClassHints());
            markNativeCopyUnavailable(
                nativeCopySource,
                "native_copy_opened_non_media_page",
                false,
                currentWechatClassHints()
            );
            resetNativeCopyState();
            if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
                openLatestVisiblePostMedia(0);
                return true;
            }
            handler.postDelayed(() -> openLatestVisiblePostMedia(0), 900);
            return true;
        }
        if (consumeExternallyCopiedPostTextIfAvailable(now)) {
            return true;
        }
        if (AutomationStore.nativeCopyScriptCopied(this)
            && nativeCopyPhase != NATIVE_COPY_READ_CLIPBOARD
            && nativeCopyPhase != NATIVE_COPY_WAIT_FOREGROUND_CLIPBOARD) {
            AutomationLogger.log(this, "收到外部复制完成信号，准备前台读取剪贴板");
            nativeCopyPhase = NATIVE_COPY_READ_CLIPBOARD;
            nativeCopyLastActionMs = now - POST_CONTEXT_COPY_CLIPBOARD_DELAY_MS;
            persistNativeCopyState();
            scheduleNativeCopyCheck("读取外部复制剪贴板", 100);
            return true;
        }
        switch (nativeCopyPhase) {
            case NATIVE_COPY_WAIT_INITIAL_RESULT:
                if (now - nativeCopyLastActionMs < POST_CONTEXT_COPY_RETRY_DELAY_MS) {
                    scheduleNativeCopyCheck("等待朋友圈复制菜单", 300);
                    return true;
                }
                if (isSingleTextViewPage()) {
                    AutomationLogger.log(this, "检测到朋友圈全文页，进入全文复制阶段: reason=" + reason);
                    nativeCopyPhase = NATIVE_COPY_LONG_PRESS_FULL_TEXT;
                    nativeCopyLastActionMs = 0;
                    persistNativeCopyState();
                    return maybeAdvanceNativeCopyPostContext(reason);
                }
                if (clickWechatCopyMenu(false)) {
                    nativeCopyPhase = NATIVE_COPY_READ_CLIPBOARD;
                    nativeCopyLastActionMs = now;
                    persistNativeCopyState();
                    scheduleNativeCopyCheck(
                        "读取朋友圈剪贴板",
                        POST_CONTEXT_COPY_CLIPBOARD_DELAY_MS
                    );
                    return true;
                }
                if (hasWechatTransientMenu()) {
                    dismissWechatTransientMenu();
                }
                int nextAttempt = nativeCopyAttempts + 1;
                if (nextAttempt < POST_TEXT_LONG_PRESS_CANDIDATES.length) {
                    nativeCopyAttempts = nextAttempt;
                    if (!longPressPostTextCandidate(nextAttempt)) {
                        markNativeCopyUnavailable(
                            nativeCopySource,
                            "long_press_failed",
                            false,
                            "candidate=" + nextAttempt
                        );
                        resetNativeCopyState();
                        openLatestVisiblePostMedia(0);
                        return true;
                    }
                    nativeCopyLastActionMs = now;
                    persistNativeCopyState();
                    scheduleNativeCopyCheck(
                        "重试朋友圈文字复制",
                        POST_CONTEXT_COPY_RETRY_DELAY_MS
                    );
                    return true;
                }
                markNativeCopyUnavailable(
                    nativeCopySource,
                    "copy_menu_not_found",
                    false,
                    "attempts=" + POST_TEXT_LONG_PRESS_CANDIDATES.length
                        + " hints=" + currentWechatClassHints()
                );
                resetNativeCopyState();
                openLatestVisiblePostMedia(0);
                return true;

            case NATIVE_COPY_LONG_PRESS_FULL_TEXT:
                if (!isSingleTextViewPage()) {
                    AutomationLogger.log(this, "复制读取时已离开全文页，继续保存媒体: "
                        + currentWechatClassHints());
                    resetNativeCopyState();
                    openLatestVisiblePostMedia(0);
                    return true;
                }
                if (now - nativeCopyLastActionMs < 700) {
                    return true;
                }
                if (nativeCopyAttempts >= 3 + POST_TEXT_LONG_PRESS_CANDIDATES.length) {
                    markNativeCopyUnavailable(nativeCopySource, "full_text_copy_menu_not_found", true, "");
                    nativeCopyPhase = NATIVE_COPY_RETURN_TO_LIST;
                    nativeCopyLastActionMs = now;
                    persistNativeCopyState();
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    return true;
                }

                longPressFullTextNativeCopyOnce(nativeCopyAttempts);
                nativeCopyAttempts++;
                nativeCopyPhase = NATIVE_COPY_WAIT_COPY_MENU;
                nativeCopyLastActionMs = now;
                persistNativeCopyState();
                return true;

            case NATIVE_COPY_WAIT_COPY_MENU:
                if (now - nativeCopyLastActionMs < 550) {
                    scheduleNativeCopyCheck("等待复制菜单", 350);
                    return true;
                }
                if (clickWechatCopyMenu(true)) {
                    nativeCopyPhase = NATIVE_COPY_READ_CLIPBOARD;
                    nativeCopyLastActionMs = now;
                    persistNativeCopyState();
                    scheduleNativeCopyCheck(
                        "读取全文剪贴板",
                        POST_CONTEXT_COPY_CLIPBOARD_DELAY_MS
                    );
                    return true;
                }
                nativeCopyPhase = NATIVE_COPY_LONG_PRESS_FULL_TEXT;
                nativeCopyLastActionMs = now - 800;
                persistNativeCopyState();
                return true;

            case NATIVE_COPY_READ_CLIPBOARD:
                if (now - nativeCopyLastActionMs < POST_CONTEXT_COPY_CLIPBOARD_DELAY_MS) {
                    scheduleNativeCopyCheck("等待剪贴板", 250);
                    return true;
                }
                requestForegroundClipboardRead();
                nativeCopyPhase = NATIVE_COPY_WAIT_FOREGROUND_CLIPBOARD;
                nativeCopyLastActionMs = now;
                persistNativeCopyState();
                scheduleNativeCopyCheck("等待前台剪贴板读取", 900);
                return true;

            case NATIVE_COPY_WAIT_FOREGROUND_CLIPBOARD:
                if (now - nativeCopyLastActionMs < 700) {
                    scheduleNativeCopyCheck("等待前台剪贴板结果", 250);
                    return true;
                }
                String copiedText = AutomationStore.nativeCopyText(this);
                if (copiedText.isEmpty()) {
                    copiedText = readClipboardText();
                }
                boolean copied = !copiedText.isEmpty() && !nativeCopySentinel.equals(copiedText);
                boolean copiedFromFullTextPage = isSingleTextViewPage()
                    || AutomationStore.nativeCopyScriptCopied(this);
                AutomationStore.clearNativeCopyScriptCopied(this);
                applyNativeCopiedPostTextToContext(
                    nativeCopySource,
                    copiedText,
                    copied,
                    copiedFromFullTextPage
                );
                AutomationLogger.log(this, "微信复制朋友圈文字轮询结果: copied=" + copied
                    + " chars=" + (copied ? copiedText.length() : 0));
                if (copiedFromFullTextPage) {
                    nativeCopyPhase = NATIVE_COPY_RETURN_TO_LIST;
                    nativeCopyLastActionMs = now;
                    persistNativeCopyState();
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    return true;
                }
                resetNativeCopyState();
                openLatestVisiblePostMedia(0);
                return true;

            case NATIVE_COPY_RETURN_TO_LIST:
                if (now - nativeCopyLastActionMs < 800) {
                    scheduleNativeCopyCheck("等待返回朋友圈列表", 350);
                    return true;
                }
                resetNativeCopyState();
                openLatestVisiblePostMedia(0);
                return true;

            case NATIVE_COPY_NONE:
            default:
                resetNativeCopyState();
                return false;
        }
    }

    private boolean consumeExternallyCopiedPostTextIfAvailable(long now) {
        String copiedText = AutomationStore.nativeCopyText(this);
        if (copiedText.isEmpty() || nativeCopySentinel.equals(copiedText)) {
            return false;
        }

        boolean copiedFromFullTextPage = isSingleTextViewPage()
            || AutomationStore.nativeCopyScriptCopied(this);
        AutomationStore.clearNativeCopyScriptCopied(this);
        String source = nativeCopySource.isEmpty() ? "moments_list" : nativeCopySource;
        applyNativeCopiedPostTextToContext(source, copiedText, true, copiedFromFullTextPage);
        AutomationLogger.log(this, "接收外部前台剪贴板朋友圈文字: chars="
            + copiedText.length() + " fullTextPage=" + copiedFromFullTextPage);
        if (copiedFromFullTextPage) {
            nativeCopyPhase = NATIVE_COPY_RETURN_TO_LIST;
            nativeCopyLastActionMs = now;
            persistNativeCopyState();
            performGlobalAction(GLOBAL_ACTION_BACK);
            return true;
        }

        resetNativeCopyState();
        openLatestVisiblePostMedia(0);
        return true;
    }

    private boolean longPressPostTextCandidate(int candidateIndex) {
        int safeIndex = Math.max(0, Math.min(
            candidateIndex,
            POST_TEXT_LONG_PRESS_CANDIDATES.length - 1
        ));
        DisplayMetrics metrics = realMetrics();
        float[] candidate = POST_TEXT_LONG_PRESS_CANDIDATES[safeIndex];
        int x = Math.round(metrics.widthPixels * candidate[0]);
        int y = Math.round(metrics.heightPixels * candidate[1]);
        AutomationLogger.log(this, "尝试长按朋友圈正文复制: candidate="
            + (safeIndex + 1) + "/" + POST_TEXT_LONG_PRESS_CANDIDATES.length
            + " x=" + x + " y=" + y);
        return longPress(x, y);
    }

    private void markNativeCopyUnavailable(
        String captureSource,
        String status,
        boolean copiedFromFullTextPage,
        String detail
    ) {
        try {
            JSONObject record = pendingPostContextRecord;
            if (record == null) {
                record = buildUnavailablePostContextRecord(
                    captureSource,
                    "native_copy_unavailable",
                    "accessibility_context_missing"
                );
            }
            record.put("nativeCopyAttempted", true);
            record.put("nativeCopyStatus", status);
            record.put("nativeCopyFromFullTextPage", copiedFromFullTextPage);
            record.put("nativeCopyCharCount", 0);
            if (detail != null && !detail.isEmpty()) {
                record.put("nativeCopyDetail", detail);
            }
            pendingPostContextRecord = record;
            AutomationLogger.log(this, "朋友圈文字复制不可用: status=" + status
                + " detail=" + detail);
        } catch (JSONException e) {
            AutomationLogger.log(this, "写入复制不可用状态失败: "
                + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private boolean hasWechatTransientMenu() {
        return findByLabelsInWindows(new String[]{
            "复制", "保存图片", "保存视频", "发送给朋友", "收藏", "不感兴趣"
        }) != null;
    }

    private void dismissWechatTransientMenu() {
        AutomationLogger.log(this, "关闭微信临时菜单后继续文字复制");
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private void longPressFullTextNativeCopyOnce(int retry) {
        DisplayMetrics metrics = realMetrics();
        int x = Math.round(metrics.widthPixels * POST_FULL_TEXT_LONG_PRESS_X_RATIO);
        int y = Math.round(metrics.heightPixels * POST_FULL_TEXT_LONG_PRESS_Y_RATIO);
        AutomationLogger.log(this, "轮询长按朋友圈全文页正文复制: x=" + x
            + " y=" + y + " retry=" + retry);
        longPress(x, y);
    }

    private void resetNativeCopyState() {
        nativeCopyPhase = NATIVE_COPY_NONE;
        nativeCopyAttempts = 0;
        nativeCopyLastActionMs = 0;
        nativeCopySentinel = "";
        nativeCopySource = "";
        AutomationStore.clearNativeCopyState(this);
    }

    private void persistNativeCopyState() {
        AutomationStore.setNativeCopyState(
            this,
            nativeCopyPhase,
            nativeCopySource,
            nativeCopySentinel,
            nativeCopyAttempts,
            nativeCopyLastActionMs
        );
    }

    private boolean restoreNativeCopyStateIfNeeded() {
        if (nativeCopyPhase != NATIVE_COPY_NONE) {
            return true;
        }

        int storedPhase = AutomationStore.nativeCopyPhase(this);
        if (storedPhase == NATIVE_COPY_NONE) {
            return false;
        }

        nativeCopyPhase = storedPhase;
        nativeCopySource = AutomationStore.nativeCopySource(this);
        nativeCopySentinel = AutomationStore.nativeCopySentinel(this);
        nativeCopyAttempts = AutomationStore.nativeCopyAttempts(this);
        nativeCopyLastActionMs = AutomationStore.nativeCopyLastActionMs(this);
        AutomationLogger.log(this, "恢复朋友圈文字复制阶段: phase=" + nativeCopyPhase
            + " attempts=" + nativeCopyAttempts
            + " source=" + nativeCopySource);
        return true;
    }

    private void afterNativeCopyInitialLongPress(
        String captureSource,
        String sentinel,
        int retry
    ) {
        if (!workflowRunning) {
            return;
        }
        if (AutomationStore.stopRequested(this)) {
            finishWorkflow("用户停止");
            return;
        }
        if (!isWechatActive()) {
            failWorkflow("读取朋友圈文字失败: 微信不在前台 " + wechatActiveSummary());
            return;
        }

        if (isSingleTextViewPage()) {
            longPressFullTextForNativeCopy(captureSource, sentinel, 0);
            return;
        }

        if (clickWechatCopyMenu(false)) {
            handler.postDelayed(
                () -> afterNativeCopyClicked(captureSource, sentinel, false),
                700
            );
            return;
        }

        if (retry < 2) {
            handler.postDelayed(
                () -> afterNativeCopyInitialLongPress(captureSource, sentinel, retry + 1),
                600
            );
            return;
        }

        AutomationLogger.log(this, "朋友圈正文复制菜单未出现，继续保存媒体: "
            + currentWechatClassHints());
        openLatestVisiblePostMedia(0);
    }

    private void longPressFullTextForNativeCopy(
        String captureSource,
        String sentinel,
        int retry
    ) {
        if (!workflowRunning) {
            return;
        }
        if (retry > 2) {
            AutomationLogger.log(this, "全文页复制菜单未出现，返回列表继续保存媒体");
            backToMomentsListThenOpenMedia();
            return;
        }

        DisplayMetrics metrics = realMetrics();
        int x = Math.round(metrics.widthPixels * POST_FULL_TEXT_LONG_PRESS_X_RATIO);
        int y = Math.round(metrics.heightPixels * POST_FULL_TEXT_LONG_PRESS_Y_RATIO);
        AutomationLogger.log(this, "长按朋友圈全文页正文复制: x=" + x + " y=" + y
            + " retry=" + retry);
        if (!longPress(x, y)) {
            handler.postDelayed(
                () -> longPressFullTextForNativeCopy(captureSource, sentinel, retry + 1),
                500
            );
            return;
        }

        handler.postDelayed(
            () -> clickFullTextCopyMenu(captureSource, sentinel, retry),
            900
        );
    }

    private void clickFullTextCopyMenu(String captureSource, String sentinel, int retry) {
        if (!workflowRunning) {
            return;
        }
        if (clickWechatCopyMenu(true)) {
            handler.postDelayed(
                () -> afterNativeCopyClicked(captureSource, sentinel, true),
                700
            );
            return;
        }

        handler.postDelayed(
            () -> longPressFullTextForNativeCopy(captureSource, sentinel, retry + 1),
            500
        );
    }

    private void afterNativeCopyClicked(
        String captureSource,
        String sentinel,
        boolean returnFromFullTextPage
    ) {
        String copiedText = readClipboardText();
        boolean copied = !copiedText.isEmpty() && !sentinel.equals(copiedText);
        applyNativeCopiedPostTextToContext(captureSource, copiedText, copied, returnFromFullTextPage);
        AutomationLogger.log(this, "微信复制朋友圈文字结果: copied=" + copied
            + " chars=" + (copied ? copiedText.length() : 0)
            + " returnFromFullTextPage=" + returnFromFullTextPage);

        if (returnFromFullTextPage || isSingleTextViewPage()) {
            backToMomentsListThenOpenMedia();
        } else {
            openLatestVisiblePostMedia(0);
        }
    }

    private void applyNativeCopiedPostTextToContext(
        String captureSource,
        String copiedText,
        boolean copied,
        boolean copiedFromFullTextPage
    ) {
        try {
            JSONObject record = pendingPostContextRecord;
            if (record == null) {
                record = buildUnavailablePostContextRecord(
                    captureSource,
                    copied ? "native_copy" : "native_copy_empty",
                    "accessibility_context_missing"
                );
            }

            record.put("nativeCopyAttempted", true);
            record.put("nativeCopyStatus", copied ? "copied" : "empty_or_unchanged_clipboard");
            record.put("nativeCopyFromFullTextPage", copiedFromFullTextPage);
            record.put("nativeCopyCharCount", copied ? copiedText.length() : 0);
            if (copied) {
                AutomationStore.setNativeCopyText(this, copiedText);
                record.put("body", copiedText);
                record.put("bodyCharCount", copiedText.length());
                record.put("extractionStatus", "native_copy");
                if (copiedFromFullTextPage) {
                    record.put("hasFullTextLink", true);
                }
            }
            pendingPostContextRecord = record;
        } catch (JSONException e) {
            AutomationLogger.log(this, "写入复制文字上下文失败: "
                + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void backToMomentsListThenOpenMedia() {
        if (!workflowRunning) {
            return;
        }
        if (!performGlobalAction(GLOBAL_ACTION_BACK)) {
            AutomationLogger.log(this, "从朋友圈全文页返回失败，仍尝试继续点开媒体");
            openLatestVisiblePostMedia(0);
            return;
        }

        handler.postDelayed(() -> openLatestVisiblePostMedia(0), 1000);
    }

    private boolean clickWechatCopyMenu(boolean allowFallback) {
        AccessibilityNodeInfo node = findByLabelsInWindows(new String[]{"复制"});
        if (node != null && clickNode(node)) {
            return true;
        }
        if (!allowFallback) {
            return false;
        }

        DisplayMetrics metrics = realMetrics();
        int x = Math.round(metrics.widthPixels * 0.50f);
        int y = Math.round(metrics.heightPixels * 0.64f);
        AutomationLogger.log(this, "复制菜单节点未找到，使用坐标兜底: x=" + x + " y=" + y);
        return tap(x, y);
    }

    private boolean isSingleTextViewPage() {
        return currentWechatClassHints().contains("snssingletextview");
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
            AutomationLogger.log(this, "读取剪贴板失败: "
                + e.getClass().getSimpleName() + " " + e.getMessage());
            return "";
        }
    }

    private void requestForegroundClipboardRead() {
        try {
            Intent intent = new Intent(this, CommandActivity.class)
                .setAction(AutomationStore.ACTION_READ_CLIPBOARD)
                .setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(intent);
            AutomationLogger.log(this, "已请求前台 Activity 读取朋友圈复制剪贴板");
        } catch (RuntimeException e) {
            AutomationLogger.log(this, "请求前台读取剪贴板失败: "
                + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private JSONObject buildPostContextRecordFromCurrentSurface(String captureSource)
        throws JSONException {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        DisplayMetrics metrics = realMetrics();

        JSONArray nodes = new JSONArray();
        int[] count = new int[]{0};
        collectVisibleNodes(root, nodes, 0, count);
        int rootNodeCount = nodes.length();
        int windowNodeCount = collectVisibleNodesFromWechatWindows(nodes, count);
        List<PostTextNode> textNodes = extractPostTextCandidates(nodes, metrics);

        JSONObject record = new JSONObject();
        record.put("type", "post_context");
        record.put("captureMode", "wechat_native_save_menu");
        record.put("captureSource", captureSource);
        record.put("capturedAt", timestampForFile());
        record.put("packageName", WECHAT_PACKAGE);
        record.put("root", rootSummary(root));
        record.put("eventClass", lastWechatEventClassName);
        record.put("windowClass", lastWechatWindowClassName);
        record.put("classHints", currentWechatClassHints());
        record.put("targetMediaCandidate", postMediaCandidateJson(0, metrics));
        record.put("rootVisibleNodeCount", rootNodeCount);
        record.put("windowVisibleNodeCount", windowNodeCount);
        record.put("nodeCount", nodes.length());
        record.put("nodeExtractStatus", nodeExtractStatus(root, nodes.length()));

        JSONArray candidateTexts = new JSONArray();
        for (PostTextNode textNode : textNodes) {
            candidateTexts.put(postTextNodeJson(textNode));
        }
        record.put("candidateTextCount", candidateTexts.length());
        record.put("candidateTexts", candidateTexts);
        applyPostContextHeuristics(record, textNodes);
        record.put("nodes", nodes);
        return record;
    }

    private JSONObject buildUnavailablePostContextRecord(
        String captureSource,
        String extractionStatus,
        String reason
    ) throws JSONException {
        AccessibilityNodeInfo root = getRootInActiveWindow();

        JSONObject record = new JSONObject();
        record.put("type", "post_context");
        record.put("captureMode", "wechat_native_save_menu");
        record.put("captureSource", captureSource);
        record.put("capturedAt", timestampForFile());
        record.put("packageName", WECHAT_PACKAGE);
        record.put("extractionStatus", extractionStatus);
        record.put("reason", reason);
        record.put("root", rootSummary(root));
        record.put("eventClass", lastWechatEventClassName);
        record.put("windowClass", lastWechatWindowClassName);
        record.put("classHints", currentWechatClassHints());
        record.put("author", "");
        record.put("postedAtText", "");
        record.put("body", "");
        record.put("bodyCharCount", 0);
        record.put("hasFullTextLink", false);
        return record;
    }

    private int collectVisibleNodesFromWechatWindows(JSONArray nodes, int[] count)
        throws JSONException {
        int before = nodes.length();
        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows == null) {
            return 0;
        }

        for (AccessibilityWindowInfo window : windows) {
            if (count[0] >= MOMENTS_COLLECT_MAX_NODES) {
                break;
            }
            AccessibilityNodeInfo windowRoot = window == null ? null : window.getRoot();
            if (windowRoot == null || windowRoot.getPackageName() == null
                || !WECHAT_PACKAGE.contentEquals(windowRoot.getPackageName())) {
                continue;
            }
            collectVisibleNodes(windowRoot, nodes, 0, count);
        }
        return nodes.length() - before;
    }

    private List<PostTextNode> extractPostTextCandidates(
        JSONArray nodes,
        DisplayMetrics metrics
    ) {
        List<PostTextNode> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int targetY = Math.round(metrics.heightPixels * POST_MEDIA_TAP_CANDIDATES[0][1]);
        int minY = Math.max(
            Math.round(metrics.heightPixels * 0.11f),
            targetY - Math.round(metrics.heightPixels * 0.46f)
        );
        int maxY = Math.min(
            Math.round(metrics.heightPixels * 0.96f),
            targetY + Math.round(metrics.heightPixels * 0.38f)
        );
        int minRight = Math.round(metrics.widthPixels * 0.10f);
        int maxLeft = Math.round(metrics.widthPixels * 0.94f);

        for (int i = 0; i < nodes.length(); i++) {
            if (candidates.size() >= POST_CONTEXT_MAX_TEXT_NODES) {
                break;
            }

            JSONObject item = nodes.optJSONObject(i);
            if (item == null) {
                continue;
            }

            String text = normalizeContextText(item.optString("text"));
            String description = normalizeContextText(item.optString("description"));
            String value = text.isEmpty() ? description : text;
            if (value.isEmpty() || isPostContextIgnoredText(value)) {
                continue;
            }

            JSONObject bounds = item.optJSONObject("bounds");
            if (bounds == null) {
                continue;
            }

            int left = bounds.optInt("left");
            int top = bounds.optInt("top");
            int right = bounds.optInt("right");
            int bottom = bounds.optInt("bottom");
            if (right <= left || bottom <= top) {
                continue;
            }
            int centerY = (top + bottom) / 2;
            if (centerY < minY || centerY > maxY || right < minRight || left > maxLeft) {
                continue;
            }

            String key = value + "@" + left + "," + top + "," + right + "," + bottom;
            if (!seen.add(key)) {
                continue;
            }

            candidates.add(new PostTextNode(
                item.optInt("index", i),
                value,
                item.optString("className"),
                item.optString("viewId"),
                left,
                top,
                right,
                bottom
            ));
        }

        Collections.sort(candidates, (a, b) -> {
            if (a.top != b.top) {
                return a.top - b.top;
            }
            if (a.left != b.left) {
                return a.left - b.left;
            }
            return a.index - b.index;
        });
        return candidates;
    }

    private void applyPostContextHeuristics(
        JSONObject record,
        List<PostTextNode> textNodes
    ) throws JSONException {
        boolean hasFullTextLink = false;
        PostTextNode authorNode = null;
        PostTextNode timeNode = null;

        for (PostTextNode textNode : textNodes) {
            if (textNode.text.contains("全文")) {
                hasFullTextLink = true;
            }
            if (timeNode == null && looksLikeMomentTimeText(textNode.text)) {
                timeNode = textNode;
            }
            if (authorNode == null && isLikelyAuthorText(textNode.text)) {
                authorNode = textNode;
            }
        }

        List<String> bodyParts = new ArrayList<>();
        boolean passedAuthor = authorNode == null;
        for (PostTextNode textNode : textNodes) {
            if (textNode == authorNode) {
                passedAuthor = true;
                continue;
            }
            if (!passedAuthor) {
                continue;
            }
            if (textNode == timeNode || isPostContextBodyExcluded(textNode.text)) {
                continue;
            }
            if (!bodyParts.contains(textNode.text)) {
                bodyParts.add(textNode.text);
            }
        }

        String author = authorNode == null ? "" : authorNode.text;
        String postedAtText = timeNode == null ? "" : timeNode.text;
        String body = join(bodyParts, "\n");

        record.put("author", author);
        record.put("postedAtText", postedAtText);
        record.put("body", body);
        record.put("bodyCharCount", body.length());
        record.put("hasFullTextLink", hasFullTextLink);
        if (authorNode != null) {
            record.put("authorNode", postTextNodeJson(authorNode));
        }
        if (timeNode != null) {
            record.put("timeNode", postTextNodeJson(timeNode));
        }

        String status = "raw_nodes_only";
        if (record.optInt("nodeCount") <= 0) {
            status = record.optString("nodeExtractStatus", "no_visible_nodes");
        } else if (textNodes.isEmpty()) {
            status = "no_candidate_text";
        } else if (!author.isEmpty() || !postedAtText.isEmpty() || !body.isEmpty()) {
            status = "derived";
        } else {
            status = "candidate_text_only";
        }
        record.put("extractionStatus", status);
    }

    private JSONObject postMediaCandidateJson(int candidateIndex, DisplayMetrics metrics)
        throws JSONException {
        int safeIndex = Math.max(0, Math.min(candidateIndex, POST_MEDIA_TAP_CANDIDATES.length - 1));
        float[] candidate = POST_MEDIA_TAP_CANDIDATES[safeIndex];

        JSONObject json = new JSONObject();
        json.put("candidateIndex", safeIndex);
        json.put("xRatio", candidate[0]);
        json.put("yRatio", candidate[1]);
        json.put("x", Math.round(metrics.widthPixels * candidate[0]));
        json.put("y", Math.round(metrics.heightPixels * candidate[1]));
        return json;
    }

    private JSONObject postTextNodeJson(PostTextNode node) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("index", node.index);
        json.put("text", node.text);
        json.put("className", node.className);
        json.put("viewId", node.viewId);

        Rect bounds = new Rect(node.left, node.top, node.right, node.bottom);
        json.put("bounds", boundsJson(bounds));
        return json;
    }

    private boolean isLikelyAuthorText(String text) {
        if (text.length() > 40) {
            return false;
        }
        return !looksLikeMomentTimeText(text)
            && !isPostContextBodyExcluded(text)
            && !text.contains("全文");
    }

    private boolean isPostContextBodyExcluded(String text) {
        if (text.isEmpty()) {
            return true;
        }
        if (looksLikeMomentTimeText(text) || isPostContextIgnoredText(text)) {
            return true;
        }
        if (text.equals("全文") || text.equals("收起") || text.equals("展开")) {
            return true;
        }
        if (text.matches("\\d+条评论") || text.matches("\\d+人赞")) {
            return true;
        }
        return text.endsWith("评论") && text.length() <= 6;
    }

    private boolean isPostContextIgnoredText(String text) {
        String value = normalizeContextText(text);
        if (value.isEmpty()) {
            return true;
        }

        String[] exact = new String[]{
            "朋友圈", "返回", "相机", "拍摄", "拍照分享", "Camera",
            "微信", "通讯录", "发现", "我", "视频号", "扫一扫", "搜一搜",
            "看一看", "直播", "小程序", "更多", "更多信息", "详情",
            "赞", "评论", "删除", "回复", "图片", "视频", "播放", "暂停",
            "发送给朋友", "收藏", "保存图片", "保存视频", "保存到手机", "保存"
        };
        for (String label : exact) {
            if (value.equals(label)) {
                return true;
            }
        }

        return (value.startsWith("第") && value.endsWith("张"))
            || (value.contains("头像") && value.length() <= 12);
    }

    private boolean looksLikeMomentTimeText(String text) {
        String value = normalizeContextText(text);
        if (value.isEmpty()) {
            return false;
        }
        return value.matches(".*(刚刚|分钟前|小时前|昨天|前天|今天|\\d+天前|\\d+月\\d+日|\\d{4}年\\d+月\\d+日|\\d{1,2}:\\d{2}).*");
    }

    private String normalizeContextText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
            .trim();
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
        if (postImageAssumeViewer && !isPostImageViewer()) {
            failWorkflow("朋友圈图片/视频保存中断: 当前已不在图片/视频浏览器 "
                + currentWechatClassHints());
            return;
        }
        if (postImageAssumeViewer && shouldBackAfterFailedMediaTap()) {
            failWorkflow("朋友圈图片/视频保存中断: 已离开图片/视频浏览器 "
                + currentWechatClassHints());
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
        if (currentNativeSaveLikelyVideo || isCurrentPostVideoViewer()) {
            appendPostVideoSkippedRecord(imageIndex, "video_long_press_disabled_to_avoid_contactinfo");
            finishWorkflow("朋友圈视频上下文已保存，视频原生保存已跳过: "
                + postImageCaptureDir.getAbsolutePath());
            return;
        }
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
        if (postImageAssumeViewer
            && !isPostImageViewer()
            && !isWechatNativeSaveMenuSurface()) {
            failWorkflow("朋友圈图片/视频保存中断: 保存菜单阶段已离开图片/视频浏览器 "
                + currentWechatClassHints());
            return;
        }
        if (postImageAssumeViewer && shouldBackAfterFailedMediaTap()) {
            failWorkflow("朋友圈图片保存中断: 保存菜单阶段已离开图片/视频浏览器 "
                + currentWechatClassHints());
            return;
        }

        String[] labels = new String[]{
            "保存图片", "保存视频", "保存到手机", "保存到相册", "保存"
        };
        AccessibilityNodeInfo node = findByLabelsInWindows(labels);
        if (node != null && clickNode(node)) {
            String actionLabel = safeString(node.getText());
            if (actionLabel.contains("视频")) {
                currentNativeSaveLikelyVideo = true;
            }
            appendPostImageActionRecord(imageIndex, actionLabel, "node");
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

        if (isPostImageViewer() || isWechatNativeSaveMenuSurface()) {
            if (tapNativeSaveFallback()) {
                appendPostImageActionRecord(imageIndex, "fallback_tap", "coordinate");
                AutomationLogger.log(this, "微信原生保存菜单坐标兜底: index="
                    + (imageIndex + 1) + "/" + postImageCaptureCount);
                handler.postDelayed(() -> afterNativeSaveClicked(imageIndex), 1200);
                return;
            }
        } else {
            AutomationLogger.log(this, "跳过保存菜单坐标兜底: 当前不在媒体浏览器/保存菜单 "
                + currentWechatClassHints());
        }

        failWorkflow("未找到微信保存图片/视频菜单");
    }

    private boolean isWechatNativeSaveMenuSurface() {
        String classes = currentWechatClassHints();
        return classes.contains("dialog")
            || classes.contains("popupwindow")
            || classes.contains("a4")
            || findByLabelsInWindows(new String[]{
                "保存图片", "保存视频", "保存到手机", "保存到相册"
            }) != null;
    }

    private void appendPostVideoSkippedRecord(int imageIndex, String reason) {
        JSONObject record = new JSONObject();
        try {
            record.put("type", "native_save_end");
            record.put("capturedAt", timestampForFile());
            record.put("reason", reason);
            record.put("mediaIndex", imageIndex);
            record.put("mediaNumber", imageIndex + 1);
            record.put("likelyVideo", true);
            record.put("savedMediaCount", 0);
            record.put("requestedMediaCount", postImageCaptureCount);
            record.put("classHints", currentWechatClassHints());
            appendPostImageRecord(record);
        } catch (IOException | JSONException e) {
            AutomationLogger.log(this, "朋友圈视频跳过记录失败: "
                + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void afterNativeSaveClicked(int imageIndex) {
        if (!workflowRunning) {
            return;
        }
        if (currentNativeSaveLikelyVideo || isCurrentPostVideoViewer()) {
            finishWorkflow("朋友圈视频原生保存完成: " + postImageCaptureDir.getAbsolutePath());
            return;
        }
        if (imageIndex + 1 >= postImageCaptureCount) {
            finishWorkflow("朋友圈图片/视频原生保存完成: " + postImageCaptureDir.getAbsolutePath());
            return;
        }
        swipeNextPostImageOrFinishAtEnd(imageIndex);
    }

    private void finishPostCaptureWithoutMedia(String reason) {
        if (!workflowRunning) {
            return;
        }

        try {
            preparePostImageCaptureSession(0);
            appendNoMediaRecord(reason);
        } catch (IOException | JSONException e) {
            failWorkflow("保存朋友圈文字/上下文失败: "
                + e.getClass().getSimpleName() + " " + e.getMessage());
            return;
        }

        finishWorkflow("朋友圈文字/上下文保存完成: "
            + postImageCaptureDir.getAbsolutePath());
    }

    private void appendNoMediaRecord(String reason) throws IOException, JSONException {
        JSONObject record = new JSONObject();
        record.put("type", "native_save_end");
        record.put("capturedAt", timestampForFile());
        record.put("reason", reason);
        record.put("savedMediaCount", 0);
        record.put("requestedMediaCount", postImageCaptureCount);
        record.put("classHints", currentWechatClassHints());
        appendPostImageRecord(record);
    }

    private void swipeNextPostImageOrFinishAtEnd(int imageIndex) {
        captureMediaFingerprint("切换下一张前 index=" + imageIndex, new MediaFingerprintCallback() {
            @Override
            public void onSuccess(long beforeFingerprint) {
                if (!workflowRunning) {
                    return;
                }
                if (!swipeNextPostImage()) {
                    failWorkflow("朋友圈图片/视频切换下一张失败");
                    return;
                }
                handler.postDelayed(
                    () -> captureFingerprintAfterSwipe(imageIndex, beforeFingerprint),
                    POST_IMAGE_SWIPE_DELAY_MS
                );
            }

            @Override
            public void onFailure(String error) {
                AutomationLogger.log(WechatAutomationService.this,
                    "切换前截图指纹失败，按旧逻辑继续: " + error);
                if (!swipeNextPostImage()) {
                    failWorkflow("朋友圈图片/视频切换下一张失败");
                    return;
                }
                handler.postDelayed(
                    () -> capturePostImage(imageIndex + 1),
                    POST_IMAGE_SWIPE_DELAY_MS
                );
            }
        });
    }

    private void captureFingerprintAfterSwipe(int imageIndex, long beforeFingerprint) {
        captureMediaFingerprint("切换下一张后 index=" + imageIndex, new MediaFingerprintCallback() {
            @Override
            public void onSuccess(long afterFingerprint) {
                int distance = Long.bitCount(beforeFingerprint ^ afterFingerprint);
                if (distance <= POST_IMAGE_SAME_FINGERPRINT_MAX_DISTANCE) {
                    appendPostImageEndRecord(
                        imageIndex,
                        "swipe_no_change",
                        distance,
                        beforeFingerprint,
                        afterFingerprint
                    );
                    finishWorkflow("朋友圈图片/视频原生保存完成: 已到最后一个媒体 "
                        + postImageCaptureDir.getAbsolutePath());
                    return;
                }
                capturePostImage(imageIndex + 1);
            }

            @Override
            public void onFailure(String error) {
                AutomationLogger.log(WechatAutomationService.this,
                    "切换后截图指纹失败，按旧逻辑继续: " + error);
                capturePostImage(imageIndex + 1);
            }
        });
    }

    private void appendPostImageEndRecord(
        int imageIndex,
        String reason,
        int fingerprintDistance,
        long beforeFingerprint,
        long afterFingerprint
    ) {
        JSONObject record = new JSONObject();
        try {
            record.put("type", "native_save_end");
            record.put("capturedAt", timestampForFile());
            record.put("reason", reason);
            record.put("lastSavedMediaIndex", imageIndex);
            record.put("lastSavedMediaNumber", imageIndex + 1);
            record.put("requestedMediaCount", postImageCaptureCount);
            record.put("fingerprintDistance", fingerprintDistance);
            record.put("beforeFingerprint", Long.toUnsignedString(beforeFingerprint));
            record.put("afterFingerprint", Long.toUnsignedString(afterFingerprint));
            record.put("classHints", currentWechatClassHints());
            appendPostImageRecord(record);
        } catch (IOException | JSONException e) {
            AutomationLogger.log(this, "朋友圈结束记录失败: "
                + e.getClass().getSimpleName() + " " + e.getMessage());
        }
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
            record.put("likelyVideo", currentNativeSaveLikelyVideo);
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
        if (classes.contains("contactinfo")
            || classes.contains("launcherui")
            || classes.contains("snstimeline")
            || classes.contains("improvesnstimeline")
            || classes.contains("snsuser")) {
            return false;
        }

        return classes.contains("snsbrowse")
            || classes.contains("snsimage")
            || classes.contains("snsonlinevideo")
            || classes.contains("imagegallery")
            || classes.contains("imagepreview")
            || classes.contains("galleryui");
    }

    private boolean isCurrentPostVideoViewer() {
        String classes = currentWechatClassHints();
        return classes.contains("snsonlinevideo")
            || classes.contains("onlinevideo")
            || classes.contains("videoactivity");
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
        if (postImageCaptureDir != null) {
            AutomationLogger.log(this, "朋友圈原生保存已启动，停止媒体候选点击");
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
            if (pendingPostContextRecord != null) {
                finishPostCaptureWithoutMedia("no_media_opened_from_visible_candidates");
                return;
            }
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
        long startedMs = System.currentTimeMillis();
        AutomationStore.setPostMediaOpenPending(this, candidateIndex, startedMs);
        scheduleAssumeViewerActivityWake(candidateIndex, startedMs, POST_MEDIA_OPEN_ACTIVITY_WAKE_MS);
        if (!tap(x, y)) {
            AutomationStore.clearPostMediaOpenPending(this);
            cancelAssumeViewerActivityWake();
            failWorkflow("点开朋友圈媒体候选点击失败");
            return;
        }

        handler.postDelayed(() -> afterPostMediaCandidateTap(candidateIndex), 1700);
        scheduleAutomationWake("朋友圈媒体候选点击后检查", 1900);
    }

    private void afterPostMediaCandidateTap(int candidateIndex) {
        if (!workflowRunning) {
            return;
        }
        if (postImageCaptureDir != null) {
            AutomationLogger.log(this, "朋友圈原生保存已启动，忽略媒体候选回调");
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
            AutomationStore.clearPostMediaOpenPending(this);
            startPostImageCaptureAfterViewerCheck();
            return;
        }

        if (shouldBackAfterFailedMediaTap()) {
            AutomationLogger.log(this, "媒体候选可能进入非浏览器页面，先返回再试下一个: "
                + currentWechatClassHints());
            AutomationStore.clearPostMediaOpenPending(this);
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
        AutomationStore.clearPostMediaOpenPending(this);
        openLatestVisiblePostMedia(candidateIndex + 1);
    }

    private boolean shouldTryOpenMediaFromCurrentWechatSurface() {
        String classes = currentWechatClassHints();
        return isWechatActive()
            && !shouldBackAfterFailedMediaTap()
            && !classes.contains("launcherui")
            && (classes.contains("snstimeline")
                || classes.contains("improvesnstimeline")
                || classes.contains("recyclerview")
                || classes.contains("朋友圈"));
    }

    private boolean shouldBackAfterFailedMediaTap() {
        String classes = currentWechatClassHints();
        return classes.contains("landingpage")
            || classes.contains("dynamiccanvas")
            || classes.contains("webview")
            || classes.contains("appbrand")
            || classes.contains("小程序")
            || classes.contains("snsuser")
            || classes.contains("contactinfo")
            || classes.contains("contactlabel")
            || classes.contains("contactremark")
            || classes.contains("profile")
            || classes.contains("label")
            || classes.contains("tag")
            || classes.contains("客户")
            || classes.contains("标签")
            || classes.contains("snssingletextview")
            || classes.contains("全文");
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
        AutomationStore.clearPostMediaOpenPending(this);
        cancelAssumeViewerActivityWake();
        resetNativeCopyState();
        pendingPostContextRecord = null;
        pendingPostContextScreenshotPath = "";
        postImageCaptureDir = null;
        currentNativeSaveLikelyVideo = false;
        workflowRunning = false;
        transition(Stage.COMPLETED, status);
        AutomationLogger.log(this, "流程结束: " + status);
        AutomationStore.clearCommand(this, status);
    }

    private void failWorkflow(String reason) {
        AutomationStore.clearPostMediaOpenPending(this);
        cancelAssumeViewerActivityWake();
        resetNativeCopyState();
        pendingPostContextRecord = null;
        pendingPostContextScreenshotPath = "";
        postImageCaptureDir = null;
        currentNativeSaveLikelyVideo = false;
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

    private void captureMediaFingerprint(String reason, MediaFingerprintCallback callback) {
        if (Build.VERSION.SDK_INT < 30) {
            callback.onFailure("screenshot_unsupported");
            return;
        }
        captureScreenshotBitmap(reason, new ScreenshotBitmapCallback() {
            @Override
            public void onSuccess(Bitmap bitmap) {
                try {
                    callback.onSuccess(mediaFingerprint(bitmap));
                } finally {
                    bitmap.recycle();
                }
            }

            @Override
            public void onFailure(String error) {
                callback.onFailure(error);
            }
        });
    }

    private long mediaFingerprint(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int left = Math.round(width * 0.10f);
        int top = Math.round(height * 0.14f);
        int right = Math.round(width * 0.90f);
        int bottom = Math.round(height * 0.86f);
        int cellW = Math.max(1, (right - left) / 8);
        int cellH = Math.max(1, (bottom - top) / 8);

        int[] values = new int[64];
        int total = 0;
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int x = Math.max(0, Math.min(width - 1, left + col * cellW + cellW / 2));
                int y = Math.max(0, Math.min(height - 1, top + row * cellH + cellH / 2));
                int pixel = bitmap.getPixel(x, y);
                int red = (pixel >> 16) & 0xff;
                int green = (pixel >> 8) & 0xff;
                int blue = pixel & 0xff;
                int luminance = (red * 299 + green * 587 + blue * 114) / 1000;
                values[row * 8 + col] = luminance;
                total += luminance;
            }
        }

        int average = total / values.length;
        long fingerprint = 0L;
        for (int i = 0; i < values.length; i++) {
            if (values[i] >= average) {
                fingerprint |= (1L << i);
            }
        }
        return fingerprint;
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

    private interface MediaFingerprintCallback {
        void onSuccess(long fingerprint);

        void onFailure(String error);
    }

    private static final class PostTextNode {
        final int index;
        final String text;
        final String className;
        final String viewId;
        final int left;
        final int top;
        final int right;
        final int bottom;

        PostTextNode(
            int index,
            String text,
            String className,
            String viewId,
            int left,
            int top,
            int right,
            int bottom
        ) {
            this.index = index;
            this.text = text;
            this.className = className;
            this.viewId = viewId;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
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
