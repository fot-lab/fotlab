# 调研 — RapidRAW 处理管线（Processing Pipeline）

- ID: RAPIDR-SURVEY-000004
- Status: Draft
- Priority: P2
- Created: 2026-09-18
- Owner: —
- Related: `rules/STRUCT/detail/RAPIDR-SURVEY-000001.md`（Android 构建与渲染/显示路径）、`rules/STRUCT/detail/RAPIDR-SURVEY-000002.md`（导入/存储/非破坏性模型）、`rules/STRUCT/detail/RAPIDR-SURVEY-000003.md`（安卓交互/WebView/重写可行性）、`rules/REVIEW/detail/FOTLAB-UNIFFI-000001.md`（FotLab UniFFI/Kotlin 方向）

> **范围声明**：基于 `external/RapidRAW` 的 **shallow clone（`--depth 1`，`v1.6.4`）**。仅文档化，不修改上游。聚焦 RapidRAW 的**图像处理管线**：从 RAW 解码到预览/导出的完整 stage 顺序、CPU 与 GPU 分工、非破坏性参数如何落进管线、与 FotLab 的复用关系。

## TL;DR

- RapidRAW 的处理管线是**两段式（Two-Stage）**：**Stage 1（CPU，几何/结构）** 做解码、AI 修补、透视变形、镜头模糊、旋转/翻转/裁剪；**Stage 2（GPU，逐像素色彩/影调）** 用 **wgpu + WGSL（`shader.wgsl`）** 做曝光→去雾→白平衡→影调→高光/阴影→色彩校准→HSL→色彩分级→LUT→曲线→暗角→颗粒。
- RAW 解码用 **`rawler` + `imgop::develop`**（`ProcessingStep::Demosaic`）；缩略图走 `fast_demosaic`（Speed 算法）。解码后有一道 **CPU 基线**：gamma 2.38 + 对比度 1.28（`apply_cpu_default_raw_processing`），让未编辑 RAW 也「看着正常」。
- **非破坏性**：编辑是 JSON（`adjustments`），渲染时反序列化为 `AllAdjustments` 实时套用，原图永不被改写（详见 000002）。多级哈希缓存（几何哈希 / 变换哈希 / GPU uniform 哈希）保证交互流畅。
- **预览 vs 导出共用同一套 Stage**：预览 = CPU 几何结果（缓存+降采样）在显示端再跑 GPU 色彩（桌面 `WgpuDisplay`+`display.wgsl` 实时渲染；安卓走 2D Canvas，见 000001/000003）；导出 = 跑完整 Stage 1+Stage 2 全分辨率，再缩放/水印/编码/写 EXIF。TIFF 16-bit 走 16-bit 精度路径。
- **对 FotLab**：`rawler`/`imgop` 解码与 `shader.wgsl` 的 `AllAdjustments` 色彩模型可直接复用；整个管线在 Rust 端、与前端无关，符合 000003 档③「UniFFI 暴露给原生前端」的方向。

## 1. 架构总览：两段式管线

```
[原始文件 bytes]
   │
   ▼ Stage 0  Decode (CPU, raw_processing.rs)
   │   rawler::get_decoder → imgop::develop(RawDevelop, Demosaic)
   │   apply_orientation + recover_clipped_pixel
   │   → LoadedImage (DynamicImage, RGB32F/RGBA)
   │   (非 RAW 直接由 image_loader 载入)
   ▼ Stage 1  Geometry / Structure (CPU)
   │   composite_patches_on_image   (AI 修补/生成)
   │   → apply_geometry_warp        (透视/引导变形)
   │   → apply_lens_blur            (镜头模糊)
   │   → apply_spatial_transformations (粗旋→翻转→精旋→裁剪)
   │   → DynamicImage (CPU)  ← 多级缓存
   ▼ Stage 2  Color / Tone (GPU, wgpu + shader.wgsl)
   │   compute pipelines: sharpness/tonal/clarity/structure/flare 模糊图
   │   fs_main 逐像素: 降噪→锐化→局部对比→曝光→辉光/光晕→光斑→
   │     去雾→白平衡→影调→高光→色彩校准→HSL→色相→自然饱和→
   │     色彩分级→遮罩→暗角→色调映射→曲线→LUT→颗粒→抖动
   ▼
[输出 DynamicImage / 显示纹理]
   预览: 显示端 GPU 实时渲染 (桌面) / 2D Canvas (安卓)
   导出: 缩放+水印 → 编码(JPEG/PNG/WebP/TIFF/JXL)+EXIF
```

