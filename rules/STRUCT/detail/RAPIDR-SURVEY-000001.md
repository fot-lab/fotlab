# 调研 — external/RapidRAW 模块（Tauri 封装的 Rust RAW 编辑器）

- ID: RAPIDR-SURVEY-000001
- Status: Draft
- Priority: P2
- Created: 2026-09-18
- Owner: —
- Related: `rules/STRUCT/detail/DNGLAB-RAWDEV-000001.md`（dnglab 的 `rawler::imgop::develop` 开发管线，我们已编译的同源路径）、`rules/REVIEW/detail/DNGLAB-RAWLER-000001.md`（rawler 解码契约）、`rules/STRUCT/detail/RAWTRP-PIPELN-000001.md`（RawTherapee 开发管线分类，用于对比阶段完整性）、`rules/REVIEW/detail/FOTLAB-RAWLER-000001.md`（我们当前的 rawler 用法）、`external/rawloader`（父仓引用的 rawloader submodule，与 RapidRAW 依赖的 `rawler` crate 同源）

> **范围声明（up front）**：本调研基于 `external/RapidRAW` 的 **shallow clone（`--depth 1`，标签 `v1.6.4`）**，只读最新提交，但目录与管线结构完整。RapidRAW 是 **Tauri v2 + Rust 后端 + React/TypeScript 前端** 的跨平台 RAW 照片编辑器（上游 `io.github.CyberTimon.RapidRAW`，我们上游 fork 同 owner：`../RapidRAW.git`，`main` 分支）。本研究只做文档化，**不修改任何上游源码**。重点回答两点：(1) `src-tauri/gen/android` 如何构建 App；(2) 图像渲染管线是怎样的。

## Background & Goal

`FOTLAB-STUDIO-000001` 要求 Studio 展示真实开发图（真实 demosaic / WB / 相机→输出色彩），而我们当前的 `rawler_fotlab::decode_to_png` 仍是未处理的全传感器 dump（`DNGLAB-RAWLER-000001`）。RapidRAW 是一个**已经完整跑通的 Rust RAW 开发者**，其管线（RAW 解码→几何/镜头→颜色/色调→输出）对我们规划「全量使用 dnglab/rawler」具有直接的参考与对照价值。

本调研目标：
1. 厘清 `src-tauri/gen/android` 这个 **生成式 Android 包装壳** 的构建链路；
2. 梳理 RapidRAW 的 **图像渲染管线**（核心在 Rust，与平台无关），并标注其在 Android 上的呈现差异；
3. 为后续与 dnglab/rawler、RawTherapee 管线的对比提供结构化事实。

## 调研范围

- 路径：`external/RapidRAW/`
- 形态：Tauri v2 应用（`src-tauri/tauri.conf.json` 的 `$schema` 为 `config/2`）
  - `src-tauri/src/*.rs`（43 个 Rust 源）— 后端与图像管线，**编译进 `.so`，Android/桌面共用同一份 crate**
  - `src/**`（React + TypeScript + Vite，74 个 `.tsx`）— 前端 UI，打包为 WebView 资源
  - `src-tauri/gen/android/` — Tauri 生成的 Android Gradle 工程（**非管线代码**，只是壳）
- 许可：见 `LICENSE`（上游仓库，本仓未单列）
- 关键依赖：Rust 侧 `rawler`（RAW 解码）、`image`、`wgpu`（GPU 处理）、`half`、`bytemuck`；前端 `react`、`vite`

## 仓库结构（代码结构）

```
external/RapidRAW/
├── src/                      # 前端：React + TS + Vite
│   ├── components/panel/editor/ImageCanvas.tsx   # 主图 2D Canvas 呈现
│   ├── hooks/useImageLoader.ts                   # 图像加载
│   └── ...
├── src-tauri/
│   ├── src/                  # Rust 后端 / 图像管线
│   │   ├── lib.rs            # Tauri 命令注册 + 管线编排入口
│   │   ├── raw_processing.rs # RAW 解码（rawler）
│   │   ├── image_processing.rs  # 几何/颜色/色调/AGX/LUT/曲线/HSL
│   │   ├── gpu_processing.rs # wgpu 显示与 GPU 处理（DisplayTransform / WgpuDisplay）
│   │   ├── adjustment_utils.rs  # 空间变换（旋转/裁剪/翻转）
│   │   ├── lens_blur.rs / lens_correction.rs / lut_processing.rs / denoising.rs / export_processing.rs ...
│   │   └── shaders/          # WGSL：blur.wgsl, display.wgsl, flare.wgsl, shader.wgsl
│   ├── gen/android/          # Tauri 生成的 Android 工程（见 §1）
│   ├── tauri.conf.json       # Tauri v2 配置（构建/窗口/资源/文件关联）
│   ├── Cargo.toml / rust-toolchain.toml
│   └── resources/ lensfun_db/  # 打包进 App 的资源（LUT、镜头库）
└── package.json vite.config.mjs index.html
```

