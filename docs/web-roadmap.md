# Web 功能路线与进度

浏览器复用 `shared/commonMain` 的协议 Core；不得在 JS、UI 中重写协议、猜测厂商变体，或用网页测试冒充真机兼容。按最新决定，共享 Kotlin Compose UI 与 Core 迁移为 `wasmJs` 浏览器应用，不另写 HTML/JS 界面；独立 Kotlin/JS 功能库保留。

## 已完成的基础

- [x] File/Blob → 有界内存 BinarySource，Promise API、结构化错误、Blob 输出和 TypeScript 声明。
- [x] 三个既有 JPEG＋MP4 创建目标：OPPO/OnePlus、Google V2、Google V1；默认不转码、不覆盖原片。
- [x] 基础检测、Structure/Protocol 验证、原字节视频提取；真实 Chromium 主线程和 module Worker 验收。
- [x] 直接复用 Android 的共享 Compose UI；浏览器适配文件选择、大小、下载和媒体预览，同源中文字体。
- [x] 基线 CI：25 项功能检查＋20 项窄屏/宽屏 UI 检查；三个协议共六次真实下载并核对视频字节。
- [x] 用户已手动验证网页可用；这不是所有设备相册兼容性认证。

## 继续实施

- [x] 只读协议接口实现：多重检测、Inspect、Analyze、关键照片位置与来源读取。
- [x] Clean Split 接口实现：多资产 Blob 输出、变化清单及分项保真证明；与 Raw Extract 分开。
- [x] 有限 JPEG Convert 接口实现：与 Create 分开，不隐式修复或转码；下述 EXIF 限制仍保留。
- [x] 有限 Repair：默认 dry-run，显式应用安全修改；结构化报告保留阻止/无操作/修复前后问题。Google V1/V2 长度修复通过浏览器验证；默认新建 OPPO 布局保留安全拒绝，见下方限制。
- [x] 已有 JPEG 关键照片 metadata 修改共享入口：输入十进制秒，明确 Nearest/100ms、报告请求和实际时间，Core 验证图片编码/视频保留后另存；不抽帧或替换封面。分数时钟可在明确容差内量化并报告，Exact/FrameIndex/零容差保持严格。新增代码实际验收以对应 CI 为准。
- [x] 共享 Kotlin `commonTest` 的浏览器运行：804 项原有纯内存测试在 `f742538` 的 [真实 Chrome 测试 job](https://github.com/ohyooo/LivePhoto/actions/runs/39184904432/job/117845433218) 全部通过，零失败、零跳过，逐类 JUnit XML 与源声明核对。47 项桌面素材/真实 FFmpeg 测试与宿主适配移入 `desktopTest`，测试内容及素材字节不改；`7190817` 的[三平台 Native CI](https://github.com/ohyooo/LivePhoto/actions/runs/39180470040) 各 925 项 shared＋44 项 CLI 测试通过并独立核验。不是重写 JavaScript 测试，也不是 Kotlin/Wasm 迁移；Android 尚未执行这 804 项共用测试。
- [x] 已公开 API 的浏览器回归：正向/结构化错误、不可变输入、多资产、字节一致性和安全拒绝；主线程与 Worker 验收分开，当前 Worker 覆盖 Create、Raw Extract 和 Repair 预览。
- [x] 当前十个公开 API 的 Worker 专项：42 项检查覆盖三个创建目标、只读接口、Clean Split、Convert、Repair 预览/应用或安全拒绝、错误与预算，以及跨线程 Blob 哈希；在 `5009915` 的 [Web CI 协议 job](https://github.com/ohyooo/LivePhoto/actions/runs/39141307170/job/117716540201) 由真实 Chromium 全部执行成功。此处仅指该组验收，不冒充整轮 UI/播放器或发布成功；未来新接口仍须补充专项。
- [x] 共享 Compose UI 接入只读 Analyze：单独选择待检查照片，不依赖或覆盖生成用的图片、视频；展示多重匹配、分层验证/覆盖、关键照片、媒体信息与问题代码，Unknown/NonLive、未知/零时间和 Partial/NotRun 分开。Android/Wasm 复用同一控件，输入只读、无输出事务，不增加网页协议实现。当前提交的实际编译、两端 UI/IO 验收以 CI 为准，不用此前 Wasm 迁移结果代替。
- [x] 共享 UI 原始视频提取入口：与只读检查分开触发，复用 Core Raw Extract，确认唯一视频及 ExactExtraction/SHA-256 后才发起下载或 Android MediaStore 保存；单张输入 32 MiB，仅已识别 MP4/MOV，不清理、不转码、不修改原片。新提交实际验收以 CI 为准，不用上轮只读检查结果代替。
- [x] 共享 Compose Clean Split 入口：有界内存中准备普通 JPEG + MP4/MOV，Core 验证图片编码保留与视频字节一致性，按资产呈现 metadata 结果，再显式分别保存；不承诺外部成对原子提交。重选文件清除待保存结果，失败不输出、不转码；当前提交实际验收以 CI 为准，不能用上轮 Raw Extract 验收代替。
- [x] 共享 Compose 有限协议转换 UI：独立目标选择和另存入口，三个既有 JPEG 目标；复用 Core Convert，同目标 PreserveAsIs，不将普通照片变成 Create、不自动修复/转码、不授权 metadata 损失，保留 OPPO→Google 的 EXIF 安全拒绝。输入/目标变更清旧报告，具体两端验收以对应提交 CI 为准，不能拿上轮拆分结果代替。
- [x] 共享 Compose 有限 Repair UI：默认只读预览、存在安全方案才允许显式另存；应用前核对已审阅输入摘要与完整方案，保留 no-op/blocked/OPPO 模糊布局的无输出行为。仅 SafeMetadataOnly，不 remux/转码/编码音频、不改原片；单一 JPEG 导出，报告不展开私人 metadata 值。新提交实际两端验收以 CI 为准，不能拿上轮转换结果代替。

## 后续、尚未实现

- [ ] 不移动 EXIF 的固定长度 XMP 槽位优化及独立保留证明。当前 Core 对 XMP 调整会移动未验证 EXIF 的布局返回 `UNSAFE_METADATA_REWRITE`，Web 保留此拒绝，不删除 EXIF 或增加丢失授权。新建 OPPO 文件转 Google 的两个方向受此限制；同目标原样复制与无此布局依赖的转换分开验证。
- [ ] 独立核验 OPPO 显式次级 `Padding="0"` 的修复边界。当前默认新建布局含此字段，现有 Core Repair 刻意不接受；Web 返回 `REPAIR_AMBIGUOUS` 且无输出，不能把可创建误写为可修复，也不在适配层移除字段绕过。
- [ ] 大文件随机访问、减少内存副本、UI 后台 Worker 和明确的取消语义。当前不是任意大文件流式实现。
- [x] 网页有限 A/B/C 编辑：原生 Video/Canvas 预览、暂停拖动、当前画面 C 截图及生成 key；上次生成结果按住播放、松开回封面。
- [x] 网页有限媒体预处理：按需同源单线程 ffmpeg.wasm 导出 A/B copy 裁剪、显式 8-bit SDR H.264 视频转码；音频始终 copy，取消/预算/超时和输出包验证。不是桌面进程，不默认转码、不增加音频编码。
- [x] 浏览器有限 Core MediaBackend 接入：共用 FFmpeg backend 的 probe/trim/remux/transcode/extractFrame 与验证逻辑，新增有界私有内存执行适配，实际执行标识 ffmpeg-wasm；JS 提供显式可选 createMediaBackend，Wasm 有类型化适配。默认协议库不加载媒体引擎；后端均为 Experimental，格式/轨道/metadata/色彩与时间线限制不放宽。新增代码真实验证以对应 CI 为准。
- [ ] 将现有播放器的宽松预处理操作逐项迁移到上述严格 Core 媒体 profile。目前保留原有 Video/Canvas 当前画面截图及 ffmpeg.wasm 预处理，避免缩小已可用输入范围；不能把新有限 API 当作全部播放器流程已统一或全部媒体格式支持。
- [x] Kotlin/Wasm Compose 应用：`6837e1a` 的 [Web CI 39190852421](https://github.com/ohyooo/LivePhoto/actions/runs/39190852421) 全部 7 个 job 通过。复用现有 `ui/commonMain`，浏览器入口采用类型化互操作；公共 Kotlin 测试在 JS/Wasm 下各 804 项、零失败零跳过，原有 222 项外围检查及 Worker 内部 42 项也通过。真实界面生成和下载由 Wasm 应用执行，独立 JS 库用于审计输出，不重写界面或协议。后续产物命名和缓存调整须再次通过相同 CI，在线状态以实际部署为准；不能仅凭 Skiko/FFmpeg 的 Wasm 文件宣称完成。
- [ ] 搁置的观察项：播放器画面外透明孔洞/残影。用户未观察到，暂不投入处理、不阻塞后续任务，也不标记为已修复。

Apple 新写入仍暂停，移动端系统媒体后端仍是单独 TODO。输入不上传服务器；仓库和 CI 不增加手机/reference 原片或其衍生成品。测试用独立合成素材，报告不附带媒体。

## 本轮状态

深浅色跟随浏览器及右上角太阳/月亮切换已在 [Web CI 39135176016](https://github.com/ohyooo/LivePhoto/actions/runs/39135176016) 的 `26c8790` 验证并部署：70 项协议、38 项 UI、11 项媒体、91 项播放器、12 项主题，共 222 项。真实指针切换、浏览器偏好变更、手动覆盖、媒体保留和刷新恢复跟随均有独立证据；透明孔洞仍是已知 TODO。

普通播放器整体半屏高度、宽布局 96dp 音量条及竖屏单行控件已在 `23f69d5` 的 [Web CI](https://github.com/ohyooo/LivePhoto/actions/runs/39179034248) 验证并部署；全屏不受半屏限制。竖屏播放、静音、音量、倍速、全屏保持同一行，按钮保留自身空间，音量条使用剩余宽度，而不是单独换行。Worker 的 42 项检查单独记入协议报告 `worker` 字段，不把主线程 70 项改称 Worker 验收，也未将网页耗时工作自动迁到 Worker。

共用测试使用独立 `Test Kotlin common protocol` job，与四个外围浏览器测试 job 并行，复用编译缓存但不缓存测试结果。每轮对照 `commonTest` 源声明核验全部用例、零跳过/失败，保存逐套 XML 哈希，全部 job 通过才发布。上述历史结果为 Kotlin/JS；本轮增加同一份测试的 `wasmJs` 执行及共享 Compose 应用 Wasm 入口，实际结果以对应 CI 为准，不用旧结果代替新代码验证。

CI 已拆分为一次生产构建、四个并行测试 job（协议、媒体、播放器、完整 UI）和汇总发布。[Web CI 39131691095](https://github.com/ohyooo/LivePhoto/actions/runs/39131691095) 在代码 `6b7e960` 的六个 job 全部成功：70 项协议、11 项媒体、91 项播放器、38 项完整 UI，共 210 项。各测试下载同次构建产物，核对提交号与逐文件哈希，不重复编译；某组失败不会取消其它组。全部通过才发布正式库和网站，成功汇总后清除临时传递产物，失败保留独立报告。

缓存已真实验证：Core/UI 编译与 Webpack 命中 Gradle 任务缓存，该次 Gradle 构建为 58 秒（上一轮 3 分 37 秒）；FFmpeg 测试工具缓存恢复并验证约 3 秒，无需每个 job 重跑 Homebrew。此数字是对应运行的观测，不是固定耗时承诺。

共享播放器交互已在上述后续 CI 验证并随暗色版本部署，视觉残影尚未修复：播放器完整控制 UI 在 Kotlin commonMain，Web 只保留媒体表面与操作适配；删除 A/B/C 按钮命名。裁剪为勾选后展开的双手柄区间条，封面为独立可展开的单手柄时间轴，持续拖动时更新预览；真实测试在松手前核对图片变化。显式近似 key 可在容差内量化到目标整数时钟并报告 `KEY_TIMESTAMP_QUANTIZED`；Exact、FrameIndex 和零容差不放宽。生成回归用 29.97fps / 90kHz / AAC；91 项播放器检查覆盖两尺寸、三种视频形状、真实指针/键盘/全屏/选段。每次部署须对应实际通过的提交，不能拿旧版验收证明新改动。

浏览器 A/B/C 有限编辑已通过 [Web CI 39071450910](https://github.com/ohyooo/LivePhoto/actions/runs/39071450910)，实际代码 `b2378e2`：**70 项协议＋38 项 UI＋11 项媒体检查**全部成功。共享 Compose 控件与原生 Video/Canvas 负责暂停预览、当前画面 C 截图；A/B 导出使用按需、同源、单线程 ffmpeg.wasm。copy 模式验证音视频编码包连续且不变，报告实际保留范围；显式 H.264 转码仅接受 8-bit SDR，音频始终 copy。C 时间仅在有验证依据时映射，无法证明时要求重新截取。上次结果可按住播放，仅模拟效果，不代表相册兼容。编辑上限 32 MiB / 60 秒，输出上限 48 MiB，总处理超时 120 秒；原片不覆盖。

验收在真实 Chromium 中完成两个尺寸、12 次实际生成下载，核对 C/key、裁剪后时间重定位、转码后旧 C 拒绝和重新截取。媒体检查另用原生 ffprobe 独立核对压缩包 SHA256：copy 视频/音频不变，显式转码只改变视频；包含取消、超预算、无效范围与损坏媒体拒绝。四份产物的外层/逐文件哈希及四张合成界面截图已独立复核。此结果不是 Safari/所有浏览器、HDR 保真、手机相册或新版本已部署的证明。

`repairLivePhoto` 只开放 SafeMetadataOnly，默认 dry-run，显式应用后返回 Repair 报告及原子提交的 assets；success 不等于已修复，被阻止/无需修改/预览时均无输出。Google V1/V2 的无操作、独立损坏长度修复、过滤阻止、保真和幂等通过验证；OPPO 默认新建布局单独验证安全拒绝/无输出/输入不变，不算修复成功。加上错误/预算/歧义后缀检查，[Web CI 39006350139](https://github.com/ohyooo/LivePhoto/actions/runs/39006350139) 在代码提交 `a9699f3` 通过功能库 **70 项**、共享 UI **22 项**。UI 实际从 `/livephoto/` 子路径加载字体/Wasm，保留两尺寸六次真实下载审计；主线程与 Worker 均调用修复 API。产物清单、哈希与四张界面截图已独立复核。本阶段没有新增 Repair UI 按钮，也没有扩大协议支持。

只读检查/分析/关键照片读取、Clean Split、Convert 的 JS 适配和 TypeScript 声明已实现；当前全部公开接口已另有 42 项 Worker 专项。接口可用不表示任意容器变体均受支持，也不表示已在共享 UI 开放操作按钮或自动后台执行。

上一阶段的 50 项功能库检查（结构化检查、Clean Split、有限 Convert、安全拒绝及 Worker 基础）和 20 项 UI 操作均保留在后续检查中。**测试是否通过，以对应提交的 [Web CI](https://github.com/ohyooo/LivePhoto/actions/workflows/web.yml) 为准，不能用旧结果代替新代码验证。** 未来新接口的 Worker 增量验收、已有载体的独立关键照片写入、更多共享 UI 功能及正式浏览器 Core MediaBackend 仍未完成。