## 2. Stage 0 — 解码（CPU，`raw_processing.rs`）

- 入口 `develop_raw_image`（`raw_processing.rs:15`）→ `develop_internal`（`raw_processing.rs:109`）：
  - `rawler::get_decoder(&source)`（`raw_processing.rs:128`）识别相机 RAW；
  - `decoder.raw_image(...)` 得到 `RawImage`，再由 **`imgop::develop::RawDevelop`** 走 `ProcessingStep`（含 `Demosaic`）（`raw_processing.rs:6,171`）。
  - `fast_demosaic` 标志切换 `DemosaicAlgorithm::Speed`（缩略图/快速路径）与更高质量的默认算法（`raw_processing.rs:174-175`）；`get_fast_demosaic_scale_factor`（`raw_processing.rs:274`）为快速路径提供降采样系数。
  - `apply_orientation`（EXIF 方向，`image_processing.rs:1237`）；高光裁剪恢复 `recover_clipped_pixel`（洋红/去饱和，`raw_processing.rs:61-107`）。
- **CPU 基线色调**：解码后即调 `apply_cpu_default_raw_processing`（`image_processing.rs:1161`）——对 RGB32F 做 `gamma=2.38` + `contrast=1.28` 全局映射，使未经用户编辑的 RAW 也呈现合理对比/亮度。后续用户调整（Stage 2）在此之上叠加。
- 非 RAW（JPEG/PNG/WebP/TIFF/JXL 等）由 `image_loader` 直接载入为 `LoadedImage.image`，不经 rawler。
- 非破坏性：原始文件 bytes 只读一次，解码产物进缓存；原文件不被修改（见 000002）。

## 3. Stage 1 — 几何/结构（CPU）

组合入口有两处，逻辑一致：
- 预览：`compute_patched_and_warped`（`lib.rs:233`）→ `apply_spatial_transformations`（`lib.rs:225`）。
- 导出：`apply_all_transformations`（`adjustment_utils.rs:115`）。

顺序（以 `apply_all_transformations` 为准，`adjustment_utils.rs:115-132`）：

1. **`composite_patches_on_image`**（`image_loader.rs:398`）——把 AI 修补/生成（`aiPatches`）合成到图上。
2. **`apply_geometry_warp`**（`image_processing.rs:1250`）——透视/引导变形（guided perspective），`is_geometry_identity` 为真则跳过。
3. **`apply_lens_blur`**（`lens_blur.rs`）——镜头模糊效果。
4. **`apply_spatial_transformations`**（`adjustment_utils.rs:93-113`）——**顺序固定**：
   - 粗旋 `apply_coarse_rotation`（`orientationSteps`，90° 步进）
   - 翻转 `apply_flip`（H/V）
   - 精旋 `apply_rotation`（任意角度）
   - 裁剪 `apply_crop`（返回 `unscaled_crop_offset`，供 Stage 2 遮罩对齐）
   → 产出 `DynamicImage`（CPU）。

**缓存（性能关键）**：Stage 1 结果按哈希缓存——
- `full_transformed_cache`：按 `calculate_transform_hash(adjustments)`（全变换哈希）；
- `patched_warped_cache`：按几何哈希（`calculate_patched_warped_hash`）；
- `full_warped_cache`：供遮罩生成复用（`get_cached_full_warped_image`，`lib.rs:286`）。
仅当哈希变化才重算，故拖拽滑块时只重跑受影响的 Stage。

