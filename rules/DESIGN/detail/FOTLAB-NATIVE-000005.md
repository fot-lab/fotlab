# RawTherapee DCP/LCP 解析绑定 + develop 流水线 Camera/Lens Profile 校正

- ID: FOTLAB-NATIVE-000005
- Status: Draft
- Priority: P1
- Created: 2026-09-28
- Owner: —
- Related: `rules/DESIGN/detail/FOTLAB-NATIVE-000004.md`（纯 Rust 移植 RT 解马赛克——本文档**刻意不采用**其"避开 GPL C++ 链接"路线，改为链接 RT 解析器）、`rules/DESIGN/detail/FOTLAB-NATIVE-000001.md`（`external/` 只读 + 第一方绑定代码归属 `app/src/binding/`）、`app/src/binding/cxx/rawtherapee_fotlab/README.md`（既有 RT cxx 绑定，本功能**直接在其内扩展**，不再新建 crate）、`rules/STRUCT/detail/EXIFTL-SURVEY-000001.md`（DCP/LCP 既有调研：DCP 为 TIFF/DNG、LCP 为 ZIP 内 XML）、`rules/DESIGN/detail/FOTLAB-PIPELN-000001.md`（loader/develop/process 流水线）、`rawler_fotlab/src/develop.rs`（`develop_image` 流水线，本功能的接入点）。

> **Note on naming**：`FOTLAB-` 项目码 + `NATIVE` 类别（原生集成），序号 `000005`（`NATIVE` 下个序号）。与 `FOTLAB-NATIVE-000004` 同用 `NATIVE` 类别——二者是同一 RT 能力的**两条相反路线**：000004 纯 Rust 重写（不链接 GPL C++），本文档直接绑定 RT 的 C++ 解析器（链接 `librtengine`）。**本功能不新建 crate**，直接在既有的 `rawtherapee_fotlab` cxx crate 内扩展（其已构建 `librtengine.a`、已含 DCP/LCP 解析所需符号）。

> **Scope**：**规划 + 分步实施**。本文档定义 (a) 在既有 cxx crate `rawtherapee_fotlab` 内**扩展** DCP/LCP 解析（新增 shim + `dcp.rs`/`lcp.rs`），(b) 它如何调用 RT 的 `DCPProfile`/`LCPProfile` 把解析结果抽成纯 Rust 参数结构、(c) 在 `develop.rs` 流水线的接入点（exposure 之前、CFA mosaic 空间）、(d) Kotlin 侧两个新功能（Camera Profile / Lens Profile based correction）的参数契约、(e) 默认关闭 + 短路跳过的门控、(f) 分步落地与验收。**不含**把 RT 的 `DCPProfile::apply` / `LCPMapper` 原样搬到 Rust——我们**只复用其解析**，校正逻辑在 Rust 侧实现（理由见 §Open Questions OQ1/OQ2）。

## Background & Goal

**现状（既有能力）**：RawTherapee 的 `external/RawTherapee/rtengine/dcp.cc`+`dcp.h` 与 `lcp.cc`+`lcp.h` **已经完整实现** DCP（DNG 相机剖面）与 LCP（Adobe 镜头剖面）的解析：
- `DCPProfile` 构造函数用**自研 TIFF/IFD walker**（不依赖 libtiff/exiv2）读 DNG 标签：`ColorMatrix1/2`、`ForwardMatrix1/2`、`ProfileToneCurve`、`ProfileHueSatMap(DATA_1/DATA_2)`、`ProfileLookTable`、`BaselineExposureOffset` 等；数据模型在 `dcp.h:113-136` 已清晰定义。
- `LCPProfile` 构造函数用 **expat 直接吃 XML**（`g_fopen` + `XML_Parse`，`lcp.cc:180-227`）；**不自带解压**——假定输入已是 XML（真正的 ZIP 型 `.lcp` 需先 unzip，见 OQ5）。抽取透视/暗角/CA 模型参数。

**目标**：既然 RT 已能解析，**不再重构解析逻辑**（与 000004 的纯 Rust 重写路线相反），而是：
1. 在既有 cxx crate `app/src/binding/cxx/rawtherapee_fotlab` 内**扩展**，新增 C++ shim 调用 RT 的 `DCPProfile`/`LCPProfile` 把解析结果抽成纯 Rust 参数结构（`DevelopParams` 可携带的字段）；
2. 在 `rawler_fotlab` 流水线里**调用**这些校正，接通 Kotlin 侧两个新功能：**Camera Profile based correction**（DCP）与 **Lens Profile based correction**（LCP）；
3. 调用位置 **exposure 之前、作用 CFA mosaic 空间**；**允许传入参数、短路跳过、默认不启用，只有参数明确启用才启用**。

