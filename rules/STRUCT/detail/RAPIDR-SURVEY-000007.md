# 调研 — RapidRAW 如何对待 JPG/PNG 等非 RAW 文件：解码、色彩管理与管线分支

- ID: RAPIDR-SURVEY-000007
- Status: Draft
- Priority: P2
- Created: 2026-09-18
- Owner: —
- Related: `rules/STRUCT/detail/RAPIDR-SURVEY-000003.md`（渲染/加速）、`rules/STRUCT/detail/RAPIDR-SURVEY-000004.md`（处理管线）、`rules/STRUCT/detail/RAPIDR-SURVEY-000005.md`（LUT，消费线性输入）、`rules/STRUCT/detail/RAPIDR-SURVEY-000006.md`（`Intermediate→DynamicImage::ImageRgba32F→Arc<DynamicImage>` 内存表示）

> **范围声明**：基于 `external/RapidRAW` 的 shallow clone（`--depth 1`，`v1.6.4`）。只做文档化，不改上游。聚焦：RapidRAW 把 JPG/PNG 这类「非 RAW」当什么处理？有无 demosaic/calibrate？色彩空间怎么管？GPU 管线与非 RAW 有何分支？有哪些局限（对 FotLab 的启示）。

## TL;DR（结论先行）

| 问题 | 结论 |
| --- | --- |
| 如何判定 RAW / 非 RAW | 纯**扩展名**匹配：`is_raw_file`（`formats.rs:81`）查 `RAW_EXTENSIONS`（dng/arw/nef/cr3/raf…）；JPG/PNG/webp/jxl/exr/hdr/tiff 等落在 `NON_RAW_EXTENSIONS`（`formats.rs:73-79`）。**不看 Magic Number、不看容器内容**。 |
| 非 RAW 走不走 demosaic/calibrate | **不走**。RAW 走 `raw_processing::develop_raw_image`（demosaic+calibrate→线性 f32）；非 RAW 走 `load_image_with_orientation`（`image_loader.rs:353`），用 `image` crate 直接解码 + EXIF 方向校正，**无 demosaic、无黑/白点、无传感器 WB**。 |
| 非 RAW 在内存里是什么 | `DynamicImage::ImageRgb32F`（**3 通道、sRGB 编码值直接当 f32**，`to_rgb32f()` 仅做 8bit/255，不做 sRGB→linear），与 RAW 的 `ImageRgba32F`（4 通道、线性）不同。 |
| 色彩管理对不对 | **GPU 主管线正确**：`shader.wgsl:1718-1726` 在管线入口对 `is_raw==0`（非 RAW）先 `srgb_to_linear` 再进入线性色彩处理；对 RAW 直接用线性值。LUT（见 000005）仍消费线性输入，一致。 |
| 默认「观感」差异 | 输出阶段（tonemap，1913-1924）：RAW 默认 `linear_to_srgb` + `BRIGHTNESS_GAMMA 1.1` + smoothstep 对比曲线（更「 punchy」）；非 RAW 仅纯 `linear_to_srgb`。所以同一张图导出成 JPG 再导入，观感不等于原 RAW。 |
| 主要坑 | **完全忽略 ICC/色彩描述文件，强制按 sRGB 解读**；HDR/EXR 也在非 RAW 列表却按 sRGB 处理（潜在偏色）；8bit JPG 量化/条带；非 RAW 无 alpha（3 通道）。 |

## 1. 判定：`is_raw` 如何决定路线

纯扩展名匹配，无内容嗅探：

```81:90:external/RapidRAW/src-tauri/src/formats.rs
pub fn is_raw_file<P: AsRef<Path>>(path: P) -> bool {
    let ext = match path.as_ref().extension().and_then(|s| s.to_str()) {
        Some(e) => e,
        None => return false,
    };
    RAW_EXTENSIONS.iter().any(|(raw_ext, _)| raw_ext.eq_ignore_ascii_case(ext))
}
```

非 RAW 扩展名清单（`formats.rs:73-79`）：`jpg jpeg png gif bmp tiff tif webp jxl exr hdr tga ico dds qoi ff pnm/pbm/pgm/ppm/pam`。**注意 `exr`/`hdr` 也在非 RAW 列表**——这对 HDR 文件是隐患（§7.2）。

该布尔值被一路携带：`LoadedImage.is_raw`（`app_state.rs:33-38`）→ `LoadImageResult.is_raw` → 前端 `SelectedImage.isRaw` → 后端 `get_global_adjustments_from_json(..., is_raw, ...)` 写进 `GlobalAdjustmentsParams.is_raw_image`（`image_processing.rs:2295`）→ 最终进 `shader.wgsl` 的 `is_raw_image: u32`。

