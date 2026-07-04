package com.perrychoi.wechatmomentscontroller;

import android.content.Context;
import android.content.SharedPreferences;

final class AutomationStore {
    static final String ACTION_READ_CLIPBOARD =
        "com.perrychoi.wechatmomentscontroller.READ_CLIPBOARD";

    static final String COMMAND_NONE = "none";
    static final String COMMAND_WECHAT_OBSERVE_ONLY = "wechat_observe_only";
    static final String COMMAND_WECHAT_ALBUM_TEST = "wechat_album_test";
    static final String COMMAND_WECHAT_COMPOSE_TEST = "wechat_compose_test";
    static final String COMMAND_WECHAT_MOMENTS_COLLECT = "wechat_moments_collect";
    static final String COMMAND_WECHAT_POST_IMAGE_CAPTURE = "wechat_post_image_capture";
    static final String COMMAND_POINT_TAP_TEST = "point_tap_test";

    private static final String PREFS = "automation";
    private static final String KEY_COMMAND = "command";
    private static final String KEY_STOP_REQUESTED = "stop_requested";
    private static final String KEY_LAST_STATUS = "last_status";
    private static final String KEY_LAST_DIAGNOSTIC = "last_diagnostic";
    private static final String KEY_LAST_SCREENSHOT = "last_screenshot";
    private static final String KEY_LAST_EXPORT = "last_export";
    private static final String KEY_MOMENT_TEXT = "moment_text";
    private static final String KEY_MOMENT_COLLECT_PAGES = "moment_collect_pages";
    private static final String KEY_POST_IMAGE_COUNT = "post_image_count";
    private static final String KEY_POST_IMAGE_ASSUME_VIEWER = "post_image_assume_viewer";
    private static final String KEY_POST_CONTEXT_CAPTURE_TEXT = "post_context_capture_text";
    private static final String KEY_POINT_KEY = "point_key";
    private static final String KEY_NATIVE_COPY_PHASE = "native_copy_phase";
    private static final String KEY_NATIVE_COPY_SOURCE = "native_copy_source";
    private static final String KEY_NATIVE_COPY_SENTINEL = "native_copy_sentinel";
    private static final String KEY_NATIVE_COPY_ATTEMPTS = "native_copy_attempts";
    private static final String KEY_NATIVE_COPY_LAST_ACTION_MS = "native_copy_last_action_ms";
    private static final String KEY_NATIVE_COPY_TEXT = "native_copy_text";
    private static final String KEY_NATIVE_COPY_SCRIPT_COPIED = "native_copy_script_copied";
    private static final String KEY_POST_MEDIA_OPEN_PENDING = "post_media_open_pending";
    private static final String KEY_POST_MEDIA_OPEN_STARTED_MS = "post_media_open_started_ms";
    private static final String KEY_POST_MEDIA_OPEN_CANDIDATE = "post_media_open_candidate";
    private static final String KEY_AUTOMATION_WAKE_TICK = "automation_wake_tick";
    private static final String DEFAULT_MOMENT_TEXT = "自动化测试，请忽略";
    private static final int DEFAULT_MOMENT_COLLECT_PAGES = 6;
    private static final int MIN_MOMENT_COLLECT_PAGES = 1;
    private static final int MAX_MOMENT_COLLECT_PAGES = 30;
    private static final int DEFAULT_POST_IMAGE_COUNT = 9;
    private static final int MIN_POST_IMAGE_COUNT = 1;
    private static final int MAX_POST_IMAGE_COUNT = 30;

    private AutomationStore() {
    }

    static void requestWorkflow(Context context, String command) {
        prefs(context)
            .edit()
            .putString(KEY_COMMAND, command)
            .putBoolean(KEY_STOP_REQUESTED, false)
            .putString(KEY_LAST_STATUS, "已请求执行: " + command)
            .apply();
    }

    static void requestWorkflow(Context context, String command, String momentText) {
        prefs(context)
            .edit()
            .putString(KEY_COMMAND, command)
            .putString(KEY_MOMENT_TEXT, cleanMomentText(momentText))
            .putBoolean(KEY_STOP_REQUESTED, false)
            .putString(KEY_LAST_STATUS, "已请求执行: " + command)
            .apply();
    }

