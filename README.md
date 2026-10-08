# LivePhoto

[![Build](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml/badge.svg)](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)

使用 Kotlin Multiplatform 实现的 Live Photo / Motion Photo Core 与命令行工具。可检测、检查、验证、创建、转换、提取和拆分支持范围内的动态照片，并提供有限的修复、关键帧位置修改及媒体处理能力。

**当前为开发中的实验性版本，不是全厂商、全格式兼容工具。** Core 与 CLI、文件路径及具体媒体库解耦；当前构建目标为 JVM，尚未交付 Native、Android、iOS 或 GUI。能力以运行时返回的 `implementation`、`conditions`、`coverage` 和保留报告为准，不能把 `Experimental`、`Planned` 或 `Partial` 理解为全面支持。

## 目录

- [下载与运行](#下载与运行)
- [前提与推荐](#前提与推荐)
- [快速开始](#快速开始)
- [命令与参数](#命令与参数)
- [协议与能力范围](#协议与能力范围)
- [从源码编译与测试](#从源码编译与测试)
- [CI 与产物](#ci-与产物)
- [进度、已完成与 TODO](#进度已完成与-todo)
- [常见问题](#常见问题)

## 下载与运行

进入 [GitHub Actions / Build](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)，选择成功的运行，在 **Artifacts** 下载对应平台的 `LivePhoto-<平台>-<run_id>-<attempt>`。

Actions 的 artifact 是外层下载包；解压后再展开里面的 `.zip` / `.tar.gz`，得到完整应用目录。保留同包的 `.sha256` 以校验**内层应用归档**，不是校验外层 artifact。

| 平台 | 应用归档 | 解压后的入口 | 当前交付状态 |
| --- | --- | --- | --- |
| Windows x64 | `LivePhoto-windows-x64.zip` | `LivePhoto/LivePhoto.exe` | 已构建与便携验收 |
| Linux x64 | `LivePhoto-linux-x64.tar.gz` | `LivePhoto/bin/LivePhoto` | 已通过 CI 构建与便携验收 |
| macOS ARM64 | 尚无已交付包 | 脚本预留 `LivePhoto.app/Contents/MacOS/LivePhoto` | runner 环境问题，暂缓 |

不提供 Intel macOS、Windows ARM 或 Linux ARM 包。当前是便携应用目录，**不是单个独立 EXE，也不是 Kotlin/Native 二进制**：通过 `jpackage app-image` 携带精简 Java 运行时，无需在使用机器上另外安装 Java。不要只复制 EXE、删除 `runtime/`、应用 JAR 或 Windows 媒体 helper。暂不提供 MSI/DMG 安装器。

Windows PowerShell，在解压位置运行：

```powershell
.\LivePhoto\LivePhoto.exe --version
.\LivePhoto\LivePhoto.exe --help
```

Linux，在解压位置运行：

```sh
./LivePhoto/bin/LivePhoto --version
./LivePhoto/bin/LivePhoto --help
```

## 前提与推荐

- **只做协议操作通常不需要 FFmpeg。** 检测、结构检查、原样提取及有限的协议封装/编辑，可直接由 Core 完成。结构检查不代表已经解码视频。
- **抽帧、裁剪、转码等推荐使用自己准备的 FFmpeg。** 后端选择顺序为：`--ffmpeg` 显式路径 → 系统 `PATH` → 已实现且运行时可用的系统 API → 禁用相应能力。工具不会自动下载或安装 FFmpeg、Java 或其他后端，也不识别 shell 的 FFmpeg 别名；PATH 中使用绝对目录。
- Windows 系统 API 当前仅提供有限的 AVC/MP4、无音轨、完整软件解码 Probe 回退；并非通用媒体处理后端。系统抽帧/裁剪/remux/transcode 与其它 OS 系统适配器仍待实现。先用 `media-capabilities` 查看当前机器实际可用能力。
- NAS 验证使用用户安装的 `C:\Program Files\FFmpeg\bin\ffmpeg.exe`。使用 shared 版 FFmpeg 时，要保留它旁边的 DLL；不要只移动 EXE。CLI 使用 `--ffmpeg` 或 PATH；测试另支持 `LIVEPHOTO_FFMPEG`，`FFMPEG_HOME` 本身不是 CLI 的查找入口。
- 推荐先 `inspect` / `analyze`，再执行修改；重要原片另行备份。输入保持不可变，输出使用**尚不存在的新目录**，不会覆盖输入或已有输出。
- 默认禁止转码。`--allow-transcode` 只是显式授权，仍受操作、输入及目标的能力和安全条件限制。不要用它绕过未知 metadata、HDR、GainMap、MakerNote 或资源依赖风险。
- 默认保留策略是 `BestEffortWithReport`：必须阅读 `preservation` 报告，而不是假设所有 metadata 均无损。`--strict` 要求严格保留，无法证明时会拒绝输出；这不是“关闭警告”开关。
- Apple 使用图片和视频两个资产，不能只凭文件名或扩展名认定配对。一般单个带 CID 的文件只是 `Candidate`；传入两个内容标识匹配的资产才可验证 Pair。

例如，查看后端及执行真实解码检查：

```powershell
$LP = (Resolve-Path .\LivePhoto\LivePhoto.exe).Path
& $LP media-capabilities --ffmpeg 'C:\Program Files\FFmpeg\bin\ffmpeg.exe'
& $LP probe --input '.\video.mp4' --decode-check --ffmpeg 'C:\Program Files\FFmpeg\bin\ffmpeg.exe'
```

## 快速开始

以下为 PowerShell 示例，先把 `$LP` 指向解压后的程序。Linux 用实际入口替换 `& $LP`。路径包含空格时加引号，命令名、协议 ID 和枚举值按示例大小写使用。

### 检测、检查与验证

```powershell
& $LP detect --input '.\reference\livephoto.jpg'
& $LP inspect --input '.\reference\livephoto.jpg'
& $LP analyze --input '.\reference\livephoto.jpg'
& $LP validate --input '.\reference\livephoto.jpg' --layers Structure,Protocol
& $LP get-key --input '.\reference\livephoto.jpg'
```

`detect` 按内容判断，可返回多个协议匹配；`inspect` 展示结构和 metadata，不等同于有效性结论。`validate` 默认检查 `Structure,Protocol,Media`，注意 `coverage`、各层 checks 以及 `NotRun` / `Partial`；退出码为 0 也不等于完整媒体或设备认证。

**参考成品不是“严格验证必定成功”的样本。** `reference/livephoto.jpg` 的 secondary item 声明了非规范 `Padding=0`，已有测试明确要求严格 Google V2 协议验证为 `Invalid`；它的媒体结构检查也可能报告问题。因此对它执行上述 analyze/validate 返回退出码 **4** 是实际、预期的报告，不应修改检查使其变绿。下节用普通 `video.jpg + video.mp4` 创建的新成品才是本项目的成功闭环示例。

### 普通图片 + 视频 → Google Motion Photo

```powershell
& $LP capabilities --target google.motionphoto.v2 --profile jpeg
& $LP create --image '.\reference\video.jpg' --video '.\reference\video.mp4' --target google.motionphoto.v2 --profile jpeg --output-dir '.\out-create'
```

输入必须符合有限的 JPEG / 视频 profile；默认不转码。输出发布在 `out-create/assets/`，JSON 的 `result.output.assets[].path` 给出真实文件位置；**不要假设输出总叫 `livephoto.jpg`**。后续示例里的 `motion.jpg` 指你按 JSON 路径选取的结果或自己的输入文件。

### 原样提取、干净拆分、协议转换

```powershell
& $LP extract --input '.\motion.jpg' --output-dir '.\out-extract'
& $LP split --input '.\motion.jpg' --output-dir '.\out-split'
& $LP convert --input '.\motion.jpg' --target google.microvideo.v1 --profile jpeg --output-dir '.\out-convert'
```

`extract` 原样提取已确认资源，默认资源选择由 Core 决定；`split` 输出普通媒体并清除已确认的动态照片专属 metadata。两者不是同一个操作，也不一定产出相同图片字节。

### Apple 双资产与关键帧 metadata

```powershell
& $LP inspect --input '.\IMG.jpg' --pair-video '.\IMG.mov'
& $LP extract --input '.\IMG.jpg' --pair-video '.\IMG.mov' --output-dir '.\out-apple-raw'
& $LP set-key --input '.\IMG.jpg' --pair-video '.\IMG.mov' --frame-index 1 --output-dir '.\out-apple-key'
```

`set-key` 修改协议意义的 key/presentation 位置，**不会重新生成封面图**。Apple 修改入口只接受已确认的有限结构，不能将任意相机原片视为可写。

普通媒体创建 Apple 需要**显式 profile**，并满足 JPEG metadata 与原视频容器的分类门禁：

```powershell
& $LP create --image '.\plain.jpg' --video '.\plain.mov' --target apple.livephoto --profile jpeg-mov --output-dir '.\out-apple-create'
```

不会隐式把 MP4 改成 MOV；MP4 输入应查询并使用 `jpeg-mp4`。省略 Apple profile 的 Generic Create 仍是 `Planned`，HEIC Create/ConvertTo 也未实现。

### 修复：先预览，再明确写入

```powershell
& $LP repair --input '.\damaged-motion.jpg'
& $LP repair --input '.\damaged-motion.jpg' --issues MOTION_VIDEO_LENGTH_MISMATCH --apply --output-dir '.\out-repair'
```

当前仅修复能够唯一证明的有限 metadata 问题，不猜协议、视频边界、ID 或单位。预览不写入；预览时不能传 `--output-dir`。Apple 冲突 ID 的 `ExplicitRePair` 尚未实现，也没有 CLI `--authority` 参数。

### 抽帧、裁剪、remux 与显式转码

```powershell
& $LP extract-frame --input '.\video.mp4' --frame-index 0 --format Jpeg --output-dir '.\out-frame'
& $LP trim --input '.\video.mp4' --start-us 0 --end-us 1000000 --mode LosslessPreferred --output-dir '.\out-trim'
& $LP remux --input '.\video.mp4' --container Mov --output-dir '.\out-remux'
& $LP transcode --input '.\video.mp4' --container Mp4 --codec Avc --allow-transcode --output-dir '.\out-transcode'
```

这些示例假设可用 FFmpeg 在 PATH 中，也可追加 `--ffmpeg '完整路径'`。`LosslessPreferred` 是默认裁剪模式，应读取结果中请求与实际边界；`Exact` 必要时转码仍需 `--allow-transcode`。`--frame-index` 是 **zero-based presentation order**，不是 decode order。时间参数单位为微秒。

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
| `repair` | `--input`；可选 `--pair-video`、`--issues`、`--strict`；写入需 `--apply --output-dir` | 有限安全修复 |
| `set-key` | `--input`、位置、`--output-dir`；可选 `--pair-video`、`--strict` | 修改 key metadata |
| `extract-frame` | `--input`、位置、`--format`、`--output-dir`；可选 `--pair-video`、`--resource`、`--ffmpeg` | 提取视频帧 |
| `replace-cover` | `--input`、位置、`--format`、`--output-dir`；可选 `--pair-video`、`--update-key`、`--strict`、`--ffmpeg` | 重建主图 |
| `trim` | `--input`、`--start-us`、`--end-us`、`--output-dir`；可选 `--pair-video`、`--mode`、`--resource`、`--strict`、`--allow-transcode`、`--ffmpeg` | 输出裁剪后的普通视频 |
| `remux` | `--input`、`--container`、`--output-dir`；可选 `--pair-video`、`--resource`、`--strict`、`--ffmpeg` | 不转码重封装普通视频 |
| `transcode` | `--input`、`--container`、`--codec`、`--allow-transcode`、`--output-dir`；可选 `--pair-video`、`--strict`、`--ffmpeg` | 显式转码普通视频 |

补充参数与取值：

| 参数 | 取值 / 约束 |
| --- | --- |
| `--max-bytes N` | 所有命令可用；正整数，默认 `1073741824`（1 GiB），设置 Context 的读取与内存预算，并非只限制输入文件大小 |
| `--layers` | 逗号分隔 `Structure,Protocol,Media`，不加空格 |
| `--target`、`--profile` | 下节列出的协议 ID / profile；未知组合不会静默回退 |
| `--output-dir` | 尚不存在的新目录；同一次操作全部资产共同发布到其 `assets/` 子目录 |
| `--strict` | 支持此选项的协议/媒体修改要求严格保留；不是 Raw Extract 的参数 |
| `--frame-index N` / `--time-us N` | 二选一；索引从 0 开始，时间为微秒；`--track-id ID` 只可搭配索引 |
| `--format` | `Jpeg` 或 `Png`；具体操作仍有格式和保留条件 |
| `--resource ID` | 选择一个视频资源，用于 probe/extract-frame/trim/remux；ID 来自 inspect，不是文件路径 |
| `--resources ID,ID` | extract 的资源列表，逗号分隔；默认留空交由 Core 选择 |
| `--raw-carrier` | extract 额外请求完整 carrier；不代表 Clean |
| `--issues CODE,CODE` | repair 的问题码过滤；不授权猜测性修复 |
| `--same-target` | convert：`PreserveAsIs`（默认）或 `Normalize`；Normalize 仍需已实现的 writer |
| `--start-us`、`--end-us`、`--mode` | trim，以及 create/convert 的复合裁剪；模式 `LosslessPreferred`（默认）、`LosslessOnly`、`Exact` |
| `--key-outside` | 仅 create/convert 复合裁剪：`Reject`（默认）、`ClampExplicitly`、`ClearIfSupported`；不是独立 trim 的选项 |
| `--replacement-frame-index` / `--replacement-time-us` | 仅 create/convert：独立的派生封面选择，二选一；`--replacement-track-id` 仅配合 replacement 索引 |
| `--container` | remux/transcode：`Mp4` 或 `Mov` |
| `--codec` | transcode：`Avc` 或 `Hevc` |
| `--allow-transcode` | 仅 create/convert/trim/transcode；显式授权，不保证操作可用 |
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

### 环境

当前仓库固定 Kotlin **2.4.20**、Gradle Wrapper **9.8.0**、Java Toolchain **25**。这些是当前配置值，不是自动追踪最新版；不要为了编译 README 示例自行升级工具链。

需要已有的 **JDK 25**，`JAVA_HOME` 指向 JDK 根目录。项目禁用自动供应 Java，并通过 `JAVA_HOME` 发现 toolchain；无需系统 Gradle，始终使用 Wrapper。Wrapper 和首次依赖解析需要网络，允许下载仓库指定的 Gradle 与依赖，但不会安装 JDK。打包另需 JDK 的 `jpackage` / `jlink`，以及已有的 PowerShell 7；应用使用者不需要这些构建工具。

本项目协作验证约定：**源码在本地修改，所有 Gradle、测试、CLI 可执行验证只在 `ssh_nas_ci` 的 `C:\Users\admin\Desktop\LivePhoto` 执行**；不要在当前 macOS 开发主机编译或运行项目，也不要使用 WSL。先同步本地源码，远程不作为长期源码编辑位置。

每次修改/构建/测试前先 fetch 检查上游；只允许安全快进，分叉或冲突时停止，不能自动 merge/rebase/reset/丢弃工作树修改。

### NAS 的 Windows PowerShell 示例

通过 `ssh ssh_nas_ci` 连接，确保已进入 PowerShell，然后：

```powershell
Set-Location 'C:\Users\admin\Desktop\LivePhoto'
$env:JAVA_HOME = 'C:\Program Files\jdk\jbr-25'

# 每次 Gradle 操作之前检查；若发现文件，停止并交由用户处理。
if (Get-ChildItem -Recurse -File -Filter gradle-daemon-jvm.properties) {
    throw '发现 gradle-daemon-jvm.properties；请先由用户删除，再继续。'
}

.\gradlew.bat build :core:jvmTest :cli:installDist --console=plain --warning-mode=all
```

完整重新执行验收时追加 `--rerun-tasks`。不要把缓存命中、旧 XML 或 `NO-SOURCE` 当作新代码已测试。

产物位置：

- Core JAR：`core/build/libs/`。
- CLI JAR：`cli/build/libs/livephoto-cli.jar`；不是可独立 `java -jar` 的 fat JAR。
- 带依赖的 JVM CLI 目录：`cli/build/install/cli/`，入口 `bin/cli.bat`，运行时需要已有 Java 25。
- JVM 分发归档：`cli/build/distributions/`。
- 测试报告：`core/build/reports/tests/jvmTest/`、`cli/build/reports/tests/test/`；XML 在对应 `build/test-results/`。

源码构建后的 CLI：

```powershell
.\cli\build\install\cli\bin\cli.bat --help
```

如需远程 fresh-report 验收记录，使用现有脚本（同样先完成上游检查与 JDK 设置）：

```powershell
.\scripts\remote-phase-validate.ps1 -Phase readme-check -GradleTasks @('build', ':core:jvmTest', ':cli:installDist')
```

脚本检查 JVM criteria 文件并写入本轮 run ID、时间、结果及 fresh XML 汇总，记录位于 `.validation/`，不提交到仓库。长任务复用现有远程 tmux/psmux 会话，避免 SSH 断开中止验收。

### 可选媒体集成测试

纯协议测试不依赖 FFmpeg；没有后端时相关媒体集成测试可以跳过。需要完整 FFmpeg 验收时，在上述构建前设置：

```powershell
$env:LIVEPHOTO_FFMPEG = 'C:\Program Files\FFmpeg\bin\ffmpeg.exe'
$env:LIVEPHOTO_REQUIRE_FFMPEG = 'true'
$env:LIVEPHOTO_REQUIRE_REFERENCE = 'true'
```

Windows 系统 API 专项验收还可设置 `LIVEPHOTO_REQUIRE_WINDOWS_MEDIA=true`，缺少要求的运行时能力会失败，而不是把未执行当作成功。这些环境变量用于测试，不是常规 CLI 参数；按验收需要设置，结束后移除本轮新增的变量。

reference 测试使用仓库中的 `reference/video.jpg`、`reference/video.mp4` 和 `reference/livephoto.jpg`，检查封装、提取及保留结果；不会要求参考成品中的 embedded MOV 必须与独立原 MP4 相同。用户报告可识别不等于本项目已完成所有设备验收。

### 打包 Windows 便携应用

在 NAS 完成 `:cli:installDist` 和 `:core:jvmTest` 后：

```powershell
.\scripts\package-portable.ps1 -Platform windows-x64 -Destination '.\ci-output\portable-readme'
```

Destination 必须使用新路径，脚本不覆盖既有应用 image；会调用 `jpackage`、生成归档与 SHA-256，解压到带空格路径进行 smoke tests。它还消费 jvmTest 导出的合成 fixture，不能只构建 CLI 就跳过这些前提。成功归档是 `LivePhoto-windows-x64.zip`，最后输出 `PORTABLE_SUCCESS`；任何前面单项 SUCCESS 都不能代替完整打包结果。

Linux x64 使用同一脚本的 `-Platform linux-x64`，**必须在对应 OS/架构的构建机运行，不支持交叉 jpackage**；本项目已通过 GitHub Actions 执行该流程。macOS ARM64 仅预留脚本入口，尚无成功交付证明。建议复用现有 Gradle 缓存，保留数 GB 磁盘余量；image、解压验收目录与素材副本会明显增加空间使用。

## CI 与产物

[Build workflow](.github/workflows/build.yml) 在每次 push、pull request 和手动触发时运行 Windows x64 / Linux x64 构建、测试、便携打包及 smoke tests。只使用 runner 已有 JDK 25，不自动下载 Java。

每个平台上传三类 artifacts，默认保留 **14 天**：

- `LivePhoto-...`：应用归档与 SHA-256；打包成功后上传，下载运行选这个。
- `build-outputs-...`：JAR 与 JVM 分发包。
- `build-reports-...`：测试报告、XML、合成 fixture、Gradle 和便携日志；构建失败时也尝试上传已有报告。

两平台成功通常共 6 个 artifacts。CI 可能没有 FFmpeg，不能把它的协议/便携测试等同于 NAS 的完整真实媒体集成验收，也不能把缺失工具后的 skip 当作该媒体操作成功。

## 进度、已完成与 TODO

### 最新可核验检查点

截至 **2026-10-08**，代码检查点 [`30454b9`](https://github.com/ohyooo/LivePhoto/commit/30454b9b32678999bae284a812875e2cef3f5e12)：

- NAS Host：`DESKTOP-DCMRJ35`；Directory：`C:\Users\admin\Desktop\LivePhoto`。
- Command：`.\gradlew.bat build :core:jvmTest :cli:installDist --console=plain --warning-mode=all --rerun-tasks`。
- 本轮 **706 tests，0 failures / errors / skips**；独立核对本轮 XML 时间，不借用以前的 381 项或其它旧结果。
- 完整 Windows 便携验收成功，包括已有 FFmpeg 集成与无 FFmpeg 的合成协议验收；这仍不是设备认证。
- [对应 CI 37713982144](https://github.com/ohyooo/LivePhoto/actions/runs/37713982144) 成功；Windows/Linux 合计 6 个非空、未过期 artifacts 已核对。

这是特定提交的证据，不是对以后每个提交的永久保证。项目**尚未完成所有 phases**；任务范围和未覆盖 profile 持续扩展，暂不提供缺乏固定分母的总体百分比。

本 README 的基础命令另于 2026-10-08 在上述 NAS 使用已验证的便携程序试跑：help/version、读取、reference 预期 Invalid、普通媒体 Create 后协议验证、字节精确视频 Extract、Clean Split、转换均通过。这是文档示例验收，不是重新运行 706 项单元测试，也不表示本文所有条件性示例或 TODO 已实现。

### 已完成的实施批次

- [x] Phase 1：KMP/JVM 工程、Domain/Core API、错误/能力/保留模型、随机访问 IO 和媒体后端契约。
- [x] Phase 2 基础批次：JPEG、XMP/XML、最小 EXIF/TIFF、ISO-BMFF、范围/预算/输入身份与保留门禁；有限 HEIF item graph 基础。
- [x] Google JPEG V1/V2 主流程，以及有限 Google HEIC 创建/读取/提取/Clean/同协议转换/key/长度修复批次。
- [x] OPPO/OnePlus、Samsung、vivo、Huawei/Honor、Legacy/Fusion 的已声明有限协议批次；不能据此扩展为全厂商兼容。
- [x] Apple 有限 JPEG/MOV、JPEG/MP4 双资产 Create/ConvertTo/读取/提取/清理/key；有限 HEIC Pair 读取、SetKey、Clean 与幂等拆分。
- [x] 唯一证据的有限 metadata Repair、只读预览/allowlist/幂等/失败回滚；Apple 源绑定 CID inspection evidence。
- [x] key metadata 与抽帧/封面重建分离；FFmpeg 有限 probe、抽帧、裁剪、remux、显式 transcode 及 Core 集成。
- [x] Windows 有限系统 API 完整解码 Probe 回退与隔离 helper；不能当作系统媒体编辑已完成。
- [x] 薄 CLI、JSON/退出码、两资产原子发布、源变化/预算/取消/篡改/第二资产失败回归。
- [x] Windows/Linux 便携 CI、reference 测试、合成 parser/writer/round-trip/byte-exact/metadata/malformed 测试与有限真实媒体解码验收。

### 剩余 TODO

- [ ] **下一项：Apple 有限 ExplicitRePair。** 明确选定和授权两个输入，通过新鲜 CID 证据指定权威；不自动选择冲突 ID，原子输出并独立验证。目前仅证据读取已实现。
- [ ] Apple Generic Create、HEIC Create/ConvertTo、HEIC 跨协议转换/Normalize、更多真实相机 MakerNote 和复杂媒体 profile；已有有限路径不代表这些完成。
- [ ] 扩展复杂 HEIF/AVIF、HDR/GainMap、未知 metadata 关联、辅助资源/混合轨道等；无法证明安全时继续 Unsupported/Partial，不先删 metadata 再宣称无损。
- [ ] Windows 系统抽帧/裁剪/remux/transcode；macOS/其它平台官方 API 后端。没有合格后端时继续禁用相关操作，不自动安装工具。
- [ ] 恢复 macOS ARM64 runner 前置条件与真实打包验收；不以 Intel Mac 或 Windows/Linux ARM 替代。
- [ ] **L4 真机兼容验收（用户明确暂缓）。** 后续补充未编辑厂商原片、设备型号、系统/相册版本、导入后的动态播放/声音/key 表现；不能由合成测试或自生成 round-trip 代替。
- [ ] 扩展真实 upstream/厂商原片 conformance、更多完整媒体/保留证明与不支持变体回归；持续记录每项能力的证据和边界。
- [ ] 未来 Compose UI、Android/iOS/Native targets：不属于当前 Core + CLI 交付范围，不提前引入 GUI 依赖。

## 常见问题

**没有 FFmpeg 还能使用吗？** 可以使用不需要媒体后端的有限协议能力。需要实际媒体处理时查询 `media-capabilities`；系统后端没有该操作则返回 Unsupported，不会制造输出。

**为什么 `--strict` 拒绝我认为正常的照片？** 字节未移动不自动证明未知 MakerNote/metadata 关联完整保留。查看错误和 preservation；尤其 Apple 首次 Clean 可能仍是 MetadataPreserving Unknown，不能声称严格保留。

**为什么输入 `.jpg` 仍识别成其它格式，或 Apple 单文件只是 Candidate？** 检测依据内容而不是扩展名；Apple 配对依据正式解析的标识和双资产结构，不按名字猜。

**为什么创建/转换报 Planned 或 Unsupported？** 查询完整 `--target` / `--profile` 能力；部分 Create 必须显式 profile，某些合法媒体容器/codec/metadata 变体尚未实现。不要通过改扩展名、默认转码或删除未知字段绕过。

**输出目录已存在怎么办？** 换一个不存在的目录，哪怕现有目录为空也不复用。输出路径读取 JSON；失败时不要把不完整结果当成成功发布，也不要盲目覆盖重试。

**测试全过为什么不能说手机兼容？** Core 协议/媒体检查、合成 conformance、真实解码和相册导入行为是不同层级的证据。当前设备验收仍在 TODO。

## 项目结构与参考

- `core/src/commonMain/`：公共模型、协议解析/写入、保留及原子操作逻辑。
- `core/src/jvmMain/`：文件 IO、可选 FFmpeg/Windows 系统 API adapter。
- `cli/`：参数 → Request → Core → JSON，业务逻辑不放在 CLI。
- `scripts/`：远程验收、便携打包和 Windows 隔离 helper 配置。
- `reference/`：已授权提交的三个测试媒体；设计规范与实施日志在本地保留，不随此 README 上传。
- `upstream/`：只读参考，不是项目依赖，不包含在发行包中；不复制其业务架构。

README 的信息组织参考 [Gradle](https://github.com/gradle/gradle/blob/master/README.md) 的入门/构建导航与 [scrcpy](https://github.com/Genymobile/scrcpy/blob/master/README.md) 的前提/下载/用法组织；命令、参数、平台状态和能力边界来自本仓库实际源码与验收记录。