## 2. 解码：非 RAW 路径「什么都不做」（无 demosaic/calibrate）

`load_base_image_from_bytes`（`image_loader.rs:80-193`）两条分支：

```174:192:external/RapidRAW/src-tauri/src/image_loader.rs
    } else {
        let mut image = load_image_with_orientation(bytes, cancel_token)?;
        if apply_to_non_raws
            && !use_fast_raw_dev
            && (color_nr_amount > 0.0 || sharpening_amount > 0.0)
        {
            // 仅当 settings.apply_preprocessing_to_non_raws 为真（默认 false）才做 NR/锐化
            remove_raw_artifacts_and_enhance(&mut image, color_nr_amount, sharpening_amount);
        }
        Ok(image)
    }
```

非 RAW 解码主体（`load_image_with_orientation`，`image_loader.rs:353-396`）：`ImageReader::decode()` → 读 EXIF `Orientation` → `apply_orientation` → **`DynamicImage::ImageRgb32f(oriented_image.to_rgb32f())`**。

关键事实：`image` crate 的 `to_rgb32f()` **只做 `u8/255` 归一化，不做 sRGB→linear 转换**。所以 JPG/PNG 的 sRGB 编码（gamma）被原样当作 f32 数值载入。这与 RAW（demosaic 后已是线性 f32）形成对照。

> 没有 demosaic、没有 black/white point、没有相机 WB 矩阵——这些都只在 `raw_processing` 里发生。`apply_cpu_default_raw_processing`（`image_processing.rs:1161`，做默认 gamma 2.38 之类处理）**仅当 `is_raw` 时调用**（见 `lib.rs:307`、`file_management.rs:1764`、`focus_stacking.rs:2093`），非 RAW 完全跳过。

## 3. 内存表示：3 通道 sRGB-float vs 4 通道线性-float

| | RAW | 非 RAW（JPG/PNG…） |
| --- | --- | --- |
| 解码产物类型 | `DynamicImage::ImageRgba32F`（4 通道） | `DynamicImage::ImageRgb32F`（3 通道，无 alpha） |
| 数值含义 | 线性光（经 demosaic/calibrate） | sRGB 编码值（gamma）直接当 f32 |
| 缓存 | `state.original_image`（LoadedImage） | 同左，`is_raw=false` |
| 下游 | GPU 直接当线性用 | GPU 先 `srgb_to_linear`（§4.1） |

`composite_patches_on_image`（`image_loader.rs:398-821`）用 `match` 同时处理 `ImageRgb32F`/`ImageRgba32F`/`_` 三种变体，所以 3 通道非 RAW 也能正常走合成/几何/缩放。非 RAW 的 alpha 在加载阶段即已丢弃。

## 4. 色彩管理核心：GPU 主管线按 `is_raw` 分支（关键）

### 4.1 管线入口的线性化（决定性的一行）

```1718:1726:external/RapidRAW/src-tauri/src/shaders/shader.wgsl
    var initial_linear_rgb: vec3<f32>;
    let is_raw = adjustments.global.is_raw_image;
    if (is_raw == 0u) {
        initial_linear_rgb = srgb_to_linear(color_from_texture);
    } else {
        initial_linear_rgb = color_from_texture;
    }
```

即**非 RAW 在色彩管线最开始被 `srgb_to_linear` 转成线性**，RAW 直接当作线性。之后 WB/exposure/tonemap/LUT 全部在线性空间完成，最后再编码回 sRGB（§4.2）。这是 RapidRAW 对非 RAW 色彩正确的根本保证。

### 4.2 输出 tonemap / sRGB 编码（raw 有额外对比曲线）

```1913:1924:external/RapidRAW/src-tauri/src/shaders/shader.wgsl
    if (adjustments.global.tonemapper_mode == 1u) {
        default_tonemapped = agx_full_transform(composite_rgb_linear);
    } else if (is_raw == 1u) {
        var srgb_emulated = linear_to_srgb(composite_rgb_linear);
        const BRIGHTNESS_GAMMA: f32 = 1.1;
        srgb_emulated = pow(srgb_emulated, vec3<f32>(1.0 / BRIGHTNESS_GAMMA));
        const CONTRAST_MIX: f32 = 0.75;
        let contrast_curve = srgb_emulated * srgb_emulated * (3.0 - 2.0 * srgb_emulated);
        default_tonemapped = mix(srgb_emulated, contrast_curve, CONTRAST_MIX);
    } else {
        default_tonemapped = linear_to_srgb(composite_rgb_linear);
    }
```

