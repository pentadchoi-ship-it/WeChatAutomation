package com.perrychoi.wechatmomentscontroller;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class AutomationLogger {
    private static final String PREFS = "automation_logs";
    private static final String KEY_LINES = "lines";
    private static final int MAX_CHARS = 12000;

    private AutomationLogger() {
    }

    static void log(Context context, String message) {
        String now = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        SharedPreferences prefs = prefs(context);
        String old = prefs.getString(KEY_LINES, "");
        String next = old + now + "  " + message + "\n";
        if (next.length() > MAX_CHARS) {
            next = next.substring(next.length() - MAX_CHARS);
        }
        prefs.edit().putString(KEY_LINES, next).apply();
    }

    static String lines(Context context) {
        return prefs(context).getString(KEY_LINES, "");
    }

    static void clear(Context context) {
        prefs(context).edit().remove(KEY_LINES).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

