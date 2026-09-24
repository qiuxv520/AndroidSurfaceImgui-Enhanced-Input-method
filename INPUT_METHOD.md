# 输入法实现与维护

本文面向修改或移植输入模块的开发者。构建与日常使用见 [README](README.md)。

## 实现分工

| 组件 | 职责 |
| --- | --- |
| 原生 ImGui 程序 | 绘制界面、处理触摸、释放内嵌 APK、维护输入会话、将编辑结果应用到 ImGui |
| `InputBridge.java` | 通过 root `app_process` 运行，管理临时应用生命周期、系统剪贴板及本机消息转发 |
| `InputService.java` | 在临时辅助应用中创建 Android `EditText` 和输入连接，将输入法编辑结果返回原生程序 |

辅助包名固定为 `com.example.imguiinput`，应用标签和 APK 元数据水印为 `dum`。没有 Launcher Activity，不进入最近任务列表。它仍是正常安装的 Android 应用，可以在系统设置中看到。

## 单文件与生命周期

1. 构建时将包含 `classes.dex` 的签名 APK 转为字节数组，再链接进可执行文件。
2. 原生程序启动后，在 `/data/adb/dum-input-XXXXXX/` 创建本次运行专用目录（`0700`），将 APK 写为 `dum_input.apk`（`0400`）。每次使用新文件，避免覆盖正在被 ART 映射的代码。
3. `app_process` 直接从该 APK 加载 Java 桥接。桥接获得进程锁后，通过标准输入向包管理器传输 APK 并安装临时应用。
4. 辅助服务提前启动，在收到当前文本和显示请求后才唤起键盘。收起键盘不结束整个服务，也不卸载应用。
5. 正常关闭主窗口时，原生程序通知桥接退出。桥接卸载应用，删除临时 APK 和目录；原生退出路径提供文件清理兜底。

`/data/adb/dum_input.lock` 是保留的零字节互斥锁。不能在运行时随意删除它，否则并发进程可能锁住不同文件，破坏单一辅助包所有者的约束。

原生进程单独被终止时，Java 桥接可以通过管道 EOF 执行清理；如果整个进程树被杀死或设备断电，则不能保证清理有执行机会。正常退出最多等待桥接清理 15 秒，超时不会立即杀死仍在卸载的桥接进程。

## 输入窗口与触摸

输入服务使用透明、无阴影的 `1×1` 窗口；Android 8.0 及以上选择 `TYPE_APPLICATION_OVERLAY`，旧版本使用兼容类型。旧版本分支不代表已经逐机验证。

- 输入时窗口可以获得输入焦点，但不接收触摸；输入法使用 `IME_FLAG_NO_FULLSCREEN` 和 `IME_FLAG_NO_EXTRACT_UI`。
- 待命时设置 `FLAG_NOT_FOCUSABLE`，将焦点归还前台应用，同时保留窗口和服务连接，减少被系统回收后反复冷启动。
- 获得窗口焦点后，通过消息队列请求键盘；连接尚未准备好时短间隔重试，没有首次显示前的固定 100 毫秒等待。
- 服务不启动 Activity、不请求音频焦点。已在测试设备上验证视频继续播放；其他应用或 ROM 对窗口焦点的处理可能不同。
- C++ 根据 ImGui 可见窗口范围决定触摸归属，避免点控件时同时点击下方应用。键盘区域的触摸仍交给 Android。

悬浮窗 AppOp 只授予本辅助包。启动服务时设置 10 秒临时后台启动许可，之后自动失效，退出时也尝试撤销；不加入永久电池白名单。

## 文本同步

每次开始新的输入会话，C++ 发送当前输入框的完整 UTF-8 文本、选区、输入标志和容量。Android 端先同步 `EditText`，再显示键盘，因此再次打开后能删除旧文本。

Android 端返回完整编辑快照，而不是把所有输入模拟成按键或追加字符。桥接在 UTF-8 字节偏移和 Android 使用的 UTF-16 偏移之间转换，并用会话编号丢弃过期消息。

主要协议如下，文本字段以 Base64 编码：

| 消息 | 含义 |
| --- | --- |
| `SYNC session start end flags capacity text` | 同步当前文本、选区与输入配置 |
| `SHOW session` / `HIDE session` | 显示或收起键盘 |
| `EDIT session start end text` | Android 返回完整编辑结果 |
| `IME session visible top width height` | 键盘可见性和几何信息，用于触摸路由 |
| `HIDDEN session` | 用户收起键盘或当前输入结束 |
| `GETCLIP` / `SETCLIP text` | 读写系统剪贴板 |

