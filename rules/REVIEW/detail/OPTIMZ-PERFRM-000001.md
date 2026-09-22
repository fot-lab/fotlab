# 渲染管线性能审计总览 — 瓶颈归因、加速手段分级与待拍板项

- ID: OPTIMZ-PERFRM-000001
- Status: Observation
- Priority: P1
- Created: 2026-09-21
- Owner: —
- Related: `rules/REVIEW/detail/OPTIMZ-PERFRM-000002.md`（瓶颈归因）、`OPTIMZ-PERFRM-000003.md`（全分辨率预览）、`OPTIMZ-PERFRM-000004.md`（PNG 载荷）、`OPTIMZ-PERFRM-000005.md`（调参重跑全链路）、`OPTIMZ-PERFRM-000006.md`（Library 缩略图）、`OPTIMZ-PERFRM-000007.md`（原生侧算力未被利用）、`OPTIMZ-PERFRM-000008.md`（渲染后端路线）、`OPTIMZ-PERFRM-000009.md`（度量缺失）、`rules/REVIEW/detail/FOTLAB-RAWLER-000003.md`（superpixel 1/4 设计，未接线）、`rules/REVIEW/detail/FOTLAB-RAWLER-000004.md`（解码一次）、`rules/REVIEW/detail/FOTLAB-RAWLER-000008.md`（boost 语义）、`rules/DESIGN/detail/FOTLAB-PIPELN-000001.md`（FotRaw/FotDev IR）

> 本条目组为中文撰写（以往 `rules/**` 条目为英文）。是否要把这一条放宽写进 `rules/REVIEW.md` §General Rules 的第 1 条，见本文件 §Impact / Conflict 的 C5，**待人工确认**。

## Background & Goal

起因是"除了把 RapidRAW 的网页渲染搬进 Android WebView，还有什么办法能加速渲染管线"，前置假设是"跨越 FFI 传递比较慢"。

调研在**不改任何代码**的前提下覆盖了 `app/src/**`（Kotlin / Rust 绑定 / cxx 胶水）、`external/dnglab/rawler`、`external/RawAlchemyCpp` 与 `rules/**`，目标有三：

1. 验证"FFI 跨界是瓶颈"这个前提是否成立；
2. 列出除"换渲染后端到 WebView"之外可用的加速杠杆，按性价比与侵入性分档；
3. 把需要人工拍板的结构性取舍（minSdk、是否新增 JNI 层、是否引入 libomp、画质契约）单独拎出来，按项目规矩不自行择一。

结论拆成了 8 个子条目（`OPTIMZ-PERFRM-000002` … `000009`），本文件是它们的索引与背景。

## Finding

### 1. 前置假设不成立：FFI 本身不是瓶颈

UniFFI 0.28 的 Kotlin 绑定底层走 JNA（`app/build.gradle.kts:163`），单次跨界调用开销在 µs 级。真正消耗时间的是**一次调用要搬运的字节量**，以及**同一份像素被反复拷贝、deflate、再 inflate**——详见 `OPTIMZ-PERFRM-000002`。

### 2. 当前链路（Studio 每次参数变化都会整条重跑）

`app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioEngine.kt` 的 `reDevelop` / `setExposureEv` 每触发一次，下面 13 段全部执行一遍：

| # | 阶段 | 位置（相对仓库根目录） | 现状 |
|---|---|---|---|
| 1 | 整个文件读进 `ByteArray` | `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioEngine.kt:151`、`app/src/main/kotlin/io/github/fotlab/fotlab/media/RawlerFotlabDecoder.kt:25` | 整份 RAW 进 Java 堆 |
| 2 | 再复制一份进 `RawSource` | `app/src/binding/rust/rawler_fotlab/src/decode.rs:29` `RawSource::new_from_slice` | **第二次完整拷贝** |
| 3 | rawler decode | `external/dnglab/rawler` 各 `decompressors/*` | 部分格式 rayon 并行；CR2 / lossless NEF 串行（`DNGLAB-RAWLER-000005`） |
| 4 | rescale + `2^ev` 曝光 | `app/src/binding/rust/rawler_fotlab/src/develop.rs:211,229` | **单线程标量** |
| 5 | demosaic | rawler `PPGDemosaic` 等 | rayon 并行 |
| 6 | calibrate（WB + 3×3 矩阵） | `app/src/binding/rust/rawler_fotlab/src/calibrate.rs:136,159` | **单线程标量** |
| 7 | `crop_default` 逐行拷 | `app/src/binding/rust/rawler_fotlab/src/develop.rs:300` | **单线程** |
| 8 | rawalchemy grading（可选） | `external/RawAlchemyCpp/src/grading_fused.cpp:70` | `RA_USE_OPENMP` 未定义 → **单线程标量** |
| 9 | gamma / clip + RGBA 展开 | `app/src/binding/rust/rawler_fotlab/src/bound.rs:123,163` | **单线程标量** |
| 10 | PNG deflate | `app/src/binding/rust/rawler_fotlab/src/bound.rs:128,174` | 对整幅 RGBA8 做无损压缩 |
| 11 | `Vec<u8>` → Kotlin `ByteArray` | UniFFI + JNA（`app/build.gradle.kts:163`） | 一次整块拷贝 |
| 12 | Coil 解码 | `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioScreen.kt:248` — **未传 `.size(...)`** | 按原始分辨率解码 |
| 13 | 纹理上传 / 绘制 | `app/src/main/kotlin/io/github/fotlab/fotlab/ui/ZoomableImage.kt:203` | 全分辨率位图，最容易撞 `GL_MAX_TEXTURE_SIZE` |

