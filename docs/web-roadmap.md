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
- [ ] 有限 Repair：先 dry-run，再显式提交安全修改，报告无法修复/歧义问题。
- [ ] 关键照片 metadata 写入；与视频抽帧、替换封面分开。
- [ ] 将不依赖文件系统、真实编码素材或 FFmpeg 的公共协议回归接入 JS；不伪造平台 actual、不静默跳过后端测试。
- [ ] 为上述 API 增加真实浏览器正向、错误、不可变输入、多资产和字节一致性回归；主线程与 Worker 分开验证。
- [ ] 后续按需将已验证能力接入共享 UI，不另写网页业务逻辑。

## 后续、尚未实现

- [ ] 不移动 EXIF 的固定长度 XMP 槽位优化及独立保留证明。当前 Core 对 XMP 调整会移动未验证 EXIF 的布局返回 `UNSAFE_METADATA_REWRITE`，Web 保留此拒绝，不删除 EXIF 或增加丢失授权。新建 OPPO 文件转 Google 的两个方向受此限制；同目标原样复制与无此布局依赖的转换分开验证。
- [ ] 大文件随机访问、减少内存副本、UI 后台 Worker 和明确的取消语义。当前不是任意大文件流式实现。
- [ ] 浏览器媒体后端：抽帧、裁剪、remux、transcode。不能调用桌面 FFmpeg 进程，不能据此默认转码或增加音频编码。
- [ ] Kotlin/Wasm target 与独立验收；Compose 图形运行时的 Wasm 文件不代表 Core 已实现 Kotlin/Wasm。

Apple 新写入仍暂停，移动端系统媒体后端仍是单独 TODO。输入不上传服务器；仓库和 CI 不增加手机/reference 原片或其衍生成品。测试用独立合成素材，报告不附带媒体。

## 本轮状态

只读检查/分析/关键照片读取、Clean Split、Convert 的 JS 适配和 TypeScript 声明已实现。接口可用不表示任意容器变体均受支持，也不表示已在共享 UI 开放操作按钮。

新增接口对应的功能库检查扩展为 50 项：含三个目标的结构化检查、Clean Split 幂等性、同目标字节不变、四个实际跨协议转换方向、两个 EXIF 安全拒绝方向，以及错误/无输出、输入不变和 Worker 回归。共享 UI 的 20 项操作检查另行保留。**测试是否通过，以对应提交的 [Web CI](https://github.com/ohyooo/LivePhoto/actions/workflows/web.yml) 为准；不能用基础阶段的旧 25＋20 项结果代替。** 公共协议 commonTest 的 JS 迁移、Repair、关键照片写入及媒体后端仍未完成。