## 1. `src-tauri/gen/android` 如何构建 App

`gen/android` 是 **Tauri 生成的 Android 包装壳**，本身不含图像管线。真正的管线在 `src-tauri/src/*.rs`（编译成 `.so`），前端在 `src/**`（打包成 WebView assets）。

### 1.1 构建链路

```
tauri android build
  ├─ beforeBuildCommand: npm run build        → Vite 把前端打包到 ../dist（frontendDist，tauri.conf.json:7-8）
  ├─ 打开 gen/android Gradle 工程
  │    └─ rust Gradle 插件：对每个 ABI 调 cargo-ndk 交叉编译 Rust cdylib → 并入 jniLibs/*.so
  └─ Gradle 打包 APK/AAB：WebView 加载前端 + Tauri IPC 桥接 Rust
```

### 1.2 关键文件与机制

- **`app/build.gradle.kts`** — Android 应用工程。
  - `plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("rust") }`（`build.gradle.kts:3-7`）
  - `compileSdk/targetSdk = 36`、`minSdk = 24`、`namespace = io.github.CyberTimon.RapidRAW`（`build.gradle.kts:16-26`）
  - `rust { rootDirRel = "../../../" }`（`build.gradle.kts:75-77`）— 指回 `src-tauri` 根
  - 依赖 `androidx.webkit`（WebView）、`androidx.appcompat`、`material`、`rustls-platform-verifier`（`build.gradle.kts:79-88`）
  - release 开启 R8/ProGuard（`build.gradle.kts:56-65`）；末尾 `apply(from = "tauri.build.gradle.kts")` 是 Tauri glue（挂前端 assets、定位 Rust 库）

- **`buildSrc/.../RustPlugin.kt`** — 为 `arm64-v8a / armeabi-v7a / x86 / x86_64` 创建 product flavors，并在 `afterEvaluate` 为每个 ABI 生成 `rustBuild<Arch><Profile>` 任务，挂到 `merge*JniLibFolders`（`RustPlugin.kt:20-26` 的 abi/target 列表，`RustPlugin.kt:49-83`）。

- **`buildSrc/.../BuildTask.kt`** — 实际执行 `npm run -- tauri android android-studio-script --target <rust-target> [--release]`（`BuildTask.kt:51-66`）。该 Tauri 子命令驱动 `cargo` + `cargo-ndk` 把 Rust 编成 `aarch64/armv7/i686/x86_64-linux-android` 的 `.so`，再并入 `jniLibs`。

- **`app/src/main/java/.../MainActivity.kt`** — 继承 `TauriActivity`（`MainActivity.kt:12`）；`onWebViewCreate` 中拿到 `WebView` 并处理系统边距与返回键（`evaluateJavascript("window.__handleAndroidBack()")`，`MainActivity.kt:45-57`）。即 **Android 端真实显示表面是 WebView**，而非原生 Surface。

> 结论：`gen/android` 的作用 = 「把 Rust 后端编成 `.so` + 用 WebView 承载前端」。Android 与桌面跑**同一份 Rust crate**，差异只在显示层（见 §2.5）。

## 2. 图像渲染管线

**核心在 Rust（`src-tauri/src`），与平台无关**——Android 上同样执行（编译进 `.so`），只是最终呈现方式不同。

### 2.1 载入与 RAW 解码

- RAW 解码使用 **`rawler` crate**（注意：是 `rawloader` 的后续/派生版本；而我们父仓 `external/rawloader` 是另一个 submodule，两者同源不同包）：

```128:133:src-tauri/src/raw_processing.rs
let decoder = rawler::get_decoder(&source)?;
let mut raw_image: RawImage = decoder.raw_image(&source, &RawDecodeParams::default(), false)?;
let metadata = decoder.raw_metadata(&source, &RawDecodeParams::default())?;
```

