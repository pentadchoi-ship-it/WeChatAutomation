package com.perrychoi.wechatmomentscontroller;

import android.content.Context;
import android.content.SharedPreferences;

final class AutomationStore {
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
    private static final String KEY_POINT_KEY = "point_key";
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
        requestPostImageCapture(context, imageCount, false);
    }

    static void requestPostImageCapture(Context context, int imageCount, boolean assumeViewer) {
        prefs(context)
            .edit()
            .putString(KEY_COMMAND, COMMAND_WECHAT_POST_IMAGE_CAPTURE)
            .putInt(KEY_POST_IMAGE_COUNT, clampPostImageCount(imageCount))
            .putBoolean(KEY_POST_IMAGE_ASSUME_VIEWER, assumeViewer)
            .putBoolean(KEY_STOP_REQUESTED, false)
            .putString(KEY_LAST_STATUS, "已请求保存朋友圈图片")
            .apply();
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
