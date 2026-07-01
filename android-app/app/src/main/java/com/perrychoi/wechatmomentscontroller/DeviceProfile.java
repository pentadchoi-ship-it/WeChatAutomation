package com.perrychoi.wechatmomentscontroller;

import android.content.Context;
import android.os.Build;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class DeviceProfile {
    private static final String ASSET_NAME = "device_profiles.json";

    final String id;
    final int baselineWidth;
    final int baselineHeight;
    private final JSONObject points;

    private DeviceProfile(String id, int baselineWidth, int baselineHeight, JSONObject points) {
        this.id = id;
        this.baselineWidth = baselineWidth;
        this.baselineHeight = baselineHeight;
        this.points = points;
    }

    static DeviceProfile load(Context context, DisplayMetrics metrics) {
        try {
            JSONObject root = new JSONObject(readAsset(context));
            JSONArray profiles = root.getJSONArray("profiles");
            JSONObject fallback = null;
            JSONObject screenFallback = null;
            String defaultId = root.optString("defaultProfile", "");

            for (int i = 0; i < profiles.length(); i++) {
                JSONObject candidate = profiles.getJSONObject(i);
                if (defaultId.equals(candidate.optString("id"))) {
                    fallback = candidate;
                }
                if (screenFallback == null && screenMatches(candidate, metrics)) {
                    screenFallback = candidate;
                }
                if (matches(candidate, metrics)) {
                    return fromJson(candidate);
                }
            }

            if (screenFallback != null) {
                return fromJson(screenFallback);
            }
            if (fallback == null && profiles.length() > 0) {
                fallback = profiles.getJSONObject(0);
            }
            if (fallback != null) {
                return fromJson(fallback);
            }
        } catch (IOException | JSONException e) {
            AutomationLogger.log(context, "设备 Profile 加载失败: " + e.getClass().getSimpleName()
                + " " + e.getMessage());
        }

        return new DeviceProfile("built_in_fallback", 1080, 2340, new JSONObject());
    }

    int[] point(String key, int fallbackX, int fallbackY, DisplayMetrics metrics) {
        int x = fallbackX;
        int y = fallbackY;
        JSONArray value = points.optJSONArray(key);
        if (value != null && value.length() >= 2) {
            x = value.optInt(0, fallbackX);
            y = value.optInt(1, fallbackY);
        }

        int sourceWidth = Math.max(2, baselineWidth);
        int sourceHeight = Math.max(2, baselineHeight);
        int targetWidth = Math.max(2, metrics.widthPixels);
        int targetHeight = Math.max(2, metrics.heightPixels);
        int scaledX = Math.round((x / (float) (sourceWidth - 1)) * (targetWidth - 1));
        int scaledY = Math.round((y / (float) (sourceHeight - 1)) * (targetHeight - 1));
        return new int[]{scaledX, scaledY};
    }

    String summary() {
        return id + " baseline=" + baselineWidth + "x" + baselineHeight;
    }

    private static DeviceProfile fromJson(JSONObject value) {
        return new DeviceProfile(
            value.optString("id", "unknown"),
            value.optInt("baselineWidth", value.optInt("width", 1080)),
            value.optInt("baselineHeight", value.optInt("height", 2340)),
            value.optJSONObject("points") == null ? new JSONObject() : value.optJSONObject("points")
        );
    }

    private static boolean matches(JSONObject candidate, DisplayMetrics metrics) {
        String manufacturer = candidate.optString("manufacturer", "");
        String model = candidate.optString("model", "");

        boolean deviceMatches = manufacturer.equalsIgnoreCase(Build.MANUFACTURER)
            && model.equalsIgnoreCase(Build.MODEL);
        return deviceMatches && screenMatches(candidate, metrics);
    }

    private static boolean screenMatches(JSONObject candidate, DisplayMetrics metrics) {
        int width = candidate.optInt("width", 0);
        int height = candidate.optInt("height", 0);
        return width == metrics.widthPixels && height == metrics.heightPixels;
    }

    private static String readAsset(Context context) throws IOException {
        try (InputStream in = context.getAssets().open(ASSET_NAME);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }
}