TCP 连接仅监听 `127.0.0.1`，先校验每次运行随机生成的令牌。原生程序与 root 桥接之间使用管道，不依赖公网服务。

## ImGui 扩展与移植入口

涉及的主要文件：

- `jni/include/Android_input/InputBridge.h`、`jni/src/Android_input/InputBridge.cpp`：桥接接口与实现。
- `jni/src/main.cpp`：启动、逐帧更新、输入请求和退出顺序。
- `jni/src/Android_my_imgui/AndroidImgui.cpp`：注册剪贴板回调。
- `jni/src/Android_draw/draw_Gui.cpp`：中文示例控件。
- `input-helper/src/com/example/imguiinput/`：Java 生命周期和输入连接。

本项目给 `ImGuiInputTextState` 增加了 `ApplyExternalText()` 和 `ExternalEditPending`，在 `imgui_internal.h` / `imgui_widgets.cpp` 中实现完整文本替换、选区更新、撤销记录和容量限制。关闭输入前的最后一次编辑会先应用到用户缓冲区，再清除活动输入框。

升级 Dear ImGui 时必须迁移并验证此扩展，不能仅替换上游文件。`build-input-helper.ps1` 生成的 `DumInputApk.h` 属于构建中间文件，不要手工修改或提交。

## 测试与排错

### 本地回归

```powershell
.\tests\run-text-sync.ps1
```

成功时输出 `PASS`。测试覆盖重新聚焦、删除旧文字、中间编辑、选区、清空和 UTF-8 截断；它验证 ImGui 文本状态，不替代真机键盘测试。

### 真机检查

在可见的正式 ImGui 窗口中执行：

1. 点击“文本输入”，输入 `abc中文`。
2. 收起键盘，再次点击同一输入框，删除旧文字并继续输入；重复几次。
3. 检查“复制文本”和“粘贴文本”。
4. 在前台视频播放时唤起输入，检查视频和键盘上方的 ImGui 控件。
5. 关闭主窗口，然后检查进程和辅助包是否消失：

```powershell
adb shell pidof AndroidSurfaceImguiEnhanced
adb shell pm path com.example.imguiinput
```

两条命令均应没有相应结果。运行时 `dum-input-*` 目录也应被清理。2026-09-24 已在 Android 16 设备上完成上述输入流程及单文件独立部署、退出清理验证；唤起耗时受系统负载和输入法影响，不作为跨设备性能保证。

`tests/build-device-probe.ps1` 可以构建可选的无界面诊断程序，输出到 `input-helper/build/ime_device_probe`。它不是正常启动程序，不能与正式程序同时运行；先运行 `build.ps1` 以更新内嵌资源。运行探针后根据 `FIRST_READY` / `SECOND_READY` 各删除一个字，两个阶段成功后会输出 `DEVICE_TEST_PASS` 并清理临时应用。

### 常见问题

- **找不到 `DumInputApk.h`**：首次构建需要执行 `build.ps1`，不能直接跳过资源生成步骤运行 NDK。
- **SDK/JDK 文件或命令找不到**：检查 `-SdkRoot`、`-NdkRoot`、`-Platform`、`-BuildToolsVersion`，以及 JDK 的 `PATH`。
- **输入服务未就绪**：检查 root、包安装权限、悬浮窗策略和系统日志；第一次启动还需要完成临时应用安装。
- **APK 签名不匹配**：更换本地签名文件后，设备可能仍有上次未清理的辅助包。关闭所有本项目进程后卸载该包，再启动新版本。
- **截图看不到窗口**：检查继承的画面采集开关，以设备实际屏幕为准。
- **退出后仍有辅助包**：确认没有其他本项目实例运行，再仅卸载本项目辅助包：

```powershell
adb shell su -c 'pm uninstall com.example.imguiinput'
```

不要为了清理本项目而删除整个 `/data/adb`。如有遗留临时目录，应先确认没有正在运行的桥接进程，并逐项核对路径。

Java 桥接向标准错误输出 `dum input` 时间记录，包括服务连接、会话号、热启动状态、`latency_ms` 和资源清理状态。`latency_ms` 表示收到请求到系统报告键盘可见的时间，不是动画完全结束时间。日志不记录输入内容、剪贴板内容或连接令牌；也可检查 `adb logcat -s ImguiInput`，但部分 ROM 可能限制日志输出。