## 4. Stage 2 — 逐像素色彩/影调（GPU，wgpu + `shader.wgsl`）

### 4.1 入口与数据

- 入口 `process_and_get_dynamic_image_with_precision`（`gpu_processing.rs:1766`）：接收 `GpuContext`（wgpu 设备/队列）、`RenderRequest { adjustments: AllAdjustments, mask_bitmaps, lut, roi }`、输出精度 `RenderOutputPrecision`（8/16-bit）。
- `AllAdjustments` 由 `get_all_adjustments_from_json`（`export_processing.rs:503`）从编辑 JSON 反序列化；`global.show_clipping` 在导出时强制置 0（`export_processing.rs:504`）。
- **预计算模糊图（compute pipeline）**：`gpu_processing.rs:646-980` 用 wgpu compute 生成 `sharpness_blur / tonal_blur / clarity_blur / structure_blur / flare` 纹理，供 Stage 2 的锐化、局部对比、去雾、光斑使用（输入即 `input_texture`）。

### 4.2 主片元着色器逐阶段顺序（`shader.wgsl` `fs_main`，`shader.wgsl:1809-1996`）

输入先转 **linear**（`initial_linear_rgb`），随后（顺序即代码顺序）：

| # | 操作 | 函数 | 备注 |
| --- | --- | --- | --- |
| 1 | 降噪（亮度+色彩） | `apply_noise_reduction` | 5×5 鲁棒双边，边缘保护 |
| 2 | 锐化 | `apply_sharpen` | 用 sharpness/tonal 模糊图，含反卷积 |
| 3 | 局部对比（清晰度） | `apply_local_contrast` (clarity) | 用 clarity 模糊图 |
| 4 | 局部对比（结构/纹理） | `apply_local_contrast` (structure) | 用 structure 模糊图 |
| 5 | 中心局部对比 | `apply_centre_local_contrast` | 仅中心区域 |
| 6 | 曝光 | `apply_linear_exposure` | `×2^exposure` |
| 7 | 辉光/泛光 | `apply_glow_bloom` | `glow>0` 时 |
| 8 | 光晕 | `apply_halation` | `halation>0` 时 |
| 9 | 光斑 | flare_texture | `flare>0` 时，高光保护 |
| 10 | 去雾 | `apply_dehaze` | 用 structure 模糊图 |
| 11 | 白平衡 | `apply_white_balance` | temperature / tint |
| 12 | 中心色调与色彩 | `apply_centre_tonal_and_color` | 中心曝光/自然饱和/饱和 |
| 13 | 影调 | `apply_tonal_adjustments` | contrast / shadows / whites / blacks |
| 14 | 高光 | `apply_highlights_adjustment` | 含溢出恢复 |
| 15 | 色彩校准 | `apply_color_calibration` | 红/绿/蓝主饱和度 |
| 16 | HSL 面板 | `apply_hsl_panel` | 8 个色相区间独立 hue/sat/lum |
| 17 | 色相偏移 | `apply_hue_shift` | 全局 |
| 18 | 创意色彩 | `apply_creative_color` | saturation + vibrance（肤色保护） |
| 19 | 色彩分级 | `apply_color_grading` | 阴影/中间调/高光/全局（hue/lum，含 balance） |
| 20 | 遮罩分级 | 逐 mask `apply_color_grading` | 按 `get_mask_influence` 混合 |
| 21 | 暗角 | vignette | amount/midpoint/roundness/feather |
| 22 | 色调映射 | AGX / sRGB / raw-sRGB-对比 | `tonemapper_mode`；RAW 走提亮对比曲线 |
| 23 | 胶片曝光 | `apply_filmic_exposure` | brightness |
| 24 | 曲线 | `apply_all_curves` | 亮度/R/G/B 独立曲线 |
| 25 | 遮罩曲线 | 逐 mask `apply_all_curves` | 同上混合 |
| 26 | LUT | scene/non-scene referred | 场景型在映射前、非场景型在曲线后 |
| 27 | 颗粒 | grain | 亮度掩膜 + 频率/粗糙度 |
| 28 | 剪切警告 | show_clipping | 红=高光溢出，蓝=暗部溢出（仅预览） |
| 29 | 抖动 | dither | 仅 8-bit 输出（`HIGH_PRECISION_OUTPUT==0`） |
| 30 | 写出 | `textureStore` | clamp 到 [0,1] |

