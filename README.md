# MMD English Digital

安卓端**实时英语语音对话**应用，实现类似「豆包打电话」的全双工语音交互体验。

底层接入 **豆包实时语音模型 3.0（Seeduplex）** 全双工端到端语音大模型。

## 功能

- 一键「拨打」建立实时语音会话，说话即被识别、模型以语音实时回应
- 全双工：低时延、可随时打断（协议原生支持）
- 实时字幕：用户语音转写 + AI 回复文本滚动显示
- **数字人视频头像**：内置方形数字人视频循环播放，以圆形外框裁切显示（`clipToOutline` + oval 背景），**静音播放**（`setVolume(0,0)`）不影响对话
- **默认横屏、可一键切换竖屏**：横屏为左头像 / 右面板的双栏布局，竖屏为纵向布局；切换不重通话（`configChanges` + 重建布局）
- **自动模式（默认开，可关闭）**：启动即拨打；通话中静默 10 秒且 AI 未播报则自动挂断；挂断后本地监听，说话约 0.5 秒自动重拨（本地 WebRTC VAD，空闲监听不连服务端、零费用）
- **语音退出**：通话中用户整句文本「前 7 个字」内含有「退出」或「关闭」时，先挂断会话再结束任务并结束进程（彻底退出）；仅整句（transcription.completed）判定，避免半句误触
- 无麦克风环境（模拟器）自带的**测试音频通道**：流式发送内置英文语音，验证端到端链路

## 技术方案

| 项 | 选择 |
|---|---|
| 语音模型 | 豆包 Seeduplex 全双工（`model=1.2.6.1`） |
| 接入方式 | **直接实现 WebSocket 全双工协议**（纯 JSON 文本帧），未依赖私有 SDK |
| 端点 | `wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue` |
| 鉴权 | 请求头 `X-Api-Key` |
| 音频上行 | PCM / 16kHz / 单声道 / 16bit，20ms 一包（640B），Base64 内嵌 `input_audio_buffer.append` |
| 音频下行 | Base64 内嵌 `response.output_audio.delta`，**`pcm_s16le`（16bit int）24kHz** |
| 回声消除 | 优先硬件 `AcousticEchoCanceler`；无硬件 AEC 的设备（安卓电视）自动启用 **软件 WebRTC AEC3** 回退 |
| 语言/栈 | Kotlin + OkHttp + 原生 View |
| 构建 | Gradle 8.7 / AGP 8.4.0 / Kotlin 2.2.10 / JDK 17 |

### 软件回声消除（AEC3）—— 安卓电视打断修复

很多安卓电视没有任何音频效果器（`dumpsys media.audio_flinger` 显示 `XML effect configuration failed to load / 0 Effect Chains`），`AcousticEchoCanceler.isAvailable()` 恒为 false。此时 AI 自己外放的声音被麦克风收回，会被云端 VAD 和本地 `bargeVad` 误判为「用户在说话」，导致 **AI 一开口就被自己的声音打断**。

修复方式：在音频 I/O 边界插入一层软件 **WebRTC AEC3**（`Aec3Processor.kt`），**不触碰 SeeduplexClient 协议与 AutoCallController 状态机**：

```
AI 下行(24k) ─► AudioPlayer.write ─┬─► AudioTrack 播放
                                   └─► feedRender() [24k→16k] ─► AEC3 far-end
麦克风(16k)  ─► process() ─► AEC3 ─► 消除后16k ─┬─► client.sendAudio()
                                                └─► bargeVad.feed()
```

只在**硬件 AEC 未真正生效**（`AcousticEchoCanceler.create(session).enabled == false`）的设备上启用；手机仍走硬件 AEC，行为不变。

> ⚠️ **关键坑（本机实测）**：部分安卓电视 `AcousticEchoCanceler.isAvailable()` 返回 **true**，但 `create()/enabled` 实际失败（效果器是空壳，`enabled` 仍为 false），回声完全没被消除。**必须按“是否真正 enabled”判断**，只看 `isAvailable()` 会漏判，导致软件 AEC3 不启用、bug 依旧。

集成要点 / 坑（详见 `Aec3Processor.kt` 头部注释）：
- AEC3 在 16k 下只接受 **10ms = 160 样本**一帧；far-end 必须**连续按实时节拍**喂入（AI 音频突发到达，需队列 + 定拍线程整流，空闲补零）；
- AI 下行 24k、麦克风 16k，far-end 需 **24k→16k 重采样**；AEC3 缓冲样本是 **int16 数值范围的 float**；
- **依赖内置**：官方坐标 `cn.enaium.webrtc.aec3:webrtc-aec3-kmp` 的 AAR 写死 `minCompileSdk=37`，本工程无法直接依赖，故内置其解包产物 `app/libs/aec3-classes.jar` + `app/src/main/jniLibs/arm64-v8a/libwebrtc_aec3_jni.so`；该库用 Kotlin 2.2.10 构建，故 Kotlin 升级到 2.2.10；
- 需要其它 ABI（如 x86_64 模拟器）时，从同一 AAR 的 `jni/<abi>/` 拷入对应 `.so`；缺失 ABI 时 `Aec3Processor.createOrNull()` 捕获异常自动降级为原逻辑。

> 实机实测（KUNL-250A / Android 12 / arm64-v8a，无硬件 AEC）：回声比底噪高约 38 dB，AEC3 稳定估计时延 ≈ 20 ms，**ERLE ≈ 26 dB**。

### 协议要点（实测）

