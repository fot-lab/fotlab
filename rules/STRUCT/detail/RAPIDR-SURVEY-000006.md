# 调研 — RapidRAW 解码后图像在内存中的中间表示（RAW Develop → DynamicImage → GPU 纹理）

- ID: RAPIDR-SURVEY-000006
- Status: Draft
- Priority: P2
- Created: 2026-09-18
- Owner: —
- Related: `rules/STRUCT/detail/RAPIDR-SURVEY-000004.md`（处理管线总览 / `fs_main` stage 顺序）、`rules/STRUCT/detail/RAPIDR-SURVEY-000005.md`（LUT 子系统，消费 `ImageRgba32F` 线性输入）、`rules/STRUCT/detail/RAPIDR-SURVEY-000002.md`（非破坏性编辑 JSON）

> **范围声明**：基于 `external/RapidRAW` 的 **shallow clone（`--depth 1`，`v1.6.4`）**。仅文档化，不修改上游。聚焦 RapidRAW 在 **RAW 解码 / demosaic / calibrate 完成后**，图像数据以何种中间对象驻留内存：从 rawler 的 `Intermediate`、到统一的 `DynamicImage::ImageRgba32F`、再到 AppState 各缓存中的 `Arc<DynamicImage>`、以及上 GPU 时降为 `Rgba16Float` 纹理的完整链路。

## TL;DR

- **解码完成瞬间的原生中间对象**：`rawler::imgop::develop::Intermediate`（`raw_processing.rs:185` `developer.develop_intermediate`），内部持有 `imgop::Img<f32>`（来自 `imgop`/`imgref` 的浮点缓冲）。它已经是**黑电平扣除 + 白电平归一 + 校准(calibrate) + demosaic + 高光恢复**完成后的**线性场景参照** RGB（或 Mono / FourColor）f32 数据。
- **统一内存表示（贯穿全管线）**：`Intermediate` 被立即转换为 `image::DynamicImage::ImageRgba32F(ImageBuffer<Rgba<f32>>)`（`raw_processing.rs:251-269`），再经 `apply_orientation` 返回（`raw_processing.rs:29`）。自此往下（空间变换、GPU 渲染、AI 模型、导出、水印）全部以 `DynamicImage` 为载具，RAW 与非 RAW 归一为同一类型。
- **在内存中的持有**：解码基线图以 `Arc<DynamicImage>` 形式存入 `state.original_image`（`app_state.rs:33-38`，`image_loader.rs:1000` 写入），并通过 `get_original_image`（`lib.rs:921`）供各管线取用；另有 `decoded_image_cache`（`cache_utils.rs:243-246`，容量有限）、`full_warped_cache`、`full_transformed_cache`、`thumbnail_geometry_cache`、`gpu_image_cache` 等均以 `Arc<DynamicImage>` 缓存中间态。
- **上 GPU 的格式转换**：`process_and_get_dynamic_image_with_precision` 接收 `&DynamicImage`，经 `to_rgba_f16`（`gpu_processing.rs:506-509`）将 `f32` 降为 `f16`，上传为 **`wgpu::TextureFormat::Rgba16Float`** 2D 纹理（`gpu_processing.rs:1917-1934`）。WGSL `fs_main` 以 `texture_2d<f32>` 读取该半浮点纹理。
- **数值语义**：该 RGBA-f32 / f16 缓冲存放**线性场景参照**值（可选 sRGB→linear 去伽马），值域通常 [0,1]；非 fast-demosaic 模式下 `clamp_limit = 1000.0`（`raw_processing.rs:192-198`），即允许 HDR/高光 >1.0，因此必须用浮点（而非 u8/u16）持有。

## 1. RAW 解码的三阶段与各自产物类型

RAW 文件经 `raw_processing.rs::develop_internal` 完成开发，经历三种形态：

| 阶段 | 类型 | 说明 |
| --- | --- | --- |
| ① 底层解码 | `rawler::rawimage::RawImage` | CFA 马赛克原始数据（每像素 u16），含 `blacklevel`/`whitelevel`/`wb_coeffs` 等元数据（`raw_processing.rs:131`） |
| ② 开发（demosaic+calibrate+level） | `rawler::imgop::develop::Intermediate` | **本调研核心**：线性 f32 缓冲，已 demosaic/校准/电平归一/高光恢复 |
| ③ 统一封装 | `image::DynamicImage::ImageRgba32F` | 转 RGBA-f32，供下游全部管线消费 |