    static void requestMomentCollection(Context context, int pages) {
        prefs(context)
            .edit()
            .putString(KEY_COMMAND, COMMAND_WECHAT_MOMENTS_COLLECT)
            .putInt(KEY_MOMENT_COLLECT_PAGES, clampMomentCollectPages(pages))
            .putBoolean(KEY_STOP_REQUESTED, false)
            .putString(KEY_LAST_STATUS, "已请求采集朋友圈素材")
            .apply();
    }

    static void requestPostImageCapture(Context context, int imageCount) {
        requestPostImageCapture(context, imageCount, false, true);
    }

    static void requestPostImageCapture(Context context, int imageCount, boolean assumeViewer) {
        requestPostImageCapture(context, imageCount, assumeViewer, true);
    }

    static void requestPostImageCapture(
        Context context,
        int imageCount,
        boolean assumeViewer,
        boolean captureText
    ) {
        SharedPreferences.Editor editor = prefs(context)
            .edit()
            .putString(KEY_COMMAND, COMMAND_WECHAT_POST_IMAGE_CAPTURE)
            .putInt(KEY_POST_IMAGE_COUNT, clampPostImageCount(imageCount))
            .putBoolean(KEY_POST_IMAGE_ASSUME_VIEWER, assumeViewer)
            .putBoolean(KEY_POST_CONTEXT_CAPTURE_TEXT, captureText)
            .putBoolean(KEY_STOP_REQUESTED, false)
            .remove(KEY_NATIVE_COPY_PHASE)
            .remove(KEY_NATIVE_COPY_SOURCE)
            .remove(KEY_NATIVE_COPY_SENTINEL)
            .remove(KEY_NATIVE_COPY_ATTEMPTS)
            .remove(KEY_NATIVE_COPY_LAST_ACTION_MS)
            .remove(KEY_NATIVE_COPY_SCRIPT_COPIED)
            .remove(KEY_POST_MEDIA_OPEN_PENDING)
            .remove(KEY_POST_MEDIA_OPEN_STARTED_MS)
            .remove(KEY_POST_MEDIA_OPEN_CANDIDATE)
            .putString(KEY_LAST_STATUS, captureText
                ? "已请求保存朋友圈图文"
                : "已请求保存朋友圈图片");
        if (!assumeViewer) {
            editor.remove(KEY_NATIVE_COPY_TEXT);
        }
        editor.apply();
    }

    static void requestPointTap(Context context, String pointKey) {
        prefs(context)
            .edit()
            .putString(KEY_COMMAND, COMMAND_POINT_TAP_TEST)
            .putString(KEY_POINT_KEY, pointKey == null ? "" : pointKey)
            .putBoolean(KEY_STOP_REQUESTED, false)
            .putString(KEY_LAST_STATUS, "已请求点位测试: " + pointKey)
            .apply();
    }

    static String command(Context context) {
        return prefs(context).getString(KEY_COMMAND, COMMAND_NONE);
    }

    static void clearCommand(Context context, String status) {
        prefs(context)
            .edit()
            .putString(KEY_COMMAND, COMMAND_NONE)
            .putString(KEY_LAST_STATUS, status)
            .remove(KEY_NATIVE_COPY_PHASE)
            .remove(KEY_NATIVE_COPY_SOURCE)
            .remove(KEY_NATIVE_COPY_SENTINEL)
            .remove(KEY_NATIVE_COPY_ATTEMPTS)
            .remove(KEY_NATIVE_COPY_LAST_ACTION_MS)
            .remove(KEY_NATIVE_COPY_TEXT)
            .remove(KEY_NATIVE_COPY_SCRIPT_COPIED)
            .remove(KEY_POST_MEDIA_OPEN_PENDING)
            .remove(KEY_POST_MEDIA_OPEN_STARTED_MS)
            .remove(KEY_POST_MEDIA_OPEN_CANDIDATE)
            .apply();
    }

