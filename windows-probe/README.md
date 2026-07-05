# WeChat Windows Probe

这个目录用于 Windows 端微信朋友圈自动化的可行性探测。探针脚本会枚举微信窗口的 Microsoft UI Automation 树，并输出控件类型、坐标、可操作 pattern、关键词命中等信息，用来判断后续自动化应优先走 UIA 控件操作还是图像/坐标兜底。

默认策略偏保守：

- 不保存微信窗口截图，除非显式传入 `-CaptureScreenshot`。
- 不保存完整控件文本，除非显式传入 `-IncludeText`。
- 默认只保留微信流程相关关键词命中，例如 `朋友圈`、`发现`、`发表`、`保存`，其他文本会写成长度和哈希，避免把聊天、联系人、群名直接落盘。

## 快速校验

在仓库根目录运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_probe.ps1 -ValidateOnly
```

这只检查本机是否能找到 `Weixin.exe`，以及 PowerShell 能否加载 UI Automation 和截图所需的 .NET 组件。

## 首次探测

先手动打开微信并停在要分析的页面，然后运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_probe.ps1
```

如果希望脚本自动启动微信：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_probe.ps1 -Launch
```

## 带截图标定

截图可能包含私人聊天和联系人信息，所以需要显式开启：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_probe.ps1 -CaptureScreenshot
```

输出中会包含：

- `window.png`: 原始微信窗口截图。
- `window_overlay.png`: 标出关键词节点和可操作节点的截图。橙色是关键词命中，蓝色是可操作控件。

## 完整文本模式

只有在需要确认 UIA 节点真实文字时才使用：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_probe.ps1 -IncludeText
```

`-IncludeText` 会把 UIA 暴露的控件文本原样写入输出文件，适合测试账号或已确认没有敏感内容的页面。

## 输出文件

每次运行默认写入：

```text
WeChatAutomation/windows-probe/runs/yyyyMMdd_HHmmss/
```

主要文件：

- `summary.json`: 本次探测摘要、微信版本、窗口坐标、节点数量和输出文件路径。
- `uia_tree.json`: 树形 UIA 结构。
- `uia_nodes.jsonl`: 扁平节点列表，一行一个 JSON，方便后续脚本过滤。
- `keyword_nodes.json`: 命中微信朋友圈流程关键词的节点。
- `actionable_nodes.json`: 支持 `InvokePattern`、`ValuePattern`、`SelectionItemPattern`、`ScrollItemPattern` 的候选可操作节点。

## 推荐判定方式

1. 在微信首页运行一次，检查 `发现`、`朋友圈` 是否出现在 `keyword_nodes.json`。
2. 进入朋友圈列表运行一次，检查右上角发布入口是否有 UIA 节点或只能靠截图/坐标识别。
3. 打开朋友圈发布框运行一次，检查正文输入框、图片选择入口、`发表` 是否有可操作 pattern。
4. 若关键节点能被 UIA 定位，优先做 UIA 自动化；若只能看到窗口大框或少量自绘控件，再引入图像识别和固定窗口坐标 profile。

## 当前实测结论

测试环境：

- Windows 微信版本：`4.1.10.53`
- 探测时间：`2026-07-04`
- 坐标体系：UI Automation `BoundingRectangle` 的窗口相对坐标。

结论：

- 主微信窗口和朋友圈窗口都只暴露顶层 UIA 窗口，未暴露可点击子控件。
- 主流程应走“窗口相对坐标 + 截图验证 + 必要时图像识别”，不要把 UIA 控件树作为主链路。
- 当前 Windows 缩放环境下，Win32 `GetWindowRect` 和截图坐标不一致；点击坐标应以 UIA `BoundingRectangle` 为准。

已验证点位见：

```text
windows-probe/profiles/weixin_4.1.10.53_default.json
```

关键点位：

- `main_moments_nav`: 主微信窗口左侧光圈图标，打开朋友圈窗口。
- `moments_camera`: 朋友圈窗口左上角相机图标，打开 Windows 文件选择器。
- `moments_close`: 朋友圈窗口右上角关闭按钮。

已验证流程：

```text
主微信窗口 -> 点击 main_moments_nav -> 朋友圈窗口
朋友圈窗口 -> 点击 moments_camera -> Windows 文件选择器
```

尚未自动化的部分：

- 文件选择器内选择本地图片。
- 发布编辑页填写文字。
- 最终发表按钮。这个按钮后续也应默认保留人工确认。

## Stage1 脚本

`tools/windows_wechat_compose_stage1.ps1` 已经把前两步串成可重复流程：

```text
当前微信窗口 -> 如有需要打开朋友圈 -> 点击朋友圈相机 -> 停在 Windows 文件选择器
```

先做 DryRun：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_compose_stage1.ps1 -DryRun
```

实际打开文件选择器：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_compose_stage1.ps1
```

这个脚本不会选择图片、不会填写文案、不会点击最终发表。

## Mock Stage2

生成本地 mock 图片和文案：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\generate_windows_mock_data.ps1
```

DryRun 检查 mock manifest：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_compose_mock.ps1 -DryRun
```

实际测试到编辑页：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_compose_mock.ps1
```

这个脚本会执行：

```text
打开朋友圈 -> 打开文件选择器 -> 选择 mock 图片 -> 进入编辑页 -> 粘贴 mock 文案 -> 停在发表前
```

安全边界：

- 只使用 `windows-probe/mock-data/` 下的本地 mock 图片和 mock 文案。
- 不点击 `发表`。
- 运行结束后会停在编辑草稿页，用户可以人工检查或取消。
- 如需启用“疑似已有朋友圈编辑草稿则拒绝继续”的保守保护，可给 stage1 加 `-RefuseIfComposeEditorLikelyOpen`。该保护依赖截图像素启发式，默认关闭，避免把普通朋友圈列表误判成草稿页。