> 关键设计：降噪/锐化在**线性域最前**做；曝光→影调→色彩在中间；**色彩分级与曲线在色调映射（tonemap）之后、LUT 之前/后**；颗粒与抖动在最后。遮罩（masks）以 `texture_2d_array` 传入，分级与曲线按 `influence` 加权混合，实现局部调整。

### 4.3 输出精度

- `RenderOutputPrecision::SixteenBit`：当导出 TIFF 且 `tiff_bit_depth==Sixteen`（`export_processing.rs:527-538`）；其余 8-bit。
- 16-bit 路径让导出保留更多位深，避免多级调整后的条带。

## 5. 预览 vs 导出：同一套 Stage 的不同组合

- **预览**（`generate_transformed_preview`，`lib.rs:151`）：
  - 先算 `compute_full_transformed_res`（= Stage 1 全分辨率），按 `transform_hash` 缓存于 `full_transformed_cache`；
  - 若超过 `preview_dim` 则 `downscale_f32_image` 降采样，返回 `(DynamicImage, scale_for_gpu, crop_offset)`。
  - **色彩 Stage（Stage 2）不在此函数内**——它在**显示端**套用：桌面由 `WgpuDisplay` + `display.wgsl` 实时 GPU 渲染到 WebView 画布（见 000001）；安卓因无 wgpu 显示，走 2D Canvas 位图（见 000003）。两者共用同一 `AllAdjustments`/LUT/遮罩模型，故「所见即所得」。
- **导出**（`process_image_for_export_pipeline`，`export_processing.rs:467`）：
  1. `apply_all_transformations`（Stage 1，CPU 全分辨率）；
  2. 解析遮罩：`resolve_warped_image_for_masks` + `generate_mask_bitmap`（基于 warped 图生成遮罩位图）；
  3. `process_and_get_dynamic_image_with_precision`（Stage 2，GPU 全分辨率）；
  4. `apply_export_resize_and_watermark`（缩放/水印）；
  5. `save_image_with_metadata`（`export_processing.rs:560`）：`encode_image_to_bytes`（JPEG/PNG/WebP/TIFF/JXL，经 `formats.rs`）+ `exif_processing::write_image_with_metadata`；安卓额外 `save_image_bytes_to_android_gallery` 写 MediaStore（`export_processing.rs:587-598`）。

## 6. 非破坏性与缓存（呼应 000002）

- 编辑仅存为 JSON（`adjustments`）；渲染时 `get_all_adjustments_from_json` 反序列化为 `AllAdjustments`，**原文件零写入**（详见 000002 的 `.library/.../edits.json` 模型）。
- 缓存皆以哈希为键：`calculate_transform_hash`（全变换）、`calculate_patched_warped_hash`（几何）、`calculate_geometry_hash`、`calculate_full_job_hash`（GPU job）。拖拽滑块/切换图片时只重跑失效的 Stage，交互得以实时。

## 7. 性能要点

- **CPU 并行**：gamma/对比度、sRGB↔linear 用 `par_chunks_mut`（rayon）并行（`image_processing.rs:1168,1197`）。
- **GPU 卸载**：模糊/降采样走 wgpu compute；逐像素色彩走 fragment shader，全流程 GPU。
- **多级缓存 + 降采样**：Stage 1 全分辨率缓存 + 预览降采样；缩略图 `fast_demosaic`。
- **16-bit 导出路径**：避免多段调整的累积误差/条带。

