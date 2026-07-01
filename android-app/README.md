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
- 坐标按 `app/src/main/assets/device_profiles.json` 管理，当前包含小米 13 和华为 P30 profile。
- 流程启动时会按当前页面选择起点，支持从发现页、朋友圈页、相册页、编辑页继续。
- 从朋友圈图片/折叠详情页启动时，会先返回到朋友圈主列表再继续。
- 每一步带页面状态检查；识别不到页面时继续兜底，识别到已前进的页面时自动跳步。
- 页面识别包含无障碍节点、窗口类名和轻量截图规则；启动定位会保存页面诊断截图。
- App 内提供“点位测试 / 校准”页，可单独验证、微调并保存本机覆盖坐标。
- 启动前预检微信前台、屏幕信息、微信相册权限。
- 结构化状态日志：等待微信、预检、延迟启动、执行步骤、完成、失败。
- 失败时尝试保存屏幕截图到 App 私有目录。
- 支持只监听不点击的诊断模式。
- 支持朋友圈素材采集 MVP：保存若干屏可见截图和无障碍节点文本/描述到本地。
- 支持单条朋友圈图片/视频原生保存 MVP：手动点开目标朋友圈第一张图/视频后，按指定张数逐个长按并点击微信“保存图片/保存视频”。
- 日志记录、停止开关。
- 不自动发表内容。

## 使用步骤

1. 用 Android Studio 打开 `android-app/`。
2. 连接测试手机（当前已校准小米 13 / 华为 P30），安装运行 `app`。
3. 在 App 里确认“无障碍服务: 已启用”。如果显示未启用，点击“打开无障碍设置”，启用“朋友圈自动化辅助服务”。重装 APK 后小米系统可能会自动关闭这个开关。
4. 如果微信出现掉线/异常，先点“只监听微信 30 秒（不操作）”，再手动打开微信等待。
5. 如果监听诊断正常，回到 App 点“从微信首页开始四步测试”或“完整测试：选图并填写文字”。
6. 如果要保存某条朋友圈里的多张图片/视频，先在微信里打开目标好友那条朋友圈的第一张图片/视频，再用 App 或 ADB 命令启动保存。
7. 如果要采集朋友圈列表素材，设置“朋友圈采集屏数”，点“读取朋友圈并保存素材”，再手动打开微信首页、朋友圈页或某个好友朋友圈页。
8. 手动打开微信并停在首页，等待 3 秒后流程执行。
9. 完整测试会停在发表前，最终发表必须人工确认。

单条朋友圈图片/视频保存完成后，App 首页会显示“最近素材导出”目录。目录内容：

- `manifest.jsonl`：每行一个 JSON，第一行是 session 元信息，后续每行记录一次微信原生保存动作。

实际图片/视频文件由微信保存到系统相册；小米 13 实测图片落在 `/sdcard/Pictures/WeiXin/`，文件名类似 `mmexport*.jpg`。当前 MVP 不读取微信内部数据库/缓存。

朋友圈列表采集完成后，App 首页同样会显示“最近素材导出”目录。目录内容：

- `manifest.jsonl`：每行一个 JSON，第一行是 session 元信息，后续每行对应一屏，包含截图路径、页面识别、可见节点文本/描述/坐标。
- `screen_*.png`：每屏完整截图。

当前采集 MVP 只保存屏幕可见内容，不读取微信内部数据库，不下载朋友圈原图，也不会上传到外部服务。

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

采集朋友圈素材：

```sh
adb shell am start -n com.perrychoi.wechatmomentscontroller/.MainActivity \
  --es workflow collect \
  --ei max_pages 6
```

保存当前朋友圈图片/视频浏览器里的素材，例如九宫格 9 张。推荐用无界面命令入口，
它会立即结束并回到微信，不会像打开 MainActivity 那样长时间占用前台：

```sh
adb shell am start -n com.perrychoi.wechatmomentscontroller/.CommandActivity \
  --es workflow capture_images \
  --ez assume_viewer true \
  --ei image_count 9
```

`assume_viewer=true` 适合已经手动点开目标朋友圈第一张图片/视频的情况，会跳过微信页面类名校验，直接走长按保存。
如果已经在朋友圈列表页，也可以不传这个参数，让服务尝试点开当前可见的第一条媒体；如果误点到广告或链接页，
服务会记录失败并要求手动点开目标朋友圈第一张图/视频后重试。

也保留 MainActivity 入口，方便在 App 页面里查看状态：

```sh
adb shell am start -n com.perrychoi.wechatmomentscontroller/.MainActivity \
  --es workflow capture_images \
  --ei image_count 9
```

清空残留命令：

```sh
adb shell am start -n com.perrychoi.wechatmomentscontroller/.CommandActivity \
  --es workflow stop
```

打开点位测试页：

```sh
adb shell am start -n com.perrychoi.wechatmomentscontroller/.MainActivity \
  --es workflow calibration
```

单独测试某个 profile 点位，随后切到对应微信页面：

```sh
adb shell am start -n com.perrychoi.wechatmomentscontroller/.MainActivity \
  --es workflow tap_point \
  --es point_key moments_camera
```

保存某个本机覆盖坐标：

```sh
adb shell am start -n com.perrychoi.wechatmomentscontroller/.MainActivity \
  --es workflow set_point \
  --es point_key moments_camera \
  --ei x 990 \
  --ei y 190
```

清除某个覆盖坐标：

```sh
adb shell am start -n com.perrychoi.wechatmomentscontroller/.MainActivity \
  --es workflow clear_point \
  --es point_key moments_camera
```

清除本机全部覆盖坐标：

```sh
adb shell am start -n com.perrychoi.wechatmomentscontroller/.MainActivity \
  --es workflow clear_all_points
```

失败截图路径会显示在 App 首页“最近失败截图”。也可以通过 ADB 查看：

```sh
adb shell run-as com.perrychoi.wechatmomentscontroller \
  ls -l files/failure_screenshots
```

如果要导出某张失败截图：

```sh
adb exec-out run-as com.perrychoi.wechatmomentscontroller \
  cat files/failure_screenshots/<filename>.png > /tmp/<filename>.png
```

如果要导出最近一次朋友圈图片/视频原生保存 manifest，先在 App 首页复制“最近素材导出”路径里的目录名，例如 `native_save_20260629_232717`，再导出文件：

```sh
adb exec-out run-as com.perrychoi.wechatmomentscontroller \
  cat files/moments_native_saves/<native_save_dir>/manifest.jsonl > /tmp/native_save_manifest.jsonl
```

如果要导出朋友圈列表采集结果，先在 App 首页复制“最近素材导出”路径里的目录名，例如 `moments_20260629_213000`，再导出文件：

```sh
adb exec-out run-as com.perrychoi.wechatmomentscontroller \
  cat files/moments_exports/<moments_dir>/manifest.jsonl > /tmp/manifest.jsonl

adb exec-out run-as com.perrychoi.wechatmomentscontroller \
  cat files/moments_exports/<moments_dir>/<screen_file>.png > /tmp/<screen_file>.png
```

## 开发原则

- 每一步都应先判断页面状态，再执行动作。
- 当前页能识别时从状态机选择步骤，不强制从微信首页重跑。
- 能点击 UI 节点就不点坐标。
- 坐标只做兜底，并按设备 profile 管理；现场微调值保存为本机覆盖，不改原始 profile。
- 新增设备时先在 `device_profiles.json` 加 profile，不要把设备坐标写回流程代码。
- 发布前保留人工确认，避免误发。
- 失败时保留日志和截图，方便调整。
