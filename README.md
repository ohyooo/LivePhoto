# LivePhoto

[![Build](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml/badge.svg)](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)
[![Android](https://github.com/ohyooo/LivePhoto/actions/workflows/android.yml/badge.svg)](https://github.com/ohyooo/LivePhoto/actions/workflows/android.yml)

使用 Kotlin Multiplatform 实现的 Live Photo / Motion Photo Core 与命令行工具。可检测、检查、验证、创建、转换、提取和拆分支持范围内的动态照片，并提供有限的修复、关键帧位置修改及媒体处理能力。

**当前为开发中的实验性版本，不是全厂商、全格式兼容工具。** Core 与 CLI、文件路径及具体媒体库解耦；桌面提供三平台 Kotlin/Native 库和原生 CLI，历史 JVM 运行时与桌面系统媒体实现已移除。Android 直接复用 Core，测试界面使用 Compose；不是 Core JNI 或桌面 JVM 发行版。iOS 和完整多平台 GUI 尚未交付。能力以运行时返回的 `implementation`、`conditions`、`coverage` 和保留报告为准，不能把 `Experimental`、`Planned` 或 `Partial` 理解为全面支持。

**当前进度：桌面 Native、真实 FFmpeg、旧 JVM/系统媒体代码清理已完成三平台验证；Android 初始应用、素材回归、单张照片保存及 APK CI 已通过独立验证，等待真机手测。暂不提供移动端媒体编解码。其它业务扩展暂不实施，Apple 新功能暂停。** 见[进度与 TODO](#进度已完成与-todo)。

浏览器功能库与共享 Compose 界面已接入，用户已手动验证网页可用。后续能力及验收进度单独维护在 [Web 路线与 TODO](docs/web-roadmap.md)，不以旧阶段测试代替新接口验证。

## 目录

- [下载与运行](#下载与运行)
- [前提与推荐](#前提与推荐)
- [Android 测试应用](#android-测试应用)
- [浏览器功能库（无 UI）](#浏览器功能库无-ui)
- [浏览器共享界面](#浏览器共享界面)
- [快速开始](#快速开始)
- [命令与参数](#命令与参数)
- [协议与能力范围](#协议与能力范围)
- [从源码编译与测试](#从源码编译与测试)
- [CI 与产物](#ci-与产物)
- [进度、已完成与 TODO](#进度已完成与-todo)
- [常见问题](#常见问题)

## 下载与运行

进入 [GitHub Actions / Build](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)，选择成功的运行，在 **Artifacts** 下载对应平台的 `LivePhoto-<平台>-<run_id>-<attempt>`。

Actions 的 artifact 是外层下载包；解压后再展开里面的 `.zip` / `.tar.gz`，得到单文件程序。保留同包的 `native-cli-evidence.json`，其中 `archiveSha256` 校验**内层应用归档**，不是校验外层 artifact；程序本身也有独立哈希。

| 平台 | 应用归档 | 解压后的入口 | 当前交付状态 |
| --- | --- | --- | --- |
| Windows x64 | `LivePhoto-windows-x64.zip` | `LivePhoto.exe` | Kotlin/Native 原生程序 |
| Linux x64 | `LivePhoto-linux-x64.tar.gz` | `LivePhoto.kexe` | Kotlin/Native 原生程序 |
| macOS ARM64 | `LivePhoto-macos-arm64.tar.gz` | `LivePhoto.kexe` | Kotlin/Native 原生程序 |

不提供 Intel macOS、Windows ARM 或 Linux ARM 包。默认下载为 **Kotlin/Native 单文件程序，不携带 JVM/JRE，运行不需要 Java**。外部 FFmpeg 不在应用包内。暂不提供 MSI/DMG 安装器。历史 JVM 构建与打包流程已移除。

Windows PowerShell，在解压位置运行：

```powershell
.\LivePhoto.exe --version
.\LivePhoto.exe --help
```

Linux，在解压位置运行：

```sh
./LivePhoto.kexe --version
./LivePhoto.kexe --help
```

## 浏览器功能库（无 UI）

Web 入口直接复用 `shared/commonMain`，不依赖 Compose、桌面进程、JVM 或 FFmpeg。当前只实现 Kotlin/JS，不是 Wasm；编译、真实浏览器验收结果请查看 [Web Library CI](https://github.com/ohyooo/LivePhoto/actions/workflows/web.yml)。

成功运行上传 `LivePhoto-web-js-<run>-<attempt>`：`web-library/` 是功能库，包含 `livephoto.mjs` 浏览器入口、Kotlin 编译模块及 TypeScript 声明。保留目录中全部模块，通过 HTTP(S) 静态服务使用，不要只复制单个文件或用 `file://` 打开。不需要服务端上传、处理照片，也不提供网页界面。

```js
import {
  createLivePhoto, detectLivePhoto, validateLivePhoto, extractVideo,
} from './web-library/livephoto.mjs';

// imageFile / videoFile 是调用方提供的 File 或 Blob；按内容检测，不信扩展名。
const created = await createLivePhoto(imageFile, videoFile, {
  target: 'oplus.olive',
  replaceCameraComment: false,
});
if (!created.success) throw new Error(`${created.code} / ${created.stage}: ${created.message}`);

const livePhotoBlob = created.blob;
const detection = await detectLivePhoto(livePhotoBlob);
const validation = await validateLivePhoto(livePhotoBlob);
const extracted = await extractVideo(livePhotoBlob);
if (!extracted.success) throw new Error(extracted.message);
const originalVideoBlob = extracted.blob;
```

创建只开放 `oplus.olive`（JPEG 无尾部 profile）、`google.motionphoto.v2`、`google.microvideo.v1` 三个已有目标；浏览器 API 不扩展协议/编码支持。`replaceCameraComment: true` 只允许前文明确列出的两种相机注释替换，默认不授权。失败返回结构化 `success/code/stage/message` 且不返回输出 Blob；参数类型、提前大小检查失败则 Promise 拒绝。

`validateLivePhoto` 仅检查 Structure/Protocol；调用成功不等于 `verdict` 有效，更不代表解码、相册兼容或播放验收。此协议功能库不提供抽帧、裁剪、转码或系统相册导入；下方网页界面的媒体预处理是独立适配。输入合计上限 32 MiB，单个已有载体/输出上限 48 MiB；内存暂存和副本会使实际占用高于文件大小。建议在调用方的 module Worker 中处理，避免占用界面线程；本阶段不承诺任意大文件或可中途取消。

协议接口还提供 `inspectLivePhoto`、`analyzeLivePhoto`、`getKeyPhotoPosition`、`convertLivePhoto(input, { target })`、`splitLivePhoto`。检查结果位于 `result.report`：包含多重检测、资源范围、媒体/metadata、关键照片原始字段、检查明细及带条件的能力；Analyze 不把缺媒体后端说成可抽帧。64 位 offset/length、时间分子和任意精度 metadata 数字用十进制字符串，未知值保留 `null`。

Convert 仅开放上面三个既有 JPEG 目标，默认同目标原样保留；不隐式修复或转码。Clean Split 与 `extractVideo` 不同：前者返回清理绑定后的 `assets[]`（每项含 role、mime、digest、Blob），后者只原样提取嵌入视频。多资产结果的顶层 `blob` 为 `null`，请遍历 `assets`。写操作的 `report.preservation` 按资产报告保真证明、变化及问题；内存中集合原子发布不等于浏览器下载已持久化。以上新增接口的当前验收状态见路线文档。

三个目标不代表任意来源都能转换。当前新建 OPPO 布局将 XMP 放在 EXIF 前方，转换为 Google 时若需调整 XMP 并移动未验证 EXIF，Core 会返回 `UNSAFE_METADATA_REWRITE`，没有输出；网页不会删除 EXIF、隐式授权丢失或转码来绕过。相同目标仍可原样复制，Google 来源的有限转换与这两个拒绝方向分别验收。

`repairLivePhoto(input)` 默认仅预览已有 Core 能证明安全的 metadata 修复，不扫描猜测视频位置。确认 `result.report.blocked` 为空、核对 `proposedChanges` 后，可传 `{ dryRun: false }` 生成新文件；可用 `allowedIssueCodes` 限定问题类型。此入口不开放 remux、重新配对、转码或 metadata 丢失授权。`success` 仅表示取得报告：被阻止、无需修改或预览时均没有输出 Blob。实际修改见 `changesApplied`，验证和保真证明位于 `report.operation`。原输入不变，修复失败不返回半成品；当前验收状态见路线文档。

Repair 不保证支持所有可创建的布局。当前默认新建 OPPO 文件的目录包含显式次级 `Padding="0"`，超出既有 Core 修复门禁，会返回 `REPAIR_AMBIGUOUS`，预览也不例外；这不等于文件无效。此适配保留拒绝，不删除字段或自行更改协议。Google V1/V2 的有限长度修复与该 OPPO 布局的无输出拒绝分别测试。

构建（使用已有 JDK 25 和项目 Wrapper）：

```sh
./gradlew -Plivephoto.web=true :verifyWebBuild :shared:jsBrowserProductionLibraryDistribution
```

与 Android/Native 分开构建，不同时传入 `livephoto.android` 或 `livephoto.nativeTarget`。原始编译产物位于 `shared/build/dist/js/productionLibrary/`；`scripts/package-web.ps1` 添加 File/Blob 入口及哈希清单。Web CI 使用独立合成媒体，在真正的 Chromium 和 module Worker 中验证生产库，不把桌面测试数算成浏览器测试，也不上传媒体输入或生成结果。

协议单元测试优先使用 Kotlin `shared/src/commonTest`，由不同 target 编译同一份测试，不用 JS 重写协议断言。桌面真实 FFmpeg/文件素材测试位于 `desktopTest`，Native 主机适配位于 `nativeTest`，不会为浏览器伪造这些后端。Web 是 Kotlin/JS browser target，不是 `wasmJs`；可运行 `./gradlew -Plivephoto.web=true :shared:jsBrowserTest`，需要已有 Node.js、Chrome（必要时设置 `CHROME_BIN`）和 JDK 25。该新增测试链的实际验收状态见 Web 路线文档；外围文件选择、下载、Worker 和 UI 验收仍使用浏览器集成测试。

## 浏览器共享界面

页面默认跟随浏览器深浅色，右上角太阳／月亮按钮可在当前页面手动切换；刷新后重新跟随。普通播放器整体不超过浏览器可视高度的一半，保持视频比例，全屏不受半屏限制。宽布局加长音量条；竖屏的播放、静音、音量条、倍速和全屏保持同一行，按钮保留点击范围与间距，音量条填满剩余宽度。只有空间不足的横屏布局将音量单独换行。

浏览器界面直接运行 `ui/commonMain` 中与 Android 相同的 Compose 屏幕，不是另外维护的 HTML 表单。浏览器适配层负责文件选择、元信息读取及下载，协议封装仍调用公共 Core；媒体只在本机浏览器处理，不上传服务器。

在 [Web CI](https://github.com/ohyooo/LivePhoto/actions/workflows/web.yml) 成功运行中下载 `LivePhoto-web-ui-<run>-<attempt>`，解压后将 `web-ui/` 中全部文件放到 HTTP(S) 静态服务，访问 `index.html`。不能只复制入口 JS，也不建议通过 `file://` 打开。功能库 artifact 仍单独提供，不依赖此界面。

```sh
./gradlew -Plivephoto.web=true -Plivephoto.webUi=true :verifyWebBuild :ui:jsBrowserDistribution
```

原始静态网页产物位于 `ui/build/dist/js/productionExecutable/`。Core 与界面代码使用 Kotlin/JS；Compose 图形运行时与可选 FFmpeg 都可能包含 Wasm 文件，不表示 Core 已迁移至 Kotlin/Wasm。界面复用窗口尺寸自适应布局；选择 JPEG 和 MP4 后生成下载，协议和输入限制与现有应用一致。浏览器只能确认已发起下载，不能保证用户最终保存，因此成功提示不冒充磁盘持久化。刷新页面后需要重新选择输入。

网页视频编辑入口及新版交互的验收状态见 [Web 路线](docs/web-roadmap.md)：

1. 选择视频，使用画面外的播放控件。勾选“截取封面”后，拖动独立的单手柄时间轴，实时更新并选用视频画面；也可另选 JPEG。封面近似定位，协议时间自动换算到可表达的精度，不要求逐帧精确，不改视频采样或音频。
2. 勾选“裁剪视频”后，拖动独立时间轴的两端手柄，选择中间保留片段；点击“应用裁剪”才导出音视频 copy 结果。边界可能移至关键帧，以显示的实际范围为准；封面位置必须处于保留范围内。两种工具默认收起，隐藏工具不等于撤销已经应用的编辑。
3. 只有显式点击 H.264 转码才重新编码视频，目前限 8-bit SDR。音频始终 copy，不兼容则报错，不自动编码。转码后需重新选择封面，不能沿用无法证明的新旧帧映射。
4. 选择目标协议并生成下载。上次结果支持按住播放、松开回封面，仅模拟预览，不代表手机相册已识别。

播放器的卡片、按钮、进度条、横竖布局、区间手柄和封面时间轴均位于 Kotlin `ui/commonMain`，不是独立 JS/CSS 控制栏。Web 适配仅提供 Video/Canvas 播放与截图等平台操作；Android 可复用这些控件，但尚无移动媒体编辑后端，不表示 Android 已能执行编辑。

裁剪、显式转码按需加载随站点分发的单线程 ffmpeg.wasm，无 CDN 运行时依赖，不调用桌面 FFmpeg。首次编辑需加载较大的 Wasm。编辑限 32 MiB、60 秒内输入，输出上限 48 MiB、总处理超时 120 秒，并提供取消；实际内存占用高于文件大小。截图不保证原 HDR、色彩或 metadata，编辑也不承诺完整保留普通 metadata。FFmpeg 依赖、许可证与对应源码/构建说明随 `media/` 分发；这不是正式 Core MediaBackend 或独立协议 JS API 的媒体能力声明，也不改变 Android 的后端状态。

CI 在窄屏与宽屏验证共享界面、文件选择/取消、按钮可达性、错误展示、三个协议实际下载，并独立解析下载结果及核对视频字节。报告截图只使用临时合成素材，不上传媒体原片或生成成品。

网页包附带 [Noto Sans SC](https://github.com/notofonts/noto-cjk/releases/tag/Sans2.004) 中文字体及 OFL 许可，首次打开会从同一静态站点预加载约 8 MiB 字体，避免中文缺字或依赖外部字体服务。源码构建后用 `scripts/package-web-ui.ps1` 完成字体与许可打包；打包时需要联网，运行时不向外部字体服务发请求。

## Android 测试应用

在 [GitHub Releases](https://github.com/ohyooo/LivePhoto/releases) 的 Android 构建中，直接下载并安装 **`LivePhoto-release.apk`**；排错时可下载同版本的 **`LivePhoto-debug.apk`**。附件就是 APK，不需要解压，不附带测试报告。需要 **Android 10 / API 29 或更高**；这是实验性应用，不是正式商店发行版。主分支 CI 全部通过后才发布，不覆盖历史构建。

打开 LivePhoto，依次选择图片、视频及目标协议，再点击“生成并保存”。当前界面只开放已实现的 **JPEG＋MP4**：OPPO/OnePlus `jpeg-no-tail`、Google Motion Photo V2 `jpeg`、Google MicroVideo V1 `jpeg`。输入由内容识别，不靠文件名；同名仅便于自己选择。未知/不安全的格式、metadata 或媒体 profile 会由 Core 拒绝，不自动转换。

Compose 界面使用无原生 ActionBar 的窗口和 Material 3 `Scaffold` 安全区；内容可滚动，按钮不会放在顶部栏下面。图片、视频分别显示是否已选，取消选择保留旧输入；缺任何一项时禁用生成按钮。目前**只选视频还不能生成**，需要另选一张图片；移动端自动抽帧尚未实现。

选中文件后显示名称和大小（KiB/MiB 及准确字节数）；文件提供方没有返回大小时明确显示“未知”，不当作 0，也不为查大小读取整个视频。生成结果和错误说明显示在页面顶部，完成后自动滚回顶部；错误可长按复制，包含关联对象、处理步骤、错误码、阶段、Core 原始原因以及可用的定位/详情。区分图片读取/解析、视频读取/解析、图片元数据处理、合并写入、结果回读校验、事务提交和相册保存；输入错误附所选文件名。缺少定位时明确显示未知，不根据英文报错或扩展名猜测。Core 通过 `details` 中的 `livephoto.operation`、`livephoto.step`、`livephoto.inputRole` 提供机器可读上下文，不把中文界面文案写入协议层。

普通 JPEG 的未知尾部在这三个 Create profile 中原字节保留于图片和 MP4 之间，Google V2 / OPPO 的目录会记录相应 Primary Padding，并回读核对尾部哈希和视频起点。不会把它冒充已解析的厂商动态视频尾标；其私有含义和偏移依赖仍为未知，不能据此承诺原厂编辑功能或严格 metadata 无损。Clean Split 和需要重建图片的 Convert 若会丢弃这段非零数据，则明确拒绝；Raw Extract 仍可原样提取视频。`UNSUPPORTED_CONTAINER(Parse)` 不能仅凭扩展名确定原因，请反馈卡片中的定位和具体原因。

Android 的 OPPO/OnePlus 生成支持两种精确相机注释替换：UNDEFINED 类型、带 ASCII 前缀的 `oplus_1048576`，以及 TIFF ASCII 类型的 `oplus_1056768`，目标均为标准 `oplus_10485792`。前者使用原字段空间；后者在 EXIF 末尾追加新值，只更新该字段的类型、长度和指针。保留 EXIF 起点、其它目录与值的位置、MakerNote、图片尾部及输入原片；字段重叠、空间超限等不安全结构继续拒绝。结果显示替换记录，`MetadataPreserving=Changed`；其它普通注释仍按冲突拒绝，非标准 ASCII 文本不会被检测为 Live 标记。Core 默认不授权替换；两种写法分别使用 `loss=AllowListed` 和 `MetadataLoss.OplusCameraUserComment` / `MetadataLoss.OplusAsciiCameraUserComment` 加入 `allowedLosses`。CLI 的 Oplus `create` 使用 `--replace-oplus-user-comment` 显式启用，不能同时要求严格 metadata 保留。

输入合计上限 **32 MiB**，输出上限 **48 MiB**；此首版使用冻结的内存输入和内存事务，不是大视频工具。Core 完成验证后，把单张 JPEG 原字节写入 MediaStore 的 pending 项，回读确认一致再发布到 `Pictures/LivePhoto`，不会覆盖原片。不申请全盘读写、联网或相册遍历权限；输入由系统文件选择器授权。未完成的 pending 项只按本次创建的 URI 清理；已发布或状态不确定的项不盲删。

请在系统相册检查动态识别、播放、声音与 key；**保存成功、模拟器通过都不等于你的手机相册兼容**。Android 当前没有 FFmpeg、抽帧、裁剪、remux 或 transcode 后端。保存只支持本节的单 JPEG，不宣称实现通用配对/多资产相册事务。

应用直接依赖 `shared` 和 `ui`，Gradle 编译公共 Core 与共享 Compose 界面并打入 APK；不需要手动打包、发布或导入 AAR，也不把 Core 编译为 `.so`。文件选择、权限和相册保存由 Android 适配层提供。Android 使用常规 Kotlin/ART 字节码；桌面仍为不含 JVM 的 Native CLI。

从源码构建（已有 JDK 25、Android SDK **37.2** / Build Tools **37.0.0**，并先检查不存在 `gradle-daemon-jvm.properties`）：

```sh
./gradlew -Plivephoto.android=true :verifyAndroidBuild :android:assembleDebug :android:assembleRelease
```

Windows 使用 `.\gradlew.bat` 替换 `./gradlew`。Android 模式与桌面 `livephoto.nativeTarget` 分开，不能同时指定。APK 分别位于 `android/build/outputs/apk/debug/android-debug.apk` 和 `android/build/outputs/apk/release/android-release.apk`。用 Android Studio 导入时，在该 IDE 的 Gradle VM options 设置 `-Dorg.gradle.project.livephoto.android=true`，不要把模式写成所有平台共用的全局属性。

CI 使用 SDK 37.2 编译 debug/release，分别重新执行宿主单元测试、lint，并执行 **API 36 模拟器**协议/存储/Compose 界面回归，以及 debug → release → debug 不卸载覆盖安装和启动检查；核对签名、可调试标志、DEX、测试数及 SHA-256。编译 SDK 不等于运行验收系统版本。Actions 产物中的 **`LivePhoto-debug.apk`、`LivePhoto-release.apk` 分别直接上传单文件**，使用 `upload-artifact@v7` 的 `archive: false`，没有外层 ZIP；`compression-level: 0` 只控制压缩等级，单独设置不能移除 ZIP。发布任务用 `download-artifact@v8` 的 `skip-decompress: true` 保留 APK 自身结构。`android-reports-...` 是单独的报告 ZIP，保留 XML、日志、启动截图及混淆映射，不附带含素材的测试 APK。Native 报告只保留测试结果及素材哈希清单，不附带导出的图片、视频或媒体 fixture。手机原片与其生成结果不提交到仓库或上传 artifacts；应用 APK 不包含测试素材。

主分支 debug/release 共用固定签名，通过 Actions secrets 注入：`LIVEPHOTO_SIGNING_KEYSTORE_BASE64`、`LIVEPHOTO_SIGNING_STORE_PASSWORD`、`LIVEPHOTO_SIGNING_KEY_PASSWORD`、`LIVEPHOTO_SIGNING_KEY_ALIAS`。不把 keystore/密码提交到仓库；只在可信 main 构建恢复，缺少签名时拒绝发布。PR 使用默认 debug 签名且不发布 Releases；本地未传入签名时也使用本地 debug key，不能据此覆盖 CI 安装版。CI 的 `versionCode` 随构建递增，签名保持一致。**以前使用随机 CI debug key 的版本可能需卸载一次**；之后本仓库固定签名的 debug/release 可互相更新。产物证据记录实际证书指纹。

Release 启用 R8 代码裁剪、优化、混淆及资源裁剪；debug 不启用，便于排错。CI 在 `android-reports-...` 中保存 `android/build/outputs/mapping/release/` 的混淆映射及优化报告。排查 release 崩溃时，请保留该构建对应的 `mapping.txt`，不同版本的映射不能混用。APK 下载方式与签名保持不变。

## 前提与推荐

Native 对文件操作要求一个已存在的私有工作目录，用于冻结输入和释放临时副本。设置一次后再执行下面的示例；目录不能是公用临时目录，容量应覆盖 `--max-bytes` 预算。程序不会自动创建这个父目录。

Windows PowerShell，在当前用户有独占访问权限的位置新建目录：

```powershell
New-Item -ItemType Directory '.\livephoto-workspace'
$env:LIVEPHOTO_WORKSPACE = (Resolve-Path '.\livephoto-workspace').Path
```

Linux/macOS，Bash/Zsh：

```sh
mkdir -m 700 './livephoto-workspace'
export LIVEPHOTO_WORKSPACE="$PWD/livephoto-workspace"
```

`--version` / `--help` 不需要工作目录。Unix 示例同样适用于 macOS ARM64。

实现职责明确分为三部分：图片＋视频的 Live Photo 协议封装由 Core 代码完成；桌面裁剪、抽帧、重封装和转码直接调用 FFmpeg；未来移动端使用系统服务/API。Core 保留请求策略、协议解析、边界与实际输出验证，不自行替代 FFmpeg 编写桌面媒体剪切器或编码器。

- **只做协议操作通常不需要 FFmpeg。** 检测、结构检查、原样提取及有限的协议封装/编辑，可直接由 Core 完成。结构检查不代表已经解码视频。
- **桌面媒体处理使用自己准备的 FFmpeg。** Windows/macOS/Linux 的后端选择顺序为：`--ffmpeg` 显式路径 → 系统 `PATH` → 禁用相应媒体能力。不自动调用系统媒体 API，不自动下载或安装 FFmpeg、Java 或其它后端；不识别 shell 的 FFmpeg 别名，PATH 中使用绝对目录。没有 FFmpeg 时，纯协议解析、metadata 编辑与已有有限协议操作仍可用，需要解码/抽帧/媒体编辑的操作则明确拒绝。
- 历史 Windows 系统媒体 API及其专项测试已移除。桌面只使用 FFmpeg；未来 Android/iOS 才接系统媒体服务/API。
- 使用 shared 版 FFmpeg 时，要保留它旁边的 DLL；不要只移动 EXE。CLI 使用 `--ffmpeg` 或 PATH；测试另支持 `LIVEPHOTO_FFMPEG`，`FFMPEG_HOME` 本身不是 CLI 的查找入口。
- 推荐先 `inspect` / `analyze`，再执行修改；重要原片另行备份。输入保持不可变，输出使用**尚不存在的新目录**，不会覆盖输入或已有输出。
- 默认禁止转码。`--allow-transcode` 只是显式授权，仍受操作、输入及目标的能力和安全条件限制。不要用它绕过未知 metadata、HDR、GainMap、MakerNote 或资源依赖风险。
- 默认保留策略是 `BestEffortWithReport`：必须阅读 `preservation` 报告，而不是假设所有 metadata 均无损。`--strict` 要求严格保留，无法证明时会拒绝输出；这不是“关闭警告”开关。
- Apple 使用图片和视频两个资产，不能只凭文件名或扩展名认定配对。一般单个带 CID 的文件只是 `Candidate`；传入两个内容标识匹配的资产才可验证 Pair。

例如，查看后端及执行真实解码检查：

Windows（PowerShell）：

```powershell
.\LivePhoto.exe media-capabilities --ffmpeg 'C:\Program Files\FFmpeg\bin\ffmpeg.exe'
.\LivePhoto.exe probe --input '.\video.mp4' --decode-check --ffmpeg 'C:\Program Files\FFmpeg\bin\ffmpeg.exe'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto.kexe media-capabilities --ffmpeg '/usr/bin/ffmpeg'
./LivePhoto.kexe probe --input './video.mp4' --decode-check --ffmpeg '/usr/bin/ffmpeg'
```

## 快速开始

分别列出 Windows 和 Linux 命令，直接从解压目录运行，无需设置命令变量。路径包含空格时加引号，命令名、协议 ID 和枚举值按示例大小写使用。

### 检测、检查与验证

Windows（PowerShell）：

```powershell
.\LivePhoto.exe detect --input '.\reference\livephoto.jpg'
.\LivePhoto.exe inspect --input '.\reference\livephoto.jpg'
.\LivePhoto.exe analyze --input '.\reference\livephoto.jpg'
.\LivePhoto.exe validate --input '.\reference\livephoto.jpg' --layers Structure,Protocol
.\LivePhoto.exe get-key --input '.\reference\livephoto.jpg'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto.kexe detect --input './reference/livephoto.jpg'
./LivePhoto.kexe inspect --input './reference/livephoto.jpg'
./LivePhoto.kexe analyze --input './reference/livephoto.jpg'
./LivePhoto.kexe validate --input './reference/livephoto.jpg' --layers Structure,Protocol
./LivePhoto.kexe get-key --input './reference/livephoto.jpg'
```

`detect` 按内容判断，可返回多个协议匹配；`inspect` 展示结构和 metadata，不等同于有效性结论。`validate` 默认检查 `Structure,Protocol,Media`，注意 `coverage`、各层 checks 以及 `NotRun` / `Partial`；退出码为 0 也不等于完整媒体或设备认证。

**参考成品不是“严格验证必定成功”的样本。** `reference/livephoto.jpg` 的 secondary item 声明了非规范 `Padding=0`，已有测试明确要求严格 Google V2 协议验证为 `Invalid`；它的媒体结构检查也可能报告问题。因此对它执行上述 analyze/validate 返回退出码 **4** 是实际、预期的报告，不应修改检查使其变绿。下节用普通 `video.jpg + video.mp4` 创建的新成品才是本项目的成功闭环示例。

### 普通图片 + 视频 → Google Motion Photo

Windows（PowerShell）：

```powershell
.\LivePhoto.exe capabilities --target google.motionphoto.v2 --profile jpeg
.\LivePhoto.exe create --image '.\reference\video.jpg' --video '.\reference\video.mp4' --target google.motionphoto.v2 --profile jpeg --output-dir '.\out-create'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto.kexe capabilities --target google.motionphoto.v2 --profile jpeg
./LivePhoto.kexe create --image './reference/video.jpg' --video './reference/video.mp4' --target google.motionphoto.v2 --profile jpeg --output-dir './out-create'
```

输入必须符合有限的 JPEG / 视频 profile；默认不转码。输出发布在 `out-create/assets/`，JSON 的 `result.output.assets[].path` 给出真实文件位置；**不要假设输出总叫 `livephoto.jpg`**。后续示例里的 `motion.jpg` 指你按 JSON 路径选取的结果或自己的输入文件。

### 原样提取、干净拆分、协议转换

Windows（PowerShell）：

```powershell
.\LivePhoto.exe extract --input '.\motion.jpg' --output-dir '.\out-extract'
.\LivePhoto.exe split --input '.\motion.jpg' --output-dir '.\out-split'
.\LivePhoto.exe convert --input '.\motion.jpg' --target google.microvideo.v1 --profile jpeg --output-dir '.\out-convert'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto.kexe extract --input './motion.jpg' --output-dir './out-extract'
./LivePhoto.kexe split --input './motion.jpg' --output-dir './out-split'
./LivePhoto.kexe convert --input './motion.jpg' --target google.microvideo.v1 --profile jpeg --output-dir './out-convert'
```

`extract` 原样提取已确认资源，默认资源选择由 Core 决定；`split` 输出普通媒体并清除已确认的动态照片专属 metadata。两者不是同一个操作，也不一定产出相同图片字节。

### Apple 双资产与关键帧 metadata

Windows（PowerShell）：

```powershell
.\LivePhoto.exe inspect --input '.\IMG.jpg' --pair-video '.\IMG.mov'
.\LivePhoto.exe extract --input '.\IMG.jpg' --pair-video '.\IMG.mov' --output-dir '.\out-apple-raw'
.\LivePhoto.exe set-key --input '.\IMG.jpg' --pair-video '.\IMG.mov' --frame-index 1 --output-dir '.\out-apple-key'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto.kexe inspect --input './IMG.jpg' --pair-video './IMG.mov'
./LivePhoto.kexe extract --input './IMG.jpg' --pair-video './IMG.mov' --output-dir './out-apple-raw'
./LivePhoto.kexe set-key --input './IMG.jpg' --pair-video './IMG.mov' --frame-index 1 --output-dir './out-apple-key'
```

`set-key` 修改协议意义的 key/presentation 位置，**不会重新生成封面图**。Apple 修改入口只接受已确认的有限结构，不能将任意相机原片视为可写。

普通媒体创建 Apple 默认使用有限 `jpeg-mov` profile，并满足 JPEG metadata 与原视频容器的分类门禁。输入必须是已分类的普通 JPEG＋MOV；允许一份经安全模型验证的标准 EXIF，保留 GPS、方向和普通 UserComment。不接受已有 Live Photo、已有 MakerNote、缩略图/未知引用、不明非零 metadata 空隙或无法安全合并的字段。Create 的标准 EXIF 证明不能单独授权 Convert 的来源清理：

Windows（PowerShell）：

```powershell
.\LivePhoto.exe create --image '.\plain.jpg' --video '.\plain.mov' --target apple.livephoto --output-dir '.\out-apple-create'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto.kexe create --image './plain.jpg' --video './plain.mov' --target apple.livephoto --output-dir './out-apple-create'
```

可显式指定 `--profile jpeg-mov`，效果相同。不需要媒体后端，也不会隐式把 MP4 改成 MOV；MP4 输入必须显式使用 `--profile jpeg-mp4`。默认 Create 为 `Experimental`，不代表任意相机 JPEG/MakerNote 的 Generic Create 已完成；默认 ConvertTo 仍为 `Planned`，转换须指定已实现的 profile，HEIC Create/ConvertTo 也未实现。

视频允许一个经完整校验的 iTunes 普通文本目录：标题、作者、注释、编码工具与版权，每个字段必须唯一、UTF-8、locale=0，非空且不超过 64 KiB。目录原字节、媒体样本和时间线保留，不重封装、不转码；结构参考 [FFmpeg 的实际写入实现](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/movenc.c)。freeform、未声明的 keys 表、未知字段/编码及多个目录仍拒绝。支持有限 Apple 追加写入、读取和 Clean/Split，以及有限 Apple→Google 转换；不放宽 remux/trim/transcode 的 metadata 门禁。首次清理/转换仍有未知关联，`MetadataPreserving=Unknown`，因此严格策略拒绝；清理后的普通视频再次 Split 才可证明整文件字节不变。

另支持经验证的旧式 QuickTime 普通文本目录：`©nam/©ART/©des/©cmt/©swr/©cpy`；每个 atom 唯一，使用精确 16-bit 字节长度、`und` 语言码和有效 UTF-8，非空且不超过 65,535 字节。不猜测 MacRoman、其它语言编码或混合目录；目录仍只原样保留，不重写普通字段。两种注释 atom 可并存，不将其当作 Live Photo CID。

还支持 FFmpeg `use_metadata_tags` 的有限 `moov/udta/meta` 普通文本：仅 `title/artist/comment/encoder/copyright`，唯一 `mdta` 键、完整一对一的 1-based 值索引，UTF-8 类型 1、locale=0，非空且不超过 64 KiB。目录整体原样保留，不能混入 CID、still-image-time 或私有字段；不开放电影级 Apple metadata 合并、混合 timed metadata 或通用键表改写。

**已知 FFprobe 展示差异：** 本轮索引式文本素材在追加 Apple CID 后，FFprobe 将普通标题显示成“原标题 + CID”，但 Core 独立校验确认原目录字节未变；Clean 后四个普通标签回读准确。FFmpeg 的 [MOV 读取器](https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/mov.c)只加载首个 metadata keys 表，这与观察一致，不能据此声称所有工具或相册正确处理多个 metadata 作用域。该兼容项仍待后续验收，不通过删除普通字段规避。

Google V1/V2 JPEG 的标准 EXIF 现可用于有限 Apple 转换：要求一个明确源绑定，清除源绑定前后的 EXIF payload 完整字节一致，且两侧都通过标准字段/位置独立性检查。保留字节序、GPS、方向、普通 UserComment、外置值的 TIFF 相对位置、图像编码、视频样本及已知 key；目标须显式 `jpeg-mov` 或 `jpeg-mp4` 并匹配原视频容器，不转码或重封装。来源的未知 metadata 关联仍报告 `MetadataPreserving=Unknown`，因此此路径不能加 `--strict`。已有 MakerNote、私有字段、缩略图、未知引用仍拒绝，其它来源按下述独立子集确认。

Samsung 标准 JPEG SEF 的普通 EXIF 也可用于有限 `jpeg-mp4` 转换：仅版本 107、非 Legacy、无空隙的 MotionPhoto_Data / MotionPhoto_Version 两记录、原 MP4；可带已确认的 Google V2 基础层，不接受其它混合绑定。来源清理与 EXIF 保留分别验证，不删除普通/私有 SEF 以绕过限制。无已知 key 时必须显式指定 `--frame-index`；来源 metadata 关联仍为 Unknown，严格策略拒绝。不是 Samsung 相册或 Apple 真机兼容认证。

Huawei `basic60` JPEG 的标准 EXIF 支持有限 `jpeg-mp4` 转换：仅原图紧接完整 MP4、随后恰 60 字节已确认尾标、无间隙或其它绑定的结构。原尾标的时间单位未知，不按字段名猜测；必须显式选择 `--frame-index`（从 0 开始），例如 `LivePhoto convert --input 'huawei.jpg' --target apple.livephoto --profile jpeg-mp4 --frame-index 0 --output-dir 'out-apple'`。EXIF 原字节、图像编码和视频样本分别验证，未知关联仍不支持 `--strict`。Honor、HEIC、额外扩展和未知字段不因此获得写入权限；不代表相册兼容认证。

vivo 最小版本一 JPEG 的标准 EXIF 支持有限 `jpeg-mp4` 转换：要求版本/来源/媒体套件为 `1 / 1 / 1.0.0.9`、仅 Primary/Motion 两项目录、原 MP4 紧接完整 JPEG。保留已知 key 与原 EXIF 字节、图像编码和视频样本；无已知 key 时需显式选择帧。GainMap、Flags、其它厂商字段/版本和混合绑定继续拒绝；首次转换的未知 metadata 关联仍不允许 `--strict`，不代表所有 vivo 相机或相册兼容。

Windows：

```powershell
.\LivePhoto.exe convert --input '.\google-motion.jpg' --target apple.livephoto --profile jpeg-mp4 --output-dir '.\out-apple-convert'
```

Linux：

```sh
./LivePhoto.kexe convert --input './google-motion.jpg' --target apple.livephoto --profile jpeg-mp4 --output-dir './out-apple-convert'
```

### 修复：先预览，再明确写入

Windows（PowerShell）：

```powershell
.\LivePhoto.exe repair --input '.\damaged-motion.jpg'
.\LivePhoto.exe repair --input '.\damaged-motion.jpg' --issues MOTION_VIDEO_LENGTH_MISMATCH --apply --output-dir '.\out-repair'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto.kexe repair --input './damaged-motion.jpg'
./LivePhoto.kexe repair --input './damaged-motion.jpg' --issues MOTION_VIDEO_LENGTH_MISMATCH --apply --output-dir './out-repair'
```

当前仅修复能够唯一证明的有限问题，不猜协议、视频边界、ID 或单位。默认模式为 `SafeMetadataOnly`；预览不写入，不能传 `--output-dir`。

有限 `ExplicitRemux`（Experimental）处理 **Samsung JPEG SEF mpv3，或 vivo 最小版本一 JPEG 中，独立有效的 MOV 视频应为 MP4** 的容器约束问题。vivo 仅接受 `VMotionPhotoVersion=1`、`VMotionPhotoSource=1`、`VMediaKitVersion=1.0.0.9` 和无辅助资源的 Primary/Motion 目录；未知 vendor 字段不授权重写。需要可用的 Remux 后端；可显式传 `--ffmpeg`，否则查 PATH，不自动安装。私有 streamcopy 验证全部 sample/configuration/时间线与已分类 metadata，再重建同协议的长度/索引，只发布最终一张照片；保留图片编码、原 key（包括未知值 `-1`）及既有 vendor 字段，不转码。未知/损坏媒体、Legacy SEF、普通 SEF 记录、辅助资源、EXIF/MPF/不明 ownership 等会拒绝，不能用来修复任意损坏文件。

Windows（PowerShell）：

```powershell
.\LivePhoto.exe repair --input '.\samsung-motion.jpg' --mode ExplicitRemux --issues UNSUPPORTED_CONTAINER
.\LivePhoto.exe repair --input '.\samsung-motion.jpg' --mode ExplicitRemux --issues UNSUPPORTED_CONTAINER --apply --strict --output-dir '.\out-remux-repair'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto.kexe repair --input './samsung-motion.jpg' --mode ExplicitRemux --issues UNSUPPORTED_CONTAINER
./LivePhoto.kexe repair --input './samsung-motion.jpg' --mode ExplicitRemux --issues UNSUPPORTED_CONTAINER --apply --strict --output-dir './out-remux-repair'
```

vivo 使用相同命令，换成自己的 vivo 输入路径即可。预览不执行媒体处理、不创建输出；CLI 的工具可用性探测可能运行只读版本检查。已是该有限 MP4 profile 时再次执行没有改动，也不需要媒体后端。Google 兼容基础层对 Samsung 整 SEF 后缀/纯视频长度，以及 vivo Motion `Padding=0` 的已有诊断仍保留；目标厂商方言与严格 Google 验证分开报告，不能据此宣称所有 profile 或设备通过。

Huawei 也使用相同命令，但仅限 **JPEG basic60、紧邻原图的独立 MOV、无 XMP/EXIF/MPF/未知 APP、无间隙或额外扩展**。完整 JPEG 和尾标前 40 字节原样复制，仅按验证后的 MP4 长度改写最后 20 字节的 `LIVE_` 字段；不会猜测历史字段单位或生成 key，`TIMESTAMP_SEMANTICS_UNKNOWN` 仍保留。HEIC、Honor 和未知扩展不在修复范围，默认 `SafeMetadataOnly` 不会执行重封装。

Apple 有限 `ExplicitRePair` 支持明确选定的 JPEG/HEIC＋MOV/MP4：先单独 `inspect` 你选为权威的图片或视频，将返回的 `result.pairing.evidence[0].id.value` 填入 `--authority`。下例 `EVIDENCE_ID` 必须替换为该文件当前版本的真实证据 ID；文件变化后重新读取，不得复用其它文件的 ID。权威来自视频时改图片 CID，来自图片时改视频 CID，不会自动选边或生成新 ID。

Windows（PowerShell）：

```powershell
.\LivePhoto.exe inspect --input '.\primary.heic'
.\LivePhoto.exe repair --input '.\primary.heic' --pair-video '.\motion.mov' --mode ExplicitRePair --authority 'EVIDENCE_ID'
.\LivePhoto.exe repair --input '.\primary.heic' --pair-video '.\motion.mov' --mode ExplicitRePair --authority 'EVIDENCE_ID' --apply --output-dir '.\out-pair'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto.kexe inspect --input './primary.heic'
./LivePhoto.kexe repair --input './primary.heic' --pair-video './motion.mov' --mode ExplicitRePair --authority 'EVIDENCE_ID'
./LivePhoto.kexe repair --input './primary.heic' --pair-video './motion.mov' --mode ExplicitRePair --authority 'EVIDENCE_ID' --apply --output-dir './out-pair'
```

此功能为 Experimental：只接受已确认 ownership 的 CID-only MakerNote 和专用 movie metadata，改写一侧固定宽度 CID，整对共同发布。普通 MakerNote、混合私有 metadata 等未覆盖结构会拒绝；无法证明未知 ID 关联，因此保留报告明确 `MetadataPreserving=Unknown`，`--strict` 不可用于发生 CID 改写的请求。匹配成功不证明原始同次拍摄，也不证明相册兼容；默认不转码。

### 抽帧、裁剪、remux 与显式转码

Windows（PowerShell）：

```powershell
.\LivePhoto.exe extract-frame --input '.\video.mp4' --frame-index 0 --format Jpeg --output-dir '.\out-frame'
.\LivePhoto.exe trim --input '.\video.mp4' --start-us 0 --end-us 1000000 --mode LosslessPreferred --output-dir '.\out-trim'
.\LivePhoto.exe remux --input '.\video.mp4' --container Mov --output-dir '.\out-remux'
.\LivePhoto.exe transcode --input '.\video.mp4' --container Mp4 --codec Avc --allow-transcode --output-dir '.\out-transcode'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto.kexe extract-frame --input './video.mp4' --frame-index 0 --format Jpeg --output-dir './out-frame'
./LivePhoto.kexe trim --input './video.mp4' --start-us 0 --end-us 1000000 --mode LosslessPreferred --output-dir './out-trim'
./LivePhoto.kexe remux --input './video.mp4' --container Mov --output-dir './out-remux'
./LivePhoto.kexe transcode --input './video.mp4' --container Mp4 --codec Avc --allow-transcode --output-dir './out-transcode'
```

这些示例假设可用 FFmpeg 在 PATH 中，也可追加 `--ffmpeg '完整路径'`。`LosslessPreferred` 是默认裁剪模式，应读取结果中请求与实际边界；`Exact` 必要时转码仍需 `--allow-transcode`。`--frame-index` 是 **zero-based presentation order**，不是 decode order。时间参数单位为微秒。

FFmpeg remux 的音频子集为 **MP4→MP4、一个视频轨＋至多两个 AAC-LC 音轨**：要求已分类的基础 ASC、canonical roll recovery map、容器及普通 metadata。逐轨保持 track ID/顺序、配置、sample、呈现时间线与 metadata，并回读验证后才发布；不会选一条音轨而丢掉其它音轨。带音频的 MP4→MOV、三条及更多音轨、未知 descriptor/metadata 或 recovery map 仍拒绝。remux 不授权编码，也不代表音频重编码已经实现。

AAC-LC 配置读取包括基础 ASC，以及已知 `0x2b7 / AOT=5 / sbrPresentFlag=0` 扩展：它明确标记不启用 SBR，原配置字节在媒体处理后保留，不转换成较短配置。SBR 启用、未知扩展、附加字段、非零 padding 或截断配置仍拒绝；不据此开放 HE-AAC。已知标记可见 [FFmpeg 配置写入源码](https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/aacenc.c)。

带音频 `trim` 使用 FFmpeg **全轨 `copy`**：当前支持 MP4 单 AVC 视频（闭合 IDR、无帧重排）＋至多两个基础 AAC-LC 音轨，无复杂原始 edits。默认 `LosslessPreferred` 按视频安全边界裁剪，只保留该范围内完整 AAC 包；不丢音轨、不保留隐藏 preroll/tail、不偷偷编码。读取 `actualStart/actualEnd` 以及每个 `tracks[].presented/encoded/timelineMap`：AAC 包边界可能留下短暂首尾音频空隙，结果以 `AUDIO_PACKET_BOUNDARY_GAP` 明确报告，时间线保持同步。中途切入 AAC 的 decoder recovery 不保证与原段 PCM 波形完全相同，报告 `AUDIO_RECOVERY_BOUNDARY_CHANGED` / `MetadataPreserving=Unknown`，此时 `--strict` 会拒绝。`LosslessOnly` / `Exact` 要求音视频包边界满足容差；需要音频编码才能精确切割时仍拒绝，即使提供 `--allow-transcode`。已有 `create` / `convert` 的裁剪参数复用此路径，key 按视频实际起点重置，但仍遵守协议写入器现有单音轨限制。

显式 `transcode` 目前仅支持 **MP4 中至多 64 帧的单 AVC 视频**：无重排/编辑列表、identity transform，逐帧已知 BT.709 limited 8-bit SDR，不改尺寸或帧率（保留 VFR）。可带至多两个基础 AAC-LC 音轨，要求 canonical roll map、ES descriptor flags=0 和已分类 metadata；仅重新编码视频，音轨配置、每个 sample 和时间线原样保留，并完整解码与独立回读验证。它不支持 HEVC 输入、HDR、音频编码、MOV 或需要编码的带音频 Exact 裁剪；需要 `--allow-transcode`，不能同时要求 `--strict`。整文件 `BitstreamPreserving=Changed`、`MetadataPreserving=Unknown`，不会因音频原样复制而宣称整体无损。FFmpeg 的编码/复制区分见[官方 streamcopy 文档](https://ffmpeg.org/ffmpeg.html#Streamcopy)。

`replace-cover` 与 `extract-frame` 分开：前者将抽出的帧作为动态照片主图，需要额外满足 metadata/图像编码保留门禁；`--update-key` 才请求一并修改 key。支持范围不能只从命令存在推断。

## 命令与参数

没有位置参数：文件必须通过对应的 `--input` / `--image` / `--video` 提供。选项不可重复，只能用于适用命令；不支持把任意参数传给任意命令。当前 `--help` / `--version` 是独立调用，不是 `create --help` 形式。

| 命令 | 必需参数 / 主要选项 | 作用 |
| --- | --- | --- |
| `detect`、`inspect`、`analyze`、`get-key` | `--input`，可选 `--pair-video` | 协议检测、库存、综合分析、key 读取 |
| `validate` | `--input`，可选 `--pair-video`、`--layers` | 分层验证 |
| `capabilities` | `--target`，可选 `--profile` | 查询协议操作状态及条件 |
| `media-capabilities` | 可选 `--ffmpeg` | 查询本机后端与发现警告 |
| `probe` | `--input`；可选 `--pair-video`、`--resource`、`--decode-check`、`--ffmpeg` | 结构/实际解码探测 |
| `create` | `--image`、`--video`、`--target`、`--output-dir` | 普通媒体创建动态照片 |
| `convert` | `--input`、`--target`、`--output-dir`；可选 `--pair-video`、`--same-target` | 动态照片协议转换 |
| `extract` | `--input`、`--output-dir`；可选 `--pair-video`、`--resources`、`--raw-carrier` | 原样提取 |
| `split` | `--input`、`--output-dir`；可选 `--pair-video`、`--strict` | 干净拆分 |
| `repair` | `--input`；可选 `--pair-video`、`--issues`、`--strict`、`--mode`、`--authority`；写入需 `--apply --output-dir` | 有限安全 metadata 修复或显式重配对 |
| `set-key` | `--input`、位置、`--output-dir`；可选 `--pair-video`、`--strict` | 修改 key metadata |
| `extract-frame` | `--input`、位置、`--format`、`--output-dir`；可选 `--pair-video`、`--resource`、`--ffmpeg` | 提取视频帧 |
| `replace-cover` | `--input`、位置、`--format`、`--output-dir`；可选 `--pair-video`、`--update-key`、`--strict`、`--ffmpeg` | 重建主图 |
| `trim` | `--input`、`--start-us`、`--end-us`、`--output-dir`；可选 `--pair-video`、`--mode`、`--resource`、`--strict`、`--allow-transcode`、`--ffmpeg` | 输出裁剪后的普通视频 |
| `remux` | `--input`、`--container`、`--output-dir`；可选 `--pair-video`、`--resource`、`--strict`、`--ffmpeg` | 不转码重封装普通视频 |
| `transcode` | `--input`、`--container`、`--codec`、`--allow-transcode`、`--output-dir`；可选 `--pair-video`、`--strict`、`--ffmpeg` | 显式转码普通视频 |

补充参数与取值：

| 参数 | 取值 / 约束 |
| --- | --- |
| `--log-level` | `off`（默认）、`error`、`debug`、`trace`；诊断写入 stderr，JSON 仍在 stdout。也可通过 `LIVEPHOTO_LOG_LEVEL` 设置默认级别 |
| `--max-bytes N` | 所有命令可用；正整数，默认 `1073741824`（1 GiB），设置 Context 的缓存/materialize 与输出字节预算，不是总内存上限，也并非只限制输入文件大小 |
| `--layers` | 逗号分隔 `Structure,Protocol,Media`，不加空格 |
| `--target`、`--profile` | 下节列出的协议 ID / profile；未知组合不会静默回退 |
| `--output-dir` | 尚不存在的新目录；同一次操作全部资产共同发布到其 `assets/` 子目录 |
| `--strict` | 支持此选项的协议/媒体修改要求严格保留；不是 Raw Extract 的参数 |
| `--frame-index N` / `--time-us N` | 二选一；索引从 0 开始，时间为微秒；`--track-id ID` 只可搭配索引 |
| `--format` | `Jpeg` 或 `Png`；具体操作仍有格式和保留条件 |
| `--quality` | 保留参数，仅 extract-frame/replace-cover：整数 0..100。当前桌面 FFmpeg JPEG profile 只支持默认质量，显式指定会拒绝，不自动近似映射；省略此参数即可。历史 Windows 系统 JPEG 质量适配代码保留但停用 |
| `--resource ID` | 选择一个视频资源，用于 probe/extract-frame/trim/remux；ID 来自 inspect，不是文件路径 |
| `--resources ID,ID` | extract 的资源列表，逗号分隔；默认留空交由 Core 选择 |
| `--raw-carrier` | extract 额外请求完整 carrier；不代表 Clean |
| `--issues CODE,CODE` | repair 的问题码过滤；不授权猜测性修复 |
| repair 的 `--mode` / `--authority` | `SafeMetadataOnly`（默认）、有限 Samsung/vivo/Huawei JPEG `ExplicitRemux`、有限 Apple `ExplicitRePair`。冲突重配对要求当前选定源的正式 CID evidence ID；容器修复不接受此权威参数 |
| `--same-target` | convert：`PreserveAsIs`（默认）或 `Normalize`；Normalize 仍需已实现的 writer |
| `--start-us`、`--end-us`、`--mode` | trim，以及 create/convert 的复合裁剪；模式 `LosslessPreferred`（默认）、`LosslessOnly`、`Exact` |
| `--key-outside` | 仅 create/convert 复合裁剪：`Reject`（默认）、`ClampExplicitly`、`ClearIfSupported`；不是独立 trim 的选项 |
| `--replacement-frame-index` / `--replacement-time-us` | 仅 create/convert：独立的派生封面选择，二选一；`--replacement-track-id` 仅配合 replacement 索引 |
| `--container` | remux/transcode：`Mp4` 或 `Mov` |
| `--codec` | transcode：`Avc` 或 `Hevc` |
| `--allow-transcode` | 仅 create/convert/trim/transcode；显式授权，不保证操作可用 |
| `--replace-oplus-user-comment` | 仅 create；授权 Oplus 输出替换上述两种精确相机注释，保留原片和其它字段 |
| `--ffmpeg` | 仅 media-capabilities/probe/create/convert/extract-frame/replace-cover/trim/remux/transcode；可执行文件路径，不是目录 |
| `--decode-check` | 仅 probe：请求实际解码检查；失败不自动降级成“解码成功” |
| `--update-key` | 仅 replace-cover：独立请求更新 key metadata |

create/convert 还接受 `--frame-index` / `--time-us` / `--track-id` 作为 key 位置，并接受上表所列复合裁剪、派生封面、`--strict`、`--allow-transcode` 与 `--ffmpeg`。key 位置和 replacement 位置是两套独立参数；Apple 有限 assembler 等入口会明确拒绝未实现的复合编辑。

正常结果与错误输出为 JSON；帮助和版本为文本。

| 退出码 | 意义 |
| --- | --- |
| `0` | 请求成功；仍需检查 verdict、coverage、能力及保留报告 |
| `2` | 参数错误 |
| `3` | Core / IO / 能力错误，包括 Planned/Unsupported 请求 |
| `4` | validation/analyze 的验证 Invalid，或 repair 存在 blocked 问题 |

## 协议与能力范围

下表描述当前有限实现，不是相册/手机兼容认证。**某 profile 可读不代表它可创建，某协议可创建不代表任意两种协议都可直接转换。** 对具体操作先查询 `capabilities`，再对实际输入 `analyze`；细节以 Core 条件及结果为准。

| 协议 ID | profile | 已落地范围与主要边界 |
| --- | --- | --- |
| `google.microvideo.v1` | `jpeg` | JPEG 读/验证/原样提取；有限 Create、Convert、Clean、key 编辑与安全 offset Repair |
| `google.motionphoto.v2` | `jpeg`、`heic` | JPEG 闭环及有限 HEIC item/XMP/mpvd 闭环；复杂 item 图、HDR/GainMap 与通用 AVIF 不在完整支持范围 |
| `oplus.olive` | `jpeg-no-tail`、`oneplus-tail-bearing` | 常规 JPEG 有限写入/修复；tail-bearing 读取与原样提取，不生成或猜测未知尾挂 |
| `samsung.motionphoto` | `jpeg-sef-mpv3`、`heic-sef-mpv2` | SEF 读取/验证/原样提取；JPEG 有限写入和 SEF 长度修复，HEIC 不当作通用可写 |
| `vivo.motionphoto` | `jpeg` | 有限读取、创建、转换、Clean、key/长度修复 |
| `huawei.movingphoto` | `basic60`、`honor-extended` | basic60 有限读取/Create/Clean；honor-extended 有限读取、未知扩展保持 Partial，不开放写入或猜 key 时间单位 |
| `apple.livephoto` | `jpeg-mov`、`jpeg-mp4` | 双资产读取/提取、有限 Clean/转换/key 编辑；显式 profile 的普通 JPEG+原容器 Create/ConvertTo，Generic Create 仍 Planned |
| `apple.livephoto` | `heic-mov`、`heic-mp4` | 有限 Pair 读取/整资产 Raw/同目标原样复制、SetKey、CID-only Clean；Create/ConvertTo 及跨协议转换仍待扩展 |
| `vivo.legacy-pair` | `pair` | Legacy 有限读取/提取/Clean/ConvertFrom；不公开 Create/ConvertTo |
| `lpb.fusion.legacy` | `jpeg` | Legacy 有限读取/提取/Clean/ConvertFrom；不公开 Create/ConvertTo |

Apple CID inspection evidence 绑定 source/generation/role/字段位置；它不是共同拍摄或设备兼容的证明。HEIC metadata 验证可能保持 `Warning/Partial`，即使已确认的 item graph 和协议检查完整通过，也不能宣称全部 metadata 或媒体已验证。

## 从源码编译与测试

### 构建前提

仓库当前使用 Kotlin **2.4.21**、Gradle Wrapper **9.8.1**、JDK **25**；此次清理不升级工具链。需要已有的 JDK 25，将 `JAVA_HOME` 指向其根目录。项目禁用自动安装 Java；始终使用仓库 Wrapper。首次构建可下载指定的 Gradle、Kotlin/Native 编译器及依赖，但不会下载 JDK。

构建工具仍使用 Java，**产品代码和发行程序不依赖 JVM/JRE**。不再使用 JAR、`installDist`、`jpackage` 或 `jlink` 打包。完整测试与打包还需要 PowerShell 7、宿主 C 编译器、创建符号链接的权限，以及已有的 FFmpeg；只有编译程序时不要求安装 FFmpeg。

执行 Gradle 前，检查项目中是否有 `gradle-daemon-jvm.properties`；如有，先停止并处理该配置，不要绕过。只在对应的 OS/架构构建、执行该平台程序。

### 编译原生程序

Windows x64，PowerShell：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'
if (Get-ChildItem -Recurse -File -Filter gradle-daemon-jvm.properties) {
    throw '请先处理 gradle-daemon-jvm.properties，再运行 Gradle。'
}
.\gradlew.bat -Plivephoto.nativeTarget=windows-x64 :verifyNativeOnlyBuild :cli:linkReleaseExecutableMingwX64 --console=plain --warning-mode=all
```

Linux x64，Bash/Zsh：

```sh
export JAVA_HOME='/path/to/jdk-25'
find . -name gradle-daemon-jvm.properties -print
# 上述检查必须没有结果，再执行：
./gradlew -Plivephoto.nativeTarget=linux-x64 :verifyNativeOnlyBuild :cli:linkReleaseExecutableLinuxX64 --console=plain --warning-mode=all
```

macOS ARM64，Bash/Zsh：

```sh
export JAVA_HOME='/path/to/jdk-25'
find . -name gradle-daemon-jvm.properties -print
# 上述检查必须没有结果，再执行：
./gradlew -Plivephoto.nativeTarget=macos-arm64 :verifyNativeOnlyBuild :cli:linkReleaseExecutableMacosArm64 --console=plain --warning-mode=all
```

产物位于 `cli/build/bin/<target>/releaseExecutable/`：

| 宿主 | target | 程序 |
| --- | --- | --- |
| Windows x64 | `mingwX64` | `LivePhoto.exe` |
| Linux x64 | `linuxX64` | `LivePhoto.kexe` |
| macOS ARM64 | `macosArm64` | `LivePhoto.kexe` |

运行时设置私有 `LIVEPHOTO_WORKSPACE`，其余参数与上文下载包示例一致。不再需要 `-Plivephoto.jvm=false`：所有模块默认只有所选 Native target；旧的 `livephoto.jvm=true` 会明确报错。

### 测试与验收

推荐在 [Build workflow](.github/workflows/build.yml) 手动运行完整三平台验收。它准备真实文件链接、原生子进程测试程序、固定协议素材及 FFmpeg，重新执行测试，检查库与解压后的 CLI，再上传产物。CI 安装 FFmpeg 只供测试，产品不会自动安装。

手动执行测试前，必须准备相同前提：

- 已有 FFmpeg 路径设置为 `LIVEPHOTO_FFMPEG`，并设置 `LIVEPHOTO_REQUIRE_FFMPEG=true`、`LIVEPHOTO_REQUIRE_REFERENCE=true`。缺少真实媒体后端不是完整验收。
- 用 `scripts/prepare-file-io-fixtures.ps1` 准备真实链接，`scripts/prepare-protocol-fixtures.ps1` 展开固定素材；这些脚本拒绝覆盖未知或不同内容。
- 编译 `scripts/fixtures/process-fixture.c`，将 `LIVEPHOTO_PROCESS_FIXTURE` 指向生成的宿主程序。CI 的 `prepare-process-fixture.ps1` 展示编译参数和环境准备。
- 工作目录为仓库根目录；保留 `reference/` 的原片。每个 test task 必须重新执行，不能将缓存 XML 或 `NO-SOURCE` 算作成功。

Windows 示例（已准备上述前提）：

```powershell
.\gradlew.bat -Plivephoto.nativeTarget=windows-x64 :verifyNativeOnlyBuild :shared:mingwX64Test --rerun :cli:mingwX64Test --rerun --console=plain --warning-mode=all
```

Linux 将 Wrapper 改为 `./gradlew`、平台改为 `linux-x64`、任务 target 改为 `linuxX64`；macOS 分别使用 `macos-arm64` 与 `macosArm64`。HTML/XML 报告在各模块的 `build/reports/tests/<target>Test/`、`build/test-results/<target>Test/`。

协议兼容素材位于 `shared/src/desktopTest/resources/`，包含 19 个固定资产的 SHA-256 清单。20 项固定素材回归与协议、保留、恶意输入、真实媒体测试由三平台运行相同断言。原有素材字节保留；测试展开素材不改变产品依赖，也不代替手机相册认证。

### 库、IO 与打包边界

- `shared/commonMain` 的协议实现只使用 stdlib，不依赖路径、FFmpeg 或平台媒体 API；合并后的 Native KLIB 另包含依赖 Okio、协程及系统 SDK 绑定的平台适配。
- `cli` 负责参数 → Request → Core → JSON/日志；平台行为由接口注入，不使用 Java 反射。
- `shared` 的 Native 文件适配使用 Okio，冻结输入、预算与内容证明；输出只接受新目录，整组资产原子发布。不支持网络共享、恶意同账户并发修改或断电持久性保证。
- `shared` 的进程适配使用原生宿主 API 和协程处理管道、取消、超时及资源释放；Windows 的 Win32 绑定用于文件/进程，不是系统媒体后端。
- Native FFmpeg 复用共用媒体策略，结果独立回读；音频始终 copy，不扩大 profile，不降低 metadata/时间线门禁。
- `scripts/package-native-cli.ps1` 消费真实测试导出的素材，在对应宿主打包、解压并执行 CLI 检查。它是验收流程的一部分，不能只构建程序就跳过前提。
- `shared` KLIB 位于 `shared/build/classes/kotlin/<target>/main/`；CI 打包供 Kotlin/Native 调用，不是 C ABI、DLL、iOS framework 或可执行程序。

## CI 与产物

[Build workflow](.github/workflows/build.yml) 在每次 push、pull request 和手动触发时运行 Windows x64 / Linux x64 / macOS ARM64 的 **Native-only** 构建、共用与原生测试、单文件打包及成品 smoke tests。CI 通过 `actions/setup-java` 准备 Zulu JDK 25，仅供 Gradle 构建工具；发行应用不携带 Java。Linux/macOS 直接执行仓库的 `gradlew`。smoke 启用 trace，日志随报告上传。

每个平台上传三类 artifacts，默认保留 **14 天**：

- `LivePhoto-<平台>-...`：原生程序归档及哈希证据；打包成功后上传，下载运行选这个。
- `LivePhoto-native-core-...`：合并后的 `shared.klib`、系统 SDK 绑定与库证据；供库开发使用，不是可执行程序。
- `native-core-reports-...`：`shared`、`cli` 的测试报告、XML、Gradle 与成品日志及证据；失败时也尝试上传已有报告。

Build 三平台成功共 9 个 artifacts，全部要求真实 FFmpeg 和解压后程序的 27 项 CLI 检查（其中 9 项真实媒体、7 项合成 HEIC），不能因没有工具而跳过。CI 安装 FFmpeg 仅用于验证，不会打入应用或由应用自动安装。

所有 Action 引用均使用最新稳定主版本 `@vN`。CI 复用 JDK 缓存与 Gradle 开源 basic 缓存；Native 另按 OS/架构/工具链缓存编译器依赖。编译任务允许缓存命中，但验收用对应 Native test `--rerun` 重新执行，不把旧报告计为新测试。Native 测试成功后保存工具链缓存，即使后续产物检查失败也不重复下载已验证的工具链。

## 进度、已完成与 TODO

桌面完整验收基线：[提交 `ac4ebe3` 的三平台 CI](https://github.com/ohyooo/LivePhoto/actions/runs/38511616570)，每平台 **911 项测试，零失败、零跳过**；解压后的程序另通过 **27 项 CLI 检查**，含 9 项真实媒体检查，九份产物已独立核验。Android 初始接入独立验收：[提交 `cc781d9` 的 Android CI](https://github.com/ohyooo/LivePhoto/actions/runs/38523702403)，**9 项宿主单元测试＋9 项 API 36 模拟器测试，零失败、零跳过**；APK、签名、lint、原片哈希及 MediaStore 保存回读通过，应用和报告两份产物已独立核验。不是手机相册兼容认证，不将桌面测试数当成 Android 验证。

已完成的有限范围：

- [x] Core 协议检测、检查、分层验证、创建/转换、Raw Extract/Clean Split、有限 Repair 与 key metadata 操作。
- [x] OPPO、Google、Samsung、vivo、Huawei 及既有 Apple Pair 的有限 profile；详细边界见协议表，未支持变体仍明确拒绝。
- [x] 原子输出、输入不可变、metadata/样本/时间线保留证明及固定素材回归。
- [x] 三平台 Kotlin/Native Core、共用 CLI、文件 IO、原生进程和有限 FFmpeg 后端；单文件程序不携带 Java。
- [x] Windows/macOS/Linux 真实 FFmpeg 抽帧、remux、有限带音频裁剪、显式视频转码、封面替换及组合 Create 回归；音频只 copy。
- [x] 迁移期 JVM 运行时代码、旧 CLI、NIO/ImageIO/Java 进程桥、Windows 历史系统媒体 API及其专项测试、旧 JVM 打包/回归流程清理；保留共用/Native 测试和全部素材。Gradle 构建仍需要 JDK。
- [x] Android 初始接入：直接引用 Core 的 APK、最小手测界面、系统选择器/有界内存输入、单张 MediaStore 保存、宿主与模拟器回归及 CI 产物。
- [x] Kotlin/JS 基础功能库、Promise/File/Blob 适配、真实 Chromium/Worker 验收，以及复用 Android 的浏览器 Compose 界面；用户已手测网页可用。

后续 TODO（当前不扩展这些业务范围）：

- [ ] 真机验收：更多未编辑厂商原片及设备/OS/相册/导入方式证据，验证动态播放、声音与 key；合成和解码测试不能代替。
- [ ] 更多有规范证据的媒体时间线、容器/codec、AAC priming/复杂原始 edits 和保留 profile；由 FFmpeg 处理，逐项补独立证明。不增加音频编码。
- [ ] 其它显式 Repair、复杂 HEIF/AVIF、HDR/GainMap、辅助资源及未知 metadata 引用关系；无法证明安全时保持 Unsupported/Partial。
- [ ] **暂停 Apple 新功能**：复杂 Generic Create、HEIC 写入/转换、MakerNote 合并/兼容读取、更多普通或混合 timed metadata、CID 与普通文本的跨工具兼容；已有能力和测试保留。
- [ ] 后续移动工作：大文件/随机访问 IO、URI 生命周期、通用多资产事务、移动系统媒体后端及真机验收。iOS targets、构建/IO/媒体适配仍未实施；不复用桌面 FFmpeg 进程冒充移动支持。
- [ ] Web 后续协议接口、公共 JS 回归与浏览器媒体后端，分项状态见 [Web 路线与 TODO](docs/web-roadmap.md)。
- [ ] 完整 Compose Multiplatform GUI：当前 Android/Web 共享有限测试界面，更完整的工具界面仍待实现，不扩大 Core 业务范围。

### 诊断日志

排查异常时可使用 `LivePhoto detect --input 'livephoto.jpg' --log-level trace 2>trace.log`。默认不记录日志；debug 提供操作结果，trace 增加阶段进度、耗时及未捕获异常的有界调用栈。异常消息和命令参数不写入诊断，避免泄露素材路径或 metadata。已转为结构化 Core 错误的异常仅记录错误码和阶段，不保证拥有底层调用栈。独立 `--help` / `--version` 不产生诊断。

macOS 在解压目录使用实际入口（终端 CLI，无需应用 bundle）：

```sh
./LivePhoto.kexe --version
./LivePhoto.kexe detect --input './livephoto.jpg' --log-level trace 2>trace.log
```

该包未提供 Apple 公证或发行证书签名；不同下载方式可能触发 Gatekeeper，不要为运行它关闭全局安全检查。

## 常见问题

**没有 FFmpeg 还能使用吗？** 可以使用不需要媒体后端的有限协议能力。当前桌面端不调用系统媒体 API 回退；需要实际媒体处理时查询 `media-capabilities`，没有 FFmpeg 就返回 Unsupported，不会制造输出。

**为什么 `--strict` 拒绝我认为正常的照片？** 字节未移动不自动证明未知 MakerNote/metadata 关联完整保留。查看错误和 preservation；尤其 Apple 首次 Clean 可能仍是 MetadataPreserving Unknown，不能声称严格保留。

**为什么输入 `.jpg` 仍识别成其它格式，或 Apple 单文件只是 Candidate？** 检测依据内容而不是扩展名；Apple 配对依据正式解析的标识和双资产结构，不按名字猜。

**为什么创建/转换报 Planned 或 Unsupported？** 查询完整 `--target` / `--profile` 能力；部分 Create 必须显式 profile，某些合法媒体容器/codec/metadata 变体尚未实现。不要通过改扩展名、默认转码或删除未知字段绕过。

**输出目录已存在怎么办？** 换一个不存在的目录，哪怕现有目录为空也不复用。输出路径读取 JSON；失败时不要把不完整结果当成成功发布，也不要盲目覆盖重试。

**测试全过为什么不能说手机兼容？** Core 协议/媒体检查、合成 conformance、真实解码和相册导入行为是不同层级的证据。当前设备验收仍在 TODO。

## 项目结构与参考

- `shared/src/commonMain/`：公共模型、协议解析/写入、保留及原子操作逻辑。
- `shared/src/commonTest/`、`shared/src/nativeTest/`：共用协议/素材回归与真实 Native 测试。
- `shared/src/nativeMain/` 与各宿主 source set：文件/事务、原生进程及 FFmpeg 适配；不引入 Android 构建。
- `cli/`：薄 CLI 和原生入口。
- `ui/src/commonMain/`：平台无关的 Compose 界面、选择状态和结果展示；按窗口尺寸布局，不依赖 Android Uri 或 Activity。
- `android/`：Activity 与文件选择/元信息/相册保存适配，启动共享 UI；Core 无 JNI。
- `scripts/`：测试素材准备、Native 打包与证据检查。
- `reference/`：三个测试媒体，供协议与往返测试使用。
- `upstream/`：只读参考，不是项目依赖，不包含在发行包中；不复制其业务架构。

README 的信息组织参考 [Gradle](https://github.com/gradle/gradle/blob/master/README.md) 的入门/构建导航与 [scrcpy](https://github.com/Genymobile/scrcpy/blob/master/README.md) 的前提/下载/用法组织；命令、参数、平台状态和能力边界来自本仓库实际源码与验收记录。