## 8. 与 FotLab 的关系 / 复用价值

1. **解码层可复用**：RapidRAW 用 `rawler` + `imgop::develop` 做 RAW 解码/Demosaic，与 FotLab 已规划的 dnglab/rawler 方向一致；其 `fast_demosaic` 与高光恢复经验（`recover_clipped_pixel`）可直接借鉴。
2. **色彩模型可复用**：`shader.wgsl` 的 `AllAdjustments` 是一套结构清晰、覆盖完整的 GPU 色彩管线（曝光→影调→色彩分级→LUT→曲线→颗粒），FotLab Studio 若做跨平台一致「观感」，可参考其 uniform 设计与 stage 顺序。
3. **管线与前端解耦**：整个处理在 Rust/Tauri 后端，前端只传「图像 bytes + 编辑 JSON」。这正好对齐 000003 档③——若 FotLab 以 UniFFI 把 Rust 核心暴露给原生 Compose 前端，**同一套 `apply_all_transformations` + `process_and_get_dynamic_image_with_precision` 可直接复用**，无需重写算法，只换显示层。
4. **安卓短板同源**：000003 指出的安卓 2D Canvas 显示（无 wgpu 上屏）使 Stage 2 在安卓只能离屏渲染后回退位图——这是 RapidRAW 安卓交互差的根因之一；FotLab 若做原生安卓，应让 Stage 2 直接渲染到原生 Surface/Compose，而非经 WebView。

## 关键文件索引

| 关注点 | 文件:行 |
| --- | --- |
| 解码入口（rawler + imgop） | `src-tauri/src/raw_processing.rs:15,109,128,131,174` |
| 快速 demosaic 系数 | `src-tauri/src/raw_processing.rs:274` |
| 高光裁剪恢复 | `src-tauri/src/raw_processing.rs:61-107` |
| CPU 基线 gamma/对比度 | `src-tauri/src/image_processing.rs:1161-1183` |
| 方向/几何变形/色彩空间 | `src-tauri/src/image_processing.rs:1185-1261` |
| AI 修补合成 | `src-tauri/src/image_loader.rs:398` |
| 几何变形 | `src-tauri/src/image_processing.rs:1250` |
| 空间变换（粗旋→翻转→精旋→裁剪） | `src-tauri/src/adjustment_utils.rs:93-113` |
| 组合变换（导出用） | `src-tauri/src/adjustment_utils.rs:115-132` |
| 预览：几何结果+降采样+缓存 | `src-tauri/src/lib.rs:151-255` |
| GPU 色彩入口 + RenderRequest | `src-tauri/src/gpu_processing.rs:31-85,1766` |
| 模糊 compute pipeline | `src-tauri/src/gpu_processing.rs:646-994` |
| 逐像素 stage 顺序（fs_main） | `src-tauri/src/shaders/shader.wgsl:1809-1996` |
| 降噪 | `src-tauri/src/shaders/shader.wgsl:1148` |
| 锐化/局部对比 | `src-tauri/src/shaders/shader.wgsl:824-1037` |
| 白平衡/去雾/色彩分级/HSL | `src-tauri/src/shaders/shader.wgsl:687,1107,791,733` |
| 遮罩混合（分级+曲线） | `src-tauri/src/shaders/shader.wgsl:1881-1957` |
| 导出流水线 | `src-tauri/src/export_processing.rs:467-646` |
| 导出精度（16-bit TIFF） | `src-tauri/src/export_processing.rs:527-538` |
| 写盘+EXIF+安卓图库 | `src-tauri/src/export_processing.rs:560-602` |
| 编辑 JSON→AllAdjustments | `src-tauri/src/export_processing.rs:503` |
| 着色器集 | `src-tauri/src/shaders/{shader,display,blur,flare}.wgsl` |