    static void requestStop(Context context) {
        prefs(context)
            .edit()
            .putBoolean(KEY_STOP_REQUESTED, true)
            .putString(KEY_LAST_STATUS, "已请求停止")
            .apply();
    }

    static boolean stopRequested(Context context) {
        return prefs(context).getBoolean(KEY_STOP_REQUESTED, false);
    }

    static boolean hasCommand(Context context, String command) {
        return command.equals(command(context));
    }

    static void registerListener(
        Context context,
        SharedPreferences.OnSharedPreferenceChangeListener listener
    ) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener);
    }

    static void unregisterListener(
        Context context,
        SharedPreferences.OnSharedPreferenceChangeListener listener
    ) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener);
    }

    static String lastStatus(Context context) {
        return prefs(context).getString(KEY_LAST_STATUS, "空闲");
    }

    static void setDiagnostic(Context context, String diagnostic) {
        prefs(context)
            .edit()
            .putString(KEY_LAST_DIAGNOSTIC, diagnostic)
            .apply();
    }

    static String lastDiagnostic(Context context) {
        return prefs(context).getString(KEY_LAST_DIAGNOSTIC, "暂无诊断");
    }

    static void setLastScreenshotPath(Context context, String path) {
        prefs(context)
            .edit()
            .putString(KEY_LAST_SCREENSHOT, path)
            .apply();
    }

    static String lastScreenshotPath(Context context) {
        return prefs(context).getString(KEY_LAST_SCREENSHOT, "");
    }

    static void setLastExportPath(Context context, String path) {
        prefs(context)
            .edit()
            .putString(KEY_LAST_EXPORT, path)
            .apply();
    }

    static String lastExportPath(Context context) {
        return prefs(context).getString(KEY_LAST_EXPORT, "");
    }

    static String momentText(Context context) {
        return prefs(context).getString(KEY_MOMENT_TEXT, DEFAULT_MOMENT_TEXT);
    }

    static String pointKey(Context context) {
        return prefs(context).getString(KEY_POINT_KEY, "");
    }

    static int momentCollectPages(Context context) {
        return clampMomentCollectPages(
            prefs(context).getInt(KEY_MOMENT_COLLECT_PAGES, DEFAULT_MOMENT_COLLECT_PAGES)
        );
    }

    static int postImageCount(Context context) {
        return clampPostImageCount(
            prefs(context).getInt(KEY_POST_IMAGE_COUNT, DEFAULT_POST_IMAGE_COUNT)
        );
    }

    static boolean postImageAssumeViewer(Context context) {
        return prefs(context).getBoolean(KEY_POST_IMAGE_ASSUME_VIEWER, false);
    }

    static boolean postContextCaptureText(Context context) {
        return prefs(context).getBoolean(KEY_POST_CONTEXT_CAPTURE_TEXT, true);
    }

    static int nativeCopyPhase(Context context) {
        return prefs(context).getInt(KEY_NATIVE_COPY_PHASE, 0);
    }

    static String nativeCopySource(Context context) {
        return prefs(context).getString(KEY_NATIVE_COPY_SOURCE, "");
    }

    static String nativeCopySentinel(Context context) {
        return prefs(context).getString(KEY_NATIVE_COPY_SENTINEL, "");
    }

    static int nativeCopyAttempts(Context context) {
        return prefs(context).getInt(KEY_NATIVE_COPY_ATTEMPTS, 0);
    }

    static long nativeCopyLastActionMs(Context context) {
        return prefs(context).getLong(KEY_NATIVE_COPY_LAST_ACTION_MS, 0L);
    }

    static String nativeCopyText(Context context) {
        return prefs(context).getString(KEY_NATIVE_COPY_TEXT, "");
    }

    static void markNativeCopyScriptCopied(Context context) {
        prefs(context)
            .edit()
            .putBoolean(KEY_NATIVE_COPY_SCRIPT_COPIED, true)
            .apply();
        bumpAutomationWakeTick(context);
    }

    static boolean nativeCopyScriptCopied(Context context) {
        return prefs(context).getBoolean(KEY_NATIVE_COPY_SCRIPT_COPIED, false);
    }

    static void clearNativeCopyScriptCopied(Context context) {
        prefs(context)
            .edit()
            .remove(KEY_NATIVE_COPY_SCRIPT_COPIED)
            .apply();
    }

    static void setNativeCopyState(
        Context context,
        int phase,
        String source,
        String sentinel,
        int attempts,
        long lastActionMs
    ) {
        prefs(context)
            .edit()
            .putInt(KEY_NATIVE_COPY_PHASE, phase)
            .putString(KEY_NATIVE_COPY_SOURCE, source == null ? "" : source)
            .putString(KEY_NATIVE_COPY_SENTINEL, sentinel == null ? "" : sentinel)
            .putInt(KEY_NATIVE_COPY_ATTEMPTS, attempts)
            .putLong(KEY_NATIVE_COPY_LAST_ACTION_MS, lastActionMs)
            .apply();
    }

    static void setNativeCopyText(Context context, String text) {
        prefs(context)
            .edit()
            .putString(KEY_NATIVE_COPY_TEXT, text == null ? "" : text)
            .apply();
    }

    static void clearNativeCopyState(Context context) {
        prefs(context)
            .edit()
            .remove(KEY_NATIVE_COPY_PHASE)
            .remove(KEY_NATIVE_COPY_SOURCE)
            .remove(KEY_NATIVE_COPY_SENTINEL)
            .remove(KEY_NATIVE_COPY_ATTEMPTS)
            .remove(KEY_NATIVE_COPY_LAST_ACTION_MS)
            .remove(KEY_NATIVE_COPY_SCRIPT_COPIED)
            .apply();
    }

    static void setPostMediaOpenPending(Context context, int candidateIndex, long startedMs) {
        prefs(context)
            .edit()
            .putBoolean(KEY_POST_MEDIA_OPEN_PENDING, true)
            .putLong(KEY_POST_MEDIA_OPEN_STARTED_MS, startedMs)
            .putInt(KEY_POST_MEDIA_OPEN_CANDIDATE, candidateIndex)
            .apply();
    }

    static boolean postMediaOpenPending(Context context) {
        return prefs(context).getBoolean(KEY_POST_MEDIA_OPEN_PENDING, false);
    }

    static long postMediaOpenStartedMs(Context context) {
        return prefs(context).getLong(KEY_POST_MEDIA_OPEN_STARTED_MS, 0L);
    }

    static int postMediaOpenCandidate(Context context) {
        return prefs(context).getInt(KEY_POST_MEDIA_OPEN_CANDIDATE, 0);
    }

    static void clearPostMediaOpenPending(Context context) {
        prefs(context)
            .edit()
            .remove(KEY_POST_MEDIA_OPEN_PENDING)
            .remove(KEY_POST_MEDIA_OPEN_STARTED_MS)
            .remove(KEY_POST_MEDIA_OPEN_CANDIDATE)
            .apply();
    }

    static void bumpAutomationWakeTick(Context context) {
        prefs(context)
            .edit()
            .putLong(KEY_AUTOMATION_WAKE_TICK, System.currentTimeMillis())
            .apply();
    }

    static boolean isAutomationWakeKey(String key) {
        return KEY_AUTOMATION_WAKE_TICK.equals(key);
    }

    static String defaultMomentText() {
        return DEFAULT_MOMENT_TEXT;
    }

    static int defaultMomentCollectPages() {
        return DEFAULT_MOMENT_COLLECT_PAGES;
    }

    static int defaultPostImageCount() {
        return DEFAULT_POST_IMAGE_COUNT;
    }

    private static String cleanMomentText(String value) {
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_MOMENT_TEXT;
        }

        return value.trim();
    }

    private static int clampMomentCollectPages(int pages) {
        return Math.max(
            MIN_MOMENT_COLLECT_PAGES,
            Math.min(MAX_MOMENT_COLLECT_PAGES, pages)
        );
    }

    private static int clampPostImageCount(int imageCount) {
        return Math.max(
            MIN_POST_IMAGE_COUNT,
            Math.min(MAX_POST_IMAGE_COUNT, imageCount)
        );
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
