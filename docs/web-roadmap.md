# Web 功能路线与进度

浏览器复用 `shared/commonMain` 的协议 Core；不得在 JS、UI 中重写协议、猜测厂商变体，或用网页测试冒充真机兼容。先完善 Kotlin/JS，Kotlin/Wasm 是后续可选项，不是当前发布前提。

## 已完成的基础

- [x] File/Blob → 有界内存 BinarySource，Promise API、结构化错误、Blob 输出和 TypeScript 声明。
- [x] 三个既有 JPEG＋MP4 创建目标：OPPO/OnePlus、Google V2、Google V1；默认不转码、不覆盖原片。
- [x] 基础检测、Structure/Protocol 验证、原字节视频提取；真实 Chromium 主线程和 module Worker 验收。
- [x] 直接复用 Android 的共享 Compose UI；浏览器仅适配文件选择、大小和下载，同源中文字体。
- [x] 基线 CI：25 项功能检查＋20 项窄屏/宽屏 UI 检查；三个协议共六次真实下载并核对视频字节。
- [x] 用户已手动验证网页可用；这不是所有设备相册兼容性认证。

## 继续实施

- [x] 只读协议接口实现：多重检测、Inspect、Analyze、关键照片位置与来源读取。
- [x] Clean Split 接口实现：多资产 Blob 输出、变化清单及分项保真证明；与 Raw Extract 分开。
- [x] 有限 JPEG Convert 接口实现：与 Create 分开，不隐式修复或转码；下述 EXIF 限制仍保留。
- [x] 有限 Repair：默认 dry-run，显式应用安全修改；结构化报告保留阻止/无操作/修复前后问题。Google V1/V2 长度修复通过浏览器验证；默认新建 OPPO 布局保留安全拒绝，见下方限制。
- [ ] 关键照片 metadata 写入；与视频抽帧、替换封面分开。
- [ ] 将不依赖文件系统、真实编码素材或 FFmpeg 的公共协议回归接入 JS；不伪造平台 actual、不静默跳过后端测试。
- [x] 已公开 API 的浏览器回归：正向/结构化错误、不可变输入、多资产、字节一致性和安全拒绝；主线程与 Worker 验收分开，当前 Worker 覆盖 Create、Raw Extract 和 Repair 预览。
- [ ] 为其它已公开 API 补齐 Worker 专项，以及后续新接口的相应回归；不能以主线程结果代替 Worker。
- [ ] 后续按需将已验证能力接入共享 UI，不另写网页业务逻辑。

## 后续、尚未实现

- [ ] 不移动 EXIF 的固定长度 XMP 槽位优化及独立保留证明。当前 Core 对 XMP 调整会移动未验证 EXIF 的布局返回 `UNSAFE_METADATA_REWRITE`，Web 保留此拒绝，不删除 EXIF 或增加丢失授权。新建 OPPO 文件转 Google 的两个方向受此限制；同目标原样复制与无此布局依赖的转换分开验证。
- [ ] 独立核验 OPPO 显式次级 `Padding="0"` 的修复边界。当前默认新建布局含此字段，现有 Core Repair 刻意不接受；Web 返回 `REPAIR_AMBIGUOUS` 且无输出，不能把可创建误写为可修复，也不在适配层移除字段绕过。
- [ ] 大文件随机访问、减少内存副本、UI 后台 Worker 和明确的取消语义。当前不是任意大文件流式实现。
- [ ] 浏览器媒体后端：抽帧、裁剪、remux、transcode。不能调用桌面 FFmpeg 进程，不能据此默认转码或增加音频编码。
- [ ] Kotlin/Wasm target 与独立验收；Compose 图形运行时的 Wasm 文件不代表 Core 已实现 Kotlin/Wasm。

Apple 新写入仍暂停，移动端系统媒体后端仍是单独 TODO。输入不上传服务器；仓库和 CI 不增加手机/reference 原片或其衍生成品。测试用独立合成素材，报告不附带媒体。

## 本轮状态

浏览器 A/B/C 编辑正在实现、尚待新 CI 验证：共享 Compose 控件，浏览器原生 Video/Canvas 负责暂停预览和当前画面 C 截图；A/B 导出接入按需、同源、单线程 ffmpeg.wasm。copy 模式验证音视频编码包连续且不变，报告实际保留范围；显式 H.264 转码仅接受 8-bit SDR，音频始终 copy。C 时间仅在有验证依据时映射，无法证明时要求重新截取。另有上次生成结果的按住播放预览，仅模拟效果，不代表相册兼容。暂定编辑上限 32 MiB / 60 秒，处理超时 120 秒；原片不覆盖。计划新增 10 项浏览器媒体／独立原生数据包审计，并将两尺寸 UI 检查扩展至 38 项、12 次实际生成下载；以下旧 70＋22 结果不能作为新代码验证。

`repairLivePhoto` 只开放 SafeMetadataOnly，默认 dry-run，显式应用后返回 Repair 报告及原子提交的 assets；success 不等于已修复，被阻止/无需修改/预览时均无输出。Google V1/V2 的无操作、独立损坏长度修复、过滤阻止、保真和幂等通过验证；OPPO 默认新建布局单独验证安全拒绝/无输出/输入不变，不算修复成功。加上错误/预算/歧义后缀检查，[Web CI 39006350139](https://github.com/ohyooo/LivePhoto/actions/runs/39006350139) 在代码提交 `a9699f3` 通过功能库 **70 项**、共享 UI **22 项**。UI 实际从 `/livephoto/` 子路径加载字体/Wasm，保留两尺寸六次真实下载审计；主线程与 Worker 均调用修复 API。产物清单、哈希与四张界面截图已独立复核。本阶段没有新增 Repair UI 按钮，也没有扩大协议支持。

只读检查/分析/关键照片读取、Clean Split、Convert 的 JS 适配和 TypeScript 声明已实现。接口可用不表示任意容器变体均受支持，也不表示已在共享 UI 开放操作按钮。

上一阶段的 50 项功能库检查（结构化检查、Clean Split、有限 Convert、安全拒绝及 Worker 基础）和 20 项 UI 操作均保留在本轮 70＋22 项中。**测试是否通过，以对应提交的 [Web CI](https://github.com/ohyooo/LivePhoto/actions/workflows/web.yml) 为准，不能用旧结果代替新代码验证。** 公共协议 commonTest 的 JS 迁移、其余 Worker 专项、关键照片写入、更多共享 UI 功能及浏览器媒体后端仍未完成。