```185:185:src-tauri/src/raw_processing.rs
    let mut developed_intermediate = developer.develop_intermediate(&raw_image)?;
```

`RawDevelop::default()` 默认步骤含 `Demosaic`/`Calibrate`/`SRgb` 等；`develop_internal` 按格式裁剪步骤（`raw_processing.rs:168-179`）：
- **LinearRaw 格式**：移除 `SRgb`/`Demosaic`（线性 raw 已是多通道），并按 `apply_calibration` 决定是否保留 `Calibrate`；
- **普通 Bayer（fast）**：`DemosaicAlgorithm::Speed` + 移除 `SRgb`；
- **普通 Bayer（quality）**：仅移除 `SRgb`。

无论哪条路径，`develop_intermediate` 返回的 `Intermediate` 都是**开发完成**的 RGB（或 Mono/FourColor）f32。

## 2. 核心中间对象：`Intermediate`（decode/demosaic/calibrate 完成态）

`Intermediate` 来自 rawler 的 `imgop::develop`，是解码/demosaic/calibrate 真正落定的载体。其内部为 `imgop::Img<f32>`（chunky 或 planar 浮点缓冲，由 `imgop`/`imgref` 提供），变体：

- `Intermediate::ThreeColor(Img<[f32;3]>)` — Bayer 等 demosaic 后的 RGB（最常见）；
- `Intermediate::Monochrome(Img<f32>)` — 单色；
- `Intermediate::FourColor(...)` — RGBE 等四通道。

随后 `develop_internal` 对 `Intermediate` 逐像素做**黑/白电平重缩放 + 可选去伽马 + 高光恢复**（`raw_processing.rs:207-247`）：

```207:247:src-tauri/src/raw_processing.rs
    match &mut developed_intermediate {
        Intermediate::ThreeColor(pixels) => {
            pixels.data.iter_mut().for_each(|p| {
                let mut r = (p[0] * rescale_factor).max(0.0);
                ...
                let (rec_r, rec_g, rec_b) = recover_clipped_pixel(r, g, b);
                p[0] = rec_r.clamp(0.0, clamp_limit);
                ...
            });
        }
        ...
    }
```

关键数值细节：
- `rescale_factor = (u32::MAX - black) / (white - black)`（`raw_processing.rs:189-190`），把原始量化值映射到 f32 线性比例；
- `clamp_limit`：fast-demosaic 为 `1.0`，否则为 `1000.0`（`raw_processing.rs:192-198`）—— 后者支撑 HDR/高光压缩，是必须用 f32 而非整型的根本原因；
- `recover_clipped_pixel`（`raw_processing.rs:60-107`）做高光色彩恢复（去品红/降饱和）。

**结论**：在「demosaic/calibrate 完成、尚未转 `DynamicImage`」这一刻，图像数据就活在 `Intermediate`（= `Img<f32>`）里，且已是**线性场景参照**。

## 3. 转换为统一内存表示：`DynamicImage::ImageRgba32F`

`Intermediate` 随即被转换为 `image` crate 的 `DynamicImage`，统一为 **RGBA-f32 紧凑数组**：

```251:269:src-tauri/src/raw_processing.rs
    let dynamic_image = match developed_intermediate {
        Intermediate::ThreeColor(pixels) => {
            let buffer = ImageBuffer::<Rgba<f32>, _>::from_fn(width, height, |x, y| {
                let p = pixels.data[(y * width + x) as usize];
                Rgba([p[0], p[1], p[2], 1.0])
            });
            DynamicImage::ImageRgba32F(buffer)
        }
        Intermediate::Monochrome(pixels) => {
            ...
            DynamicImage::ImageRgba32F(buffer)
        }
        _ => { return Err(anyhow!("Unsupported intermediate format for conversion")); }
    };
```

- 注意：即便源是 Mono/ThreeColor，统一补成 **RGBA**（alpha=1.0），便于后续 GPU 纹理与混合一致；
- `develop_raw_image` 再 `apply_orientation(developed_image, orientation)` 返回最终 `DynamicImage`（`raw_processing.rs:21-30`）。

**从此，RAW 解码产物的「标准内存形态」就是 `DynamicImage::ImageRgba32F`**——线性 f32 × RGBA。这与 000005 中 LUT/色彩管线期望的「线性场景参照输入」完全吻合（LUT 的 `composite_rgb_linear` 即源自此）。