**与既有决策的冲突（必须显式记录）**：`FOTLAB-NATIVE-000004` R.../C2 明确"纯 Rust，不链接 GPL C++、不加 submodule hook；禁用中的 `binding/cxx/rawtherapee_fotlab` 保持禁用"。本文档**重新启用 cxx 链接 RT**（与 `rawtherapee_fotlab` 同路线），因为用户指令明确"既然 RT 已能解析就不重构"——直接复用其解析器。许可证影响见 R8/C2。

## Requirement

### R1 — 扩展既有 cxx crate（不新建 crate）

- **直接在 `app/src/binding/cxx/rawtherapee_fotlab/` 内扩展**（不再新建 `rawtherapee_deprofile`；`rawtherapee_fotlab` 已能构建、已链接 `librtengine.a`，故 dcp.cc/lcp.cc 解析所需符号已可用）。`FOTLAB-NATIVE-000001` R1：第一方绑定代码属 `app/src/binding/`，本扩展仍落在该目录下。
- **解析为主、按需复用 RT 应用**：cxx 侧调用 `new DCPProfile(path)` / `new LCPProfile(path)` 把字段**读入纯值结构**（DCP 主要供后续计算/匹配，其色彩矩阵等"应用"留 `calibrate`/B5）；**LCP 的暗角+畸变因颜色无关、可在 CFA 空间直接复用 RT `LCPMapper::processVignette`/`correctDistortion` 应用**（见 D3）。CA 等 RGB 专属部分跳过/留 B5。
- `rawtherapee_fotlab` 已是 `rawler_fotlab` 的 **path 依赖**（cxx），`rawler_fotlab` 仍是唯一出品的 `librawler_fotlab.so`；新增的 `parse_dcp`/`parse_lcp` 作为该 crate 的公开函数直接被 `rawler_fotlab` 调用，无需新增依赖边。
- crate-type = `["cdylib", "staticlib"]` 与链接 `librtengine.a` 沿用现有 `Cargo.toml`/`build.rs`，**不改动**。

### R2 — 复用既有 RT 构建基础设施（不新增 submodule hook、不新建 crate）

- `dcp.cc`/`lcp.cc` 属于 `rtengine` 核心，已随 `rawtherapee_fotlab` 的 `librtengine.a` 一并编译。本扩展**不新建 crate、大概率不需要新的 submodule hook**——只需在现有 `rawtherapee_fotlab` 内新增 C++ shim 调用既有的 `DCPProfile`/`LCPProfile` 公开类。
- 现有 `build.rs` 已用 `RAWTHERAPEE_SRC` / `RAWTHERAPEE_GEN` / `RAWTHERAPEE_LIB` 三个环境变量编译 `cxx/rt_demosaic_shim.cc` 并链接 `librtengine.a`；本扩展仅在其编译列表**追加** `cxx/rt_deprofile_shim.cc`（及其传递依赖 lcms2/exiv2/fftw3/png/z/glibmm/OpenMP，沿用既有链接），不动构建框架。
- 若 `DCPProfile`/`LCPProfile` 符号未导出（OQ3），先确认 `librtengine.a` 是否真编了 `dcp.cc`/`lcp.cc`；真缺则扩展既有 RT 构建而非新增 hook 文件。

### R3 — 解析→Rust 参数结构（数据契约）

cxx 桥只传**纯值**（无 `Glib::ustring`/RT 对象跨 FFI）。两个 Rust 结构（定义在 `rawtherapee_fotlab/src/dcp.rs` 与 `lcp.rs`）：

> **范围拆分（用户决策）**：
> - **DCP**：本需求聚焦**正确读出内部型号与若干色彩矩阵**（`ColorMatrix1/2`、`ForwardMatrix1/2`、`BaselineExposure`、以及 HSD/Look 数据），供**手工指定或自动匹配**后续计算（`calibrate` 矩阵注入、DCPStore 式按 make/model 匹配）。**DCP 全程只读**：包括 CFA 阶段的 `BaselineExposure` 标量也只是**读出**，是否应用（以及在哪应用）**完全交给用户决定**（见 `CameraProfileParams` 各 `apply_*` 开关）；色彩矩阵/HSD/Tone/Look 同理留 `calibrate`/B5。
> - **LCP**：本需求聚焦**颜色无关**的**暗角 + 几何畸变**矫正，二者理论上是颜色无关的，可在 **CFA mosaic 空间复用 RT 的 `LCPMapper::processVignette`（单通道 RAW 路径）/ `correctDistortion`** 直接应用；**CA（逐通道）跳过/留 B5**。

