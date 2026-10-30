# 听见（Tingjian）

把手机麦克风收到的声音，实时送到蓝牙耳机里 —— 借现成的骨传导耳机当"简易助听器"用。

**MIT 开源**，下载地址：<https://github.com/Anguis-L/tingjian/releases>

## 它做什么

- 打开 App，点一下「开始」，就会在后台持续运行（前台服务 + 常驻通知）
- 用**手机麦克风**采集环境声音，经增益、软限幅、高频补偿后，从**蓝牙耳机**播放出来
- 息屏、切到别的 App 都不中断
- 插拔/切换耳机时自动重新连接
- 检测到持续啸叫/过载时自动降低增益，保护听力
- 开始前会检查有没有接耳机：**没有耳机就拒绝启动**，避免外放引起啸叫

## 这不是什么

**不是医疗器械**，不能替代专业助听器、听力师验配或医院检查。音量过大、长时间佩戴、啸叫都可能损伤听力。出现刺耳、耳痛、头晕时请立即降低音量或停用。

## 重要的物理限制：延迟

蓝牙音频链路本身有延迟，App 无法消除：

| 链路 | 大致延迟 | 适用场景 |
|---|---|---|
| 有线 / USB-C 耳机 | 十几到几十毫秒 | 近场对话可用 |
| 经典蓝牙 A2DP（绝大多数骨传导耳机） | 明显（百毫秒量级） | 适合"手机当远程麦克风"：把手机放对方面前/桌上 |
| LE Audio 耳机 + Android 13+ | 通常更低 | 需耳机和手机同时支持 |

**近距离（1 米内）直接对话时，直达声和耳机里晚到的声音叠加，可能出现回声/发闷**，这是方案本身的边界，不是 bug。判断方法是：把手机放到说话人附近（比如桌上、对方面前），用耳机听，效果会明显好于把手机戴在自己身上。

App 界面会显示当前链路类型和"本机缓冲约 X ms"（不含蓝牙传输部分），方便你判断。

## 安装

**方式一：从 Releases 下载（推荐）**

1. 打开 <https://github.com/Anguis-L/tingjian/releases>，下载最新一版的 `tingjian-v0.1.0-debug.apk`
2. 把 APK 拷到手机上点开安装，需要允许"安装未知来源应用"
3. 这个包是 **debug 签名**的测试包，仅供试用，不适合长期分发

**方式二：用 adb 安装**（需要手机打开「开发者选项 → USB 调试」并用数据线连接）

```
adb install -r tingjian-v0.1.0-debug.apk
```

## 已在模拟器上验证过

在 Android 12（x86_64 模拟器）上实测通过：

| 场景 | 结果 |
|---|---|
| 启动 App | 界面正常，大字号可读 |
| 没接耳机点「开始」 | 拒绝启动，提示"未检测到耳机，已停止（避免外放啸叫）" |
| 调试模式（允许外放）启动 | 状态变"正在运行"，48000 Hz，本机缓冲约 21 ms，电平条跟随环境声变化 |
| 按 Home 切后台 | 前台服务保持运行（`isForeground=true`），音频线程不掉 |
| 点「停止」 | 服务正常销毁，界面回到初始状态 |

没验证到的：**真实蓝牙耳机的路由与延迟**（模拟器没有蓝牙协议栈）。这一步需要你在自己的手机上接骨传导耳机试。

截图见 `docs/screenshots/`。

## 调试：没有耳机时验证音频链路

debug 包内置了一个隐藏入口，允许临时回落到手机外放（正式版代码路径不受影响）：

```powershell
adb shell am start -n com.tingjian.relay/.MainActivity --ez allow_speaker true --ez autostart true
```

它会以 -6 dB 增益自动开始，界面显示"手机外放（仅调试）"。调试完记得点停止。

## 使用

1. 先连好蓝牙耳机（骨传导耳机按正常方式配对）
2. 打开「听见」，允许麦克风权限和通知权限
3. 点「开始」，看到状态变成「正在运行」、显示耳机类型即可
4. 用滑杆调音量增益（建议从 +12 dB 附近开始，慢慢调）
5. 点「允许后台运行（关闭电池优化）」，避免系统在息屏后杀掉它
6. 停止：点 App 里的「停止」，或下拉通知栏点通知上的「停止」