## 4. 在内存中的持有与缓存（`Arc<DynamicImage>`）

解码基线图不是用完即弃，而是以 `Arc` 共享持有：

- **基线原图**：`AppState.original_image: Mutex<Option<LoadedImage>>`，其中 `LoadedImage { path, image: Arc<DynamicImage>, is_raw }`（`app_state.rs:33-38`）。`image_loader.rs:1000` 在加载完成后写入：
  ```1000:1003:src-tauri/src/image_loader.rs
      *state.original_image.lock().unwrap() = Some(LoadedImage {
          path, image: pristine_arc, is_raw,
      });
  ```
- **取用入口**：`get_original_image(state)` 返回 `(Arc<DynamicImage>, bool)`（`lib.rs:921`），导出/预览/掩码等管线均由此取基线；
- **解码缓存**：`decoded_image_cache: Mutex<DecodedImageCache>`（容量有限 LRU 式，`cache_utils.rs:243-246`：`items: Vec<(String, Arc<DynamicImage>, HashMap<String,String>)>`），按路径缓存「解码图 + EXIF」；
- **中间态缓存**（皆为 `Arc<DynamicImage>`）：`full_warped_cache` / `patched_warped_cache` / `full_transformed_cache`（`app_state.rs:169-171`，几何/AI 修补/变换结果）、`thumbnail_geometry_cache`（`ThumbnailGeometryEntry = (u64, Arc<DynamicImage>, f32)`）、`gpu_image_cache`（GPU 上传后的纹理侧缓存）。

**含义**：核心中间对象在「长期驻留」语义上是 `Arc<DynamicImage>`（具体变体 `ImageRgba32F`）。`Arc` 使预览、导出、掩码、AI 等多处零拷贝共享同一份 f32 缓冲。

## 5. 上 GPU 的格式转换（f32 → f16 / `Rgba16Float`）

`DynamicImage` 进入 GPU 渲染前，需落到 wgpu 纹理。`to_rgba_f16` 把 f32 降为半精度：

```506:509:src-tauri/src/gpu_processing.rs
fn to_rgba_f16(img: &DynamicImage) -> Vec<f16> {
    let rgba_f32 = img.to_rgba32f();
    rgba_f32.into_raw().into_iter().map(f16::from_f32).collect()
}
```

主渲染入口 `process_and_get_dynamic_image_with_precision` 用其构建 **`Rgba16Float`** 2D 纹理：

```1917:1934:src-tauri/src/gpu_processing.rs
        let img_rgba_f16 = to_rgba_f16(base_image);
        let texture_size = wgpu::Extent3d { width, height, depth_or_array_layers: 1 };
        ...
        format: wgpu::TextureFormat::Rgba16Float,
        usage: wgpu::TextureUsages::TEXTURE_BINDING | wgpu::TextureUsages::COPY_DST,
```

- WGSL 侧以 `texture_2d<f32>` 绑定（在 `shader.wgsl` 中 `input_texture: texture_2d<f32>`），即 `Rgba16Float` 被当作 f32 采样；
- 全管线中间/输出纹理统一为 `Rgba16Float`（见 `gpu_processing.rs:622/694/736/794/961/1009/1047/1174` 等），仅少量预览/合成用 `Rgba8Unorm`；
- **精度提示**：CPU 侧为 f32，上传时降 f16，故 GPU 内实际以**半浮点**运算。对极高动态范围（>1.0）与精细 LUT/色彩分级，f16 可能引入可忽略的量化误差——若 FotLab 需更高保真，可考虑 `Rgba32Float`（需硬件支持 + 带宽权衡）。

## 6. 数值语义小结（为何必须是浮点）

| 属性 | 取值 |
| --- | --- |
| 色彩空间 | **线性场景参照**（可选 sRGB→linear 去伽马，`raw_processing.rs:40-46,211-227`） |
| 通道/位深 | RGBA，**f32**（CPU）/ **f16**（GPU 纹理） |
| 典型值域 | [0, 1] |
| HDR/高光上限 | fast 模式 `1.0`；质量模式 `1000.0`（`raw_processing.rs:192-198`） |
| alpha | 恒为 1.0（解码阶段补满，见 §3） |

正是「线性 + 可能 >1.0 + 需色彩分级/LUT」的组合，决定了 RapidRAW 必须全程用浮点（f32 内存 / f16 显存），而非 `ImageRgb8/u16`。这解释了 000005 中 LUT 为何能在线性域（scene-referred，经 `linear_to_vlog`）采样。