```rust
// DCP —— 与 dcp.h:113-136 字段对齐
pub struct DcpParams {
    pub has_color_matrix: [bool; 2],
    pub color_matrix: [[f64; 3]; 3],      // ColorMatrix1/2（无则单位阵占位）
    pub has_forward_matrix: [bool; 2],
    pub forward_matrix: [[f64; 3]; 3],    // ForwardMatrix1/2
    pub temperature: [f64; 2],
    pub light_source: [i16; 2],
    pub will_interpolate: bool,
    // —— 自动匹配 / 手工指定所需标识（DCP 内嵌 DNG 标签，供 DCPStore 式匹配）——
    pub unique_camera_model: Option<String>,  // UniqueCameraModel (0xC614)
    pub camera_model: Option<String>,          // CameraModel (0xC615)
    pub make: Option<String>,
    pub model: Option<String>,
    pub has_tone_curve: bool,
    pub tone_curve: Vec<(f32, f32)>,      // (x,y) 控制点
    pub has_baseline_exposure: bool,
    pub baseline_exposure_offset: f64,
    pub has_hue_sat_map: bool,
    pub hue_sat_divisions: (u32, u32, u32), // hue/sat/val
    pub hue_sat_deltas: Vec<HsbDelta>,   // hue_shift/sat_scale/val_scale
    pub has_look_table: bool,
    pub look_table: Vec<HsbDelta>,
}
// LCP —— 与 lcp.h:51-90 / LCPPersModel 对齐（多帧插值）
pub struct LcpParams {
    pub profile_name: String,
    pub camera: String, lens: String, is_raw: bool, is_fisheye: bool,
    pub sensor_format_factor: f32,
    pub pers_model_count: usize,
    // 每帧：focal/focusDist/aperture + 透视/CA/暗角模型参数
    pub models: Vec<LcpModel>,
}
pub struct LcpModel {
    pub focal_len: f32, focus_dist: f32, aperture: f32,
    pub perspective: LcpModelCommon,  // param[5], scale_factor, img_center, foc_len
    pub chrom_rg: LcpModelCommon, chrom_g: LcpModelCommon, chrom_bg: LcpModelCommon,
    pub vignette: LcpModelCommon,
}
```

### R4 — 流水线接入点：exposure 之前、CFA mosaic 空间

- 接入 `rawler_fotlab/src/develop.rs` 的 `develop_image`，在 **`apply_exposure` 之前**（当前 `develop.rs:419` 之前）插入 deprofile 阶段，作用在 `pixels`（`Vec<f32>` 单通道、**0..1 线性**、CFA mosaic）。
- 阶段签名（Rust 侧，作用在 mosaic）：
  ```rust
  fn apply_deprofile(
      mut pixels: Vec<f32>, w: usize, h: usize,
      cfa: Option<&CFAConfig>,
      dcp: Option<&DcpParams>, lcp: Option<&LcpParams>,
      lens_meta: &LensMeta,          // 焦距/光圈/对焦距离，来自 raw 或 Kotlin
  ) -> Vec<f32>;
  ```
- **短路跳过**：`dcp` 与 `lcp` 任一为 `None` ⇒ 该子阶段恒等返回（零成本，对齐 `exposure_ev`/`ca`/`denoise_strength` 的 `Option`+`None`=skip 模式）。两个都 `None` ⇒ 整阶段恒等。

### R5 — 默认关闭 + 显式启用门控（硬要求）

- `DevelopParams`（uniffi `Record`）新增两个 `Option` 字段，**默认 `None`**（即关闭、恒等）：
  ```rust
  #[uniffi(default = None)]
  pub camera_profile: Option<CameraProfileParams>,
  #[uniffi(default = None)]
  pub lens_profile: Option<LensProfileParams>,
  ```
- Kotlin **显式**传入（含 profile 文件路径 + 启用标志）才启用；不传 ⇒ 整阶段不运行。`rawler_fotlab` 在 `develop_image` 里把这两个字段解析成 `Option<&DcpParams>` / `Option<&LcpParams>`（需先调 `rawtherapee_fotlab::parse_dcp/lcp`），`None` 直接跳过。
- **编译期无需新 feature 门控**：`rawtherapee_fotlab` 已受 RT submodule hook 条件构建门控，本扩展只是其内部新增模块；**默认关闭完全由运行期参数 `None` 保证**（R5 主机制）。保留 `DevelopParams` 两字段默认 `None`，uniffi 稳定。

### R6 — 允许传入参数（参数契约）

