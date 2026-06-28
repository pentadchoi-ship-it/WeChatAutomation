# Wechat Moments Controller MVP

这是 Android 端 MVP，目标是替代 Pico 坐标宏，使用 AccessibilityService 做更可靠的微信 UI 自动化。

当前能力：

- 从微信首页开始。
- 点击“发现”。
- 点击“朋友圈”。
- 点击右上角摄像头。
- 点击“从手机相册选择”。
- 可继续选择第一张图片并填写朋友圈文字。
- 节点文字/描述优先，设备坐标兜底。
- 支持只监听不点击的诊断模式。
- 日志记录、停止开关。
- 不自动发表内容。

## 使用步骤

1. 用 Android Studio 打开 `android-app/`。
2. 连接华为 P30，安装运行 `app`。
3. 在 App 里点击“打开无障碍设置”，启用“朋友圈自动化辅助服务”。
4. 如果微信出现掉线/异常，先点“只监听微信 30 秒（不操作）”，再手动打开微信等待。
5. 如果监听诊断正常，回到 App 点“从微信首页开始四步测试”或“完整测试：选图并填写文字”。
6. 手动打开微信并停在首页，等待 3 秒后流程执行。
7. 完整测试会停在发表前，最终发表必须人工确认。

## 本地开发环境

当前机器已准备好用户目录内的 Android 开发环境：

- JDK 17：`~/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home`
- Android SDK：`~/Library/Android/sdk`
- 已安装 SDK 包：`platform-tools`、`platforms;android-35`、`build-tools;35.0.0`、`build-tools;34.0.0`
- 项目内 Gradle wrapper：`./gradlew`

从仓库根目录进入环境并构建：

```sh
source tools/android_env.sh
cd android-app
./gradlew assembleDebug
```

连接 P30 后安装 debug APK：

```sh
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以从仓库根目录直接构建并安装到当前授权设备：

```sh
tools/install_android_app.sh
```

如果连接多台设备，传入设备序列号：

```sh
tools/install_android_app.sh <adb-device-id>
```

开发调试时可以直接用 ADB 写入完整测试命令：

```sh
adb shell am start -n com.perrychoi.wechatmomentscontroller/.MainActivity \
  --es workflow compose \
  --es moment_text "自动化测试，请忽略"
```

## 开发原则

- 每一步都应先判断页面状态，再执行动作。
- 能点击 UI 节点就不点坐标。
- 坐标只做兜底，并按设备 profile 管理。
- 发布前保留人工确认，避免误发。
- 失败时保留日志和截图，方便调整。