- RAW 默认：sRGB OETF + `1.1` 亮度 gamma + `0.75` 平滑对比曲线（更「 punchy」的默认观感）。
- 非 RAW 默认：纯 `linear_to_srgb`（标准 sRGB，无额外曲线）。
- **含义**：同一幅场景，RAW 与非 RAW 即使参数相同，导出观感也不同；RAW 导出成 JPG 再导入（round-trip）不会还原成原来的 RAW 观感。

### 4.3 锐化 / luma / 曝光 等算子也按 `is_raw` 分支

- 锐化 luma（`shader.wgsl:939-943`）：非 RAW 先 `linear_to_srgb_extended` 再取 luma（感知空间）；RAW 用 `sqrt(luma)`（线性空间）。
- 曝光（`shader.wgsl:1033-1036`）：RAW `color * ratio²`；非 RAW `srgb_to_linear(max(color_enc * ratio))`——曝光以「亮度缩放」模型作用于 sRGB 编码值再回线性。
- Flare（`gpu_processing.rs:1327-1330`）：`is_raw` 直接进 `FlareParams.is_raw`。

### 4.4 LUT 仍消费线性输入（与 000005 一致）

非 RAW 经 §4.1 `srgb_to_linear` 后已是线性，LUT（见 000005）在线性空间应用，**与 RAW 完全一致**——无需为 JPG/PNG 单独改 LUT 流程。这点对 FotLab 复用 RapidRAW 的 LUT 机制是利好。

## 5. 其它分支点（非主预览路径）

- **`apply_cpu_default_raw_processing` 仅 RAW**：`lib.rs:307-309`（`if is_raw { apply_cpu_default_raw_processing(...) }`）、`file_management.rs:1764`、`focus_stacking.rs:2093`。非 RAW 跳过。
- **导出路径（无调整时）**（`file_management.rs:1755-1767`）：
  ```1759:1764:external/RapidRAW/src-tauri/src/file_management.rs
        if use_agx {
            if !is_raw {
                final_image = crate::image_processing::apply_srgb_to_linear(final_image);
            }
            crate::image_processing::apply_cpu_agx_tonemap(&mut final_image);
        } else if is_raw {
            apply_cpu_default_raw_processing(&mut final_image);
        }
  ```
  AGX + 非 RAW 时显式 `apply_srgb_to_linear` 再 tonemap；非 RAW 且非 AGX 且无调整时基本原样输出。
- **HDR 合成**（`hdr_deghosting.rs:60-62`）：`if !is_raw_file(path) { apply_srgb_to_linear(...) }`——与非 RAW 一致先线性化再合并，逻辑自洽。
- **对焦堆栈**（`focus_stacking.rs:2093-2095`）：仅 RAW 做 `apply_cpu_default_raw_processing`。

## 6. 前端差异（UX 层）

- `is_raw` → `SelectedImage.isRaw`（`src/hooks/useImageLoader.ts:86`）。
- **库过滤**：`RawStatus`（`All`/`RawOnly`/`NonRawOnly`，`AppProperties.tsx:140-144`），`MainLibrary.tsx:201-205`、`useSortedLibrary.ts:58-61` 据此过滤。
- **降噪模态**（`DenoiseModal.tsx:264-265`）：RAW → AI 降噪（强度 50）；非 RAW → BM3D（强度 15）。
- 白平衡面板（`Color.tsx:473`）对非 RAW 仍可见——它只是温度/色调的线性乘法（`shader.wgsl:689-692`），对任意图都生效。

## 7. 局限与坑（对 FotLab 的启示）

### 7.1 完全忽略 ICC / 色彩描述文件，强制按 sRGB
RapidRAW 从不读取 PNG/JPG 内嵌的 ICC profile，也无色彩管理模块——所有非 RAW 一律当 sRGB 编码解读。若源是 ProPhoto / Display-P3 / AdobeRGB 的 JPG/PNG，会被错误映射，明显偏色。FotLab 若要做「正确的非 RAW 编辑」，应补 ICC/色彩空间识别（这是 RapidRAW 的明确缺口）。

### 7.2 HDR / EXR 在「非 RAW」列表却按 sRGB 处理（潜在偏色）
`exr`/`hdr`（`formats.rs:75`）属 `NON_RAW_EXTENSIONS`，因此走非 RAW 分支：被 `image` crate 解码后由 `srgb_to_linear` 解读。但 EXR 通常已是线性/场景-referred，再喂 `srgb_to_linear` 会**二次线性化 → 偏暗/偏色**。这是 RapidRAW 对非 RAW 色彩假设过简的硬伤，FotLab 需为 HDR 单独建「线性输入」路径，不能复用 JPG/PNG 的 sRGB 假设。

### 7.3 8bit 量化与 alpha 丢失
JPG 8bit → `to_rgb32f` 仅 `/255`，渐变区易条带；非 RAW 加载为 `ImageRgb32F`（3 通道），alpha 在解码即丢弃（PNG 透明通道不保留）。