### 3. 内存账（以 50 MP ≈ 8688×5792 为例，均为数量级估算）

- 线性 f32×3 中间 buffer：~600 MB（`app/src/binding/rust/rawler_fotlab/src/calibrate.rs:80` 的注释自己写了"~630 MB"）；
- RGBA8 展开：~200 MB；PNG 字节：数十 MB；
- Java `ByteArray` + Coil Bitmap：再数百 MB。

单次渲染峰值轻易破 1 GB —— Android 上 low-memory-kill 与 GC 抖动会参与"慢"的主观感受。

### 4. 加速杠杆（都不需要换渲染后端）

| 档 | 手段 | 子条目 |
|---|---|---|
| **T0** | 预览降分辨率（让后半段全部同比变快） | `OPTIMZ-PERFRM-000003` |
| **T0** | Coil 显式指定解码尺寸 | `OPTIMZ-PERFRM-000003` |
| **T0** | PNG 搬出交互路径 | `OPTIMZ-PERFRM-000004` |
| **T0** | 缓存"已显影 buffer"（`FotDev`）而非只缓存 `RawImage` | `OPTIMZ-PERFRM-000005` |
| **T0** | Library 用 RAW 内嵌预览出缩略图 | `OPTIMZ-PERFRM-000006` |
| **T0** | 源数据不要复制两次 | `OPTIMZ-PERFRM-000002` |
| **T1** | rayon 并行我们自己的三个全分辨率循环 | `OPTIMZ-PERFRM-000007` |
| **T1** | rawalchemy 的 OpenMP / Rust 侧切片并发 | `OPTIMZ-PERFRM-000007` |
| **T1** | release profile（LTO / codegen-units / NEON） | `OPTIMZ-PERFRM-000007` |
| **T2** | 换掉"字节数组过 FFI"：native 直填 Bitmap / DirectByteBuffer | `OPTIMZ-PERFRM-000004` |
| **T3** | GPU 后端（GLES3 着色器链；LUT 走 `GL_TEXTURE_3D`） | `OPTIMZ-PERFRM-000008` |

### 5. 关于"WebView 里画（RapidRAW 式）"

RapidRAW 快的原因是 **wgpu / GPU**（解码一次 → 常驻纹理 → 调参只改 shader uniform），不是"它是网页"。搬进 Android WebView 只会给已有链路**再加一跳**像素搬运（blob / base64，后者还膨胀 33%），外加一个 WebView 进程的内存与 GPU 上下文。它不解决本项目的瓶颈。完整评估见 `OPTIMZ-PERFRM-000008`。

## Impact / Conflict

以下五点属于结构性取舍，**按项目规矩不自行决定**，等人拍板：

- **C1 — minSdk 26 是硬约束**（`app/build.gradle.kts:40`）。AGSL `RuntimeShader`(33)、`RenderEffect`(31)、`Bitmap.wrapHardwareBuffer`(28)、`SharedMemory`(27) 全部不可用。上 GPU 后端要么自写 GLES3，要么抬 minSdk。
- **C2 — 是否允许在 UniFFI 之外新增一层 JNI**。`app/src/binding/kotlin/io/github/fotlab/fotlab_rawler/RawlerFotlabBridge.kt:14-19` 明确写着它是"唯一的跨语言边界"。native 直填 Bitmap / DirectByteBuffer 都需要打破这条规矩。
- **C3 — 是否引入 `libomp.so`**。OpenMP 路线会新增一个 .so（CI 的 jniLibs 拷贝步骤要跟着改），替代方案是 Rust 侧 rayon 切片并发调 `grade`。
- **C4 — 预览降分辨率的画质契约**。交互时用 1/4、导出时才全分辨率，需要产品确认。这与 `FOTLAB-RAWLER-000003` §C 已设计但未接线的 superpixel 1/4 是同一件事。
- **C5 — 文档语言**。`rules/REVIEW.md` §General Rules 第 1 条与 `rules/DESIGN.md` 第 1 条都要求 detail 文件用英文撰写，而本次是按"可以接受中文文档"的口头许可写成中文。**没有擅自修改那两处规则**，是否放宽请人工确认。

## Recommendation

按"先度量、再改"的顺序执行，**在实测量级出来之前不动 Tier 2 / Tier 3**：

1. **先打点**（`OPTIMZ-PERFRM-000009`）：decode / demosaic / calibrate / grade / png-encode / ui-display 六个断面，真机跑一张 45–60 MP 的 RAW。本报告的所有量级判断都是估算，必须实测确认。
2. **T0**：降分辨率 + Coil 指定 size（收益最大、改动最小）。
3. **T0**：缓存已显影 buffer —— 为所有"只重跑一个阶段"的优化铺路，也正好落在 `rules/DESIGN/detail/FOTLAB-PIPELN-000001.md` 的 `FotDev` 契约上。
4. **T0**：Library 内嵌预览缩略图。
5. **T1**：并行化 + 编译选项。注意分辨率降下来后这部分的相对收益会被削弱。
6. **T2 / T3**：属于架构变更，先走人工确认。

## Change History

- 2026-09-21 — 创建。只读调研（未改动任何代码），把"FFI 跨界慢"的前提纠正为"像素搬运 / 拷贝 / PNG 编解码慢"，并拆出 `OPTIMZ-PERFRM-000002` 至 `000009` 共 8 个子条目；同时新增 `PERFOR` 分类。全条目按人工许可使用中文撰写。