```rust
pub struct CameraProfileParams {
    pub profile_path: String,        // DCP 文件路径（Kotlin 侧提供 / 后续自动匹配）
    pub preferred_illuminant: i32,   // 0/1/2；默认 0（自动）
    pub apply_tone_curve: bool,      // 默认 true
    pub apply_look_table: bool,      // 默认 true
    pub apply_baseline_exposure: bool, // 默认 true
    pub apply_hue_sat_map: bool,     // 默认 true
}
pub struct LensProfileParams {
    pub profile_path: String,        // LCP 文件路径
    pub focal_length: f32,
    pub focus_distance: f32,
    pub aperture: f32,
    pub apply_vignette: bool,        // 默认 true
    pub apply_distortion: bool,      // 默认 true
    pub apply_ca: bool,              // 默认 true
}
```

### R7 — Kotlin 两个新功能

- **Camera Profile based correction**：Kotlin 在 `RawlerFotlabDecoder.kt` 构造 `DevelopParams` 时填充 `cameraProfile`（路径来自用户选择 / 相机自动匹配）。`StudioEngine.kt` 暴露开关。
- **Lens Profile based correction**：同上填充 `lensProfile`（路径 + 焦距/光圈/对焦距离，可取自 EXIF 或用户覆盖）。
- 两功能**独立门控**：任一 `None` 即对应校正不运行。UI 文案走 string resources（`FOTLAB-UIXDES-000003` 的 i18n 规则）。

### R8 — GPL 合规（关键）

- 链接 `librtengine` ⇒ 本绑定二进制成为 **GPL v3 衍生作品**（与 `rawtherapee_fotlab/README.md` §Licensing 一致）。本项目 `LICENSE.md` 同为 GPL-3.0，许可兼容，但**必须保留 RT 版权与许可声明**（crate 级 NOTICE + 每个 shim 文件头）。
- 与 `FOTLAB-NATIVE-000004` R8 的区别：本文档是**链接** GPL C++（非纯 Rust 重写），故 NOTICE 随 `librtengine` 携带的 RT 源码归因，不只是"ported from"。

## Design

### D1 — 扩展布局（在 `rawtherapee_fotlab` 内新增文件）

```
app/src/binding/cxx/rawtherapee_fotlab/        # 既有 crate，已构建 librtengine.a
├── Cargo.toml                 # 已存在；不动（crate-type cdylib+staticlib；cxx dep；GPL v3）
├── build.rs                   # 已存在；在现有编译列表追加 cxx/rt_deprofile_shim.cc
├── README.md                  # 已存在；补记"新增 deprofile 解析（DCP/LCP）"
├── cxx/
│   ├── rt_demosaic_shim.h/.cc # 已存在（RT demosaic 胶水）
│   ├── rt_deprofile_shim.h    # 新增：DCP/LCP 解析 C ABI（纯值结构，无 Glib::ustring/RT 对象）
│   └── rt_deprofile_shim.cc   # 新增：adapter —— new DCPProfile/LCPProfile(path) → 读字段填入结构
└── src/
    ├── lib.rs                 # 已存在；在现有 cxx bridge 追加 deprofile 切片 + re-export
    ├── dcp.rs                 # 新增：DcpParams + parse_dcp(path)->DcpParams
    ├── lcp.rs                 # 新增：LcpParams + parse_lcp(path)->LcpParams
    └── error.rs               # 新增：DeprofileError
```

- `rt_deprofile_shim.cc` 不复制 RT 算法，只**调用** `DCPProfile`/`LCPProfile` 的公开构造与 getter（如 `getHasToneCurve()`、`getIlluminants()`、`isValid()`，以及读 `color_matrix_*` 等私有字段——见 OQ3 是否需要把字段改成可读 / 加 getter）。
- 解析路径若含 ZIP 型 LCP，shim 或 Rust 预步骤需先 **unzip** 取内部 XML（OQ5）；RT 的 `LCPProfile` 本身只吃 XML。

### D2 — 与 `develop.rs` 的集成（数据流）

```
apply_scaling → take_scaled_pixels → pixels (CFA mosaic, 0..1 f32)
   │
   ▼  [NEW] deprofile(pixels, w, h, cfa, dcp, lcp, lens_meta)   ← exposure 之前
   │        dcp/lcp 为 None ⇒ 恒等（短路）
   ▼
apply_exposure → denoise → dehaze → ca → demosaic → calibrate → crop → clip
```

- 解析时机：Kotlin 传入 `camera_profile`/`lens_profile` 时，`rawler_fotlab` 在 `develop_image` 起始处（或 `develop()` 入口）调 `rawtherapee_fotlab::parse_dcp/lcp(path)` 得到 `DcpParams`/`LcpParams`，再作为 `Option` 传入 deprofile 阶段。
- deprofile 阶段本身**纯函数**、`None`=恒等，故未启用时零成本。其中 **LCP 暗角/畸变直接调 RT `LCPMapper::processVignette`/`correctDistortion` 作用在 mosaic 上**（颜色无关，受 `apply_vignette`/`apply_distortion` 门控）；**DCP 全程只读**——`BaselineExposure` 等仅是读出值，仅当用户 `apply_baseline_exposure=true` 才在 CFA 阶段 `×= 2^offset`，其色彩矩阵/HSD/Tone/Look 不在此阶段消费（留 `calibrate`/B5）。

