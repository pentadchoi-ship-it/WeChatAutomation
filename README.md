# Pico HID Touch Macro

这个工程把 Raspberry Pi Pico 做成一个 USB HID 自动触控器，用来复刻刚才看到的 `C86EC0` 类设备的核心行为：主机把它识别为键盘/鼠标/触控屏，Pico 按脚本发送点击和滑动。

仅在你自己的设备、账号和测试环境里使用。不要用它做批量骚扰、刷屏或规避平台规则的自动化。

## Android MVP 主线

商用级方向已切到 Android AccessibilityService，工程在 [android-app](/Users/perrychoi/Documents/Raspberry/android-app)。Pico 方案保留为硬件验证和兜底，不再作为主控制链路。

当前 Android MVP 支持从微信首页开始执行四步测试：

1. 点击“发现”
2. 点击“朋友圈”
3. 点击右上角摄像头
4. 点击“从手机相册选择”

同时已加入两个本地保存 MVP：其一是朋友圈列表采集，保存若干屏截图和可见节点文本/描述；其二是单条朋友圈媒体保存，手动打开目标朋友圈第一张图片/视频后，通过微信原生长按菜单逐个执行“保存图片/保存视频”，并在本地记录保存会话 manifest。

Android 工程说明见 [android-app/README.md](/Users/perrychoi/Documents/Raspberry/android-app/README.md)。

## 已观察到的原设备特征

- USB Vendor ID: `0x4348`
- USB Product ID: `0x5537`
- Manufacturer/Product: `C86EC0`
- USB 速度: Full Speed, `12 Mbps`
- HID 触控接口: `UsagePage=0x0D`, `Usage=0x04`
- 触控报告长度: `6` 字节
- 坐标范围: `0..4095`
- 触控 HID 描述符:

```text
05 0d 09 04 a1 01 05 0d 09 22 a1 02 05 0d 09 42
15 00 25 01 75 01 95 01 81 02 75 07 95 01 81 01
09 51 75 08 95 01 81 02 05 01 09 30 15 00 26 ff
0f 75 10 95 01 81 02 09 31 15 00 26 ff 0f 75 10
95 01 81 02 c0 c0 00 01
```

实际测试固件默认去掉末尾 `00 01` 两个字节，以提高 Android 兼容性；如果后面要做更激进的枚举复刻，再加回原生固件里测试。

## 推荐先用 CircuitPython

这条路最快：不用编译固件，把文件复制到 Pico 的 `CIRCUITPY` 盘就能跑。

1. 给 Pico 刷 Raspberry Pi Pico 对应的 CircuitPython UF2。
2. 把 [circuitpython/boot.py](/Users/perrychoi/Documents/Raspberry/circuitpython/boot.py)、[circuitpython/code.py](/Users/perrychoi/Documents/Raspberry/circuitpython/code.py)、[circuitpython/touch_hid.py](/Users/perrychoi/Documents/Raspberry/circuitpython/touch_hid.py)、[circuitpython/mouse_hid.py](/Users/perrychoi/Documents/Raspberry/circuitpython/mouse_hid.py)、[circuitpython/macro.json](/Users/perrychoi/Documents/Raspberry/circuitpython/macro.json) 复制到 `CIRCUITPY` 根目录。
3. 编辑 `macro.json`，把 `"enabled": true`。
4. 默认需要把 `GP14` 接到 `GND` 才会执行宏，避免一插上就乱点。
5. Pico 通过 OTG/USB 接到目标手机或电脑，等待脚本执行。

`macro.json` 支持这些动作：

```json
{"op": "tap_pct", "x": 50, "y": 90, "hold": 0.08, "after": 0.3}
{"op": "swipe_pct", "x1": 50, "y1": 80, "x2": 50, "y2": 20, "duration": 0.45, "steps": 24, "after": 0.5}
{"op": "wait", "seconds": 1.0}
```

百分比坐标更适合换屏幕尺寸；绝对坐标也可以用 `tap` 和 `swipe`，范围是 `0..4095`。

鼠标-only 模式还支持这些动作：

```json
{"op": "mouse_home"}
{"op": "mouse_tap_pct", "x": 50, "y": 50, "hold": 0.08}
{"op": "mouse_drag_pct", "x1": 50, "y1": 78, "x2": 50, "y2": 38, "duration": 0.5, "steps": 24}
{"op": "mouse_move", "x": 80, "y": 0}
```

鼠标是相对移动，宏会先 `mouse_home` 把指针推到左上角，再按 `mouse_units.width/height` 换算到目标百分比位置。P30 的初始建议值在校准宏里，后续按实际落点微调。

P30 当前校准值来自实测：`mouse_units.width=367`、`mouse_units.height=900`。如果后续坐标仍偏差明显，再按实际落点微调。

### 状态灯

Pico 板载 LED 会提示脚本状态：

- 闪 `1` 次：`code.py` 开始运行。
- 闪 `2` 次：`macro.json` 里 `"enabled": false`。
- 闪 `3` 次：需要接地的 `arm_pin` 没有接到 `GND`。
- 闪 `4` 次：准备执行宏。
- 闪 `5` 次：宏执行结束。

默认启用 `stop_if_arm_released`：宏执行过程中松开 `GP14` 接地，下一步开始前会停止。

## 华为 P30 测试流程

