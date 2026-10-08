# LivePhoto

[![Build](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml/badge.svg)](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)

使用 Kotlin Multiplatform 实现的 Live Photo / Motion Photo Core 与命令行工具。可检测、检查、验证、创建、转换、提取和拆分支持范围内的动态照片，并提供有限的修复、关键帧位置修改及媒体处理能力。

**当前为开发中的实验性版本，不是全厂商、全格式兼容工具。** Core 与 CLI、文件路径及具体媒体库解耦；当前构建目标为 JVM，尚未交付 Native、Android、iOS 或 GUI。能力以运行时返回的 `implementation`、`conditions`、`coverage` 和保留报告为准，不能把 `Experimental`、`Planned` 或 `Partial` 理解为全面支持。

**当前进度：有限 Core + CLI 闭环已可测试，Windows/Linux 便携包已交付；Apple 有限 ExplicitRePair 已完成测试、便携包和 CI 验收。后续仍有显式修复模式、Apple 写入及系统媒体后端扩展；真机验收暂缓。** 具体阶段状态、后续顺序与验收条件见[进度与 TODO](#进度已完成与-todo)。

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
- Windows 系统 API 提供有限的 AVC/MP4 或 MOV、无音轨、完整软件解码 Probe 回退：单视频轨、48×48 至 4096×2304、至多 64 帧、唯一且可精确表示为 100ns 的 presentation 时间。新 MOV 路径已通过实际测试与 Windows 便携验收；并非通用媒体处理后端。系统抽帧/裁剪/remux/transcode 与其它 OS 系统适配器仍待实现。先用 `media-capabilities` 查看当前机器实际可用能力。
- 使用 shared 版 FFmpeg 时，要保留它旁边的 DLL；不要只移动 EXE。CLI 使用 `--ffmpeg` 或 PATH；测试另支持 `LIVEPHOTO_FFMPEG`，`FFMPEG_HOME` 本身不是 CLI 的查找入口。
- 推荐先 `inspect` / `analyze`，再执行修改；重要原片另行备份。输入保持不可变，输出使用**尚不存在的新目录**，不会覆盖输入或已有输出。
- 默认禁止转码。`--allow-transcode` 只是显式授权，仍受操作、输入及目标的能力和安全条件限制。不要用它绕过未知 metadata、HDR、GainMap、MakerNote 或资源依赖风险。
- 默认保留策略是 `BestEffortWithReport`：必须阅读 `preservation` 报告，而不是假设所有 metadata 均无损。`--strict` 要求严格保留，无法证明时会拒绝输出；这不是“关闭警告”开关。
- Apple 使用图片和视频两个资产，不能只凭文件名或扩展名认定配对。一般单个带 CID 的文件只是 `Candidate`；传入两个内容标识匹配的资产才可验证 Pair。

例如，查看后端及执行真实解码检查：

Windows（PowerShell）：

```powershell
.\LivePhoto\LivePhoto.exe media-capabilities --ffmpeg 'C:\Program Files\FFmpeg\bin\ffmpeg.exe'
.\LivePhoto\LivePhoto.exe probe --input '.\video.mp4' --decode-check --ffmpeg 'C:\Program Files\FFmpeg\bin\ffmpeg.exe'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto/bin/LivePhoto media-capabilities --ffmpeg '/usr/bin/ffmpeg'
./LivePhoto/bin/LivePhoto probe --input './video.mp4' --decode-check --ffmpeg '/usr/bin/ffmpeg'
```

## 快速开始

分别列出 Windows 和 Linux 命令，直接从解压目录运行，无需设置命令变量。路径包含空格时加引号，命令名、协议 ID 和枚举值按示例大小写使用。

### 检测、检查与验证

Windows（PowerShell）：

```powershell
.\LivePhoto\LivePhoto.exe detect --input '.\reference\livephoto.jpg'
.\LivePhoto\LivePhoto.exe inspect --input '.\reference\livephoto.jpg'
.\LivePhoto\LivePhoto.exe analyze --input '.\reference\livephoto.jpg'
.\LivePhoto\LivePhoto.exe validate --input '.\reference\livephoto.jpg' --layers Structure,Protocol
.\LivePhoto\LivePhoto.exe get-key --input '.\reference\livephoto.jpg'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto/bin/LivePhoto detect --input './reference/livephoto.jpg'
./LivePhoto/bin/LivePhoto inspect --input './reference/livephoto.jpg'
./LivePhoto/bin/LivePhoto analyze --input './reference/livephoto.jpg'
./LivePhoto/bin/LivePhoto validate --input './reference/livephoto.jpg' --layers Structure,Protocol
./LivePhoto/bin/LivePhoto get-key --input './reference/livephoto.jpg'
```

`detect` 按内容判断，可返回多个协议匹配；`inspect` 展示结构和 metadata，不等同于有效性结论。`validate` 默认检查 `Structure,Protocol,Media`，注意 `coverage`、各层 checks 以及 `NotRun` / `Partial`；退出码为 0 也不等于完整媒体或设备认证。

**参考成品不是“严格验证必定成功”的样本。** `reference/livephoto.jpg` 的 secondary item 声明了非规范 `Padding=0`，已有测试明确要求严格 Google V2 协议验证为 `Invalid`；它的媒体结构检查也可能报告问题。因此对它执行上述 analyze/validate 返回退出码 **4** 是实际、预期的报告，不应修改检查使其变绿。下节用普通 `video.jpg + video.mp4` 创建的新成品才是本项目的成功闭环示例。

### 普通图片 + 视频 → Google Motion Photo

Windows（PowerShell）：

```powershell
.\LivePhoto\LivePhoto.exe capabilities --target google.motionphoto.v2 --profile jpeg
.\LivePhoto\LivePhoto.exe create --image '.\reference\video.jpg' --video '.\reference\video.mp4' --target google.motionphoto.v2 --profile jpeg --output-dir '.\out-create'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto/bin/LivePhoto capabilities --target google.motionphoto.v2 --profile jpeg
./LivePhoto/bin/LivePhoto create --image './reference/video.jpg' --video './reference/video.mp4' --target google.motionphoto.v2 --profile jpeg --output-dir './out-create'
```

输入必须符合有限的 JPEG / 视频 profile；默认不转码。输出发布在 `out-create/assets/`，JSON 的 `result.output.assets[].path` 给出真实文件位置；**不要假设输出总叫 `livephoto.jpg`**。后续示例里的 `motion.jpg` 指你按 JSON 路径选取的结果或自己的输入文件。

### 原样提取、干净拆分、协议转换

Windows（PowerShell）：

```powershell
.\LivePhoto\LivePhoto.exe extract --input '.\motion.jpg' --output-dir '.\out-extract'
.\LivePhoto\LivePhoto.exe split --input '.\motion.jpg' --output-dir '.\out-split'
.\LivePhoto\LivePhoto.exe convert --input '.\motion.jpg' --target google.microvideo.v1 --profile jpeg --output-dir '.\out-convert'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto/bin/LivePhoto extract --input './motion.jpg' --output-dir './out-extract'
./LivePhoto/bin/LivePhoto split --input './motion.jpg' --output-dir './out-split'
./LivePhoto/bin/LivePhoto convert --input './motion.jpg' --target google.microvideo.v1 --profile jpeg --output-dir './out-convert'
```

`extract` 原样提取已确认资源，默认资源选择由 Core 决定；`split` 输出普通媒体并清除已确认的动态照片专属 metadata。两者不是同一个操作，也不一定产出相同图片字节。

### Apple 双资产与关键帧 metadata

Windows（PowerShell）：

```powershell
.\LivePhoto\LivePhoto.exe inspect --input '.\IMG.jpg' --pair-video '.\IMG.mov'
.\LivePhoto\LivePhoto.exe extract --input '.\IMG.jpg' --pair-video '.\IMG.mov' --output-dir '.\out-apple-raw'
.\LivePhoto\LivePhoto.exe set-key --input '.\IMG.jpg' --pair-video '.\IMG.mov' --frame-index 1 --output-dir '.\out-apple-key'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto/bin/LivePhoto inspect --input './IMG.jpg' --pair-video './IMG.mov'
./LivePhoto/bin/LivePhoto extract --input './IMG.jpg' --pair-video './IMG.mov' --output-dir './out-apple-raw'
./LivePhoto/bin/LivePhoto set-key --input './IMG.jpg' --pair-video './IMG.mov' --frame-index 1 --output-dir './out-apple-key'
```

`set-key` 修改协议意义的 key/presentation 位置，**不会重新生成封面图**。Apple 修改入口只接受已确认的有限结构，不能将任意相机原片视为可写。

普通媒体创建 Apple 默认使用有限 `jpeg-mov` profile，并满足 JPEG metadata 与原视频容器的分类门禁。输入必须是已分类的普通 JPEG＋MOV；不接受已有 Live Photo 或无法安全合并的 EXIF/MakerNote：

Windows（PowerShell）：

```powershell
.\LivePhoto\LivePhoto.exe create --image '.\plain.jpg' --video '.\plain.mov' --target apple.livephoto --output-dir '.\out-apple-create'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto/bin/LivePhoto create --image './plain.jpg' --video './plain.mov' --target apple.livephoto --output-dir './out-apple-create'
```

可显式指定 `--profile jpeg-mov`，效果相同。不需要媒体后端，也不会隐式把 MP4 改成 MOV；MP4 输入必须显式使用 `--profile jpeg-mp4`。默认 Create 为 `Experimental`，不代表任意相机 JPEG/MakerNote 的 Generic Create 已完成；默认 ConvertTo 仍为 `Planned`，转换须指定已实现的 profile，HEIC Create/ConvertTo 也未实现。

### 修复：先预览，再明确写入

Windows（PowerShell）：

```powershell
.\LivePhoto\LivePhoto.exe repair --input '.\damaged-motion.jpg'
.\LivePhoto\LivePhoto.exe repair --input '.\damaged-motion.jpg' --issues MOTION_VIDEO_LENGTH_MISMATCH --apply --output-dir '.\out-repair'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto/bin/LivePhoto repair --input './damaged-motion.jpg'
./LivePhoto/bin/LivePhoto repair --input './damaged-motion.jpg' --issues MOTION_VIDEO_LENGTH_MISMATCH --apply --output-dir './out-repair'
```

当前仅修复能够唯一证明的有限问题，不猜协议、视频边界、ID 或单位。默认模式为 `SafeMetadataOnly`；预览不写入，不能传 `--output-dir`。

有限 `ExplicitRemux`（Experimental）处理 **Samsung JPEG SEF mpv3，或 vivo 最小版本一 JPEG 中，独立有效的 MOV 视频应为 MP4** 的容器约束问题。vivo 仅接受 `VMotionPhotoVersion=1`、`VMotionPhotoSource=1`、`VMediaKitVersion=1.0.0.9` 和无辅助资源的 Primary/Motion 目录；未知 vendor 字段不授权重写。需要可用的 Remux 后端；可显式传 `--ffmpeg`，否则查 PATH，不自动安装。私有 streamcopy 验证全部 sample/configuration/时间线与已分类 metadata，再重建同协议的长度/索引，只发布最终一张照片；保留图片编码、原 key（包括未知值 `-1`）及既有 vendor 字段，不转码。未知/损坏媒体、Legacy SEF、普通 SEF 记录、辅助资源、EXIF/MPF/不明 ownership 等会拒绝，不能用来修复任意损坏文件。

Windows（PowerShell）：

```powershell
.\LivePhoto\LivePhoto.exe repair --input '.\samsung-motion.jpg' --mode ExplicitRemux --issues UNSUPPORTED_CONTAINER
.\LivePhoto\LivePhoto.exe repair --input '.\samsung-motion.jpg' --mode ExplicitRemux --issues UNSUPPORTED_CONTAINER --apply --strict --output-dir '.\out-remux-repair'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto/bin/LivePhoto repair --input './samsung-motion.jpg' --mode ExplicitRemux --issues UNSUPPORTED_CONTAINER
./LivePhoto/bin/LivePhoto repair --input './samsung-motion.jpg' --mode ExplicitRemux --issues UNSUPPORTED_CONTAINER --apply --strict --output-dir './out-remux-repair'
```

vivo 使用相同命令，换成自己的 vivo 输入路径即可。预览不执行媒体处理、不创建输出；CLI 的工具可用性探测可能运行只读版本检查。已是该有限 MP4 profile 时再次执行没有改动，也不需要媒体后端。Google 兼容基础层对 Samsung 整 SEF 后缀/纯视频长度，以及 vivo Motion `Padding=0` 的已有诊断仍保留；目标厂商方言与严格 Google 验证分开报告，不能据此宣称所有 profile 或设备通过。

Huawei 也使用相同命令，但仅限 **JPEG basic60、紧邻原图的独立 MOV、无 XMP/EXIF/MPF/未知 APP、无间隙或额外扩展**。完整 JPEG 和尾标前 40 字节原样复制，仅按验证后的 MP4 长度改写最后 20 字节的 `LIVE_` 字段；不会猜测历史字段单位或生成 key，`TIMESTAMP_SEMANTICS_UNKNOWN` 仍保留。HEIC、Honor 和未知扩展不在修复范围，默认 `SafeMetadataOnly` 不会执行重封装。

Apple 有限 `ExplicitRePair` 支持明确选定的 JPEG/HEIC＋MOV/MP4：先单独 `inspect` 你选为权威的图片或视频，将返回的 `result.pairing.evidence[0].id.value` 填入 `--authority`。下例 `EVIDENCE_ID` 必须替换为该文件当前版本的真实证据 ID；文件变化后重新读取，不得复用其它文件的 ID。权威来自视频时改图片 CID，来自图片时改视频 CID，不会自动选边或生成新 ID。

Windows（PowerShell）：

```powershell
.\LivePhoto\LivePhoto.exe inspect --input '.\primary.heic'
.\LivePhoto\LivePhoto.exe repair --input '.\primary.heic' --pair-video '.\motion.mov' --mode ExplicitRePair --authority 'EVIDENCE_ID'
.\LivePhoto\LivePhoto.exe repair --input '.\primary.heic' --pair-video '.\motion.mov' --mode ExplicitRePair --authority 'EVIDENCE_ID' --apply --output-dir '.\out-pair'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto/bin/LivePhoto inspect --input './primary.heic'
./LivePhoto/bin/LivePhoto repair --input './primary.heic' --pair-video './motion.mov' --mode ExplicitRePair --authority 'EVIDENCE_ID'
./LivePhoto/bin/LivePhoto repair --input './primary.heic' --pair-video './motion.mov' --mode ExplicitRePair --authority 'EVIDENCE_ID' --apply --output-dir './out-pair'
```

此功能为 Experimental：只接受已确认 ownership 的 CID-only MakerNote 和专用 movie metadata，改写一侧固定宽度 CID，整对共同发布。普通 MakerNote、混合私有 metadata 等未覆盖结构会拒绝；无法证明未知 ID 关联，因此保留报告明确 `MetadataPreserving=Unknown`，`--strict` 不可用于发生 CID 改写的请求。匹配成功不证明原始同次拍摄，也不证明相册兼容；默认不转码。

### 抽帧、裁剪、remux 与显式转码

Windows（PowerShell）：

```powershell
.\LivePhoto\LivePhoto.exe extract-frame --input '.\video.mp4' --frame-index 0 --format Jpeg --output-dir '.\out-frame'
.\LivePhoto\LivePhoto.exe trim --input '.\video.mp4' --start-us 0 --end-us 1000000 --mode LosslessPreferred --output-dir '.\out-trim'
.\LivePhoto\LivePhoto.exe remux --input '.\video.mp4' --container Mov --output-dir '.\out-remux'
.\LivePhoto\LivePhoto.exe transcode --input '.\video.mp4' --container Mp4 --codec Avc --allow-transcode --output-dir '.\out-transcode'
```

Linux（Bash/Zsh）：

```sh
./LivePhoto/bin/LivePhoto extract-frame --input './video.mp4' --frame-index 0 --format Jpeg --output-dir './out-frame'
./LivePhoto/bin/LivePhoto trim --input './video.mp4' --start-us 0 --end-us 1000000 --mode LosslessPreferred --output-dir './out-trim'
./LivePhoto/bin/LivePhoto remux --input './video.mp4' --container Mov --output-dir './out-remux'
./LivePhoto/bin/LivePhoto transcode --input './video.mp4' --container Mp4 --codec Avc --allow-transcode --output-dir './out-transcode'
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
| `--max-bytes N` | 所有命令可用；正整数，默认 `1073741824`（1 GiB），设置 Context 的缓存/materialize 与输出字节预算，不是总内存上限，也并非只限制输入文件大小 |
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
| repair 的 `--mode` / `--authority` | `SafeMetadataOnly`（默认）、有限 Samsung/vivo/Huawei JPEG `ExplicitRemux`、有限 Apple `ExplicitRePair`。冲突重配对要求当前选定源的正式 CID evidence ID；容器修复不接受此权威参数 |
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

当前仓库固定 Kotlin **2.4.20**、Gradle Wrapper **9.8.1**、Java Toolchain **25**。这些是当前配置值；Wrapper 更新 workflow 可提交版本更新，但编译时不会自动升级配置，不要为了 README 示例自行切换工具链。

需要已有的 **JDK 25**，`JAVA_HOME` 指向 JDK 根目录。项目禁用自动供应 Java，并通过 `JAVA_HOME` 发现 toolchain；无需系统 Gradle，始终使用 Wrapper。Wrapper 和首次依赖解析需要网络，允许下载仓库指定的 Gradle 与依赖，但不会安装 JDK。打包另需 JDK 的 `jpackage` / `jlink`，以及已有的 PowerShell 7；应用使用者不需要这些构建工具。

### Windows PowerShell

在项目根目录执行，将 `JAVA_HOME` 配置为已有 JDK 25 的实际安装目录。下面的 JDK 路径只是占位示例：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'

# 本项目通过 JAVA_HOME 选择已有 JDK，执行前检查冲突配置。
if (Get-ChildItem -Recurse -File -Filter gradle-daemon-jvm.properties) {
    throw '发现 gradle-daemon-jvm.properties；请先处理该配置，再继续。'
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

### 可选媒体集成测试

纯协议测试不依赖 FFmpeg；没有后端时相关媒体集成测试可以跳过。需要完整 FFmpeg 验收时，在上述构建前设置：

```powershell
$env:LIVEPHOTO_FFMPEG = 'C:\Program Files\FFmpeg\bin\ffmpeg.exe'
$env:LIVEPHOTO_REQUIRE_FFMPEG = 'true'
$env:LIVEPHOTO_REQUIRE_REFERENCE = 'true'
```

Windows 系统 API 专项验收还可设置 `LIVEPHOTO_REQUIRE_WINDOWS_MEDIA=true`，缺少要求的运行时能力会失败，而不是把未执行当作成功。这些环境变量用于测试，不是常规 CLI 参数；按验收需要设置，结束后移除本轮新增的变量。

reference 测试使用仓库中的 `reference/video.jpg`、`reference/video.mp4` 和 `reference/livephoto.jpg`，检查封装、提取及保留结果；不会要求参考成品中的 embedded MOV 必须与独立原 MP4 相同。用户报告可识别不等于本项目已完成所有设备验收。

协议兼容回归包含 **12 个单文件组合及 3 组配对，共 18 个资产**。`core/src/jvmTest/resources/protocol-compatibility/v1/` 保存无损字节差分、完整 SHA-256 和资产清单；测试还原并校验整文件，再检查多重匹配、非规范字段与原样视频提取。差分只引用参考视频或其它已校验资产中完全相同的字节，不重写协议字段。包含 **16 个回归测试**，无需网络或额外工具运行，不增加产品运行时依赖。

这些样本也用于暴露差异，不强行期待全为 Valid：Google V2 的 secondary `Padding=0`、Samsung 旧式 SEF footer 必须明确报告。Huawei HEIC + 固定尾标现可有界读取并原样提取视频，图像 item graph 与视频范围分别验证，仍为 Partial/Candidate，不授权 HEIC 写入；部分 MOV 仍有解析范围缺口。部分 Apple 样本将 MakerNote 放在 IFD0，本 Core 当前只授权正式 ExifIFD；vivo 旧式配对存在 sample/header duration 边界，需要继续核查。文件名中的 `H.265` 不是实际 codec 证明。样本不代表真实设备相册兼容。

### 打包 Windows 便携应用

完成 `:cli:installDist` 和 `:core:jvmTest` 后，在项目根目录执行：

```powershell
.\scripts\package-portable.ps1 -Platform windows-x64 -Destination '.\ci-output\portable-readme'
```

Destination 必须使用新路径，脚本不覆盖既有应用 image；会调用 `jpackage`、生成归档与 SHA-256，解压到带空格路径进行 smoke tests。它还消费 jvmTest 导出的合成 fixture，不能只构建 CLI 就跳过这些前提。成功归档是 `LivePhoto-windows-x64.zip`，最后输出 `PORTABLE_SUCCESS`；任何前面单项 SUCCESS 都不能代替完整打包结果。

Linux x64 使用同一脚本的 `-Platform linux-x64`，**必须在对应 OS/架构的构建机运行，不支持交叉 jpackage**；本项目已通过 GitHub Actions 执行该流程。macOS ARM64 仅预留脚本入口，尚无成功交付证明。建议复用现有 Gradle 缓存，保留数 GB 磁盘余量；image、解压验收目录与素材副本会明显增加空间使用。

## CI 与产物

[Build workflow](.github/workflows/build.yml) 在每次 push、pull request 和手动触发时运行 Windows x64 / Linux x64 构建、测试、便携打包及 smoke tests。CI 通过 `actions/setup-java` 准备 Zulu JDK 25；这是 workflow 的环境准备，与项目禁用 Gradle 自动供应 Java、应用运行时不安装 Java 的策略不同。Linux 直接执行仓库中带可执行位的 `gradlew`。

每个平台上传三类 artifacts，默认保留 **14 天**：

- `LivePhoto-...`：应用归档与 SHA-256；打包成功后上传，下载运行选这个。
- `build-outputs-...`：JAR 与 JVM 分发包。
- `build-reports-...`：测试报告、XML、合成 fixture、兼容回归素材包及哈希清单、Gradle 和便携日志；构建失败时也尝试上传已有报告。

两平台成功通常共 6 个 artifacts。CI 可能没有 FFmpeg，不能把它的协议/便携测试等同于配置完整媒体后端的集成验收，也不能把缺失工具后的 skip 当作该媒体操作成功。

## 进度、已完成与 TODO

### 最新可核验检查点

Apple 有限 ExplicitRePair 已交付：[`fceb13c`](https://github.com/ohyooo/LivePhoto/commit/fceb13c5c226e0861515795abaadcc2978930b02) 实际全量构建 **711 tests，0 failures / errors / skips**，包含真实编码 MOV 修复后完整解码；Windows 新便携包全部验收成功，新增空 PATH 两侧权威/两种容器的重配对 smoke。[对应 CI 37742117365](https://github.com/ohyooo/LivePhoto/actions/runs/37742117365) 成功，Windows/Linux 合计 6 个非空、未过期 artifacts 已核对。

随后 [`18f8122`](https://github.com/ohyooo/LivePhoto/commit/18f81223adc4933b69abc0570745d13ba9b030be) 补充独立合成 CID 字段边界回归：36/37 字节与单个 NUL 保持、额外终止符/混合 timed metadata/身份别名拒绝。该批次实际全量 **713 tests，0 failures / errors / skips**；只改测试及 fixture helper，不扩展业务能力。[对应 CI 37742738670](https://github.com/ohyooo/LivePhoto/actions/runs/37742738670) 成功，6 个非空、未过期 artifacts 已核对。

Windows 有限 MOV 系统 Probe 扩展：[`91cec67`](https://github.com/ohyooo/LivePhoto/commit/91cec677bb13f8df74c31e851e29a133a5f7954e) 实际全量 **714 tests，0 failures / errors / skips**，包含 B 帧/VFR、音轨拒绝，以及现有 FFmpeg 明确封装 MOV 后只用系统后端完整解码。人工 qt brand fixture 仅为合成容器结构＋真实 AVC samples，单独标明其范围，不能当作相机原片或通用 remux 证明。完整 Windows 便携验收与[对应 CI 37743888829](https://github.com/ohyooo/LivePhoto/actions/runs/37743888829) 成功，6 个非空、未过期 artifacts 已核对。

协议兼容素材批次此前全量验证 **730 tests，0 failures / errors / skips**，含 16 个回归测试；整文件哈希与素材保持不变。最新提交的 Windows/Linux 构建、测试和上传结果以 [Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml) 为准；解析缺口另列 TODO，不能把素材覆盖等同于所有协议兼容完成。

Huawei HEIC 有限读取批次：全量 **740 tests，0 failures / errors / skips**，包含两个兼容素材的精确视频提取，以及有界图像、越界/开放长度/未知扩展/源变化拒绝测试。完整 Windows 便携验收成功，包含空 PATH、误导扩展名、视频整文件 SHA 一致和 Clean 拒绝用例。媒体语义仍 Partial，key 时间单位仍未知；不开放该 profile 的 HEIC Create、Clean、Repair 或跨格式 Convert。

有限 Samsung ExplicitRemux 批次：[`a804b3d`](https://github.com/ohyooo/LivePhoto/commit/a804b3d79eb758487abead37a5383dda73a7f67e) 全量 **749 tests，0 failures / errors / skips**，包含独立 SEF 合成测试、真实 AVC MOV 修复后完整解码、预览零写入/allowlist/幂等、后端样本/配置/时间线/metadata 改动及声称转码拒绝、最终 key 篡改/写入失败/取消/源变化回滚，以及 CLI 按模式发现后端/显式路径转发回归。新 Windows 便携包全部验收成功，[对应 CI 37758770263](https://github.com/ohyooo/LivePhoto/actions/runs/37758770263) 两平台成功，6 个非空、未过期 artifacts 已核对；不证明设备兼容。

有限 vivo ExplicitRemux 扩展批次：全量 **751 tests，0 failures / errors / skips**，包含版本一原字段、已知/未知 key、VFR samples 保留及复杂 profile/GainMap/未知 vendor/错误时长拒绝；真实 AVC Samsung/vivo 双协议修复后提取、逐 sample 核对和完整解码均成功。新完整 Windows 便携验收成功，包含两协议显式路径修复、key 保持、空 PATH 幂等与无后端拒绝。提交后的 CI/6 artifacts 以 [Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml) 对应提交为准，不借用 Samsung 上一批产物；复杂 vivo 与真机验收仍未完成。

有限 Huawei basic60 ExplicitRemux 扩展批次：[`d91d23e`](https://github.com/ohyooo/LivePhoto/commit/d91d23e6c7ad5f4af2ca4a5657cec6db556fc9d2)，Gradle 9.8.1 下全量 **755 tests，0 failures / errors / skips**；真实 AVC 三协议修复、原始 sample 验证与完整解码通过。新增完整 JPEG/普通注释/尾标原始字节、未知 key 单位保持，扩展拒绝、尾标篡改、写失败、取消与预算不足不发布的回归。新完整 Windows 便携包验收通过；[对应 CI 37804198168](https://github.com/ohyooo/LivePhoto/actions/runs/37804198168) 两平台成功，6 个非空、未过期 artifacts 已核对；不代表设备兼容。

Apple 有限默认 Create 入口：[`2f6554a`](https://github.com/ohyooo/LivePhoto/commit/2f6554a61122265ba1d5beebd59aee0727d4e0ea)，新全量 **757 tests，0 failures / errors / skips**。省略 profile 只接入普通 JPEG＋MOV 的已有 assembler，计划解析为 `jpeg-mov`；保持样本、配置、时间线及源文件不变，不执行 remux/转码。包含 AVC/HEVC 合成正负例、真实 AVC MOV 独立样本验证与完整解码。新完整 Windows 便携验收成功，新增空 PATH 创建、key 保持、MP4 拒绝且不发布输出的 smoke；[对应 CI 37806758463](https://github.com/ohyooo/LivePhoto/actions/runs/37806758463) 两平台成功，6 个非空、未过期 artifacts 已核对。

Windows 系统解码缓冲区读取：[`686fa4c`](https://github.com/ohyooo/LivePhoto/commit/686fa4cf01f77d5db2520ed2bf078e3017dd9b55)，新全量 **758 tests，0 failures / errors / skips**。隔离 worker 实际锁定并读取有效帧缓冲区，验证字节预算、返回原始缓冲区证据；包含 B 帧与小预算拒绝回归。该证据不解释像素 stride/色彩、不输出图片，系统 `ExtractFrame` 仍为 `Unsupported`。新完整 Windows 便携验收成功，验证内置 worker 新模式与原有 Core 系统回退；[对应 CI 37808199497](https://github.com/ohyooo/LivePhoto/actions/runs/37808199497) 两平台成功，6 个非空、未过期 artifacts 已核对。

截至 **2026-10-08**，代码检查点 [`30454b9`](https://github.com/ohyooo/LivePhoto/commit/30454b9b32678999bae284a812875e2cef3f5e12)：

- 完整验收 **706 tests，0 failures / errors / skips**；结果对应上述提交，不借用旧报告。
- 完整 Windows 便携验收成功，包括已有 FFmpeg 集成与无 FFmpeg 的合成协议验收；这仍不是设备认证。
- [对应 CI 37713982144](https://github.com/ohyooo/LivePhoto/actions/runs/37713982144) 成功；Windows/Linux 合计 6 个非空、未过期 artifacts 已核对。

这是特定提交的证据，不是对以后每个提交的永久保证。项目**尚未完成所有 phases**；任务范围和未覆盖 profile 持续扩展，暂不提供缺乏固定分母的总体百分比。

本 README 的基础命令另于 2026-10-08 使用已验证的便携程序试跑：help/version、读取、reference 预期 Invalid、普通媒体 Create 后协议验证、字节精确视频 Extract、Clean Split、转换均通过。这是文档示例验收，不是重新运行 706 项单元测试，也不表示本文所有条件性示例或 TODO 已实现。

### 阶段状态一览

状态按**工作包**记录，不按代码量或测试数量推算完成百分比。“已完成”只针对注明的范围；“部分完成”表示已有可用路径，但还存在本节 TODO。有限 ExplicitRePair 已交付，正继续 conformance 与后续能力工作包。

| 阶段 / 工作包 | 当前状态 | 已完成 | 仍未完成 |
| --- | --- | --- | --- |
| Phase 1：工程与公共 Core 契约 | 已完成当前范围 | KMP/JVM 工程、统一模型/API、IO/后端契约 | 新平台接入属后续范围，不等于已交付 Native |
| Phase 2：解析与安全基础 | 已完成基础批次；复杂格式部分完成 | JPEG/XMP/EXIF/BMFF、范围/预算/身份检查、有限 HEIF 图 | 通用 HEIF/AVIF 与复杂 metadata 关联 |
| Phase 3：Google 主流程 | 部分完成，有限闭环可用 | JPEG V1/V2 与有限 HEIC 的读取、创建、提取、拆分、转换、key/修复 | 未支持的资源图、HDR/GainMap 等变体 |
| 厂商协议与 Legacy | 部分完成 | 已声明的 Oplus/Samsung/vivo/Huawei/Legacy 子集 | 未确认的尾挂、Honor 写入、复杂厂商变体及设备证明 |
| Apple Pair / Create / Convert | 部分完成 | 有限 JPEG 两资产写入，默认 Create 限 JPEG＋MOV；HEIC 读取、原样输出、SetKey/Clean；CID inspection evidence；有限显式重配对实现 | 复杂 Generic/HEIC 写入、更多原片与跨协议路径 |
| Repair | 部分完成；有限容器修复通过构建/便携验收 | 有限 SafeMetadataOnly；ExplicitRePair 已交付；Samsung/最小版本一 vivo/Huawei basic60 JPEG ExplicitRemux、全量测试、真实解码及新便携验收 | 其它容器修复与复杂 MakerNote/metadata profile；各提交 CI 结果见 Actions |
| Cover / Key Photo | 部分完成 | key metadata 与抽帧/封面重建分离，已有有限编辑路径 | 更复杂图像编码、orientation/ICC/HDR/metadata 保留 |
| MediaBackend | 部分完成；有限 MOV Probe 已交付 | 可选 FFmpeg 有限操作；Windows 系统有限 MP4/MOV Probe；完整解码、便携包与 CI 验收 | 系统媒体编辑、其它 OS 官方 API 适配 |
| CLI / Windows-Linux 便携 CI | 已完成当前有限入口批次 | 薄 CLI、JSON、退出码、两平台打包与 smoke/artifacts | macOS ARM64 打包暂缓；新 Core 能力仍需逐项接入 CLI |
| Conformance / fixtures | 持续补齐 | 已有 L0–L3 范围内的合成/真实样本/往返/保真证据 | 尚未覆盖全部变体；L4 真机验收暂缓 |

每个实施工作包完成后，应同时更新本表、下面的 TODO 和对应提交/验收检查点。只有完成实际构建、测试与 CLI 验收并核对相应 CI/产物，才把该批次标记为完成；测试数增加本身不代表某个阶段全部完成。

### 已完成的实施批次

- [x] Phase 1：KMP/JVM 工程、Domain/Core API、错误/能力/保留模型、随机访问 IO 和媒体后端契约。
- [x] Phase 2 基础批次：JPEG、XMP/XML、最小 EXIF/TIFF、ISO-BMFF、范围/预算/输入身份与保留门禁；有限 HEIF item graph 基础。
- [x] Google JPEG V1/V2 主流程，以及有限 Google HEIC 创建/读取/提取/Clean/同协议转换/key/长度修复批次。
- [x] OPPO/OnePlus、Samsung、vivo、Huawei/Honor、Legacy/Fusion 的已声明有限协议批次；不能据此扩展为全厂商兼容。
- [x] Apple 有限 JPEG/MOV、JPEG/MP4 双资产 Create/ConvertTo/读取/提取/清理/key；有限 HEIC Pair 读取、SetKey、Clean 与幂等拆分。
- [x] 唯一证据的有限 metadata Repair、只读预览/allowlist/幂等/失败回滚；Apple 源绑定 CID inspection evidence 与有限 ExplicitRePair。
- [x] key metadata 与抽帧/封面重建分离；FFmpeg 有限 probe、抽帧、裁剪、remux、显式 transcode 及 Core 集成。
- [x] Windows 有限系统 API 完整解码 Probe 回退与隔离 helper；不能当作系统媒体编辑已完成。
- [x] 薄 CLI、JSON/退出码、两资产原子发布、源变化/预算/取消/篡改/第二资产失败回归。
- [x] Windows/Linux 便携 CI、reference 测试、合成 parser/writer/round-trip/byte-exact/metadata/malformed 测试与有限真实媒体解码验收。

### 后续顺序与验收目标

以下是当前推进顺序，不是已实施状态；遇到真实依赖可调整小范围顺序，不能因此跳过验证或把暂缓项计为完成。

| 顺序 | 工作包 | 状态 / 前提 | 交付时应看到的结果 |
| --- | --- | --- | --- |
| 已完成批次 | Apple 有限 ExplicitRePair | **已完成有限范围交付**；复用既有 Request 与新鲜 CID 证据 | 用户指定两个源及权威，冲突 ID 不自动选；独立验证后原子发布 |
| 2 | Repair 其它显式模式及 Apple 写入扩展 | Samsung/vivo/Huawei basic60 JPEG 有限容器修复已通过；剩余路径先确认 ownership/依赖，重大规范/API 冲突需报告 | 每个 mode/profile 独立标能力；不能凭其它入口已可用推断支持 |
| 3 | 系统 MediaBackend 扩展 | 未实现系统编辑；逐个平台检查官方 API 与运行时可用性 | 能力查询真实，抽帧/裁剪/remux/transcode 分别验证；无实现则禁用 |
| 4 | 复杂 HEIF/AVIF / metadata / 媒体 profile | 部分基础已有，其余持续扩展 | 图像/音轨/时间线/metadata 各自证明，无法保证时拒绝或明确 Partial |
| 持续 | 每批新增 Core 能力的 CLI 与 conformance | 随上述工作包推进，不集中到最后才测 | 合成正负例、真实媒体、保留、原子性与便携 CLI 同步回归 |
| 条件恢复后 | macOS ARM64 便携包 | **暂缓：runner 环境前置问题** | ARM64 主机/已有 JDK、构建、归档解压与 CLI smoke 全部实际通过 |
| 补齐素材后 | L4 真机兼容 | **暂缓：待真实原片与设备证据** | 按厂商、设备/OS/相册、导入方式记录动态播放、声音、key 等证据 |
| 当前范围之外 | Compose UI、Android/iOS/Native 入口 | 未来规划，不计入本轮 Core + CLI 交付 | 复用统一 Core/Application API，不提前引入 GUI 依赖 |

### 已完成工作包：有限 ExplicitRePair

- [x] 核对有限 JPEG/HEIC 图片 CID 与 MOV/MP4 CID 的真实字段编码、位置、宽度和 ownership；未知结构不猜测。
- [x] 要求显式选定图片和视频，并由本次输入的新鲜 evidence 指定权威；缺失、过期、冲突或未知权威必须拒绝。
- [x] 补齐 dryRun 预览及允许问题码过滤；预览零写入，不自动生成 ID 或择一覆盖。
- [x] 实现有限 CID 改写与双资产共同原子发布，保留原图编码、视频 sample/configuration/时间线及未授权字段。
- [x] 独立检查输出配对与协议、保留报告；覆盖第二资产失败、源变化、预算/取消、篡改回滚、再次执行无变化；真实编码 MOV 修复后全解码。
- [x] 实际构建/测试和 CLI 验收；提交推送、核对对应 CI 与 6 个 artifacts。

### 剩余范围清单

- [x] 接入 12 个单文件组合及 3 组配对的兼容回归测试，校验 18 个资产的整文件 SHA-256；不算 L4 设备认证。
- [x] Huawei HEIC + 固定 60 字节尾标的有界读取与精确视频提取；保护图像 item extent 边界，未知 Honor/扩展不取得纯视频权威，HEIC 写入继续拒绝。
- [x] 有限 Samsung ExplicitRemux：全量/真实媒体测试、新便携包验收通过；其它协议/未知媒体恢复不在此范围，各提交 CI 见 Actions。
- [x] 最小版本一 vivo ExplicitRemux：全量/双协议真实媒体与新便携验收通过；保持原 vendor 字段、未知 key 和既有 Google 基础层诊断。
- [x] Huawei basic60 JPEG 有限 ExplicitRemux：全量/三协议真实媒体与新便携验收通过，完整原图与前 40 字节尾标保持；未知时间单位仍报告，HEIC/Honor/扩展不授权修复。
- [ ] 继续核查兼容读取边界：MOV/vivo 的 `mdhd` 与 `stts` 差异须结合 CTS/edit 与官方语义核对；Apple IFD0 MakerNote 的有限兼容读取需独立确认，不猜容差，不自动授权旧结构写入。
- [ ] 扩展其它显式 Repair 与 Apple 写入：有限 ExplicitRePair、Samsung/最小版本一 vivo/Huawei basic60 JPEG ExplicitRemux 已有实现；其它 profile、更复杂 MakerNote/private metadata 尚未实现，不能借用独立 remux 能力。
- [x] Apple 有限默认 JPEG＋MOV Create：757 项全量测试、新完整 Windows 便携包和对应两平台 CI 与 6 个 artifacts 已验收；不开放 MP4 自动转换或复杂 metadata 写入。
- [ ] Apple 复杂 Generic Create、HEIC Create/ConvertTo、HEIC 跨协议转换/Normalize、更多真实相机 MakerNote 和复杂媒体 profile；已有有限默认路径不代表这些完成。
- [ ] 扩展复杂 HEIF/AVIF、HDR/GainMap、未知 metadata 关联、辅助资源/混合轨道等；无法证明安全时继续 Unsupported/Partial，不先删 metadata 再宣称无损。
- [x] Windows 真实解码缓冲区读取前置：758 项全量测试、新完整 Windows 便携包、对应两平台 CI 与 6 个 artifacts 已验收；不是已完成抽帧。
- [ ] Windows 系统抽帧下一工作包：证明 NV12 像素布局与 SDR 色彩、按呈现顺序选定实际帧并核对 PTS，再生成 JPEG、独立验证图片和双重源不变检查后原子发布；未知 HDR/布局继续拒绝。
- [ ] Windows 系统抽帧/裁剪/remux/transcode；macOS/其它平台官方 API 后端。没有合格后端时继续禁用相关操作，不自动安装工具。
- [x] Windows 有限 MOV 系统 Probe 扩展：实际测试、Windows 便携包与对应 CI 产物验收通过；不因此声称音频/HDR 或通用 MOV 支持。[微软格式表](https://learn.microsoft.com/en-us/windows/win32/medfound/supported-media-formats-in-media-foundation)列出 `.mov`，但本项目仍逐项限制并验证 decoder/profile。
- [ ] 恢复 macOS ARM64 runner 前置条件与真实打包验收；不以 Intel Mac 或 Windows/Linux ARM 替代。
- [ ] **L4 真机兼容验收（暂缓，待真实原片与设备证据）。** 后续补充未编辑厂商原片、设备型号、系统/相册版本、导入后的动态播放/声音/key 表现；不能由合成测试或自生成 round-trip 代替。
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
- `scripts/`：验收、便携打包和 Windows 隔离 helper 配置。
- `reference/`：三个测试媒体，供协议与往返测试使用。
- `upstream/`：只读参考，不是项目依赖，不包含在发行包中；不复制其业务架构。

README 的信息组织参考 [Gradle](https://github.com/gradle/gradle/blob/master/README.md) 的入门/构建导航与 [scrcpy](https://github.com/Genymobile/scrcpy/blob/master/README.md) 的前提/下载/用法组织；命令、参数、平台状态和能力边界来自本仓库实际源码与验收记录。