### D3 — 校正分解（CFA 空间 vs RGB 空间）

DCP/LCP 的语义**不全在 CFA mosaic 空间成立**（见 OQ1/OQ2）。按"用户 directive：exposure 之前、CFA mosaic"的约束，把校正**分解为两段**：

| 子操作 | 是否 CFA 成立 | 本需求处理 |
|------|------|------|
| **DCP 内部型号 / 色彩矩阵读出** | n/a（解析） | **B1 解析进 `DcpParams`**（含 `unique_camera_model`/`camera_model`/`make`/`model` + `ColorMatrix1/2`/`ForwardMatrix1/2`/`BaselineExposure`/HSD/Look）；供手工指定 + 自动匹配 + `calibrate` 注入（下游） |
| DCP `BaselineExposureOffset`（标量线性缩放） | ✅ 每 photosite 乘常数 | **读出**进 `DcpParams.baseline_exposure_offset`；CFA 阶段**仅当用户 `apply_baseline_exposure=true` 时**才 `×= 2^offset`（默认 true，用户决定），与 DCP 其他子操作一致"只读 + 用户决定" |
| DCP `ColorMatrix`/`ForwardMatrix`（cam→XYZ） | ❌ 需 RGB | 解析提取 → **下游 `calibrate` 矩阵注入**（非 CFA 应用；与 `FOTLAB-NATIVE-000004` D4 同槽） |
| DCP `ToneCurve` / `HueSatMap` / `LookTable` | ❌ 需 RGB/HSL | 解析提取 → **B5 post-demosaic RGB 阶段**（见 OQ1） |
| **LCP 暗角**（位置乘性缩放） | ✅ 位置相关、颜色无关 | **CFA 阶段，复用 RT `LCPMapper::processVignette`（单通道 RAW 路径）** |
| **LCP 几何畸变**（warp） | ✅ 几何、颜色无关 | **CFA 阶段，复用 RT `LCPMapper::correctDistortion`** |
| LCP CA（色差） | ❌ 逐通道 | **跳过 / B5 RGB 阶段**（不调 RT `correctCA`） |

→ 第一版（满足"exposure 之前、CFA 空间 + 默认关闭"）落地：**DCP = 全程只读（B1 读出型号+矩阵+baseline 标量），baseline 是否应用由用户 `apply_baseline_exposure` 决定**；**LCP = CFA 里复用 RT 暗角+畸变**（颜色无关，直接 apply，受 `apply_vignette`/`apply_distortion` 门控）。DCP 不自动施加任何东西；LCP 的颜色无关算子复用 RT。DCP 色彩矩阵/HSD/Tone/Look 与 LCP CA 的应用留作下游/B5。

### D4 — 与 Kotlin 的集成

- `RawlerFotlabDecoder.kt`：在构造 `DevelopParams` 处新增 `cameraProfile`/`lensProfile` 两个可选字段；默认不传（关闭）。
- `StudioEngine.kt` / UI：两个独立开关（Camera Profile / Lens Profile based correction）；开启时填路径与参数。
- `RawlerFotlabBridge.kt`：uniffi 生成的 `DevelopParams` 已含新字段，无需手改（仅 Kotlin 侧赋值）。

### D5 — 错误处理与短路

- 解析失败（文件不存在 / 非 DCP/LCP / RT 返回 `!isValid()`）：`rawler_fotlab` 侧**降级为关闭该子阶段**（记日志 + 返回恒等），不使整次 develop 失败——与 `FOTLAB-NATIVE-000004` D4-5 "回落日志"同纪律。
- 几何/CA 等未实现子操作：对应 `apply_*` 标志为 false 时跳过。

## Phases（分步实施）

每批**一次到位**、可独立评审回退；门控（默认关闭）贯穿所有批次。