## 权限说明

| 权限 | 用途 |
|---|---|
| 麦克风 | 采集声音，核心功能 |
| 通知 | 前台服务必须显示常驻通知（Android 系统要求） |
| 修改音频设置 | 保留音频通路设置能力 |

## 构建（开发者）

本项目不依赖 AndroidX，纯 Java + 平台 API，`minSdk 26 / targetSdk 34 / compileSdk 35`。

需要：JDK 17+（本机用 JDK 21）、Android SDK（platform 35 + build-tools 35.0.0）。SDK 位置写在 `local.properties` 的 `sdk.dir`（这个文件不入库，本地自己建）。

```bash
# macOS / Linux
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

也可以直接用 Android Studio 打开项目目录构建（推荐第一次这么用，它会自动处理 SDK 路径）。

<details>
<summary>原作者机器上的独立工具链（可选参考，不入库）</summary>

为了让构建不污染系统环境，原作者的机器上用了独立的一套路径：

| 组件 | 位置 |
|---|---|
| JDK | `C:\jdk-21` |
| Android SDK（platform 35 + build-tools 35.0.0） | `.android-sdk/` |
| Gradle 8.9 | `tools/gradle-8.9/` |
| Gradle 依赖缓存 | `.gradle-home/` |

这些目录都在 `.gitignore` 里（`tools/`、`.android-sdk/`、`.gradle-home/`、`local.properties`），不会进仓库。同机上另有 `scripts/build.ps1` 一键脚本，因为写死了上述路径，也没有随仓库提供。

手动跑法：

```powershell
$env:JAVA_HOME='C:\jdk-21'
$env:ANDROID_SDK_ROOT='X:\Tingjian\.android-sdk'
$env:GRADLE_USER_HOME='X:\Tingjian\.gradle-home'
tools\gradle-8.9\bin\gradle.bat --project-dir X:\Tingjian assembleDebug
```

</details>

## 代码结构

```
app/src/main/java/com/tingjian/relay/
 ├─ MainActivity.java       界面：开始/停止、增益、清晰度、链路状态、免责声明
 ├─ AudioRelayService.java  前台服务（microphone|mediaPlayback），持有引擎与通知
 ├─ AudioEngine.java        采集→处理→播放的实时线程、设备选择、断连恢复、过载保护
 ├─ AudioRoutes.java        输出/输入设备优先级选择与中文描述
 └─ Dsp.java                直流阻断、高频搁架、增益、软限幅
```

## 参考项目 / Prior art

动手前看过下面几个开源项目，**只借鉴了思路**（例如用 `MODE_NORMAL` + `setPreferredDevice` 把声音送到指定蓝牙设备、前台服务保活、设备切换时重开音频流这几件事在社区里早有共识），**代码是从 Android 平台 API 独立实现的**，结构、命名、实现规模都不一样：

- [hhkkoo11/phone-hearing-aid](https://github.com/hhkkoo11/phone-hearing-aid) — 注意它的许可是 **Non-Commercial（禁止商用）**，与本项目无代码关系，也不受其许可约束；本项目是 MIT。
- [thivyan-studios/hark-app](https://github.com/thivyan-studios/hark-app) — BSD-3-Clause
- [vatbub/HearingAidAndroid](https://github.com/vatbub/HearingAidAndroid) — Apache-2.0

如果你要再参考上面这几个项目，请各自遵守它们自己的许可条款。

## 后续可做

- Oboe/AAudio 全双工版本（进一步降低本机延迟、抗时钟漂移）
- 三频段 EQ 与听力曲线
- 延迟自测（拍手对比）与链路延迟提示
- LE Audio 优先路由（`TYPE_BLE_HEADSET`）
- 通话时自动让路（来电监听）

## 许可

[MIT](LICENSE) © 2026 Anguis-L

再次强调：本软件是实验性质的音频工具，**不是医疗器械**，不提供任何听力健康方面的保证，使用风险自负。
