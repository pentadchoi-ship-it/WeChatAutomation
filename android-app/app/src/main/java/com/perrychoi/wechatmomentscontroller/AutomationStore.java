package com.perrychoi.wechatmomentscontroller;

import android.content.Context;
import android.content.SharedPreferences;

final class AutomationStore {
    static final String COMMAND_NONE = "none";
    static final String COMMAND_WECHAT_OBSERVE_ONLY = "wechat_observe_only";
    static final String COMMAND_WECHAT_ALBUM_TEST = "wechat_album_test";
    static final String COMMAND_WECHAT_COMPOSE_TEST = "wechat_compose_test";

    private static final String PREFS = "automation";
    private static final String KEY_COMMAND = "command";
    private static final String KEY_STOP_REQUESTED = "stop_requested";
    private static final String KEY_LAST_STATUS = "last_status";
    private static final String KEY_MOMENT_TEXT = "moment_text";
    private static final String DEFAULT_MOMENT_TEXT = "自动化测试，请忽略";

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

    static String momentText(Context context) {
        return prefs(context).getString(KEY_MOMENT_TEXT, DEFAULT_MOMENT_TEXT);
    }

    static String defaultMomentText() {
        return DEFAULT_MOMENT_TEXT;
    }

    private static String cleanMomentText(String value) {
        if (value == null || value.trim().isEmpty()) {
            return DEFAULT_MOMENT_TEXT;
        }

        return value.trim();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