华为 P30 官方屏幕分辨率是 `2340*1080`，竖屏坐标可按 `1080x2340` 处理。先用 [circuitpython/macro_p30_touch_test.json](/Users/perrychoi/Documents/Raspberry/circuitpython/macro_p30_touch_test.json) 做触控验证。

1. P30 保持竖屏，关闭自动旋转。
2. 打开一个空白画板、备忘录手写页，或在开发者选项里打开“指针位置”。
3. 把 `macro_p30_touch_test.json` 复制为 `macro.json`，把 `"enabled": true`。
4. Pico 的 `GP14` 接 `GND`，再通过 USB-C OTG 连接到 P30。
5. 观察中心点点击、中心上滑、底部左右点位是否落在预期位置。

如果 P30 只看到 U 盘、看不到任何触控动作，先用 [circuitpython/macro_p30_mouse_diagnostic.json](/Users/perrychoi/Documents/Raspberry/circuitpython/macro_p30_mouse_diagnostic.json) 替换 `macro.json` 做鼠标诊断。P30 应该出现鼠标指针并移动；如果鼠标也不动，说明 Pico 还没运行 CircuitPython 脚本或 HID 没有成功启用。

Pico 插回 Mac 并挂载为 `CIRCUITPY` 后，可以用安装脚本直接写入鼠标诊断宏并自动启用：

```sh
tools/install_to_circuitpython.sh /Volumes/CIRCUITPY circuitpython/macro_p30_mouse_diagnostic.json
```

如果 LED 已经闪 `4` 次和 `5` 次，但 P30 仍然没有鼠标动作，改用标准鼠标-only 启动文件测试：

```sh
tools/install_to_circuitpython.sh /Volumes/CIRCUITPY circuitpython/macro_p30_mouse_diagnostic.json circuitpython/boot_mouse_only.py
```

`boot_mouse_only.py` 默认会隐藏 `CIRCUITPY` U 盘和 USB 串口，只暴露键盘/鼠标。要再次在 Mac 上看到 `CIRCUITPY` 盘，插入 Pico 前把 `GP15` 接到 `GND`。

现在鼠标已经能动后，下一步跑校准宏：

```sh
tools/install_to_circuitpython.sh /Volumes/CIRCUITPY circuitpython/macro_p30_mouse_calibrate.json circuitpython/boot_mouse_only.py
```

在 P30 上打开“开发者选项 > 指针位置”，看三次点击大概落在哪里：`10%,10%`、`50%,50%`、`90%,90%`。如果点击整体偏左/偏上，增大 `mouse_units.width/height`；如果偏右/偏下，减小对应值。

如果坐标跳得太快，改跑慢速校准宏：

```sh
tools/install_to_circuitpython.sh /Volumes/CIRCUITPY circuitpython/macro_p30_mouse_calibrate_slow.json circuitpython/boot_mouse_only.py
```

慢速宏会依次停在 `50%,50%`、`90%,90%`、`10%,10%`，每个点停 `5` 秒，方便读取指针位置。

有些 Android 版本不会在鼠标悬停移动时更新顶部坐标，只会在左键按下时更新。遇到这种情况，跑“按住读数”校准宏：

```sh
tools/install_to_circuitpython.sh /Volumes/CIRCUITPY circuitpython/macro_p30_mouse_hold_calibrate.json circuitpython/boot_mouse_only.py
```

它会移动到 `50%,50%`、`90%,90%`、`10%,10%`，每个点按住左键 `4` 秒，方便看顶部坐标。

鼠标宏演示：

```sh
tools/install_to_circuitpython.sh /Volumes/CIRCUITPY circuitpython/macro_p30_mouse_demo.json circuitpython/boot_mouse_only.py
```

实际流程可以从模板复制：

```sh
tools/install_to_circuitpython.sh /Volumes/CIRCUITPY circuitpython/macro_p30_mouse_workflow_template.json circuitpython/boot_mouse_only.py
```

微信从首页进入相册选择的四步测试宏：

```sh
tools/install_to_circuitpython.sh /Volumes/CIRCUITPY circuitpython/macro_p30_wechat_album_test.json circuitpython/boot_mouse_only.py
```

触控测试宏同理：

```sh
tools/install_to_circuitpython.sh /Volumes/CIRCUITPY circuitpython/macro_p30_touch_test.json
```

P30 像素坐标换算到 HID 坐标：

```sh
python3 tools/hid_coord.py 540 1170
python3 tools/hid_coord.py 50 72 --percent
```

换算关系：

```text
hid_x = round(pixel_x * 4095 / 1079)
hid_y = round(pixel_y * 4095 / 2339)
```

## 更接近原设备的原生固件

[pico-sdk/](/Users/perrychoi/Documents/Raspberry/pico-sdk/CMakeLists.txt) 里是 Pico SDK/TinyUSB 版本：不依赖 CircuitPython 的磁盘和串口接口，USB 枚举更接近原设备。

本机目前没有 `cmake` 和 `arm-none-eabi-gcc`，所以我没有在本地编译出 `.uf2`。装好 Pico SDK 工具链后可以这样编译：

```sh
export PICO_SDK_PATH=/path/to/pico-sdk
cmake -S pico-sdk -B build -DPICO_BOARD=pico
cmake --build build
```

产物会在 `build/c86ec0_pico.uf2`。源码默认也需要 `GP14` 接地才会执行示例宏。