- 非 RAW（jpg/png/tiff/...）走 `image` crate。
- `get_cached_full_warped_image` 取原图；若是 RAW 先做 `apply_cpu_default_raw_processing`（RAW→RGB 基线处理）：

```307:309:src-tauri/src/lib.rs
if is_raw {
    apply_cpu_default_raw_processing(cow_image.to_mut());
}
```

### 2.2 几何 / 镜头

编排在 `generate_transformed_preview` → `compute_full_transformed_res` → `compute_patched_and_warped`：

```233:254:src-tauri/src/lib.rs
let patched_image = ... composite_patches_on_image(...);   // AI 修复补丁合成
let warped = apply_geometry_warp(patched_image, adjustments);   // 镜头畸变/几何（含横向色差 TCA）
let blurred = crate::lens_blur::apply_lens_blur(warped, adjustments);
```

具体函数（`image_processing.rs`）：`warp_image_geometry:690`、`apply_geometry_warp:1250`、`interpolate_pixel_with_tca:511`（横向色差）、`apply_crop:1309`、`apply_rotation:1289`、`apply_flip:1339`、`apply_orientation:1237`。

### 2.3 颜色 / 色调（管线主体）

两套实现：GPU（wgpu + WGSL）与 CPU 等价实现。
- 统一入口结构 `AllAdjustments` / `RenderRequest`（含 `mask_bitmaps`、`lut`、`roi`、`RenderOutputPrecision` 8/16-bit）：

```31:36:src-tauri/src/gpu_processing.rs
pub struct RenderRequest<'a> {
    pub adjustments: AllAdjustments,
    pub mask_bitmaps: &'a [ImageBuffer<Luma<u8>, Vec<u8>>],
    pub lut: Option<Arc<Lut>>,
    pub roi: Option<Roi>,
}
```

- `shader.wgsl` 的 `GlobalAdjustments` uniform 列出全部可调项：exposure/brightness/contrast/highlights/shadows/whites/blacks、temperature/tint、saturation/vibrance/hue、**16 点 luma/R/G/B 曲线**、**8 段 HSL**、color grading（shadows/mid/high + global + balance）、color calibration、**AGX tonemap 的 3×3 矩阵**、LUT（scene/display-referred + intensity）、clarity/structure/dehaze、sharpness、luma/color 降噪、grain、vignette、glow/halation/flare、chromatic aberration：

```33:118:src-tauri/src/shaders/shader.wgsl
struct GlobalAdjustments {
    exposure: f32, brightness: f32, contrast: f32, ...
    agx_pipe_to_rendering_matrix: mat3x3<f32>,
    agx_rendering_to_pipe_matrix: mat3x3<f32>,
    ...
    hsl: array<HslColor, 8>,
    luma_curve: array<Point, 16>,
    ...
}
```

- AGX 色调映射：`calculate_agx_matrices_glam:1832` / `calculate_agx_matrices:1872`、`apply_cpu_agx_tonemap:1903`（线性空间矩阵运算）。
- 色彩空间转换：`apply_srgb_to_linear:1185` / `apply_linear_to_srgb:1211`。
- LUT：`.cube` 解析在 `lut_processing.rs`（`Lut`），支持 scene/display-referred。
- 遮罩：最多 `MAX_MASKS` 个 `mask_bitmaps` 做局部调整。

### 2.4 输出与预览

`generate_transformed_preview` 用 **hash 缓存** 全分辨率结果，再 `downscale_f32_image` 到 `preview_dim` 给实时预览，返回 `(DynamicImage, scale_for_gpu, crop_offset)`：

```151:194:src-tauri/src/lib.rs
pub fn generate_transformed_preview(...) -> Result<(DynamicImage, f32, (f32, f32)), String> {
    ...
    let (transformed_full_res, unscaled_crop_offset) = { /* full_transformed_cache，hash 命中复用 */ };
    ...
    Ok((final_preview_base, scale_for_gpu, unscaled_crop_offset))
}
```

两级缓存：`patched_warped_cache`（几何/AI 补丁/镜头模糊）、`full_transformed_cache`（全部调整），均以 adjustments 的 hash 为键，避免重复整图重算（`lib.rs:159-231`）。

### 2.5 显示层（平台差异点）

- **桌面（Windows/macOS）**：`gpu_processing.rs` 的 `WgpuDisplay` 用 wgpu 把最终纹理经 `display.wgsl` 上屏到窗口 Surface（处理 rect/clip/window/image_size 变换、pixelated 模式、背景色）：