| 批次 | 内容 | 出口标准 |
| --- | --- | --- |
| **B0 骨架** | 在 `rawtherapee_fotlab` 内扩展：`cxx/rt_deprofile_shim.cc` + `src/{dcp,lcp,error}.rs` + `lib.rs` bridge 切片 + `build.rs` 追加编译项；`DevelopParams` 两字段（`None` 默认，uniffi 稳定） | crate 编译通过（CI native job，复用既有 librtengine.a 链接） |
| **B1 DCP 解析+接入** | cxx shim 抽 `DCPProfile` 全字段 → `DcpParams`；`parse_dcp(path)`；`camera_profile` 字段接入 `develop_image`，`None`⇒恒等；Kotlin 可传路径 | 用样例 DCP 解析出 `ColorMatrix`/`BaselineExposure` 等字段，单测断言非空/合理；关闭时零成本 |
| **B2 LCP 解析+接入** | cxx shim 抽 `LCPProfile` 模型参数 → `LcpParams`（含 per-model `perspective`/`vignette`/`chrom_*`）；`parse_lcp(path)`（含 unzip 取内部 XML，OQ5）；`lens_profile` 字段接入；Kotlin 可传路径+焦距/光圈/对焦 | 样例 LCP 解析出 `pers_model_count`/模型参数；关闭时零成本 |
| **B3 CFA 空间应用（复用 RT）** | 实现 deprofile 阶段 CFA 应用：**LCP 暗角 + 畸变直接复用 RT `LCPMapper::processVignette`（单通道）/ `correctDistortion`** 作用在 mosaic 上（构造 `LCPMapper` 时 `useCADistP=false`，CA 不调，受 `apply_vignette`/`apply_distortion` 门控）；**DCP 为只读**，仅当用户 `apply_baseline_exposure=true` 时对 mosaic `×= 2^offset`，否则仅透传解析值；短路跳过 | 对平场图：暗角使四角按模型衰减、畸变使网格对齐、baseline 使整幅线性平移；`None`/未启用对应开关时输出逐像素等于输入 |
| **B4 Kotlin 双功能 UI** | `StudioEngine`/`RawlerFotlabDecoder` 暴露两个开关并填 `DevelopParams`；string resources 文案 | UI 可独立开关两功能；默认关闭；开启并传路径后端到端 develop 出图（含 baseline/暗角效果） |
| **B5（决策后）RGB 空间补全** | 依 OQ1/OQ2 决策：DCP `ColorMatrix`→`calibrate` 矩阵注入；DCP `ToneCurve`/`HueSatMap`/`LookTable` + LCP 几何/CA → post-demosaic RGB 阶段 | 待决策后定出口标准 |

## Constraints

- C1 — `external/RawTherapee` 只读，**不产生 `.patch`**；复用 `rawtherapee_fotlab` 既有 hook 构建 `librtengine.a`。
- C2 — 链接 GPL C++（`librtengine`）⇒ 本绑定二进制为 GPL v3 衍生作品；保留 RT 版权/许可声明（R8）。与 `FOTLAB-NATIVE-000004` C2 路线相反，系用户明确指令。
- C3 — **默认关闭、显式启用**为硬要求（R5）：`DevelopParams` 两字段默认 `None`（运行期门控）；编译期不另设 feature（crate 已受 RT hook 条件构建门控）。
- C4 — **禁止本地编译**（`rules/ACTION.md`）：验证只走云 CI（push → 读日志）。
- C5 — 接入点在 **exposure 之前、CFA mosaic 空间**（R4/D2），满足用户 directive。
- C6 — `None`/未启用 ⇒ 短路恒等，零成本（R4/R5/D5）。

## Impacted Modules

- `app/src/binding/cxx/rawtherapee_fotlab/{Cargo.toml, build.rs, README.md, cxx/rt_deprofile_shim.{h,cc}, src/{lib.rs,dcp.rs,lcp.rs,error.rs}}`（在既有 crate 内扩展）
- `app/src/binding/rust/rawler_fotlab/{src/develop.rs, src/lib.rs}`（`DevelopParams` 两字段 + deprofile 阶段接入；Cargo.toml 已依赖 `rawtherapee_fotlab`，无需改依赖边）
- Kotlin：`RawlerFotlabDecoder.kt`、`StudioEngine.kt`、`RawlerFotlabBridge.kt`、string resources
- CI（native 构建复用既有 `librtengine` 链接，无需新 job；`.md` 不触发，代码触发）

## Open Questions