### 7.4 RAW 默认对比曲线不作用于非 RAW（round-trip 不一致）
见 §4.2：RAW 默认带额外 `1.1` gamma + 对比曲线，非 RAW 仅纯 sRGB。导致「RAW 导出成 JPG → 再导入编辑」无法还原原观感。FotLab 若想统一观感，应在非 RAW 上也可选「RAW 风格 tonemap」或显式记录来源。

### 7.5 CPU / 安卓回退路径的 sRGB 处理需复核
桌面 GPU 路径（§4）已正确按 `is_raw` 线性化。但安卓 `use_wgpu_renderer=false`（`000003` §5.3）走 CPU 回退（`process_and_get_dynamic_image` 返回 JPEG/PNG 字节）。CPU 导出路径（`file_management.rs:1755`）只在「无调整 + AGX」时才显式 `apply_srgb_to_linear`，**完整 CPU 实时预览路径对非 RAW 的 srgb 处理建议单独复核**，确认与 GPU 主线行为一致，否则安卓端非 RAW 预览可能偏色。

## 8. RAW vs 非 RAW 逐阶段对照

| 阶段 | RAW | 非 RAW（JPG/PNG…） |
| --- | --- | --- |
| 判定 | `is_raw_file`=true（扩展名） | false |
| 解码 | `develop_raw_image`（demosaic+calibrate→线性 f32） | `ImageReader::decode` + EXIF 方向 |
| 默认 RAW 处理 | `apply_cpu_default_raw_processing`（gamma 等） | 跳过（除非 `apply_preprocessing_to_non_raws`） |
| 内存类型 | `ImageRgba32F`（4ch 线性） | `ImageRgb32F`（3ch sRGB-float） |
| 管线入口 | 直接当线性 | `srgb_to_linear`（shader 1718-1726） |
| tonemap 默认 | sRGB + 1.1γ + 对比曲线 | 纯 `linear_to_srgb` |
| 锐化/luma | 线性空间 | sRGB 感知空间 |
| 曝光模型 | `color*ratio²` | `srgb_to_linear(srgb*ratio)` |
| LUT | 线性消费 | 线性消费（一致） |
| 降噪默认 | AI（强度 50） | BM3D（强度 15） |

## 关键文件索引

| 主题 | 位置 |
| --- | --- |
| 扩展名判定 RAW / 非 RAW | `src-tauri/src/formats.rs:4-90` |
| 解码分叉（raw vs 非 raw） | `src-tauri/src/image_loader.rs:80-193` |
| 非 RAW 解码（to_rgb32f，无线性化） | `src-tauri/src/image_loader.rs:353-396` |
| LoadedImage（is_raw 字段） | `src-tauri/src/app_state.rs:33-38` |
| 默认 RAW 处理（仅 raw 调用） | `src-tauri/src/image_processing.rs:1161-1182` |
| is_raw → GPU 参数 | `src-tauri/src/image_processing.rs:2295` |
| **GPU 入口线性化（非 raw srgb_to_linear）** | `src-tauri/src/shaders/shader.wgsl:1718-1726` |
| GPU tonemap 输出分支（raw 额外曲线） | `src-tauri/src/shaders/shader.wgsl:1913-1924` |
| GPU 锐化/luma、曝光分支 | `src-tauri/src/shaders/shader.wgsl:939-943, 1033-1036` |
| srgb_to_linear / linear_to_srgb 实现 | `src-tauri/src/shaders/shader.wgsl:222-237` |
| 导出路径非 raw AGX 线性化 | `src-tauri/src/file_management.rs:1755-1767` |
| HDR 合成非 raw 线性化 | `src-tauri/src/hdr_deghosting.rs:60-62` |
| 前端 isRaw / 库过滤 / 降噪分支 | `src/hooks/useImageLoader.ts:86`、`src/components/ui/AppProperties.tsx:140-144`、`src/components/modals/DenoiseModal.tsx:264-265` |

## 与 FotLab 的关系 / 备注

- RapidRAW 对非 RAW 的处理**模型简单且基本正确**（GPU 主线按 `is_raw` 正确线性化），可作为 FotLab 非 RAW 预览/编辑的参考实现。
- 但有三处 RapidRAW **明确缺口**，FotLab 若要做「专业级非 RAW」应补强：(1) **ICC/色彩空间识别**（§7.1）；(2) **HDR/EXR 不应复用 JPG 的 sRGB 假设**（§7.2）；(3) **安卓 CPU 回退路径的 sRGB 一致性复核**（§7.5）。
- LUT 机制对非 RAW 透明可用（§4.4），FotLab 可放心复用 RapidRAW 的 LUT 加载/应用层。
