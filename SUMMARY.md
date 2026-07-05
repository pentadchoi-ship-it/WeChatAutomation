# 项目阶段总结

## 当前成果

- 完成了原始 USB 设备枚举分析，确认目标设备名为 `C86EC0`，核心行为是 USB HID 输入注入。
- 记录了关键枚举参数：`VID=0x4348`、`PID=0x5537`、HID Touch Screen、坐标范围 `0..4095`。
- 搭建了 Raspberry Pi Pico 的 CircuitPython HID 宏方案，支持触控、鼠标、点击、滑动、拖拽和安全接地触发。
- 为华为 P30 增加了测试和校准宏，包含触控测试、鼠标诊断、慢速校准、按住读数校准和微信相册入口测试。
- 增加了 Pico SDK/TinyUSB 原生固件工程，保留更接近原设备枚举的实现路径。
- 增加了 Android AccessibilityService MVP，作为更可靠的主控制链路，当前可从微信首页进入朋友圈相册选择流程，并保留人工发表确认。
- 增加了 Windows 微信朋友圈探针链路，已验证图片查看器批量保存、正文右键复制、折叠长文完整复制和 UIA/MSAA 可行性探测。
- 增加了安装脚本和坐标换算工具，方便把宏写入 `CIRCUITPY` 和换算 P30 坐标。

## 当前方向

商用级主线已从 Pico 坐标宏转向 Android AccessibilityService 和 Windows 官方客户端 UI 自动化探针。Pico 方案保留为硬件验证、鼠标/触控兜底和原设备行为复刻参考。

## 重要安全边界

- 只用于自有设备、测试账号和授权场景。
- Android MVP 不自动点击最终发表按钮。
- Windows 探针只做本地保存、复制和可行性验证，不自动发布或发送消息。
- Windows 运行产物目录默认通过 `.gitignore` 排除，避免截图、媒体和复制文本进入版本库。
- Pico 宏默认需要 `GP14` 接地才执行，避免误触。

## 主要目录

- `circuitpython/`: Pico CircuitPython HID 宏与 P30 配置。
- `pico-sdk/`: Pico SDK/TinyUSB 原生固件源码。
- `android-app/`: Android 无障碍自动化 MVP。
- `windows-probe/`: Windows 微信朋友圈探针说明、坐标 profile 和运行产物忽略规则。
- `tools/`: 安装、环境和坐标换算脚本。