- **OQ1 — DCP 的 RGB 空间部分如何落（决策点）**：`ColorMatrix`/`ForwardMatrix`（cam→XYZ）、`ToneCurve`、`HueSatMap`、`LookTable` 本质上需要 RGB 三通道，无法在 CFA mosaic/pre-exposure 正确应用。建议：ColorMatrix 注入 `calibrate` 的 cam→working 矩阵（与 000004 D4 同槽）；ToneCurve/HueSatMap/LookTable 放到 post-demosaic RGB 阶段（B5）。需确认这是否符合"Camera Profile based correction"的预期范围，还是第一版只做 baseline exposure。
- **OQ2 — LCP 范围（已部分拍板）**：用户确认 LCP 聚焦**颜色无关的暗角 + 几何畸变**，二者在 CFA 空间成立、可复用 RT `processVignette`/`correctDistortion` 直接 apply（B3）。**CA（逐通道）跳出 CFA 范围**，留 B5 RGB 阶段（不调 RT `correctCA`）；v1 接受"镜头校正 = 暗角 + 畸变"，CA 后续补。
- **OQ3 — `librtengine.a` 是否真含 `dcp.cc`/`lcp.cc`（已确认 2026-09-28）**：`rtengine/CMakeLists.txt` 第 32 行 `dcp.cc`、第 88 行 `lcp.cc` 均在源清单中，确认编进 `librtengine.a`，`DCPProfile`/`LCPProfile` 符号可链接。私有字段访问采用 **header-only inline getter**（`dcp.h` 加 `getColorMatrix1/2`/`getForwardMatrix1/2`/`getHasColorMatrix1/2`/`getHasForwardMatrix1/2`/`getBaselineExposureOffsetValue`；`lcp.h` 加 `getProfileName`/`getCamera`/`getLens`/`getIsRaw`/`getIsFisheye`/`getSensorFormatFactor`/`getPersModelCount`），属 out-of-band 头文件改动、不碰 `.cc`、无需重编 `librtengine.a`（字段布局不变），与既有 demosaic hook 实践一致（README 已记）。注意 `DCPProfile` 不存 make/model，自动匹配见 OQ4。
- **OQ4 — profile 自动匹配（已列为第一版关注点）**：用户明确要"自动匹配后续计算"。需在 DCP 内提取相机标识（`unique_camera_model`/`camera_model`/`make`/`model`，OQ3 需确认 getter/标签读取）以支持 DCPStore 式按 make/model 匹配标准剖面；第一版至少**解析并暴露**这些标识，`rawler_fotlab` 侧可据 raw 的 make/model 选最接近的 DCP（匹配算法留后续，但数据结构先就位）。LCP 自动匹配（按镜头+焦距）同理，待定。
- **OQ5 — LCP 的 ZIP 外壳**：RT 的 `LCPProfile` 直接吃 XML，不解压（前期 EXIFTL 调研已确认 LCP 为 ZIP 内 XML）。对真实 ZIP 型 `.lcp`，需在 shim 或 Rust 预步骤 **unzip** 取内部 XML（可借系统 `unzip` / 或 Rust `zip` crate 在 `rawler_fotlab` 侧先解）。需确定解压责任落在哪一层。

## Change History

