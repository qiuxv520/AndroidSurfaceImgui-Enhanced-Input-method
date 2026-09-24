# AndroidSurfaceImgui Enhanced Input Method

运行在 Android 上的原生 C++ / Dear ImGui 示例，提供系统软键盘输入和剪贴板读写。发布产物只有一个可执行文件，不需要手动部署 APK 或 DEX。

> 需要 root。单文件分发不代表完全不安装应用：运行期间会自动安装名为 `dum` 的临时输入辅助应用，退出时卸载。它没有桌面入口，但仍可在系统应用列表中看到。

## 功能

- 中文界面的文本输入、复制和粘贴控件。
- 调用设备现有输入法，支持中文输入、光标/选区同步和删除上次输入的文字。
- 收起键盘后可再次点击同一输入框继续编辑。
- 输入服务使用透明 `1×1` 悬浮窗口，不启动 Activity、不请求音频焦点；已在测试设备上验证输入时前台视频继续播放。
- 预热服务并复用输入窗口，减少再次唤起时的冷启动开销。
- ImGui 窗口内的触摸交给 ImGui，键盘和窗口外的触摸交给 Android。
- APK 和桥接 DEX 内嵌；正常关闭主窗口后自动卸载辅助应用、清理运行时临时资源。

## 运行环境与兼容性

| 项目 | 当前情况 |
| --- | --- |
| 架构 | 构建和发布目标为 `arm64-v8a` |
| 最低编译 API | `android-22`，不等于所有 Android 5.1 以上设备均已验证 |
| 设备权限 | root，能够执行 `su`、访问触摸设备及 `/data/adb` |
| 图形后端 | 主程序默认 Vulkan；源码同时提供 OpenGL ES 后端 |
| 已验证设备 | Android 16 的 OnePlus 设备；输入、再次删除、剪贴板、视频播放与退出清理已实测 |

项目使用 Android 私有 Surface 接口及 root 桥接，不同系统版本、厂商 ROM、输入法和安全策略可能影响行为。新增输入功能没有完成全版本兼容性测试，不保证所有设备表现相同。

## 直接运行

仓库保留已编译的 [AndroidSurfaceImguiEnhanced](libs/arm64-v8a/AndroidSurfaceImguiEnhanced)。连接 ADB 后，只需推送这一个文件：

```powershell
adb push libs/arm64-v8a/AndroidSurfaceImguiEnhanced /data/local/tmp/AndroidSurfaceImguiEnhanced
adb shell su -c 'chmod 755 /data/local/tmp/AndroidSurfaceImguiEnhanced'
adb shell su -c /data/local/tmp/AndroidSurfaceImguiEnhanced
```

1. 等待窗口显示“输入法已就绪”，点击“文本输入”唤起键盘。
2. 使用“复制文本”和“粘贴文本”读写系统剪贴板。
3. 收起键盘后再次点击输入框，可继续编辑或删除原来的文字。
4. 关闭 ImGui 主窗口结束程序；仅收起键盘不会卸载辅助应用。

更新程序前先关闭正在运行的旧版本，不要覆盖仍在执行的二进制文件。

## 从源码构建

当前提供 Windows PowerShell 构建脚本，需要以下工具：

- Android NDK：已验证 `25.2.9519653`。
- Android SDK：默认使用 `platforms/android-35` 和 `build-tools/35.0.1`。
- JDK：`java`、`javac`、`keytool` 可从 `PATH` 调用，且能运行上述 SDK 工具。本地验证环境为 JDK 25。
- ADB：仅部署和设备测试需要。

```powershell
git clone https://github.com/ssyclr/AndroidSurfaceImgui-Enhanced-Input-method.git
cd AndroidSurfaceImgui-Enhanced-Input-method

# 按自己的安装位置调整路径
.\build.ps1 -SdkRoot 'D:\ASWJ' -NdkRoot 'D:\ASWJ\ndk\25.2.9519653'
```

可选参数：`-Platform android-35`、`-BuildToolsVersion 35.0.1`、`-Jobs 4`。

构建顺序为 Java → DEX → 签名 APK → 内嵌字节数组 → NDK 编译链接。最终输出：

```text
libs/arm64-v8a/AndroidSurfaceImguiEnhanced
```

`obj/` 和 `input-helper/build/` 是不提交到 Git 的中间目录。辅助 APK 中已经包含桥接 DEX，不需要在发布目录另放 APK、DEX 或 `.idsig`。首次构建或修改 Java 后应运行 `build.ps1`；只改 C++ 时可以使用 NDK 增量构建。

构建脚本在本地生成开发用签名文件 `input-helper/build/debug.keystore`，不会将其提交到仓库。它仅用于本示例临时辅助应用，不应作为其他产品的正式签名密钥。

## 验证

本地文本同步回归测试需要支持 C++17 的 MinGW `g++`：

```powershell
.\tests\run-text-sync.ps1
# 或指定编译器位置
.\tests\run-text-sync.ps1 -Compiler 'D:\Tools\mingw64\bin\g++.exe'
```

测试覆盖重新聚焦、删除旧文字、中间编辑、选区、清空和 UTF-8 容量边界。设备验证步骤及可选诊断工具见 [输入法实现与维护](INPUT_METHOD.md#测试与排错)。

## 源码导航

| 路径 | 用途 |
| --- | --- |
| `jni/src/Android_draw/draw_Gui.cpp` | 示例界面和中文控件 |
| `jni/src/Android_input/` | 内嵌资源释放、C++ 输入会话与剪贴板桥接 |
| `input-helper/src/` | root Java 桥接和输入窗口 Service |
| `jni/src/Android_touch/` | 原始触摸读取与路由 |
| `jni/src/ImGui/`、`jni/include/ImGui/` | Dear ImGui 与本项目的文本同步扩展 |
| `tests/` | 文本同步回归与设备诊断源码 |
| `build.ps1`、`build-input-helper.ps1` | 单文件构建入口与资源构建 |

## 注意事项

- 使用 root 包管理命令自动安装辅助应用，不弹出安装界面；系统仍保留正常的应用可见性。
- 程序仅为自身辅助包设置悬浮窗权限和短期后台启动许可，不修改永久电池白名单。
- 本机桥接使用随机令牌校验连接；辅助应用的 `INTERNET` 权限用于 loopback 通信，当前实现不连接外部服务器。
- 整个进程树被强制终止、设备断电或系统拒绝卸载时，自动清理可能无法完成。恢复方法见 [输入法实现与维护](INPUT_METHOD.md#测试与排错)。
- “过录制”是继承的画面采集相关开关，截图或录屏可能看不到原生 Surface；输入功能应以设备实际显示为准。

## 更新记录与来源

变更记录见 [CHANGELOG.md](CHANGELOG.md)，实现细节见 [INPUT_METHOD.md](INPUT_METHOD.md)。

本项目基于 [Bzi-Han/AndroidSurfaceImgui](https://github.com/Bzi-Han/AndroidSurfaceImgui) 及 [AFan4724/AndroidSurfaceImgui-Enhanced](https://github.com/AFan4724/AndroidSurfaceImgui-Enhanced) 的增强版本继续开发，使用 Dear ImGui、FreeType 和 Font Awesome 等组件。C++ 调用剪贴板的接入参考了开发时提供的示例，原始参考材料不作为构建输入。

许可证沿用仓库 [LICENSE](LICENSE)。分发时请保留上游署名和第三方组件的声明；本次整理不更改现有许可证条款。
