# LivePhoto Web UI 设计交接

面向重新设计界面的 ChatGPT 或设计师。产品是浏览器本地运行的动态照片工具：用图片和视频生成 Live Photo，也能检查、提取、拆分和有限转换已有动态照片。界面实现必须继续使用 Kotlin Compose Multiplatform，编译为 Kotlin/Wasm；不是另建 HTML、React 或 JavaScript UI。

可以重新组织布局、导航、视觉层级和文案，但不能改变下面的业务语义、输入限制与安全边界。当前范围仅 Web，不需要设计 Android、iOS、账户、云端上传或付费系统。

## 技术结构

```text
共享 Compose UI（ui/commonMain）
    → LivePhotoUiPlatform / VideoEditor
    → 浏览器 Kotlin/Wasm 任务适配与后台 Worker
    → shared 的统一 Application / Core API
    → 协议代码 / 可选 FFmpeg Wasm 媒体后端
```

浏览器 File、Blob、下载、原生视频画面与 Canvas 由平台适配承接；控件、交互状态和布局在共享 Kotlin 中。不要在 UI 或 JS 里再实现一套协议、裁剪、转码逻辑。独立 Kotlin/JS 功能库面向外部接入和测试，不是 Compose 页面必须绕行的接口。

文件只在浏览器内处理，不自动上传。FFmpeg 引擎是按需加载的静态资源；“需要加载引擎”不等于“需要上传媒体”。

## 当前功能和信息架构