```52:64:src-tauri/src/gpu_processing.rs
pub struct WgpuDisplay {
    pub surface: wgpu::Surface<'static>,
    pub pipeline: wgpu::RenderPipeline,
    ...
}
impl WgpuDisplay {
    pub fn render(&mut self, device: &wgpu::Device, queue: &wgpu::Queue) {
```

- **Android / Linux**：`gpu_processing.rs` 顶部有 `#[cfg(not(any(target_os = "android", target_os = "linux")))] use tauri::Manager;`，即该 **Surface 上屏路径被排除**。前端收到 Rust 算好的像素后，用 **2D Canvas** 绘制（非 WebGL）：

```2073:2073:src/components/panel/editor/ImageCanvas.tsx
const ctx = canvas.getContext('2d', { willReadFrequently: true });
```

波形图等也走 2D：`Waveform.tsx:194` `new ImageData(bytes, width, height)`。

> 架构含义：Android 上「重计算全在 Rust，WebView 的 2D canvas 只做最终呈现」。wgpu 的**计算/调整 pass**（`shader.wgsl`）在 Android 上若 backend（Vulkan）可用仍可跑，但 `WgpuDisplay` 的**上屏 pass**（`display.wgsl`）是桌面专属。

## 与 FotLab 的关系 / 备注

1. **RAW 解码同源**：RapidRAW 用 `rawler` crate；父仓 `external/rawloader` 是 `rawloader` 上游的 submodule，二者同源/派生。评估在 FotLab 中统一 RAW 解码栈时，这是重要的对齐点（可对照 `DNGLAB-RAWLER-*` / `FOTLAB-RAWLER-*`）。
2. **管线完整性对照**：RapidRAW 的「RAW→几何/镜头→颜色/色调（含 AGX、曲线、HSL、LUT、遮罩）→输出」与 `RAWTRP-PIPELN-000001`（RawTherapee）的阶段分类高度吻合，可作为我们未来 `rawler_fotlab` develop 的**完整性清单**。
3. **Tauri 跨平台封装**：RapidRAW 的 Android 构建方式（Gradle + rust 插件 + WebView 承载 React 前端）是「Rust 计算 + Web 前端」跨平台化的现成范例，与我们对 dnglab 做 native binding 的思路不同但可借鉴。
4. **显示层差异**：Android 走 2D Canvas 而非 wgpu 上屏，意味着在移动端实时预览依赖「Rust 算图 → 传像素 → Canvas 绘制」的往返；若 FotLab 也上 Android，需同样面对该取舍。

## 关键文件索引

| 关注点 | 文件:行 |
| --- | --- |
| Android Gradle 工程 / rust 插件接入 | `src-tauri/gen/android/app/build.gradle.kts:3-7,16-26,75-90` |
| ABI→Rust target 映射、构建任务 | `src-tauri/gen/android/buildSrc/.../RustPlugin.kt:20-26,49-83` |
| 触发 cargo-ndk 交叉编译 | `src-tauri/gen/android/buildSrc/.../BuildTask.kt:51-66` |
| Android 主活动（WebView 承载） | `src-tauri/gen/android/app/src/main/java/.../MainActivity.kt:12,45-57` |
| Tauri v2 构建/资源/文件关联配置 | `src-tauri/tauri.conf.json:5-8,27-39,40-227` |
| RAW 解码（rawler） | `src-tauri/src/raw_processing.rs:128-133` |
| 管线编排入口 / 预览缓存 | `src-tauri/src/lib.rs:151-194,197-231,286-309` |
| 几何/镜头/颜色/AGX/LUT/曲线/HSL | `src-tauri/src/image_processing.rs:690,511,1250,1289,1309,1339,1161,1185,1211,1903,1872` |
| GPU 处理 / 显示层（平台 gating） | `src-tauri/src/gpu_processing.rs:8,31-36,38-50,52-64` |
| GPU 调整 uniform（全可调项） | `src-tauri/src/shaders/shader.wgsl:33-118` |
| 上屏 present pass | `src-tauri/src/shaders/display.wgsl`（全文件） |
| 前端主图 2D Canvas 呈现 | `src/components/panel/editor/ImageCanvas.tsx:2073` |
| 前端加载 | `src/hooks/useImageLoader.ts` |