本轮已实测：

- `20260704_230740`: 选择两张 mock 图片后进入朋友圈编辑页。
- `20260704_230830`: mock 文案已粘贴，页面停在 `发表` 按钮前。

## Save Probe

朋友圈图片保存链路已验证。

手动/脚本链路：

```text
朋友圈列表 -> 点击一张图片 -> 图片查看器
图片查看器 -> Ctrl+S -> Windows 保存对话框
保存对话框 -> Alt+N 聚焦文件名 -> 粘贴目标路径 -> Alt+S 保存
```

已验证结果：

- 右键菜单可打开，但未在第一屏看到直接的 `保存图片/另存为`。
- `Ctrl+S` 可以稳定打开 Windows `保存` 对话框。
- 用 `Alt+N` 聚焦文件名输入框、`Alt+S` 保存，比直接粘贴/回车更稳定。
- 文件已成功保存到 `windows-probe/save-runs/20260704_232645/moment_save_probe.jpg`，大小 `124670` bytes。

保存当前已打开的朋友圈图片查看器内容：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_save_current_image.ps1
```

DryRun：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_save_current_image.ps1 -DryRun
```

当前脚本前置条件：

- 已经手动或用坐标脚本打开某一张朋友圈图片。
- 脚本只负责从图片查看器保存当前图片，不负责选择哪一条朋友圈。

## Batch Save MVP-1

从已经打开的朋友圈图片查看器开始，批量保存同组图片：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_save_viewer_batch.ps1 -MaxItems 9
```

当前批量链路：

```text
图片查看器 -> Ctrl+S -> Windows 保存对话框
保存对话框 -> Alt+D 切到目标目录 -> 文件名输入框填短文件名 -> 点击保存
保存成功 -> 记录 length/sha256 -> Right 切换下一张
遇到重复 sha256 或保存失败 -> 停止
```

已验证结果：

- `20260705_110713`: 从当前图片查看器连续保存，前 5 张为唯一图片。
- 第 6 次保存的 sha256 与第 5 张一致，脚本判定 `duplicate_detected` 并停止。
- 本轮没有保存失败；重复文件已删除，最终保留 5 个文件。
- 输出目录：`windows-probe/save-runs/batch_20260705_110713/`。

输出结构：

- `media/`: 保存下来的图片文件。
- `events.jsonl`: 每次保存、右箭头导航、重复检测的事件流水。
- `manifest.json`: 本轮汇总，包括停止原因、保存数量、唯一 sha256 数量和媒体清单。

## Text Copy Probe

朋友圈正文不需要 OCR 也有一条可行链路：右键正文区域，微信会弹出包含 `复制` 的菜单，点击第一项后可以从剪贴板读取正文。

当前脚本：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_copy_visible_text.ps1 -X 220 -Y 480
```

当前链路：

```text
朋友圈列表 -> 右键正文区域 -> 点击菜单第一项复制 -> 读取剪贴板 -> 保存 copied_text.txt -> 恢复原剪贴板
```

已验证结果：

- `20260705_111856`: 手动点击 `复制` 菜单后，剪贴板成功变为正文文本，长度 `25`。
- `20260705_112018`: 复用脚本 `windows_wechat_copy_visible_text.ps1` 成功保存同一段正文，状态 `copied`。
- `20260705_112423`: 对一条带 `全文` 的折叠长文本做对比测试，折叠态复制与展开后复制的正文长度同为 `110`，sha256 完全一致。
- 该方法依赖正文区域的窗口相对坐标；后续批量化时需要先定位每条朋友圈正文的可右键区域。
- 对折叠长文本，当前样本显示无需先点 `全文` 也可复制完整正文；批量化时可先直接复制折叠态，失败或长度异常时再点击 `全文` 重试。

输出结构：

- `copied_text.txt`: 复制到的正文文本。
- `summary.json`: 复制状态、点击坐标、文本长度和 sha256。

## Comment Controls Probe

评论控件读取探针：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_comment_controls_probe.ps1
```

探针会同时尝试：

- UI Automation 枚举。
- MSAA / Active Accessibility 枚举。
- `Ctrl+A` / `Ctrl+C` 剪贴板复制测试。

默认隐私策略：

- 不输出原始文本，只记录长度、hash、关键词命中和节点数量。
- 剪贴板复制测试结束后会尝试恢复之前的文本剪贴板内容。
- 如需调试真实 UIA/MSAA 原文，可显式加 `-IncludeRawText`，只建议在测试账号或无敏感内容页面使用。

当前已在图片查看器状态下实测：

- `20260704_234953`: UIA 只有 `2` 个节点。
- MSAA 可枚举 `24` 个节点，但 `textBearingNodeCount = 0`。
- 剪贴板复制结果 `afterTextLength = 0`。

这说明图片查看器状态下不能通过控件/剪贴板读出朋友圈正文或评论。需要在手动打开某条朋友圈评论详情页后再跑一次该探针，才能判断评论详情页是否额外暴露文本控件。

## 辅助探针

导航候选点击：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_nav_probe.ps1 -Target aperture
```

任意窗口相对坐标点击：

```powershell
powershell -ExecutionPolicy Bypass -File .\WeChatAutomation\tools\windows_wechat_click_probe.ps1 -X 92 -Y 31 -Label moments_camera_icon
```

点击探针会在 `windows-probe/nav-runs/` 或 `windows-probe/click-runs/` 下保存 `before.png`、`after_*.png` 和 `summary.json`。这些运行产物默认被 `.gitignore` 忽略。