- 客户端上行事件：`session.create` → `input_audio_buffer.append` → `input_audio_buffer.commit` → `session.close`
- 服务端下行事件：`session.created` / `conversation.item.input_audio_transcription.delta`（**累积快照**，非增量）/ `response.output_text.delta`（增量）/ `response.output_audio.delta` / `response.done`
- 模型依赖上行音频流保活；无麦克风时需发送静音事件 `input_audio_mute.commit` 保活
- ⚠️ **输出音频格式坑**：`format.type="pcm"` 返回的是 **32 位浮点**（float32），若按 16bit 播放会得到**全是噪声**；要直接播放必须用 **`pcm_s16le`**（16bit 小端）。上行同理用 16bit（`"pcm"` 输入实测接受 16bit int）。

## 目录结构

```
app/src/main/java/com/mmd/englishdigital/
├── MainActivity.kt          # 通话 UI + 编排（拨号/挂断/测试音频/字幕/自动开关；AEC3 接线）
├── SeeduplexClient.kt       # Seeduplex 全双工 WebSocket 客户端（协议实现，自动功能零改动）
├── AudioIo.kt               # 麦克风采集（硬件 AEC）+ 音频播放（暴露 AEC3 far-end 参考）
├── Aec3Processor.kt         # 软件回声消除 WebRTC AEC3（无硬件 AEC 设备的回退；详见文件头注释）
├── LocalVad.kt              # 本地 VAD（WebRTC VAD GMM）+ 严格 640 字节定长重打包
└── AutoCallController.kt    # 自动拨号/挂断状态机（启动即拨 / 静默挂断 / 说话重拨）
app/libs/
└── aec3-classes.jar         # 软件 AEC3 的 Kotlin API（从官方 AAR 解包，绕过 minCompileSdk=37）
app/src/main/jniLibs/arm64-v8a/
└── libwebrtc_aec3_jni.so    # 软件 AEC3 原生库（arm64-v8a；其它 ABI 从同一 AAR 拷入）
app/src/main/assets/
└── test_input.pcm           # 内置英文测试语音（16k/mono/16bit）
app/src/main/res/raw/
└── avatar.mp4               # 数字人头像视频（720x720 方形，循环静音播放）
app/src/main/res/layout/         # 竖屏布局（纵向）
app/src/main/res/layout-land/    # 横屏布局（左头像 / 右面板）
```

### 本地 VAD 集成要点

- 依赖：com.cloudflare.realtimekit.android-vad:webrtc:2.0.9（Maven Central，MIT，含 4 ABI 原生库）
- 版本坑：该库 2.0.10 用 Kotlin 2.2 构建；本项目为配合软件 AEC3 已把 Kotlin 升到 2.2.10，仍沿用 2.0.9（Kotlin 1.9.21 构建，可被更高版本编译器读取）
- 帧长硬约束：VadWebRTC 在 16kHz 下只接受恰好 320 样本 = 640 字节；LocalVad 内部累加重打包，攒够 640 才判定，余量留存
- 自检：内置英文语音 245760 字节 ÷ 640 = 384 帧，实测判为语音 259 帧（67%），无帧错误
- ⚠️ **线程坑（实机崩溃根因）**：VAD 判决在音频采集线程回调，若在该线程直接触发拨号/挂断（会碰 UI 控件），会抛 CalledFromWrongThreadException 崩溃。AutoCallController 已把所有回调入口统一切回主线程执行。

## 安装到安卓电视

同一个 APK 同时支持手机与电视（已含 Leanback 启动入口、声明触摸屏非必需）。

方式一 ADB 网络安装（推荐）：
```bash
# 电视端：设置 → 关于 → 连点"版本号"7 次进入开发者模式
#         开发者选项 → 打开"USB 调试 / 网络调试(ADB)"
#         设置 → 网络 → 查看电视 IP（如 192.168.1.50）
adb connect 192.168.1.50:5555
adb install -r MMD-English-Digital-v1.6-tv-release.apk
```

方式二 U 盘安装：
1. APK 拷到 FAT32 U 盘，插入电视
2. 电视装个文件管理器（FX / X-plore）打开该 APK
3. 首次需在 设置 → 安全 → 未知来源 中允许该文件管理器安装

电视上的注意事项：
- 麦克风：多数电视没有可被 App 使用的麦克风，语音对话需外接 USB 麦克风；无麦克风时可用界面上的「测试音频」按钮验证链路
- 遥控器：无触摸屏，用方向键导航；按钮已加焦点高亮
- 需要联网；首次会请求麦克风权限（遥控器确认）

## 构建

**API Key 不参与构建，也不写入源码或 APK** —— 由用户在 App 的「设置」页（右上角 ⚙）手动填写，仅保存在本机（SharedPreferences 明文）。

```bash
export ANDROID_HOME=/opt/android-sdk
./gradlew :app:assembleRelease
```

> ✅ 打包出的 APK 内不含任何 Key；换 Key 无需重新打包，在 App 里改即可。

## 实测结论（redroid Android 14 模拟器）

- `session.created` ✅
- 用户语音识别：`"Hello, Emma. I want to practice my English today."` ✅
- AI 语音回复：13 个 `response.output_audio.delta` 音频块 + 11 段文本 ✅
- 0 错误事件 ✅

## Roadmap

- [ ] MMD / 数字人虚拟形象渲染
- [ ] 真实麦克风场景下的 AEC 调优与打断测试
- [ ] 英语纠错反馈（发音评分）
- [ ] 会话历史持久化
