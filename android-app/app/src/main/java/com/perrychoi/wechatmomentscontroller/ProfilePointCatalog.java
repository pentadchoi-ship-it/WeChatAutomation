package com.perrychoi.wechatmomentscontroller;

final class ProfilePointCatalog {
    static final PointSpec[] POINTS = new PointSpec[]{
        new PointSpec("discover_tab", "发现 tab", 710, 2246),
        new PointSpec("moments_entry", "朋友圈入口", 215, 290),
        new PointSpec("moments_camera", "朋友圈相机", 990, 185),
        new PointSpec("choose_from_album", "从手机相册选择", 840, 2080),
        new PointSpec("first_photo_check", "第一张图选择圈", 215, 293),
        new PointSpec("album_done", "相册完成", 940, 2248),
        new PointSpec("compose_text", "编辑页文字区", 245, 337),
        new PointSpec("compose_paste", "粘贴菜单", 150, 265)
    };

    private ProfilePointCatalog() {
    }

    static PointSpec find(String key) {
        if (key == null) {
            return null;
        }

        for (PointSpec point : POINTS) {
            if (point.key.equals(key)) {
                return point;
            }
        }
        return null;
    }

    static final class PointSpec {
        final String key;
        final String label;
        final int fallbackX;
        final int fallbackY;

        PointSpec(String key, String label, int fallbackX, int fallbackY) {
            this.key = key;
            this.label = label;
            this.fallbackX = fallbackX;
            this.fallbackY = fallbackY;
        }
    }
}
