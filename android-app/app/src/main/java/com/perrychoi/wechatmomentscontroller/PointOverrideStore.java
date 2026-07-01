package com.perrychoi.wechatmomentscontroller;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;

final class PointOverrideStore {
    private static final String PREFS = "point_overrides";
    private static final String KEY_X_SUFFIX = ".x";
    private static final String KEY_Y_SUFFIX = ".y";

    private PointOverrideStore() {
    }

    static boolean hasPointOverride(
        Context context,
        DeviceProfile profile,
        DisplayMetrics metrics,
        String pointKey
    ) {
        String prefix = keyPrefix(profile, metrics, pointKey);
        SharedPreferences prefs = prefs(context);
        return prefs.contains(prefix + KEY_X_SUFFIX) && prefs.contains(prefix + KEY_Y_SUFFIX);
    }

    static Coordinate pointOverride(
        Context context,
        DeviceProfile profile,
        DisplayMetrics metrics,
        String pointKey
    ) {
        if (!hasPointOverride(context, profile, metrics, pointKey)) {
            return null;
        }

        String prefix = keyPrefix(profile, metrics, pointKey);
        int maxX = Math.max(0, metrics.widthPixels - 1);
        int maxY = Math.max(0, metrics.heightPixels - 1);
        int x = clamp(prefs(context).getInt(prefix + KEY_X_SUFFIX, 0), 0, maxX);
        int y = clamp(prefs(context).getInt(prefix + KEY_Y_SUFFIX, 0), 0, maxY);
        return new Coordinate(x, y);
    }

    static void setPointOverride(
        Context context,
        DeviceProfile profile,
        DisplayMetrics metrics,
        String pointKey,
        int x,
        int y
    ) {
        String prefix = keyPrefix(profile, metrics, pointKey);
        int maxX = Math.max(0, metrics.widthPixels - 1);
        int maxY = Math.max(0, metrics.heightPixels - 1);
        prefs(context)
            .edit()
            .putInt(prefix + KEY_X_SUFFIX, clamp(x, 0, maxX))
            .putInt(prefix + KEY_Y_SUFFIX, clamp(y, 0, maxY))
            .apply();
    }

    static void clearPointOverride(
        Context context,
        DeviceProfile profile,
        DisplayMetrics metrics,
        String pointKey
    ) {
        String prefix = keyPrefix(profile, metrics, pointKey);
        prefs(context)
            .edit()
            .remove(prefix + KEY_X_SUFFIX)
            .remove(prefix + KEY_Y_SUFFIX)
            .apply();
    }

    static void clearAllPointOverrides(Context context, DeviceProfile profile, DisplayMetrics metrics) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (ProfilePointCatalog.PointSpec point : ProfilePointCatalog.POINTS) {
            String prefix = keyPrefix(profile, metrics, point.key);
            editor.remove(prefix + KEY_X_SUFFIX);
            editor.remove(prefix + KEY_Y_SUFFIX);
        }
        editor.apply();
    }

    static Coordinate effectivePoint(
        Context context,
        DeviceProfile profile,
        DisplayMetrics metrics,
        ProfilePointCatalog.PointSpec point
    ) {
        Coordinate override = pointOverride(context, profile, metrics, point.key);
        if (override != null) {
            return override;
        }

        int[] xy = profile.point(point.key, point.fallbackX, point.fallbackY, metrics);
        return new Coordinate(xy[0], xy[1]);
    }

    static String pointSource(
        Context context,
        DeviceProfile profile,
        DisplayMetrics metrics,
        String pointKey
    ) {
        return hasPointOverride(context, profile, metrics, pointKey) ? "override" : "profile";
    }

    private static String keyPrefix(DeviceProfile profile, DisplayMetrics metrics, String pointKey) {
        String profileId = profile == null ? "unknown" : profile.id;
        int width = metrics == null ? 0 : metrics.widthPixels;
        int height = metrics == null ? 0 : metrics.heightPixels;
        return profileId + ":" + width + "x" + height + ":" + (pointKey == null ? "" : pointKey);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static final class Coordinate {
        final int x;
        final int y;

        Coordinate(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }
}
