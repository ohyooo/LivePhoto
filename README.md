# LivePhoto

[![Build](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml/badge.svg)](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)

使用 Kotlin Multiplatform 实现的 Live Photo / Motion Photo Core 与命令行工具。可检测、检查、验证、创建、转换、提取和拆分支持范围内的动态照片，并提供有限的修复、关键帧位置修改及媒体处理能力。

**当前为开发中的实验性版本，不是全厂商、全格式兼容工具。** Core 与 CLI、文件路径及具体媒体库解耦；提供 JVM 与三平台 Kotlin/Native 库和 CLI，Native 有限 FFmpeg 媒体后端已通过三平台验收，JVM 版保留用于迁移期回归。尚未交付 Android、iOS 或 GUI。能力以运行时返回的 `implementation`、`conditions`、`coverage` 和保留报告为准，不能把 `Experimental`、`Planned` 或 `Partial` 理解为全面支持。

**当前进度：有限 Core + CLI 闭环已可测试，Windows/Linux/macOS ARM64 的 JVM 便携包已交付，macOS/Linux 真实 FFmpeg 验收已补齐。三平台独立 Native 程序的原生进程、有限 FFmpeg 后端及归档程序验收也已完成；不是将 JVM 包改名，运行不需要 Java。当前继续 Native 迁移期 JVM 清理与回归对齐，其它业务 TODO 暂不实施；Apple 新功能暂停，已有能力和测试保留。桌面系统 API 历史代码保留停用，移动端及其系统媒体 API 排在最后。** 具体阶段状态、后续顺序与验收条件见[进度与 TODO](#进度已完成与-todo)。

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

Actions 的 artifact 是外层下载包；解压后再展开里面的 `.zip` / `.tar.gz`，得到单文件程序。保留同包的 `native-cli-evidence.json`，其中 `archiveSha256` 校验**内层应用归档**，不是校验外层 artifact；程序本身也有独立哈希。

| 平台 | 应用归档 | 解压后的入口 | 当前交付状态 |
| --- | --- | --- | --- |
| Windows x64 | `LivePhoto-windows-x64.zip` | `LivePhoto.exe` | Kotlin/Native 原生程序 |
| Linux x64 | `LivePhoto-linux-x64.tar.gz` | `LivePhoto.kexe` | Kotlin/Native 原生程序 |
| macOS ARM64 | `LivePhoto-macos-arm64.tar.gz` | `LivePhoto.kexe` | Kotlin/Native 原生程序 |

不提供 Intel macOS、Windows ARM 或 Linux ARM 包。默认下载为 **Kotlin/Native 单文件程序，不携带 JVM/JRE，运行不需要 Java**。外部 FFmpeg 不在应用包内。暂不提供 MSI/DMG 安装器。历史 JVM 便携包移到 [JVM Regression](https://github.com/ohyooo/LivePhoto/actions/workflows/jvm-regression.yml)，以 `LivePhoto-jvm-...` 区分，仅供迁移期回归和对照；其内层仍为完整 jpackage 应用目录，不能只复制启动器。

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
- 先前 Windows 系统 API 适配器及历史测试代码保留，但不进入默认桌面后端发现，也不在常规便携验收中初始化。历史系统解码/抽帧/remux/裁剪验收不代表当前桌面入口仍开放这些回退。后续系统媒体 API 接入仅面向 Android/iOS，移动端排在剩余桌面与协议任务之后。
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

当前仓库固定 Kotlin **2.4.21**、Gradle Wrapper **9.8.1**、Java Toolchain **25**。这些是当前配置值；Wrapper 更新 workflow 可提交版本更新，但编译时不会自动升级配置，不要为了 README 示例自行切换工具链。

需要已有的 **JDK 25**，`JAVA_HOME` 指向 JDK 根目录。项目禁用自动供应 Java，并通过 `JAVA_HOME` 发现 toolchain；无需系统 Gradle，始终使用 Wrapper。Wrapper 和首次依赖解析需要网络，允许下载仓库指定的 Gradle 与依赖，但不会安装 JDK。打包另需 JDK 的 `jpackage` / `jlink`，以及已有的 PowerShell 7；应用使用者不需要这些构建工具。

### Windows PowerShell

在项目根目录执行，将 `JAVA_HOME` 配置为已有 JDK 25 的实际安装目录。下面的 JDK 路径只是占位示例：

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-25'

# 本项目通过 JAVA_HOME 选择已有 JDK，执行前检查冲突配置。
if (Get-ChildItem -Recurse -File -Filter gradle-daemon-jvm.properties) {
    throw '发现 gradle-daemon-jvm.properties；请先处理该配置，再继续。'
}

.\scripts\prepare-file-io-fixtures.ps1
.\gradlew.bat build :core:jvmTest :cli:installDist --console=plain --warning-mode=all
```

完整重新执行验收时追加 `--rerun-tasks`。不要把缓存命中、旧 XML 或 `NO-SOURCE` 当作新代码已测试。

全量 `build` 也包含文件适配测试，需先用 PowerShell 7 运行上述链接素材准备脚本，并具备创建符号链接的权限。Linux/macOS 可用 `pwsh -File ./scripts/prepare-file-io-fixtures.ps1`；缺少真实链接会使测试失败，不会静默跳过。重复准备已知素材可复用，遇到未知既有文件则拒绝覆盖。

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

历史 Windows 系统 API 专项测试代码保留，但常规验证不设置 `LIVEPHOTO_REQUIRE_WINDOWS_MEDIA=true`，相关真实系统调用测试会明确跳过；它不是启用桌面 CLI 回退的开关。FFmpeg 与纯协议测试继续运行，跳过的系统专项测试不计为本轮执行成功。

reference 测试使用仓库中的 `reference/video.jpg`、`reference/video.mp4` 和 `reference/livephoto.jpg`，检查封装、提取及保留结果；不会要求参考成品中的 embedded MOV 必须与独立原 MP4 相同。用户报告可识别不等于本项目已完成所有设备验收。

协议兼容回归包含 **12 个单文件组合、1 个更新的 vivo 单文件样本及 3 组配对，共 19 个资产**。`core/src/jvmTest/resources/protocol-compatibility/v1/` 保存无损字节差分、完整 SHA-256 和资产清单；20 项回归已迁到 `commonTest`，由 JVM 与三平台 Native 执行相同断言。测试还原并校验整文件，再检查多重匹配、非规范字段与原样视频提取。差分只引用参考视频或其它已校验资产中完全相同的字节，不重写协议字段。Native 测试前执行 `scripts/prepare-protocol-fixtures.ps1`，仅用构建环境解压原有 gzip；Core 主库不增加文件 IO、gzip、Java 或第三方依赖。CI 要求全部素材回归实际执行并上传已核验资产清单；不是设备认证。

`user-photos.tsv` 对应用户补充的 11 个导出成品：10 个与原固定资产字节一致，更新的 vivo 文件独立保存，旧样本不删除。OPPO Create 额外使用原封面＋原视频，与固定成品对照协议 RDF、EXIF UserComment 的类型/长度/字节、XMP 第一 APP 段位置，并逐块检查视频和原图保留；不复制生成工具署名或伪造相机参数。输入没有 XMP 时使用已核对的 OPPO 模板；已有普通 XMP/EXIF 仍走保留门禁，不宣称所有 metadata 布局均与对照工具相同。

设备反馈：此前 4 张本项目输出经 SMB 传至 **OnePlus 11 / ColorOS 16，均未识别**；用户原有 `livephoto.jpg` 可以识别，但它实际是 Google V2＋MOV，不是 OPPO 专属模板。新的协议布局修正必须重新做设备验收，不能由 Core 自身校验通过推定问题已解决。文件名中的 `h.264` 也不是编码证明：本批原视频实际为 HEVC＋AAC，两个 Huawei HEIC 配置文件字节完全相同。

本轮 OPPO 布局修正与用户素材回归已完成新全量验证：**845 tests，824 项执行成功，0 failures / errors，21 项历史系统 API 专项跳过**。只调整已确认 JPEG＋MP4 profile 的新 XMP/EXIF 序列化，不新增格式、转码或 OnePlus 尾挂支持；真机复测仍待用户反馈。

这些样本也用于暴露差异，不强行期待全为 Valid：Google V2 的 secondary `Padding=0`、Samsung 旧式 SEF footer 必须明确报告。Huawei HEIC + 固定尾标现可有界读取并原样提取视频，图像 item graph 与视频范围分别验证，仍为 Partial/Candidate，不授权 HEIC 写入；部分 MOV 仍有解析范围缺口。部分 Apple 样本将 MakerNote 放在 IFD0，本 Core 当前只授权正式 ExifIFD；vivo 旧式配对存在 sample/header duration 边界，需要继续核查。文件名中的 `H.265` 不是实际 codec 证明。样本不代表真实设备相册兼容。

### 打包 Windows 便携应用

完成 `:cli:installDist` 和 `:core:jvmTest` 后，在项目根目录执行：

```powershell
.\scripts\package-portable.ps1 -Platform windows-x64 -Destination '.\ci-output\portable-readme'
```

Destination 必须使用新路径，脚本不覆盖既有应用 image；会调用 `jpackage`、生成归档与 SHA-256，解压到带空格路径进行 smoke tests。它还消费 jvmTest 导出的合成 fixture，不能只构建 CLI 就跳过这些前提。成功归档是 `LivePhoto-windows-x64.zip`，最后输出 `PORTABLE_SUCCESS`；任何前面单项 SUCCESS 都不能代替完整打包结果。

Linux x64 / macOS ARM64 分别使用同一脚本的 `-Platform linux-x64` / `-Platform macos-arm64`，**必须在对应 OS/架构的构建机运行，不支持交叉 jpackage**；均已通过 GitHub Actions 执行该流程。macOS bundle 打包版本为 `1.0.0`，用于满足 jpackage 的首段非零要求；产品/CLI/API 仍为开发版 `0.1.0`，不是稳定版声明。建议复用现有 Gradle 缓存，保留数 GB 磁盘余量；image、解压验收目录与素材副本会明显增加空间使用。

### Kotlin/Native Core 库

三平台 Core 复用原有 `commonMain`，不复制协议实现；每个平台都实际执行全部 **726 项共享测试，零失败、零跳过**。现有 JVM 入口保留。Native Core 是 Kotlin/Native 可引用的库，不是原生 CLI、C ABI、DLL 或 iOS framework。

在对应宿主平台显式选择 target：

```sh
# Linux x64
./gradlew -Plivephoto.nativeTarget=linux-x64 :core:compileKotlinLinuxX64 :core:linuxX64Test --rerun

# macOS ARM64
./gradlew -Plivephoto.nativeTarget=macos-arm64 :core:compileKotlinMacosArm64 :core:macosArm64Test --rerun
```

```powershell
# Windows x64
.\gradlew.bat -Plivephoto.nativeTarget=windows-x64 :core:compileKotlinMingwX64 :core:mingwX64Test --rerun
```

构建仍使用现有 JDK 25 和项目 Wrapper；显式 Native 构建会由 Kotlin 插件准备编译器及平台依赖，但库产物不包含 JVM/JRE。普通 JVM 构建不启用这些 targets，也不因此下载 Native 工具链。保留 Gradle 9.8.1，三平台已实测；KGP 的内部 Gradle 10 deprecation 警告仍保留，不通过隐藏警告宣称未来兼容。

编译后的未打包主库位于 `core/build/classes/kotlin/<target>/main/`。[Native Core CI](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml) 将已核对 `NATIVE` 平台标识的主库打包为 `core.klib`，上传 `LivePhoto-native-core-<平台>-<run_id>-<attempt>`，另附编译器版本、目标、库 SHA-256 和新执行测试数；报告 artifact 为 `native-core-reports-<平台>-<run_id>-<attempt>`。

Native 库可使用 `MemoryBinarySource` / `MemoryOutputTransaction`、下述文件适配库或调用方注入的 IO/事务。原生 CLI 另见下节；可选 FFmpeg 适配通过注入的文件/进程接口接入，协议解析不依赖 FFmpeg 或平台路径。下载 KLIB 不会使现有 JVM 便携包变成单文件原生程序。

### Native 共享 CLI 层

`cli-shared` 的 `commonMain` 负责参数解析、Request 构造、Core 调用、JSON 与日志格式化；现有 JVM CLI 已使用这一份实现。文件来源、输出事务、后端发现、环境、计时和异常帧由 `CliPlatform` 注入，不把平台 API 放进协议 Core。

生产 JSON 已移除 Java 反射，显式映射结果 DTO，保留原命令、退出码、64 位十进制字符串及句柄/token 脱敏规则。三平台各实际执行 **34 项共享 CLI 测试，零失败、零跳过**，包括现有命令转发、嵌套结果图、日志隔离和失败后的资源清理；这些测试使用内存 IO，不是原生文件/FFmpeg 或完整原生程序的验收。

CLI 输入适配现接收本次操作的 `Context`，使所有输入共享 `--max-bytes` 预算；命令结束先关闭借用来源、处理待提交事务，再调用平台会话清理。任一关闭失败仍继续清理其它资源；清理失败返回错误和退出码 3，不输出成功结果。Native FFmpeg 的输入副本与临时输出也扣除尚未释放的输入快照占用，不能另取一份完整预算。最新执行结果见 [Native CI](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)。

```sh
./gradlew -Plivephoto.nativeTarget=linux-x64 :cli-shared:linuxX64Test --rerun
./gradlew -Plivephoto.nativeTarget=macos-arm64 :cli-shared:macosArm64Test --rerun
```

```powershell
.\gradlew.bat -Plivephoto.nativeTarget=windows-x64 :cli-shared:mingwX64Test --rerun
```

[共享 CLI 首批验证](https://github.com/ohyooo/LivePhoto/actions/runs/38217562263) 每平台分别检查 Core 的 726 项和 CLI 的 34 项新执行测试，随现有库/报告 artifacts 附加 `shared-cli-evidence.json`。现有 JVM 便携包仍包含 Java 运行时；原生入口另行打包，下一阶段接原生进程及 FFmpeg 适配。

### Native 文件 IO 与整组输出

`file-io` 使用 Okio 3.18.2 提供 JVM / Linux x64 / macOS ARM64 / Windows x64 文件适配，Core 仍不依赖具体路径或 Okio：

- `FileInputSession.capture()` 将输入保存为有总字节、来源数量和校验证明预算限制的独立副本。两次读取核对原内容，再校验副本；后续读取验证固定 64 KiB 块的 SHA-256，不靠 mtime，也不因每次小读取查询身份而重复扫描整段视频。输入原文件不会被写入。
- `FileOutputTransaction` 只接受新目录，暂存并冻结所有资产，Core 回读验证后用一次同目录原子移动发布到 `assets/`。不覆盖已有文件/目录，不使用跨卷复制降级；提交响应丢失时通过 `query()` 核对整组输出，禁止盲目重试或删除可能已提交的资产。
- 只支持本地文件系统及调用方控制的可信父目录；调用方负责工作目录隐私和串行调用。拒绝符号链接及 Windows reparse points，不声称支持网络共享、恶意同账户并发修改、扁平 URI 输出或断电后的目录持久性。清理只删除记录的自有文件和空目录，遇到未知内容保留并报错。

[文件适配验证](https://github.com/ohyooo/LivePhoto/actions/runs/38263179069) 三平台各 **33 项测试，零失败、零跳过**，包含真实文件/Unicode 路径、真实系统链接、预算/溢出、短读/零进度、失败清理、两资产发布及不确定提交恢复；使用 `reference/video.jpg + video.mp4` 实际 Create → Inspect/Validate → 字节精确 Extract，原素材未改变。不是 FFmpeg 媒体操作或真机认证。Windows 首轮一项用例遇到 IO 失败，同提交重跑通过，失败记录保留，未放宽断言。

文件测试还需要真实链接素材；在有 PowerShell 及创建符号链接权限的环境先运行 `pwsh -File ./scripts/prepare-file-io-fixtures.ps1`，再执行对应宿主命令：

```sh
./gradlew -Plivephoto.nativeTarget=linux-x64 :file-io:linuxX64Test --rerun
./gradlew -Plivephoto.nativeTarget=macos-arm64 :file-io:macosArm64Test --rerun
```

```powershell
.\gradlew.bat -Plivephoto.nativeTarget=windows-x64 :file-io:mingwX64Test --rerun
```

Native 库 artifact 现在另附 `file-io.klib` 和 `file-io-evidence.json`（目标、依赖、SHA-256、字节数、执行测试数）。文件库依赖 Core、Okio，Native FFmpeg 适配另依赖原生 process 和协程；不是单独可运行的应用。协议 Core 的 KLIB 仍只依赖 stdlib。

### Native CLI（实验性交付）

`cli-native` 是 Kotlin/Native 原生程序，不携带 JVM/JRE，不是 jpackage 启动器。纯协议操作及有限媒体操作使用相同命令、JSON、退出码和 profile 限制。媒体后端查找顺序为 `--ffmpeg` 显式路径 → 绝对 PATH 目录 → 禁用；不调用 shell、不安装工具、不启用桌面系统媒体 API。FFmpeg 必须由用户预先安装，含当前有限 profile 所需的解码器、mjpeg 与 libx264；音频仅 copy。没有后端时仍可进行纯协议操作。

Native 与 JVM 共用同一套 FFmpeg 参数、媒体策略和结果验证，不扩大格式支持。抽帧后的 JPEG 由 Core 检查结构/尺寸，再单独调用 FFmpeg 完整解码；不使用 Java ImageIO，也不声称由不同解码器交叉验证。临时输出冻结后才读回，AAC hint 修复只允许已解析证明的等长字节范围，最终资产仍由 Core 验证并原子发布。已通过三平台有限媒体及成品验收；保留 JVM 版用于回归和迁移期对照。未知帧色彩仍拒绝；会改变已分类色彩 metadata 的 MOV remux 仍拒绝，不因迁移放宽。

已验证 Native 检查点 `db94928`：各平台 900 项测试（Core 783、共享 CLI 38、文件/FFmpeg 58、原生入口 5、进程 16），零失败、零跳过，含 20 项真实 FFmpeg 专项回归；解压后的程序另有 27 项 CLI 检查，含 9 项真实媒体检查。[Native 验收](https://github.com/ohyooo/LivePhoto/actions/runs/38421950143) 与 [JVM 回归](https://github.com/ohyooo/LivePhoto/actions/runs/38421950137) 均成功，18 份产物已核验。合成 HEIC 检查不代替 HEVC 解码或设备兼容证明。当前继续迁移现有 HEIF 解码、厂商修复、Apple 配对回归及两项素材导出测试，最新提交结果见 Actions；不是新增 Apple 功能或设备认证。

[Build workflow](.github/workflows/build.yml) 现在默认按宿主构建 Native release executable，上传 `LivePhoto-<平台>-<run_id>-<attempt>`，包含单文件归档和 `native-cli-evidence.json`；Windows x64 为 `LivePhoto.exe`，Linux x64 / macOS ARM64 为 `LivePhoto.kexe`，Unix tar 归档保留可执行权限。证据 JSON 给出内层归档和原生程序的 SHA-256；不要将 Native KLIB artifact 当作程序。

编译仍需现有 JDK 25 和 Wrapper；**运行原生程序不需要 Java**。入口、原生进程及 FFmpeg 适配使用 [kotlinx.coroutines 1.11.0](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0) 的正式协程等待挂起操作；该依赖不进入协议 Core。

`-Plivephoto.jvm=false` 从构建图移除 JVM targets 和 JVM CLI 模块，必须同时指定宿主 `livephoto.nativeTarget`。Native CI 使用此模式，并通过 `verifyNativeOnlyBuild` 检查实际 targets；未指定时仍保留 JVM 构建用于迁移期对照和历史专项回归。这不代表 Java 已从 Gradle 构建工具中移除，也不代表已删除历史 JVM 源码。

```sh
# 在对应宿主构建；不交叉执行其它平台程序
./gradlew -Plivephoto.jvm=false -Plivephoto.nativeTarget=linux-x64 :verifyNativeOnlyBuild :cli-native:linkReleaseExecutableLinuxX64
./gradlew -Plivephoto.jvm=false -Plivephoto.nativeTarget=macos-arm64 :verifyNativeOnlyBuild :cli-native:linkReleaseExecutableMacosArm64
```

```powershell
.\gradlew.bat -Plivephoto.jvm=false -Plivephoto.nativeTarget=windows-x64 :verifyNativeOnlyBuild :cli-native:linkReleaseExecutableMingwX64
```

产物位于 `cli-native/build/bin/<target>/releaseExecutable/`。每个需要读取输入的命令必须设置 `LIVEPHOTO_WORKSPACE`，指向**已存在、自己控制的私有本地目录**；不要选共享临时目录或网络共享。目录只用于有总预算限制的冻结输入副本，命令结束清理自有副本，不删除工作目录或未知文件。缺少设置返回结构化错误；`--help`、`--version` 和协议 capabilities 不要求这个目录。

```sh
mkdir -p -m 700 "$HOME/.livephoto-work"
export LIVEPHOTO_WORKSPACE="$HOME/.livephoto-work"
./LivePhoto.kexe create --image "video.jpg" --video "video.mp4" --target oplus.olive --profile jpeg-no-tail --output-dir "created-live"
```

```powershell
New-Item -ItemType Directory -Path "$env:LOCALAPPDATA\LivePhoto\workspace" -Force | Out-Null
$env:LIVEPHOTO_WORKSPACE = "$env:LOCALAPPDATA\LivePhoto\workspace"
.\LivePhoto.exe create --image "video.jpg" --video "video.mp4" --target oplus.olive --profile jpeg-no-tail --output-dir "created-live"
```

`--max-bytes` 同时限制输入副本总量与输出总量，默认 1 GiB；多输入共享预算，不是各自获得一份。媒体操作还需要 FFmpeg 私有输入副本和临时输出的剩余空间，因此大文件可能需要提高预算。输出目录必须不存在，整组资产验证后发布至 `assets/`。原生异常 trace 不输出可能包含路径的文本堆栈，安全结构化诊断仍写 stderr。

CI 对归档解压后的原生程序执行原素材 Create → Inspect/Validate → 字节精确 Extract，检查输入不变、预算、缺少工作目录及无 FFmpeg 的明确拒绝。另要求真实媒体 probe、抽帧、AAC copy remux/trim、显式视频转码及组合 Create；每条命令结束须释放私有副本。验收期间 `JAVA_HOME` 指向不存在的目录，子程序 PATH 清空并显式指定 FFmpeg。真实媒体测试不允许因缺少工具而跳过；不是更多格式或真机兼容性证明。

Native 迁移新增独立 `process` 适配模块：协程等待、非阻塞管道读取、受限日志、超时与取消，直接启动绝对路径的可执行文件，不经过 shell、不使用 Java `Thread` / `ProcessBuilder`。Windows 通过 UTF-16 参数和 Job Object 管理子进程，Unix 通过 `posix_spawn` 与独立进程组管理；少量 C interop 仅桥接系统声明与捕获 Win32 错误码，不增加第三方运行库。CI 编译自有小型 C 测试程序，检验真实 argv/管道/退出/取消与资源释放；辅助程序不进入发行应用。库 artifact 附 `process.klib`、自有 SDK interop KLIB 和进程测试证据；Windows 文件库复用同一份 `process-win32.klib` 绑定，避免重复生成同一 header。FFmpeg 现已接到这个原生进程层，但独立进程测试不能代替媒体与成品验收。

## CI 与产物

[Build workflow](.github/workflows/build.yml) 在每次 push、pull request 和手动触发时运行 Windows x64 / Linux x64 / macOS ARM64 的 **Native-only** 构建、共用与原生测试、单文件打包及成品 smoke tests。CI 通过 `actions/setup-java` 准备 Zulu JDK 25，仅供 Gradle 构建工具；发行应用不携带 Java。Linux/macOS 直接执行仓库的 `gradlew`。smoke 启用 trace，日志随报告上传。

每个平台上传三类 artifacts，默认保留 **14 天**：

- `LivePhoto-<平台>-...`：原生程序归档及哈希证据；打包成功后上传，下载运行选这个。
- `LivePhoto-native-core-...`：Core / 文件 / 进程 KLIB、系统 SDK 绑定与库证据；供库开发使用，不是可执行程序。
- `native-core-reports-...`：五个模块的测试报告、XML、Gradle 与成品日志及证据；失败时也尝试上传已有报告。

Build 三平台成功通常共 9 个 artifacts，全部要求真实 FFmpeg 和解压后程序的 20 项 CLI 检查，其中 9 项为真实媒体检查，不能因没有工具而跳过。CI 安装 FFmpeg 仅用于验证，不会打入应用或由应用自动安装。

[JVM Regression](.github/workflows/jvm-regression.yml) 同样在 push / pull request / 手动触发时保留三平台回归，另上传 `LivePhoto-jvm-...`、`build-outputs-...`、`build-reports-...` 共 9 份。JVM 的 macOS/Linux 要求 20 项媒体集成测试零跳过；Windows JVM 仍不强制 FFmpeg，其历史跳过项不能代替 Native 媒体验证。保留这条回归流水线是为了逐项迁移剩余专项测试，不代表默认发行程序还依赖 JVM。

所有 Action 引用均使用最新稳定主版本 `@vN`。CI 复用 JDK 缓存与 Gradle 开源 basic 缓存；Native 另按 OS/架构/工具链缓存编译器依赖。编译任务允许缓存命中，但验收用 `jvmTest --rerun` 或对应 Native test `--rerun` 重新执行，不把旧报告计为新测试。Native 测试成功后保存工具链缓存，即使后续产物检查失败也不重复下载已验证的工具链。

## 进度、已完成与 TODO

Native Core 第一阶段：[CI 38204248439](https://github.com/ohyooo/LivePhoto/actions/runs/38204248439) 的 Windows x64 / Linux x64 / macOS ARM64 均成功，各 **726 项共享测试执行通过、零 failures/errors/skips**，KLIB 与报告已上传。使用 Kotlin 2.4.21 / Gradle 9.8.1；当时只交付 Core 库，后续共享 CLI、文件 IO 和原生纯协议入口另见上节。

macOS/Linux 真实 FFmpeg 验收：[CI 38188615547](https://github.com/ohyooo/LivePhoto/actions/runs/38188615547) 三平台回归通过，两个 Unix 平台各 20 项媒体集成测试零跳过，并通过完整便携 CLI 媒体操作及无 PATH/无后端负例。九个同提交 artifacts 已核对，两个 Unix 报告下载哈希与上传 digest 一致；没有扩展旧 FFmpeg 版本或新的业务 profile。

Samsung 标准 EXIF → Apple：新全量 **835 tests，814 项执行成功，0 failures / errors；21 项历史系统 API 专项跳过**。大小端、有/无 Google 基础层、显式未知 key、原 EXIF/编码/样本保留、普通 SEF/Legacy/严格拒绝与清理视图篡改通过。新完整 Windows 无 FFmpeg 便携包通过 10 组转换（原 8 组 Google＋2 组 Samsung），并另用已有 FFmpeg 完整解码 10 个输出；只开放已声明的标准两记录 MP4 来源，更多来源仍在 TODO。

Google 标准 EXIF → Apple：新全量 **831 tests，810 项执行成功，0 failures / errors；21 项历史系统 API 专项跳过**。独立验证来源清理未改 EXIF、普通字段/偏移/编码/样本/key 保留、私有数据拒绝和视图/staging 篡改回滚。新完整 Windows 无 FFmpeg 便携包通过两协议×两字节序×两容器的 8 组转换/配对验证/严格拒绝；另用已有 FFmpeg 完整解码 8 个生成视频。来源未知关联继续报告，不代表其它来源、HEIC/MakerNote 写入或设备认证；对应发布结果见 [Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)。

Apple 有限索引式普通文本：新全量 **826 tests，805 项执行成功，0 failures / errors；21 项历史系统 API 专项跳过**。真实编码 MP4 与合成正反例验证严格 Create、目录/样本原样保留、Clean/转换、严格重复复制及整对篡改回滚；未知键/namespace、重复键、缺失/重复/越界索引和非法编码仍拒绝。新完整 Windows 无 FFmpeg 便携包对三种文本格式逐项验收通过，另用已有 FFmpeg 完整解码其 9 个生成视频，Clean/repeat 普通标签回读准确；发布结果按对应提交检查。不等同于通用 mdta 写入或设备认证，FFprobe 的成对标题展示差异见上文。

### 最新可核验检查点

AAC-LC 已知“不启用 SBR”配置兼容：新全量 **878 tests，857 项执行成功，0 failures / errors；21 项历史系统 API 专项跳过**。新增 6 项独立配置正负测试与 1 项真实双音轨 remux/裁剪测试，原样保留完整 ASC、样本和时间线，输出完整解码；原无 edit list 的 remux 不再新增取整的 identity edits。新完整 Windows 便携包通过六组音频裁剪（基础配置四组＋已知扩展单/双音轨两组）和全部既有 smoke。音频始终 copy，重编码已从推进 TODO 移除；不开放 HE-AAC、未知扩展或复杂原始 edits。三平台 CI/产物按[本提交 Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)核对。

FFmpeg 带音频裁剪：新全量 **871 tests，850 项执行成功，0 failures / errors；21 项停用的历史系统 API 专项跳过**。5 项合成策略/篡改测试与 3 项真实 FFmpeg 测试均执行成功，验证单/双 AAC 完整包复制、每轨配置/样本/时间线、同步空隙、Strict/Only/Exact 拒绝、单音轨 Create/Convert 与 key 重置。新完整 Windows 便携包通过四组单/双音轨、不同顺序的 CLI 裁剪及全部既有 smoke。只开放本节声明的有限 profile，不代表任意音频、精确音频编码或真机相册兼容；三平台 CI/产物按[本提交 Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)核对。

三条职责边界与 FFmpeg 调用整理：新全量 **863 tests，842 项执行成功，0 failures / errors；21 项停用的历史系统 API 专项跳过**。五个桌面媒体入口统一输入暂存、存储预算、取消/超时/进程错误、源身份检查和临时文件清理；解码与编码错误分阶段返回。历史 Windows 结构剪切器及五项测试移到 JVM 层，公共 Core 不再包含该媒体处理实现，源码/测试保留。新增 11 项调用管理测试和真实 FFmpeg 回归通过；新完整 Windows 便携包通过三组双 AAC remux、四组音轨保留转码及全部既有 smoke。三平台 CI 与产物按[本提交 Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)核对。此批没有扩展带音频裁剪、Apple 写入或移动端能力。

显式 AVC/VFR 视频转码保留有限 AAC：新全量 **852 tests，831 项执行成功，0 failures / errors；21 项历史系统 API 专项跳过**。真实单/双音轨、不同采样率/声道、四种轨道顺序及稀疏 ID 验证每轨 sample/configuration/时间线与普通 metadata，完整源/输出解码通过；丢轨、篡改音频 sample/配置/时间线在发布前回滚。新完整 Windows FFmpeg 便携包通过四组实际 CLI 编码/音轨保留验收，以及默认未授权、严格策略冲突、音频裁剪拒绝与全部既有 smoke。三平台 CI/上传状态按[对应提交 Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)核对；不借前一批结果，也不将缺少 FFmpeg 的 CI skip 当作实际媒体验收。

Apple 普通 MOV 短文本扩展：新全量 **823 tests，802 项执行成功，0 failures / errors；21 项历史系统 API 专项跳过**。合成结构正反例与真实编码 MOV 验证目录原字节保留、严格 Create、Clean 与再次严格复制；新完整 Windows 无 FFmpeg 便携包对 MP4/MOV 分别执行全部新验收。长度/语言/重复字段/未知类型/混合目录继续拒绝，不等同于通用 QuickTime metadata 或设备认证。

Apple 普通电影文本 Clean/Split 与有限 Apple→Google 转换：新全量 **821 tests，800 项执行成功，0 failures / errors；21 项历史系统 API 专项跳过**。仅清除确认的 CID/专用轨，普通目录原字节与媒体样本不变；再次对普通视频 Split 为严格整文件复制。首次未知关联、严格拒绝、篡改整对回滚与转换后原样提取回归通过。新完整 Windows 无 FFmpeg 便携验收通过 Create、首次 Clean、严格拒绝和再次整文件复制；不等同于通用 mdta/MakerNote/HEIC 写入或设备认证。

Apple 普通电影文本保留：本轮全量 **817 tests，796 项执行成功，0 failures / errors；21 项历史系统 API 专项跳过**。有限 iTunes 文本目录原字节与媒体样本保留；多目录/重复字段/freeform/非 UTF-8/类型与 locale 反例、整对篡改回滚通过。新完整 Windows 无 FFmpeg 便携包通过严格 Create 与 Pair 验证；不开放通用 mdta、Clean、媒体重封装或真机认证。

当前方向：桌面媒体后端仅使用用户已有 FFmpeg；以下 Windows 系统 API 检查点是保留代码的历史证据，不代表当前默认桌面入口仍调用这些 API。Android/iOS 构建、IO/事务和系统媒体适配尚未实施，列在最后一项移动端 TODO。

Apple 标准 EXIF Create 扩展：本轮全量 **813 tests：792 项执行通过，0 failures / errors；21 项历史系统 API 专项跳过**。普通 JPEG 的安全标准 EXIF 可用于有限 `jpeg-mov` / `jpeg-mp4` Create：保留字节序、GPS、方向、普通 UserComment 和既有外置值的 TIFF 相对位置；新 CID 使用追加的正式 MakerNote/IFD。独立字段回读、完整补丁核对与篡改整对回滚通过；新完整 Windows 便携包通过大小端×两种容器的严格创建、配对验证及私有 MakerNote 拒绝。已有 MakerNote、缩略图/私有引用、未知非零空隙仍拒绝；Convert 来源 EXIF 清理门禁不变，不代表 HEIC/通用 Apple 写入或设备认证。对应三平台发布见 [Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)。

Apple CID/EXIF 归属回归：本轮全量 **808 tests：787 项执行通过，0 failures / errors；21 项历史系统 API 专项跳过**。JPEG、HEIC 的大小端 TIFF 正负样本验证：独立普通 EXIF 字段仍可读取；MakerNote 与其它已知字段整块或部分共享字节时返回 `CONFLICTING_METADATA`，Repair 不产生输出。新完整 Windows 便携验收通过。未知类型保持 opaque，不猜范围、不增加写入授权；这不是复杂 MakerNote 写入或真机认证。对应三平台发布结果见 [Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)。

桌面 FFmpeg-only 回归：本轮全量 **804 tests：783 项执行通过，0 failures / errors；21 项历史系统 API 专项按当前方向跳过**。新 Windows 便携包分别完成无 FFmpeg 与已有 FFmpeg 的完整验收：前者保留协议能力并拒绝不可用的媒体操作；后者通过实际解码、remux、抽帧、裁剪、显式转码、封面替换与 Create/Convert 组合编辑。两轮均未调用系统媒体 worker。三平台 CI/产物结果见 [Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)；Windows 实际媒体验收不代替 macOS/Linux 的 FFmpeg 媒体验收。

Windows 有限系统无转码裁剪：新全量 **803 tests，0 failures / errors / skips**，包括真正非零闭合 IDR 的 3 个 VFR 样本选择、精确重置时间线/时长、逐样本与配置字节保留、普通 metadata 独立比较，以及预算、取消、音轨、B 帧和 MOV 拒绝。新完整无 FFmpeg 便携验收通过 LosslessOnly / Exact / LosslessPreferred、完整系统解码、Create/Convert 源域 key 重置与提取视频一致，需要编码的 Exact 即使授权也拒绝且零输出。commonMain 负责有界表重建，系统后端实际完成压缩封装和解码；不是整文件 no-op，也不代表通用裁剪或系统转码。对应三平台 CI/产物见 [Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)。

Windows 有限 Main/High 无 B 帧 remux：新全量 **794 tests，0 failures / errors / skips**，包含实际系统压缩样本、全部样本/配置/metadata/精确 VFR 时间线、严格保留和完整字节一致；B 帧、音轨、MOV、预算与取消仍拒绝。新完整无 FFmpeg 便携包通过 Main/High 两个独立 profile、完整系统解码及全部旧 smoke；三平台发布状态见 [Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)。仅扩大已声明有限 profile，不代表通用 remux 或系统裁剪/transcode 已完成。

Windows 有限系统 JPEG 显式质量与 CLI 参数：新全量 **792 tests，0 failures / errors / skips**，包含默认 JPEG 字节不变、0/35/75/100 的独立量化表及预算/取消反例、实际系统抽帧与 Core 原子发布。新无 FFmpeg 完整 Windows 便携包已验收，0/35/100 量化表及非法值退出 2、零输出通过；对应提交的三平台 CI/产物以 [Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml) 为准。该参数不表示无损，也不开放更多 HDR/图像 profile。

macOS ARM64 便携交付与可选诊断日志：CLI 改动实际全量 **787 tests，0 failures / errors / skips**。修复 macOS jpackage 包版本后，[CI 37877380716](https://github.com/ohyooo/LivePhoto/actions/runs/37877380716) 三平台构建、测试、打包成功，9 个 artifacts 已核对。同提交 macOS 包下载后核对外层 GitHub artifact digest、内层归档 SHA-256、ARM64 启动器和运行时；本机在仓库外工作目录通过 13 个 CLI 检查，包括 Create/Validate/Extract/Split/Convert、reference 预期 Invalid 和错误退出码。Raw 视频字节一致、输入未改变，trace 独立写入 stderr。未验证 macOS 系统媒体处理或 Apple 相册兼容；Native 迁移另列 TODO。

Apple 有限 ExplicitRePair 已交付：[`fceb13c`](https://github.com/ohyooo/LivePhoto/commit/fceb13c5c226e0861515795abaadcc2978930b02) 实际全量构建 **711 tests，0 failures / errors / skips**，包含真实编码 MOV 修复后完整解码；Windows 新便携包全部验收成功，新增空 PATH 两侧权威/两种容器的重配对 smoke。[对应 CI 37742117365](https://github.com/ohyooo/LivePhoto/actions/runs/37742117365) 成功，Windows/Linux 合计 6 个非空、未过期 artifacts 已核对。

随后 [`18f8122`](https://github.com/ohyooo/LivePhoto/commit/18f81223adc4933b69abc0570745d13ba9b030be) 补充独立合成 CID 字段边界回归：36/37 字节与单个 NUL 保持、额外终止符/混合 timed metadata/身份别名拒绝。该批次实际全量 **713 tests，0 failures / errors / skips**；只改测试及 fixture helper，不扩展业务能力。[对应 CI 37742738670](https://github.com/ohyooo/LivePhoto/actions/runs/37742738670) 成功，6 个非空、未过期 artifacts 已核对。

Windows 有限 MOV 系统 Probe 扩展：[`91cec67`](https://github.com/ohyooo/LivePhoto/commit/91cec677bb13f8df74c31e851e29a133a5f7954e) 实际全量 **714 tests，0 failures / errors / skips**，包含 B 帧/VFR、音轨拒绝，以及现有 FFmpeg 明确封装 MOV 后只用系统后端完整解码。人工 qt brand fixture 仅为合成容器结构＋真实 AVC samples，单独标明其范围，不能当作相机原片或通用 remux 证明。完整 Windows 便携验收与[对应 CI 37743888829](https://github.com/ohyooo/LivePhoto/actions/runs/37743888829) 成功，6 个非空、未过期 artifacts 已核对。

协议兼容素材批次此前全量验证 **730 tests，0 failures / errors / skips**，含 16 个回归测试；整文件哈希与素材保持不变。最新提交的 Windows/Linux 构建、测试和上传结果以 [Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml) 为准；解析缺口另列 TODO，不能把素材覆盖等同于所有协议兼容完成。

Huawei HEIC 有限读取批次：全量 **740 tests，0 failures / errors / skips**，包含两个兼容素材的精确视频提取，以及有界图像、越界/开放长度/未知扩展/源变化拒绝测试。完整 Windows 便携验收成功，包含空 PATH、误导扩展名、视频整文件 SHA 一致和 Clean 拒绝用例。媒体语义仍 Partial，key 时间单位仍未知；不开放该 profile 的 HEIC Create、Clean、Repair 或跨格式 Convert。

有限 Samsung ExplicitRemux 批次：[`a804b3d`](https://github.com/ohyooo/LivePhoto/commit/a804b3d79eb758487abead37a5383dda73a7f67e) 全量 **749 tests，0 failures / errors / skips**，包含独立 SEF 合成测试、真实 AVC MOV 修复后完整解码、预览零写入/allowlist/幂等、后端样本/配置/时间线/metadata 改动及声称转码拒绝、最终 key 篡改/写入失败/取消/源变化回滚，以及 CLI 按模式发现后端/显式路径转发回归。新 Windows 便携包全部验收成功，[对应 CI 37758770263](https://github.com/ohyooo/LivePhoto/actions/runs/37758770263) 两平台成功，6 个非空、未过期 artifacts 已核对；不证明设备兼容。

有限 vivo ExplicitRemux 扩展批次：全量 **751 tests，0 failures / errors / skips**，包含版本一原字段、已知/未知 key、VFR samples 保留及复杂 profile/GainMap/未知 vendor/错误时长拒绝；真实 AVC Samsung/vivo 双协议修复后提取、逐 sample 核对和完整解码均成功。新完整 Windows 便携验收成功，包含两协议显式路径修复、key 保持、空 PATH 幂等与无后端拒绝。提交后的 CI/6 artifacts 以 [Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml) 对应提交为准，不借用 Samsung 上一批产物；复杂 vivo 与真机验收仍未完成。

有限 Huawei basic60 ExplicitRemux 扩展批次：[`d91d23e`](https://github.com/ohyooo/LivePhoto/commit/d91d23e6c7ad5f4af2ca4a5657cec6db556fc9d2)，Gradle 9.8.1 下全量 **755 tests，0 failures / errors / skips**；真实 AVC 三协议修复、原始 sample 验证与完整解码通过。新增完整 JPEG/普通注释/尾标原始字节、未知 key 单位保持，扩展拒绝、尾标篡改、写失败、取消与预算不足不发布的回归。新完整 Windows 便携包验收通过；[对应 CI 37804198168](https://github.com/ohyooo/LivePhoto/actions/runs/37804198168) 两平台成功，6 个非空、未过期 artifacts 已核对；不代表设备兼容。

Apple 有限默认 Create 入口：[`2f6554a`](https://github.com/ohyooo/LivePhoto/commit/2f6554a61122265ba1d5beebd59aee0727d4e0ea)，新全量 **757 tests，0 failures / errors / skips**。省略 profile 只接入普通 JPEG＋MOV 的已有 assembler，计划解析为 `jpeg-mov`；保持样本、配置、时间线及源文件不变，不执行 remux/转码。包含 AVC/HEVC 合成正负例、真实 AVC MOV 独立样本验证与完整解码。新完整 Windows 便携验收成功，新增空 PATH 创建、key 保持、MP4 拒绝且不发布输出的 smoke；[对应 CI 37806758463](https://github.com/ohyooo/LivePhoto/actions/runs/37806758463) 两平台成功，6 个非空、未过期 artifacts 已核对。

Windows 系统解码缓冲区读取前置：[`686fa4c`](https://github.com/ohyooo/LivePhoto/commit/686fa4cf01f77d5db2520ed2bf078e3017dd9b55)，当批全量 **758 tests，0 failures / errors / skips**。隔离 worker 实际锁定并读取有效帧缓冲区，验证字节预算、返回原始缓冲区证据；包含 B 帧与小预算拒绝回归。该批证据不解释像素 stride/色彩、不输出图片，尚未接入系统 `ExtractFrame`。新完整 Windows 便携验收成功，验证内置 worker 新模式与原有 Core 系统回退；[对应 CI 37808199497](https://github.com/ohyooo/LivePhoto/actions/runs/37808199497) 两平台成功，6 个非空、未过期 artifacts 已核对。

Windows 有限系统 SDR 抽帧：新远程全量 **764 tests，0 failures / errors / skips**，118 份报告均属本轮。系统二维缓冲区输出紧密 NV12；8 个实际 B/VFR 呈现帧逐字节等于独立解码基准。Core 独立核对 SPS/VUI、容器色彩、完整 PTS 与零基索引，再编码并回读标准 sRGB JPEG，验证源不变后原子发布。包含未知/HDR 色彩、截断 SPS、越界选择、禁止覆盖、取消与预算不足回归。新完整 Windows 便携验收通过，空 PATH 下只用内置运行时/系统 helper 生成 JPEG；不依赖 FFmpeg 或系统 Java。该批 CI 发布结果及归档按对应提交在 [Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml) 查阅，不算设备认证。

后续 High 8-bit 扩展：新远程全量 **769 tests，0 failures / errors / skips**，118 份报告均属本轮。独立证明 High SPS 的 8-bit/4:2:0 和有限 PPS；不以 profile 名称推定 SDR。Main/High 两组各 8 个实际 B/VFR 帧逐字节核对独立 NV12 解码基准，Core JPEG/源不变/预算与取消门禁保持。新完整便携验收在无 FFmpeg、空 PATH 的媒体处理段通过两组像素/PTS/JPEG，并准确拒绝不允许丢弃普通 metadata 的替换请求；仅有限 Experimental，发布状态按对应提交 Actions 检查。

有限 MOV 抽帧色彩扩展：新全量 **771 tests，0 failures / errors / skips**，118 份报告均属本轮；完整 Windows 便携验收通过 Main/High/High-MOV 三组实际像素、PTS 与 JPEG。只接受 MOV 中精确长度、三个 BT.709 索引的 `nclc`，范围仍由独立 SPS/VUI 证明；不从缺失 range 位推定 limited。MP4 中的 `nclc`、未知索引、ICC、错误长度和范围冲突继续拒绝。合成 MOV 的完整媒体解码已验证，不算相机或真机认证；该提交 CI/产物另按 Actions 检查。

Baseline 实际像素与全 profile PPS 验收：新全量 **774 tests，0 failures / errors / skips**，118 份报告均属本轮。新增 Baseline 八个 VFR 帧逐字节独立解码证据，合法 Main/High 保持；补齐所有 PPS 的 profile 门禁与截断/冲突反例。新完整无 FFmpeg Windows 便携验收通过 Baseline/Main/High/High-MOV 四组像素、时间与 JPEG，原协议往返及拒绝无输出门禁保持。对应提交 CI/产物以 Actions 为准，不借用上一批报告。

Windows 系统 remux 内部前置实验：新全量 **776 tests，0 failures / errors / skips**，119 份报告均属本轮；新完整 Windows 便携验收与内置 helper 的无 FFmpeg 完整解码通过。有限 Baseline/VFR 压缩样本逐字节、PTS/duration/sync 及源不变已验证，附加音轨和既有输出被拒绝。曾发现系统默认时钟把 40 ms 量化，现使用独立源时间表和可精确表达的有限整数 Hz 网格，不推算或固定 VFR 的呈现位置。**这只是内部前置，不提供公开系统 remux；普通 metadata、配置、轨道集合和完整 Core 保真门禁仍待实现。** 公开 `Remux` 继续 `Unsupported`，不能将私有实验成功当作该能力完成；CI/产物按对应提交检查。

后续同容器保留实验：新全量 **782 tests，0 failures / errors / skips**，120 份报告均属本轮。单轨、无重排 MP4 的源 metadata 先严格分类；真实 Windows 后端样本逐字节和时间线验证后，与原容器头重组，完整 decoder configuration、普通 metadata、轨道和样本再次独立核对。后端新增的 UUID 不替代源 metadata；未知源字段、附加轨道、样本变化、预算不足和取消均保持门禁。**仍为内部实验，不是跨容器转换，也没有开放公开系统 remux。**

有限公开系统 remux：后续新全量 **784 tests，0 failures / errors / skips**，121 份报告均属本轮；已将上述同 MP4 保留路径接入统一 Core API，实际 strict 保真、系统执行记录和原子发布通过。仅 Baseline 单轨、无重排 MP4 → MP4；不把原始系统输出直接当 metadata 保真证据，不转码。MOV、音轨、B 帧、预算不足和取消均拒绝且不发布。状态为 `Experimental`；前两批的 `Unsupported` 仅描述当时的内部阶段，不能据本批推断其它 remux profile 已实现。对应便携验收与 CI/产物按本提交检查。

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
| Phase 1：工程与公共 Core 契约 | 已完成当前范围 | JVM 与三平台 Native Core 库、统一模型/API、IO/后端契约、共享测试；本地 Native 文件适配、整组事务及纯协议 CLI 另行交付 | Native 进程/媒体适配及移动 targets 尚未交付 |
| Phase 2：解析与安全基础 | 已完成基础批次；复杂格式部分完成 | JPEG/XMP/EXIF/BMFF、范围/预算/身份检查、有限 HEIF 图 | 通用 HEIF/AVIF 与复杂 metadata 关联 |
| Phase 3：Google 主流程 | 部分完成，有限闭环可用 | JPEG V1/V2 与有限 HEIC 的读取、创建、提取、拆分、转换、key/修复 | 未支持的资源图、HDR/GainMap 等变体 |
| 厂商协议与 Legacy | 部分完成 | 已声明的 Oplus/Samsung/vivo/Huawei/Legacy 子集 | 未确认的尾挂、Honor 写入、复杂厂商变体及设备证明 |
| Apple Pair / Create / Convert | 部分完成；暂停新增功能 | 有限 JPEG 两资产写入、安全标准 EXIF 与普通电影文本；Google/有限 Samsung/Huawei basic60/最小版本一 vivo 标准 EXIF→Apple；默认 JPEG＋MOV；有限普通文本 Clean/Split 与 Apple→Google；HEIC 读取、原样输出、SetKey/Clean；有限显式重配对 | 暂缓已有 MakerNote 合并、复杂 Generic/HEIC/mdta 写入、更多原片与跨协议路径；保留已有代码/测试 |
| Repair | 部分完成；有限容器修复通过构建/便携验收 | 有限 SafeMetadataOnly；ExplicitRePair 已交付；Samsung/最小版本一 vivo/Huawei basic60 JPEG ExplicitRemux、全量测试、真实解码及新便携验收 | 其它容器修复与复杂 MakerNote/metadata profile；各提交 CI 结果见 Actions |
| Cover / Key Photo | 部分完成 | key metadata 与抽帧/封面重建分离，已有有限编辑路径 | 更复杂图像编码、orientation/ICC/HDR/metadata 保留 |
| MediaBackend | 部分完成；桌面 FFmpeg 已接入，系统 API 历史代码保留但停用 | JVM/Native 共享有限媒体操作；单/双 AAC copy；三平台 Native 真实媒体和成品验收完成 | 更多媒体/容器/复杂 edits profile 暂不推进；音频只 copy，不计划音频编码；最后移动端系统媒体 API |
| CLI / 三平台便携 CI | 已完成当前有限入口批次 | 薄 CLI、JSON、退出码、可选 stderr 日志、三平台 JVM/Native 打包与 smoke/artifacts | 继续迁移期 JVM 清理与回归对齐；不把有限 profile 验收称为全部格式支持 |
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
- [x] 桌面默认后端统一为显式 FFmpeg → PATH → 禁用；系统 API 源码保留、不默认发现或运行。新全量测试与有/无 FFmpeg 两轮 Windows 完整便携验收通过。
- [x] 三条职责边界与 FFmpeg 调用管理整理：五个入口统一 IO/预算/取消/错误/清理；历史 Windows 自写剪切器移出 commonMain，保留源码及 JVM 测试。863 项新全量与新完整 Windows 便携验收通过，不因此扩大媒体支持范围。
- [x] FFmpeg 有限单/双 AAC 裁剪：871 项新全量与四组真实 CLI 便携验收通过；全轨 copy、逐轨实际范围/同步空隙、无隐藏包、独立样本/配置/时间线/metadata 与完整解码验证。
- [x] AAC-LC 已知“不启用 SBR”配置读取与原样复制：878 项新全量与新完整便携验收通过，未知扩展/HE-AAC 仍拒绝；不新增音频编码。
- [x] Windows 有限系统 API 完整解码 Probe 回退与隔离 helper；不能当作系统媒体编辑已完成。
- [x] 薄 CLI、JSON/退出码、两资产原子发布、源变化/预算/取消/篡改/第二资产失败回归。
- [x] Windows/Linux 便携 CI、reference 测试、合成 parser/writer/round-trip/byte-exact/metadata/malformed 测试与有限真实媒体解码验收。

### 后续顺序与验收目标

以下是当前推进顺序，不是已实施状态；遇到真实依赖可调整小范围顺序，不能因此跳过验证或把暂缓项计为完成。

| 顺序 | 工作包 | 状态 / 前提 | 交付时应看到的结果 |
| --- | --- | --- | --- |
| 已完成批次 | Apple 有限 ExplicitRePair | **已完成有限范围交付**；复用既有 Request 与新鲜 CID 证据 | 用户指定两个源及权威，冲突 ID 不自动选；独立验证后原子发布 |
| 2 | 非 Apple Repair 其它显式模式 | Samsung/vivo/Huawei basic60 JPEG 有限容器修复已通过；剩余路径先确认 ownership/依赖，重大规范/API 冲突需报告 | 每个 mode/profile 独立标能力；不能凭其它入口已可用推断支持 |
| 3 | 统一桌面 FFmpeg 后端与跨平台验收 | 三平台复用后端；已有 Windows 真实媒体验证，继续完善其它平台证据与业务范围 | 显式路径/PATH 发现，媒体结果独立回读；没有 FFmpeg 就禁用，不调用桌面系统 API |
| 4 | 复杂 HEIF/AVIF / metadata / 媒体 profile | 部分基础已有，其余持续扩展 | 图像/音轨/时间线/metadata 各自证明，无法保证时拒绝或明确 Partial |
| 持续 | 每批新增 Core 能力的 CLI 与 conformance | 随上述工作包推进，不集中到最后才测 | 合成正负例、真实媒体、保留、原子性与便携 CLI 同步回归 |
| 已完成批次 | macOS ARM64 便携包 | **已完成当前便携范围**，仍为 jpackage/JVM | ARM64 CI 构建、归档解压、smoke 与本机 13 个 CLI 检查通过；不是系统媒体后端或设备认证 |
| 当前推进 | Kotlin/Native Core 与原生 CLI | **Core、共享 CLI/无反射 JSON/日志、本地文件 IO/整组原子事务、原生进程与有限 FFmpeg 已通过三平台验收** | 继续迁移期 JVM 清理与回归对齐；原生程序与 KLIB 分开打包 |
| 补齐素材后 | L4 真机兼容 | **暂缓：待真实原片与设备证据** | 按厂商、设备/OS/相册、导入方式记录动态播放、声音、key 等证据 |
| 暂停 | Apple 新功能与复杂 HEIF Pair 写入 | **用户决定暂停；已有代码和测试保留** | 恢复时再补真实 iOS 证据，不计作已完成，也不占用当前桌面 FFmpeg 工作 |
| 最后 | Android/iOS 接入与移动端系统媒体 API | **TODO：桌面与协议剩余任务之后；尚未配置移动 target 或产物** | 先编译与 common 测试，再接 Uri/文件 IO、事务与权限，最后移动端媒体后端及设备验收；不复用桌面进程假装移动端支持 |
| 当前范围之外 | Compose UI | 未来规划，不计入本轮 Core + CLI 交付 | 复用统一 Core/Application API，不提前引入 GUI 依赖 |

### 已完成工作包：有限 ExplicitRePair

- [x] 核对有限 JPEG/HEIC 图片 CID 与 MOV/MP4 CID 的真实字段编码、位置、宽度和 ownership；未知结构不猜测。
- [x] 要求显式选定图片和视频，并由本次输入的新鲜 evidence 指定权威；缺失、过期、冲突或未知权威必须拒绝。
- [x] 补齐 dryRun 预览及允许问题码过滤；预览零写入，不自动生成 ID 或择一覆盖。
- [x] 实现有限 CID 改写与双资产共同原子发布，保留原图编码、视频 sample/configuration/时间线及未授权字段。
- [x] 独立检查输出配对与协议、保留报告；覆盖第二资产失败、源变化、预算/取消、篡改回滚、再次执行无变化；真实编码 MOV 修复后全解码。
- [x] 实际构建/测试和 CLI 验收；提交推送、核对对应 CI 与 6 个 artifacts。

### 剩余范围清单

- [x] 接入 12 个单文件组合、更新 vivo 样本及 3 组配对的兼容回归测试，校验 19 个资产的整文件 SHA-256，并登记用户 11 个导出成品的字节对应关系；不算 L4 设备认证。
- [x] Huawei HEIC + 固定 60 字节尾标的有界读取与精确视频提取；保护图像 item extent 边界，未知 Honor/扩展不取得纯视频权威，HEIC 写入继续拒绝。
- [x] 有限 Samsung ExplicitRemux：全量/真实媒体测试、新便携包验收通过；其它协议/未知媒体恢复不在此范围，各提交 CI 见 Actions。
- [x] 最小版本一 vivo ExplicitRemux：全量/双协议真实媒体与新便携验收通过；保持原 vendor 字段、未知 key 和既有 Google 基础层诊断。
- [x] Huawei basic60 JPEG 有限 ExplicitRemux：全量/三协议真实媒体与新便携验收通过，完整原图与前 40 字节尾标保持；未知时间单位仍报告，HEIC/Honor/扩展不授权修复。
- [x] 核清当前固定 MOV/vivo 素材的时间线差异并补回归：`mdhd` 恰等最后一帧 PTS，而非解码时长或完整呈现结束时间；保持严格拒绝，不猜一/两帧容差。另测合法正 CTS/edit 映射，防止混淆解码与呈现时间。新增远程全量 **766 tests，0 failures / errors / skips**；这不是新增设备兼容声明。
- [ ] 扩展其它有明确规范证据的媒体时间线变体及 Apple MakerNote 兼容读取。当前 Apple 固定素材的 IFD0 MakerNote 不取得正式 ExifIFD 权威；保持读写边界，不自动授权旧结构改写。
- [x] Apple JPEG/HEIC CID 的 EXIF 已知范围归属检查：大小端、普通独立字段、整块/部分共享范围及 Repair 零输出回归；808 项本轮全量与完整 Windows 便携验收通过。未知字段类型/私有内部引用仍不获得写入权限。
- [x] Apple 普通标准 EXIF Create：813 项本轮全量和完整 Windows 便携验收通过，大小端/GPS/方向/UserComment/原值位置保持、无 ExifIFD 追加、私有数据拒绝与整对回滚；仅有限 JPEG＋原 MOV/MP4，不开放已有 MakerNote 合并或 Convert 来源清理。
- [x] Google V1/V2 标准 EXIF→Apple：831 项新全量及新完整 Windows 8 组合便携验收、8 视频完整解码通过；来源清理与追加独立证明，未知关联/严格拒绝边界保留。
- [x] 有限 Samsung JPEG 标准 EXIF→Apple：835 项新全量、新完整 Windows 10 组合便携验收与 10 视频解码通过；非 Legacy 两记录 SEF＋原 MP4，未知 key 要求显式选择，不丢普通 SEF，不借清理证明升级 Strict。
- [x] Huawei basic60 JPEG 标准 EXIF→Apple：838 项新全量（817 执行成功、21 历史系统专项跳过）、新完整 Windows 12 组合便携验收通过；独立尾标/EXIF 来源证明、显式 key、Honor/间隙/未知时间拒绝、源不变和清理视图篡改零输出。对应提交后的三平台 CI/产物见 [Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml)，不借用上一批 CI，不证明设备兼容。
- [x] 最小版本一 vivo 标准 EXIF→Apple：841 项新全量（820 执行成功、21 历史系统专项跳过）、新完整 Windows 14 组合便携验收与 14 输出完整解码通过；非零 key/普通字段/样本/源字节保留，未知版本、私有字段、Flags、GainMap、Strict 与清理视图篡改零资产拒绝。对应提交的三平台 CI/产物以 [Build Actions](https://github.com/ohyooo/LivePhoto/actions/workflows/build.yml) 为准，不借其它提交结果。
- [ ] **暂停：**其它含 EXIF 来源的 Apple 转换与 Normalize；已有 MakerNote 合并仍未实现。恢复后按各协议分别证明清理 ownership 和普通字段保留，不能直接借用 Google 路径。
- [ ] 扩展其它显式 Repair 与 Apple 写入：有限 ExplicitRePair、Samsung/最小版本一 vivo/Huawei basic60 JPEG ExplicitRemux 已有实现；其它 profile、更复杂 MakerNote/private metadata 尚未实现，不能借用独立 remux 能力。
- [x] Apple Create 保留有限 iTunes 普通文本：817 项本轮全量，796 项执行成功、21 项历史系统专项跳过；UTF-8/多目录/重复字段/私有编码拒绝及整对篡改回滚通过。raw 保留的追加装配与读取不授权 remux/trim/transcode 改写。
- [x] Apple 有限普通文本 Clean/Split 与 Apple→Google：821 项新全量，800 项执行成功、21 项历史系统专项跳过；选择性清除 CID/专用轨、原目录/样本保留、再次 Split 整文件复制；首次 Unknown 与严格拒绝、篡改回滚及转换后原样提取回归通过。
- [ ] 其它普通电影 metadata、复杂 mdta 与混合 timed metadata 的选择性清理和转换：需要独立 ownership/引用关系证明，不能借有限文本的保留结果开放未知结构。
- [x] 有限索引式 `udta/meta` 普通文本：826 项新全量，805 项执行成功、21 项历史系统专项跳过；唯一已知键、完整值索引、UTF-8/locale 门禁，原样保留 Create/Clean/转换及整对回滚。不是电影级 CID/普通键合并或媒体后端改写授权。
- [ ] 索引式普通文本与 Apple CID 并存的跨工具兼容：已观察到 FFprobe 扁平化标题混入 CID；原目录原字节保留及 Clean 标签正常不代表此项完成，需更多独立读取器/相册证据。
- [x] 有限旧式 QuickTime 普通文本：823 项新全量与完整 Windows 双容器便携验收通过；真实 MOV、精确长度/und/UTF-8/唯一 atom、目录原样保留，未知语言/编码/混合目录继续拒绝。
- [x] Apple 有限默认 JPEG＋MOV Create：757 项全量测试、新完整 Windows 便携包和对应两平台 CI 与 6 个 artifacts 已验收；不开放 MP4 自动转换或复杂 metadata 写入。
- [ ] **暂停：**Apple 复杂 Generic Create、HEIC Create/ConvertTo、HEIC 跨协议转换/Normalize、更多真实相机 MakerNote 和复杂媒体 profile；已有有限默认路径不代表这些完成。
- [ ] 扩展复杂 HEIF/AVIF、HDR/GainMap、未知 metadata 关联、辅助资源/混合轨道等；无法证明安全时继续 Unsupported/Partial，不先删 metadata 再宣称无损。
- [x] Windows 真实解码缓冲区读取前置：758 项全量测试、新完整 Windows 便携包、对应两平台 CI 与 6 个 artifacts 已验收；不是已完成抽帧。
- [x] Windows 有限 SDR 系统抽帧实现及 NAS/便携验收：紧密 NV12、独立 SPS/VUI/容器色彩门禁、完整呈现时间线与实际帧像素、JPEG 回读、源不变与原子发布；764 项全量通过，空 PATH 便携入口通过。CI 发布状态按上述对应提交 Actions 检查；不是通用 AVC/HDR/真机支持。
- [x] 有限 High 8-bit/4:2:0 抽帧扩展：SPS/PPS 独立门禁、实际八帧像素和 JPEG 回读；新 769 项全量及无 FFmpeg 双 profile 便携验收通过。不开放 High10/422/444、自定义 scaling matrix 或 HDR。
- [x] 有限 MOV `nclc` 抽帧扩展：独立 SPS range 与容器色彩一致性检查，实际 High MOV 八帧逐字节及 JPEG 回读；771 项全量和无 FFmpeg 三 profile 完整便携验收通过。不开放未知色彩或通用 MOV 支持。
- [x] Baseline 实际八帧像素和全 profile PPS 验收：774 项全量及无 FFmpeg 四 profile 完整便携验收通过；Baseline 不借用 CABAC/加权预测或 High transform 授权。
- [x] Windows 系统 remux 内部前置：有限压缩样本/VFR 精确时钟、附加音轨拒绝、源不变与禁止覆盖；776 项全量和新完整便携验收通过。不是公开 remux 或普通 metadata 保真证明。
- [x] 内部单轨 MP4 容器头保留：实际系统样本重组与完整 Core metadata/configuration/时间线回读，782 项全量通过；不是公开系统 remux 或跨容器 muxer。
- [x] 有限公开 Windows MP4 → MP4 remux：实际系统压缩样本、源容器头恢复、完整 metadata/configuration/逐 sample Core 后置验证与原子发布；784 项全量通过，仅 Experimental Baseline 单轨、无重排范围。
- [x] Windows 有限系统 JPEG 显式质量 0..100 与 CLI `--quality`：792 项新全量及无 FFmpeg 完整便携验收通过；默认路径字节不变、独立 DQT/源不变/错误无输出。不代表无损或 FFmpeg 质量刻度等价。
- [x] Windows 有限 Main/High 8-bit/4:2:0、无 B 帧 remux：794 项新全量和无 FFmpeg 完整便携验收通过，独立两 profile 样本与源头恢复、完整 metadata/样本/时间线验证；不开放自定义 scaling、重排、音轨或跨容器。
- [ ] 扩展统一 FFmpeg remux/裁剪的音轨、复杂 edits、容器与媒体 profile，并补齐三平台真实验收；各自补独立证据，不能靠丢字段或放宽时间线检查开放能力。桌面系统 remux 代码保留、不继续扩展。
- [x] 有界双 AAC-LC MP4→MP4 remux：新全量 **847 tests，826 执行成功、21 历史系统专项跳过，0 failures / errors**；真实不同采样率/声道/频率、三种轨道顺序及稀疏 ID，每轨配置/逐 sample/metadata/源字节与完整解码通过。第二音轨独立 recovery proof、三音轨及跨容器拒绝门禁保留；对应便携与三平台产物按本提交 Actions 核对，不借上一批结果。
- [x] Windows 有限系统无转码裁剪：803 项新全量和无 FFmpeg 完整便携验收通过；真正非零 IDR 选中 VFR 样本、时长表/offset 重建、requested/actual 与 key 重置、配置/样本/metadata 独立证明、系统完整解码。Exact 仅无编码可满足时可用，不开放 encoder、音轨、B 帧、MOV、复杂 edit/sample groups 或隐藏 preroll。
- [ ] 完善统一 FFmpeg 抽帧/裁剪/transcode 的业务 profile 与三平台证据。音轨/重排/复杂 edits 需各自证明；桌面系统适配代码保留，停止新增 Windows/macOS/Linux 系统媒体 API 工作。
- [ ] AAC priming/复杂原始 edits 与更多 codec/container：后续由 FFmpeg 处理并补独立同步/样本验收，不自写媒体处理器。**用户决定不编码音频**；需要音频重编码的 Exact 裁剪保持拒绝，不列入推进任务。
- [x] 显式有限 AVC/VFR 转码保留单/双 AAC：新全量 **852 tests，831 执行成功、21 历史系统专项跳过，0 failures / errors**；四轨道顺序、稀疏 ID、逐音轨 sample/configuration/时间线/metadata 与完整解码及新完整 Windows FFmpeg 便携验收通过。三音轨、未知 recovery/descriptor、音频裁剪和未授权/Strict 仍拒绝；整文件保真不提升为无损。更多 profile 仍在上一项 TODO。
- [x] Windows 有限 MOV 系统 Probe 扩展：实际测试、Windows 便携包与对应 CI 产物验收通过；不因此声称音频/HDR 或通用 MOV 支持。[微软格式表](https://learn.microsoft.com/en-us/windows/win32/medfound/supported-media-formats-in-media-foundation)列出 `.mov`，但本项目仍逐项限制并验证 decoder/profile。
- [x] 恢复 macOS ARM64 runner 前置条件与真实打包验收；下载同提交 CI 包，核对 artifact 与内层归档哈希、ARM64 启动器/运行时，本机 13 个 CLI 检查通过，Raw 视频字节一致、源未改变。不是 Apple 相册认证或 AVFoundation 实现。
- [ ] **L4 真机兼容验收（暂缓，待真实原片与设备证据）。** 后续补充未编辑厂商原片、设备型号、系统/相册版本、导入后的动态播放/声音/key 表现；不能由合成测试或自生成 round-trip 代替。
- [ ] 扩展真实 upstream/厂商原片 conformance、更多完整媒体/保留证明与不支持变体回归；持续记录每项能力的证据和边界。
- [x] Kotlin/Native Core 第一阶段：macOS ARM64、Windows x64、Linux x64 主库与各726项共享测试实际执行通过，库/报告独立上传；默认 JVM 构建不下载 Native 编译器。
- [x] Kotlin/Native 共享 CLI 层：参数与命令分派、无反射 JSON、平台注入的日志/计时，三平台各 34 项真实 Native 测试通过；JVM 入口复用同一实现。
- [x] Kotlin/Native 文件 IO/资产集合原子事务：三个宿主各 33 项真实文件/原素材闭环测试通过，预算与内容证明、提交恢复、未知文件保全均有回归；仅新目录、本地可信文件系统、串行调用，目录持久性不保证。
- [x] Kotlin/Native 纯协议 CLI：原生入口、冻结输入会话、整组输出与实验性原生程序打包；这是已完成的纯协议检查点，媒体接入另行验收。
- [x] Kotlin/Native 原生进程适配：三平台真实参数、管道、超时/取消与资源释放测试通过；不使用 Java Thread/ProcessBuilder。
- [x] Kotlin/Native 有限 FFmpeg 后端：三平台各 840 项测试、20 项归档 CLI 检查通过，含解码/抽帧/remux/音频 copy 裁剪/显式视频转码/组合 Create；产物已上传并独立核验，不扩大 profile 或宣称真机兼容。
- [ ] Kotlin/Native 后续阶段：清理迁移期 JVM/Java 桥接、对齐剩余 JVM 专项回归和默认发行路线；已暂停的历史系统媒体代码保留。不是移除 Gradle 编译所需 JDK，不提前引入 GUI。
- [ ] **最后推进移动端 Android/iOS**：增加移动 targets 与真实构建/测试产物；适配文件/Android Uri、原子事务、权限与资源生命周期；系统媒体 API 仅在此阶段接入移动端，逐项验证 probe/抽帧/裁剪/remux/条件转码及设备行为。当前未实现，桌面 FFmpeg/803 项 JVM 测试不代替移动验收。
- [ ] 未来 Compose UI：不属于当前 Core + CLI 交付范围，不提前引入 GUI 依赖。

### 诊断日志

排查异常时可使用 `LivePhoto detect --input 'livephoto.jpg' --log-level trace 2>trace.log`。默认不记录日志；debug 提供操作结果，trace 增加阶段进度、耗时及未捕获异常的有界调用栈。异常消息和命令参数不写入诊断，避免泄露素材路径或 metadata。已转为结构化 Core 错误的异常仅记录错误码和阶段，不保证拥有底层调用栈。独立 `--help` / `--version` 不产生诊断。

macOS 在解压目录使用实际入口（终端 CLI，无需双击 `.app`）：

```sh
./LivePhoto.app/Contents/MacOS/LivePhoto --version
./LivePhoto.app/Contents/MacOS/LivePhoto detect --input './livephoto.jpg' --log-level trace 2>trace.log
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

- `core/src/commonMain/`：公共模型、协议解析/写入、保留及原子操作逻辑。
- `core/src/jvmMain/`：文件 IO、可选 FFmpeg；Windows 系统 API adapter 为保留的历史代码，不参与默认发现。
- `cli/`：参数 → Request → Core → JSON，业务逻辑不放在 CLI。
- `scripts/`：验收、便携打包和 Windows 隔离 helper 配置。
- `reference/`：三个测试媒体，供协议与往返测试使用。
- `upstream/`：只读参考，不是项目依赖，不包含在发行包中；不复制其业务架构。

README 的信息组织参考 [Gradle](https://github.com/gradle/gradle/blob/master/README.md) 的入门/构建导航与 [scrcpy](https://github.com/Genymobile/scrcpy/blob/master/README.md) 的前提/下载/用法组织；命令、参数、平台状态和能力边界来自本仓库实际源码与验收记录。