## 7. 非 RAW 输入的归一化（旁路）

对 JPEG/PNG 等，无 demosaic/calibrate，但同样归一为 `DynamicImage`：
- 嵌入预览经 `linearize_embedded_preview` → `ImageRgb32F` 并乘 0.4 近似线性（`image_loader.rs:342-351`）；
- 普通图像经 `load_image_with_orientation` 转 `ImageRgb32F`（`image_loader.rs:395`）。

因此**无论来源，进入色彩/LUT/GPU 管线的统一对象始终是 `DynamicImage`（多为 `ImageRgba32F`/`ImageRgb32F`）**——`Intermediate` 只是 RAW 路径特有的「demosaic/calibrate 完成态」中间类型。

## 8. 对 FotLab 的启示 / 复用价值

1. **RAW 开发链路可整体借鉴**：rawler 的 `RawDevelop::develop_intermediate` → `Intermediate(Img<f32>)` → `DynamicImage::ImageRgba32F` 是成熟、正确的「线性 f32 中间表示」范式；FotLab 若做 RAW 支持，建议沿用「解码产物 = 线性 f32，绝不落 u8/u16」原则。
2. **统一内存类型是关键解耦点**：RapidRAW 用单一的 `DynamicImage`（`ImageRgba32F`）贯穿 CPU/GPU/AI/导出，使 `raw_processing` / `gpu_processing` / `ai_processing` / `export_processing` 互不耦合——FotLab 同样应以一个统一像素容器作为模块间契约。
3. **`Arc<DynamicImage>` 共享缓存模式**：`original_image` + 多级 `Arc` 缓存避免重复解码/变换，值得复用；注意 `decoded_image_cache` 的容量限制（`cache_utils.rs`）防内存膨胀。
4. **GPU 半浮点取舍**：RapidRAW 选 `Rgba16Float` 平衡精度/带宽。FotLab 若主打 HDR/电影级 LUT，建议在 000005 §8 的 S/N/F-Log 工作流中评估 `Rgba32Float` 或先做 f32→f16 的误差验证。
5. **与 LUT/log 调研（000005 §8）的衔接**：本档证实「线性 f32 中间态」确实存在，但 RapidRAW **不保留相机 log 中间态**（V-Log 是着色器内临时 `linear_to_vlog` 计算的）。若 FotLab 要支持 S-Log/N-Log/F-Log 输入 LUT，应在 §3/§4 这一层提供「解码到指定 log 空间」的变体，而非依赖着色器临时编码。

## 关键文件索引

| 关注点 | 文件:行 |
| --- | --- |
| RAW 解码入口（返回 `DynamicImage`） | `src-tauri/src/raw_processing.rs:15-30` |
| `develop_intermediate` → `Intermediate` | `src-tauri/src/raw_processing.rs:185` |
| `Intermediate` 后处理（电平/去伽马/高光恢复） | `src-tauri/src/raw_processing.rs:189-247` |
| `Intermediate` → `DynamicImage::ImageRgba32F` | `src-tauri/src/raw_processing.rs:251-269` |
| `RawImage`（CFA 原始 / 解码①） | `src-tauri/src/raw_processing.rs:131` |
| 步骤裁剪（Demosaic/Calibrate 取舍） | `src-tauri/src/raw_processing.rs:168-179` |
| 基线原图持有 `Arc<DynamicImage>` | `src-tauri/src/app_state.rs:33-38`；`src-tauri/src/image_loader.rs:1000` |
| 取用入口 `get_original_image` | `src-tauri/src/lib.rs:921` |
| 解码缓存 `DecodedImageCache` | `src-tauri/src/cache_utils.rs:243-246`；`src-tauri/src/app_state.rs:172` |
| 中间态 `Arc<DynamicImage>` 缓存 | `src-tauri/src/app_state.rs:166-172` |
| f32→f16 转换 | `src-tauri/src/gpu_processing.rs:506-509` |
| 主图上传为 `Rgba16Float` 纹理 | `src-tauri/src/gpu_processing.rs:1917-1934` |
| 半浮点纹理格式统一 | `src-tauri/src/gpu_processing.rs:622,694,736,794,961,1009,1047,1174` |
| 非 RAW 线性化 | `src-tauri/src/image_loader.rs:342-351,395` |