- 2026-09-28 — 初始规划。确立：新增 cxx 绑定 `rawtherapee_deprofile`，**链接** RawTherapee 的 `DCPProfile`/`LCPProfile`（复用 `rawtherapee_fotlab` 的 `librtengine.a` 构建，不新增 submodule hook），**只解析、不应用**——校正逻辑在 Rust 侧 `rawler_fotlab` 流水线实现（与 `FOTLAB-NATIVE-000004` 的纯 Rust 重写路线相反，依用户"RT 已能解析就不重构"指令）。接入点 = `develop_image` 中 **exposure 之前、CFA mosaic 空间**；`DevelopParams` 新增 `camera_profile`/`lens_profile` 两 `Option` 字段，**默认 `None`、显式启用、短路恒等**。Kotlin 侧两个新功能：Camera Profile / Lens Profile based correction。校正按 CFA 空间（baseline exposure + 暗角）与 RGB 空间（color matrix/tone/hue-sat/几何/CA，B5 决策后补）分解。Filed as `FOTLAB-NATIVE-000005`；row appended to `rules/DESIGN/index.md`。
- 2026-09-28 — **rev 2：改为复用既有 `rawtherapee_fotlab`，不新建 `rawtherapee_deprofile` crate**（用户指令：既然 `rawtherapee_fotlab` 已经能构建，就直接在其内扩展）。据此修订 R1（扩展既有 crate，非新建兄弟目录）、R2（仅 `build.rs` 编译列表追加 shim，不动链接框架）、R3/R5/D2（路径与调用名改为 `rawtherapee_fotlab`）、D1（布局改为在 `rawtherapee_fotlab` 内新增文件）、Phases B0、C3（编译期不设 feature，默认关闭由运行期 `None` 保证）、Impacted Modules（改为既有 crate 内扩展）。核心结论不变：只复用 RT 的 DCP/LCP **解析**、校正逻辑在 Rust 侧实现；接入点仍在 exposure 之前、CFA mosaic 空间；默认关闭 + 短路恒等。
- 2026-09-28 — **rev 3：固化范围拆分（用户决策）**。DCP 聚焦**正确读出内部型号 + 色彩矩阵**（`ColorMatrix1/2`/`ForwardMatrix1/2`/`BaselineExposure`/HSD/Look）供**手工指定或自动匹配**后续计算（calibrate 注入、DCPStore 式匹配），CFA 空间仅应用 baseline **标量**；`DcpParams` 新增 `unique_camera_model`/`camera_model`/`make`/`model` 标识字段（OQ4 自动匹配）。LCP 聚焦**颜色无关的暗角 + 几何畸变**，在 CFA mosaic 空间**直接复用 RT `LCPMapper::processVignette`（单通道 RAW 路径）/ `correctDistortion`** 应用，CA（逐通道）跳过/留 B5。据此修订 R1（"解析为主、按需复用 RT 应用"）、R3（范围拆分说明 + DCP 标识字段 + LCP 应用说明）、D3（表重排：DCP 读出/基线 vs 矩阵下游；LCP 暗角+畸变 = CFA 复用 RT）、D2（LCP 应用调 RT）、B2/B3（B3 改为"复用 RT 暗角+畸变 + DCP baseline 标量"）、OQ2（部分拍板：v1=暗角+畸变）、OQ4（升为第一版关注点，先解析 make/model）。
- 2026-09-28 — **rev 4：DCP 严格"只读"**。用户明确 DCP 中的 CFA 阶段 `BaselineExposure` 标量也是**读出**、**是否应用交用户决定**（受 `CameraProfileParams.apply_baseline_exposure` 门控，默认 true），并非 deprofile 阶段自动施加。据此把 R3 范围拆分、D3 表（baseline 行）、D3 末段、D2、B3 中"DCP 仅应用 baseline 标量"一律改为"**DCP 全程只读；baseline 仅当用户启用才 `×= 2^offset`，否则透传解析值**"，与 DCP 其他子操作（矩阵/HSD/Tone/Look）一致为"只读 + 用户决定"。LCP 暗角/畸变仍复用 RT 直接 apply（受 `apply_vignette`/`apply_distortion` 门控）。
- 2026-09-28 — **rev 5：B0–B3（RT 绑定侧）实施**。确认 `dcp.cc`/`lcp.cc` 编入 `librtengine.a`（rtengine/CMakeLists.txt:32/88）。在 `rawtherapee_fotlab` 内落地：`cxx/rt_deprofile_shim.{h,cc}`（声明 `rt_parse_dcp`/`rt_parse_lcp`/`rt_apply_lcp_cfa`）、`src/{dcp,lcp,deprofile_error}.rs`、`lib.rs` 新增 `ffi_deprofile` 桥、`build.rs` 编译新 shim。私有字段用 **header-only inline getter**（dcp.h 加 `getColorMatrix1/2`/`getForwardMatrix1/2`/`getHasColorMatrix*`/`getBaselineExposureOffsetValue`；lcp.h 加 `getProfileName` 等）——不碰 `.cc`、无需重编 `librtengine.a`，与既有 demosaic hook 同套路（README 已记）。**畸变方向处理**：`correctDistortion` 在 RT 里前向/后向语义不明，故当黑盒前向映射、对其做**半径 LUT + 数值求逆**重采样 CFA（中心径向对称，fisheye 同样适用），避免把畸变方向搞反。DCP 侧仅解析（矩阵/baseline/illuminants/curve 标志），baseline 标量应用与矩阵注入留 `rawler_fotlab` 侧（B3/B4 下一步）。`DcpParams` 的 make/model 字段因 `DCPProfile` 不存相机标识暂留 `None`（OQ4）。
- 2026-09-28 — **rev 6：B3/B4（rawler_fotlab 接入）**。在 `rawler_fotlab` 内完成 deprofile 与 develop 管线的对接：`DevelopParams` 新增 `camera_profile: Option<CameraProfileParams>`、`lens_profile: Option<LensProfileParams>` 两字段（`Cargo.toml` 加 `rawtherapee_fotlab` 依赖；后者 crate-type 增 `rlib` 以便被消费）。`develop_image` 在 exposure **之前**、CFA mosaic 空间接入两段：(1) DCP 只读解析，`camera_profile.apply_baseline_exposure` 为真且 `has_baseline_exposure` 时整幅 ×= 2^offset；(2) LCP 暗角/畸变经 `apply_lcp_cfa` 直接作用于单通道 CFA mosaic（复用 RT `LCPMapper`，跳过 CA）。所有子阶段在 profile 为 `None` 或标志关闭时为 no-op，未配置的渲染零影响。解析失败仅 `log::warn!` 跳过，不中断管线。镜头焦距/光圈等经 `LensProfileParams` 由调用方从 EXIF 传入（`focal_length` 必填，余带默认值兜底）；后续可做按路径缓存解析结果。下一步 B5：DCP 色彩矩阵/HSD/Tone/Look 在 RGB 标定阶段应用（独立于本 CFA 阶段）。
