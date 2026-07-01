package com.perrychoi.wechatmomentscontroller;

import android.content.Context;
import android.content.Intent;

final class CommandIntentHandler {
    static final String ACTION_COMMAND = "com.perrychoi.wechatmomentscontroller.COMMAND";

    private static final String EXTRA_WORKFLOW = "workflow";
    private static final String EXTRA_IMAGE_COUNT = "image_count";
    private static final String EXTRA_ASSUME_VIEWER = "assume_viewer";
    private static final String EXTRA_MAX_PAGES = "max_pages";
    private static final String EXTRA_MOMENT_TEXT = "moment_text";

    private static final String WORKFLOW_CAPTURE_IMAGES = "capture_images";
    private static final String WORKFLOW_COLLECT = "collect";
    private static final String WORKFLOW_COMPOSE = "compose";
    private static final String WORKFLOW_ALBUM = "album";
    private static final String WORKFLOW_STOP = "stop";

    private CommandIntentHandler() {
    }

    static boolean handle(Context context, Intent intent, String source) {
        if (intent == null) {
            return false;
        }

        String workflow = intent.getStringExtra(EXTRA_WORKFLOW);
        if (WORKFLOW_STOP.equals(workflow)) {
            AutomationLogger.log(context, source + " 请求停止并清空命令");
            AutomationStore.requestStop(context);
            AutomationStore.clearCommand(context, source + " 已停止自动化");
            return true;
        }

        if (WORKFLOW_CAPTURE_IMAGES.equals(workflow)) {
            int count = intent.getIntExtra(
                EXTRA_IMAGE_COUNT,
                AutomationStore.defaultPostImageCount()
            );
            boolean assumeViewer = intent.getBooleanExtra(EXTRA_ASSUME_VIEWER, false);
            AutomationLogger.log(context, source + " 请求原生保存朋友圈图片/视频: count="
                + count + " assumeViewer=" + assumeViewer);
            AutomationStore.requestPostImageCapture(context, count, assumeViewer);
            return true;
        }

        if (WORKFLOW_COLLECT.equals(workflow)) {
            int pages = intent.getIntExtra(
                EXTRA_MAX_PAGES,
                AutomationStore.defaultMomentCollectPages()
            );
            AutomationLogger.log(context, source + " 请求采集朋友圈素材: pages=" + pages);
            AutomationStore.requestMomentCollection(context, pages);
            return true;
        }

        if (WORKFLOW_ALBUM.equals(workflow)) {
            AutomationLogger.log(context, source + " 请求启动四步测试");
            AutomationStore.requestWorkflow(context, AutomationStore.COMMAND_WECHAT_ALBUM_TEST);
            return true;
        }

        if (WORKFLOW_COMPOSE.equals(workflow)) {
            String text = intent.getStringExtra(EXTRA_MOMENT_TEXT);
            AutomationLogger.log(context, source + " 请求启动完整测试");
            AutomationStore.requestWorkflow(context, AutomationStore.COMMAND_WECHAT_COMPOSE_TEST, text);
            return true;
        }

        AutomationLogger.log(context, source + " 请求忽略: 未知 workflow=" + workflow);
        return false;
    }
}