当前为“新建 / 照片工具”两页，共享 Kotlin Compose 实现。宽度小于 800 为单列、800～1279 为双列、1280 起为三列，主体最大宽度 1800。源码 `57edf96` 的 [Web CI](https://github.com/ohyooo/LivePhoto/actions/runs/39969395659) 已通过全部 32 个 job，产物与 32 张合成截图已核验；含后台 Worker 的网站已部署并通过正常域名 HTTPS 文件校验。依赖经用户批准的 Compose/Material3 1.13.0-alpha02，不代表稳定版、所有浏览器或 Android 验收。

新建页依次为视频与图片输入、视频预览、裁剪、更多视频处理、封面位置、输出设置、结果。裁剪、媒体处理、封面分别折叠，折叠不清空草稿；待应用裁剪与失效封面会阻止生成。输入可更换、移除，重置需确认。结果在操作后出现，输入或目标改变后标为旧结果。照片工具有独立输入与工具选择，只显示当前工具参数。

| 功能区 | 输入和操作 | 结果与边界 |
|---|---|---|
| 新建动态照片 | 选择图片、选择视频、选择目标协议、生成并保存 | 图片 JPEG＋视频 MP4 的已支持配置；生成 JPEG 并发起下载，不覆盖原片 |
| 输入信息 | 展示文件名、大小、是否选择，允许重新选择 | 取消选择器保留旧选择；不是按扩展名保证受支持 |
| 视频预览 | 播放暂停、进度、时间、音量、静音、倍速、全屏 | 原生视频画面＋共享 Compose 控件；能播放不代表 Core 能安全处理 |
| 封面选择 | 展开后显示独立单手柄时间轴和实时预览，再明确确认 | 拖动仅预览；确认后由 Core 抽取 JPEG，采用返回的实际帧时间 |
| 裁剪 | 展开后显示缩略图、双手柄时间轴、可输入起止时间，明确应用或重置草稿 | 默认音视频 copy；实际范围可能与请求不同，必须展示二者 |
| 视频转码 | 明确点击转为 H.264 并使用 | 仅显式视频编码，音频 copy；裁剪开启时先应用裁剪，再转码 |
| 视频重新封装 | 明确点击重新封装 | 不编码；成功后替换本次生成所用的视频，不修改原文件 |
| 视频检查 | 含解码的 Core Probe | 只读，无替换输入、无下载 |
| 上次结果 | 显示封面，按住播放，松开返回封面 | 支持空格或回车；只是浏览器模拟，不是手机相册兼容性认证 |
| 检查已有动态照片 | 独立选择待检查照片并分析 | 不依赖新建的图片/视频；展示协议、结构、验证、关键照片、问题 |
| 提取原始视频 | 从已有动态照片另存视频 | Raw Extract，验证原字节一致性，不等于清理拆分 |
| 清理拆分 | 先准备和验证普通图片＋视频，再分别保存 | JPEG＋MP4/MOV 的已支持范围；两个保存状态分开，不保证外部成对原子保存 |
| 协议转换 | 已有动态照片选择另一目标并另存 | Convert，不是 Create；保留拒绝不安全 EXIF 改写的行为 |
| 安全修复 | 先只读预览方案，再显式应用另存 | 仅可证明安全的 metadata 修复；无须修复、被阻止、修复成功必须区分 |
| 修改关键照片时间 | 输入十进制秒并另存 | 只改已有照片的 key metadata，不抽帧、不替换封面；最近位置，容差 100ms |

新建和转换公开的三个目标：

- OPPO / OnePlus：`oplus.olive`，新建使用 `jpeg-no-tail` profile。
- Google Motion Photo V2：`google.motionphoto.v2`，`jpeg` profile。
- Google MicroVideo V1：`google.microvideo.v1`，`jpeg` profile。

这不是所有厂商、所有容器、所有 metadata 组合都可写。Apple 新写入暂停，不设计成可用入口。

## 播放器必须保留的交互

- 普通状态整个播放器最高不超过浏览器高度的一半；全屏不受此限制。保持原始长宽比，不裁掉视频内容。
- 竖向视频的播放、静音、音量、倍速、全屏在同一工具行，按钮保留 padding 和必要间距，其余空间给音量条。当前播放进度竖置在画面右侧。
- 横向视频的播放进度横置；音量条应有可操作宽度。窄到放不下时可适应布局，不能挤压所有命中区域。
- 倍速有 0.5、0.75、1、1.25、1.5、2 倍；菜单展开不能覆盖视频画面。
- 播放进度、裁剪范围、封面位置是三个不同控件。不要使用 A、B、C 作为对外按钮名称。
- 裁剪范围和封面位置默认折叠，点击各自标题展开。拖动时更新画面，暂停拖动可预览当前位置。
- 封面时间轴拖动期间实时更新预览，不必帧级精确，也不能自动替换已选图片；正式确认后使用 Core 的实际时间。
- copy 裁剪只有在时间映射经验证、封面位于保留范围内时才沿用并重定位旧封面；范围外拒绝替换视频。重新封装或转码等未知映射要求重新选封面，不能悄悄沿用旧时间。
- 右上角太阳/月亮菜单提供跟随系统、浅色、深色；默认跟随浏览器，手动覆盖本页有效，刷新重新跟随浏览器。

## 状态与反馈

请设计空状态、已选文件、读取信息、播放器未就绪、拖动预览、处理中、成功、失败、取消及结果需要重新选择等状态，而不只画成功页面。

新建、媒体编辑与已有照片工具相互协调忙状态，避免并发改输入；同一个视频只有一套媒体状态、结果和取消入口。媒体处理中显示取消按钮。其它操作目前并非都具备通用取消 API，不能设计成全部已经支持。

错误要让用户看到关联对象、步骤、错误码、阶段、具体原因；明确是图片、视频、合并还是保存问题。主生成错误显示在结果卡片；完成后仅在需要时滚动至结果。报告默认折叠，失败原因直接可见；支持展开、文本选择和显式复制，不自动读取或写入剪贴板。

处理失败不替换输入、不输出半成品、不自动改用另一实现或转码。重新选择待检查文件后，清除依赖旧文件的分析、拆分、转换、修复、关键照片结果；取消文件选择不清除原输入。

必须区分：

- 已生成并发起下载，与浏览器实际保存完成。
- Supported、Experimental、有条件支持、Unsupported，与“能在播放器播放”。
- Valid、Invalid、Unknown，以及 Complete、Partial、NotRun；不能压缩成一个成功勾号。
- metadata 保留的 Verified、Unknown 和变化清单，不能统一宣称“无损”。
- 修复预览、无需修复、被阻止、已应用修复；`success` 不代表一定发生修改。
- 两个拆分文件各自的保存状态；某个失败不把另一个标成失败或重新自动保存。

## 现有 Kotlin UI 接口

页面入口是 `LivePhotoScreen(platform, darkTheme, onToggleTheme, themeMode, onThemeSelected)`；`ThemeMode` 包含 System、Light、Dark。新增平台能力为 `supportsClipboard` / `copyText`、`release`、`coverWarning` / `coverPosition`；`VideoEditor` 增加只读 `thumbnail` / `previewTimeline`。这些默认实现不扩大协议或媒体写入能力。下面是设计接入清单，不代替源码的完整签名。引用字符串是不透明的平台文件引用，不是可由 UI 拼接的路径。

| `LivePhotoUiPlatform` 方法 | 用途与返回 |
|---|---|
| `rememberPicker(kind, onResult)` | Compose 文件选择器；`MediaKind.Image / Video / LivePhoto`；取消返回 null |
| `describe(reference)` | `SelectedMediaInfo(displayName, sizeBytes)` |
| `runTask(task)` | 当前共享 UI 的操作入口：`UiTaskKind` 区分七种操作；`UiTaskResult` 返回报告、失败状态、可选另存动作和修复应用动作 |
| `cancelTask()` / `supportsTaskCancellation` | 后台操作取消；仅支持的平台显示按钮；未发布的结果不得下载 |
| `create(image, video, target)` | `CoreResult<CreatedPhoto>`，包含 `OperationResult`；浏览器适配负责发起下载 |
| `analyze(reference)` | `CoreResult<AnalysisResult>` |
| `extractVideo(reference)` | `CoreResult<ExtractedVideo>`；适配负责导出 |
| `cleanSplit(reference)` | `CoreResult<PreparedCleanSplit>`，准备但不自动保存两个文件 |
| `saveSplitFile(file)` | 明确保存一个 `SplitFile`，返回状态说明 |
| `convertPhoto(reference, target)` | `CoreResult<ConvertedPhoto>` |
| `repairPhoto(reference, review = null)` | `CoreResult<PhotoRepairReport>`；null 是只读预览，应用必须传已审阅 `RepairReview` |
| `setPhotoKey(reference, requested: Time)` | `CoreResult<PhotoKeyReport>` |
| `rememberVideoEditor(reference)` | 当前输入对应的 `VideoEditor?` |
| `ImagePreview` / `ResultPreview` | Compose 平台媒体预览插槽，不在 UI 重写解码 |

平台还提供 `supportsAnalysis`、`supportsVideoEditing` 等功能开关，以及输出位置、结果预览可用性和成功说明。设计应允许不可用能力隐藏或明确禁用，不推断所有平台都有功能。

共享控件现在调用 `runTask`，不再直接展开 Core 对象。上表原有 typed 方法保留给既有宿主适配，默认桥接继续复用同一报告格式；Web 覆盖任务入口，在 Kotlin/Wasm Worker 中调用原应用 helpers 和 Core，不经过独立 JS 功能库。`UiTaskResult.saves` 是清理拆分的逐文件显式保存动作；`applyRepair` 仅在预览有安全方案时存在。不要自行构造它、绕过预览，或将取消/失败当作成功保存。

`VideoEditor` 是唯一媒体编辑接口：

```kotlin
val playback: State<VideoPlayback>
fun seek(seconds: Double)
fun togglePlayback()
fun setVolume(volume: Double)
fun setMuted(muted: Boolean)
fun setRate(rate: Double)
fun setFullscreen(fullscreen: Boolean)
suspend fun previewFrame(): String // 只读预览引用
suspend fun process(action: CoreMediaAction, start: Time, end: Time): CoreMediaEdit
fun cancel()
```

`CoreMediaAction`：`Probe / Remux / Trim / ExtractFrame / Transcode`。`CoreMediaEdit` 返回摘要，可带新图片引用或 `EditedVideo`；Probe 没有媒体输出。`VideoPlayback` 包含时长、当前时间、是否就绪/定位中、错误、尺寸、暂停、音量、静音、倍速和全屏状态。全屏按钮还要检查 `canFullscreen`。

Core 的 `Time(value, timescale)` 是有理时间，不要用 UI 显示的舍入秒数覆盖原始时间。设计只需要友好显示时间，实际请求、实际边界与 key 的换算继续交给已有应用逻辑。

## 独立浏览器功能库接口

仅供理解外部功能边界；重新设计共享 Compose UI 应优先使用上面的 Kotlin 接口。完整 TypeScript 声明在 [`scripts/web/livephoto.d.mts`](../scripts/web/livephoto.d.mts)。

协议接口均接受 Blob 并返回 `Promise<WebResult>`：

```text
createLivePhoto(image, video, options?)
detectLivePhoto(input) / inspectLivePhoto(input)
validateLivePhoto(input) / analyzeLivePhoto(input)
getKeyPhotoPosition(input)
extractVideo(input) / splitLivePhoto(input)
convertLivePhoto(input, { target })
repairLivePhoto(input, { dryRun?, allowedIssueCodes? })
```

可选 `createMediaBackend({ runtimeUrl?, signal?, onDiagnostic? })` 提供 `capabilities / probe / remux / trim / extractFrame / transcode`。转码必须明确 `authorizeTranscode: true`。诊断可能包含私人 metadata，不自动上传。

`WebResult` 有 `success / code / stage / message / protocol / verdict / coverage / issues / mime / blob / assets / report`。多资产提供角色、字节数、摘要、推荐文件名与 Blob；64 位整数及精确十进制 metadata 使用字符串，不当成 JS Number 舍入。JS 库目前没有导出 `setPhotoKey`，不能把 Kotlin UI 的能力误写成已有 JS 导出。

## 当前处理限制

- 新建图片与视频合计不超过 32 MiB；已有照片单文件不超过 32 MiB。
- 媒体编辑输入不超过 32 MiB、时长不超过 60 秒，输出不超过 48 MiB，有预算、超时与取消检查。
- 媒体后端为有限 Experimental 范围；当前写视频只开放无音频或符合验证条件的立体声 AAC。单声道、多声道等配置不能仅因能播放就允许导出。
- 音频永远不编码。裁剪、重封装不编码；H.264 视频编码必须显式操作，不默认转码、不后台自动“修复格式”。
- 抽帧和视频编码不承诺 HDR、ICC、所有 metadata 的完整保真，报告与拒绝逻辑必须保留。
- OPPO→Google 的部分 EXIF 布局改写会安全拒绝；OPPO 模糊修复布局会拒绝。不能在 UI 中增加“忽略错误”绕过。
- 生成成功不代表任何手机相册都能识别，浏览器结果预览也不提供此保证。

## UI TODO

下表同时保留已完成项和待实施项，具体以状态列为准。可以出设计稿，但必须区分纯界面调整和需要 Core 能力的项目。

| TODO | 设计需要覆盖 | 实施依赖与状态 |
|---|---|---|
| 本次整体视觉与信息架构重设计 | 两页、响应式列布局、紧凑输入和唯一生成栏 | 已实现并验收，已部署并通过正常域名 HTTPS 校验 |
| 封面流程的信息表达 | 预览、已确认、需要重选及实际位置分别显示 | 已实现并通过媒体 UI、真实生成下载检查；没有第二套抽帧实现 |
| 统一报告展示 | 摘要、折叠诊断、失败原因和显式复制 | 已实现并通过回归；结构化报告保留，显式复制前不操作剪贴板 |
| 大文件与后台处理体验 | 预算提示、页面可交互、处理/取消/结果状态 | 七个协议入口已完成分块读取，含修复摘要与 SOURCE_CHANGED；UI Worker 已验收并部署。32 MiB 限额不变，输出仍有界内存；独立 JS 库的分块 IO 与流式输出待实现 |
| 取消与任务状态一致性 | 区分已取消、已完成及保存状态 | 协议与媒体任务已提供取消；模块加载和运行中取消、迟到结果丢弃、重新操作已通过真实 UI 验收。修复取消后需重新预览；已发起的浏览器下载不可撤回 |
| 更完整的视频格式能力提示 | 选择后说明可预览、可处理、为何不能写出 | 必须使用真实能力与错误；单/多声道 AAC 保留能力待实现，不能以 UI 放行代替 |
| OPPO 转换与修复受限状态 | 显示具体限制和无输出，不能误提示已修复 | 固定长度 XMP 槽位、Padding 边界与保留证明是 Core TODO，不是加按钮即可实现 |
| 播放器透明孔洞或残影 | 保留观察记录 | 用户暂时搁置，不要求本次重设计修复，也不标记为已解决 |

不纳入本轮：Android/iOS UI、移动端系统媒体 API、Apple 新写入、批处理、云端上传、账号、项目历史。这些不是已有 API 能力，若画入远期构想必须独立标明未实现，不能混进当前验收范围。

## 设计稿交付要求

请交付窄屏和宽屏的主要界面、深浅色、关键交互与错误状态，以及组件和页面结构建议。优先让“选择输入→必要编辑→生成下载”简单可见，把诊断等高级内容渐进展开。现有原子输出、文件隐私、安全拒绝、真实保存状态和能力边界不可因视觉简化而删除。

实现阶段继续使用共享 Compose 和现有接口；不新增旧版模式、不复制媒体逻辑。当前浏览器测试覆盖真实指针/键盘/文件选择和下载，重设计后应调整定位方式而不是删除这些行为检查。

## 源码入口

- [`LivePhotoScreen.kt`](../ui/src/commonMain/kotlin/livephoto/ui/LivePhotoScreen.kt)：主页面与生成状态。
- [`LivePhotoUiPlatform.kt`](../ui/src/commonMain/kotlin/livephoto/ui/LivePhotoUiPlatform.kt)：共享页面的平台接口。
- [`VideoEditing.kt`](../ui/src/commonMain/kotlin/livephoto/ui/VideoEditing.kt) 与 [`VideoPlayer.kt`](../ui/src/commonMain/kotlin/livephoto/ui/VideoPlayer.kt)：媒体编辑、播放器和状态接口。
- [`PhotoAnalysisPanel.kt`](../ui/src/commonMain/kotlin/livephoto/ui/PhotoAnalysisPanel.kt)：已有照片工具入口。
- [`CoreMediaEditing.kt`](../ui/src/commonMain/kotlin/livephoto/ui/CoreMediaEditing.kt)：统一媒体应用编排，不在设计层重写。
- [`BrowserApplication.kt`](../ui/src/wasmJsMain/kotlin/livephoto/ui/browser/BrowserApplication.kt)：浏览器选择、预览、下载及编辑适配。
- [`web-roadmap.md`](web-roadmap.md)：功能路线与未完成项。
