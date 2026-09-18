# 调研 — RapidRAW 调色 HSL/HSV：经典 sRGB/线性 HSV，未采用 OKLab/OKHSL/OKHSV

- ID: RAPIDR-SURVEY-000008
- Status: Draft
- Priority: P2
- Created: 2026-09-18
- Owner: —
- Related: `rules/STRUCT/detail/RAPIDR-SURVEY-000004.md`（处理管线）、`rules/STRUCT/detail/RAPIDR-SURVEY-000005.md`（LUT，线性输入）、`rules/STRUCT/detail/RAPIDR-SURVEY-000007.md`（非 RAW 色彩管理）

> **范围声明**：基于 `external/RapidRAW` 的 shallow clone（`--depth 1`，`v1.6.4`）。只做文档化，不改上游。聚焦：RapidRAW 的调色 HSL/HSV 是「sRGB/线性直接转 HSV」的经典做法，还是用了 OKLab/OKHSL/OKHSV 这类感知均匀空间？

## TL;DR（结论先行）

| 问题 | 结论 |
| --- | --- |
| 是否使用 OKLab / OKLCH / OKHSL / OKHSV | **完全没有**。全仓（Rust + WGSL + 前端）搜索 `oklab\|oklch\|okhsl\|okhsv` **0 命中**，也无 CIELab/Lab。 |
| 用的是哪种 HSL/HSV | **经典 HSV**（标准 `rgb_to_hsv`/`hsv_to_rgb` 公式）。所谓「HSL 面板」实为 **HSV 变体**（`rgb_to_hsv` + 亮度匹配技巧），并非真 HSL、更非 OKHSL。 |
| 在哪套色彩空间算 HSV | **两处不一致**：全局 `hue/saturation/vibrance` 在 **sRGB 编码空间**算（`linear_to_srgb_extended`→HSV→回 linear）；8 色带「HSL 面板」直接在 **线性空间**算 `rgb_to_hsv(linear)`。两者都非感知均匀。 |
| 调色功能面 | 8 色带 HSL 面板（红/橙/黄/绿/青/蓝/紫/品红）、全局 Hue、Saturation、Vibrance、4 区 Color Grading（阴影/中间调/高光/全局）、Color Calibration（R/G/B 色相/饱和）。全部走经典 HSV。 |

## 1. 证据：全仓无任何感知色彩空间

```text
搜索 oklab|oklch|okhsl|okhsv|oklr|okch 于 external/RapidRAW（含 frontend/rust/wgsl）：0 命中
搜索 cielab|lab_color|Lab( 于 src-tauri：0 命中
```

RapidRAW 的色彩编辑全部建立在自写的 `rgb_to_hsv` / `hsv_to_rgb`（`shader.wgsl:256-284`）之上，没有任何 OKLab 家族换算函数。

## 2. HSV 实现细节（WGSL 主线）

标准公式，无感知修正：

```256:269:external/RapidRAW/src-tauri/src/shaders/shader.wgsl
fn rgb_to_hsv(c: vec3<f32>) -> vec3<f32> {
    let c_max = max(c.r, max(c.g, c.b));
    let c_min = min(c.r, min(c.g, c.b));
    let delta = c_max - c_min;
    // ... 标准 h/s/v 计算
    return vec3<f32>(h, s, c_max);
}
```

### 2.1 三个调色函数各自的色彩空间（关键不一致）

**(a) 全局 Hue 旋转**（`apply_hue_shift`，`shader.wgsl:286-296`）——**sRGB 空间 HSV**：

```290:295:external/RapidRAW/src-tauri/src/shaders/shader.wgsl
    let srgb_color = linear_to_srgb_extended(color);
    let hsv = rgb_to_hsv(srgb_color);
    var shifted_h = hsv.x + shift_degrees;
    shifted_h = (shifted_h + 360.0) % 360.0;
    let shifted_srgb = hsv_to_rgb(vec3<f32>(shifted_h, hsv.y, hsv.z));
    return srgb_to_linear(shifted_srgb);
```

即：线性 → `linear_to_srgb_extended` → HSV → 改色相 → 回 sRGB → 线性。

**(b) Saturation / Vibrance**（`apply_creative_color`，`shader.wgsl:703-704`）——同样 **sRGB 空间 HSV**：

```703:704:external/RapidRAW/src-tauri/src/shaders/shader.wgsl
    let srgb = linear_to_srgb_extended(max(processed, vec3<f32>(0.0)));
    let hsv = rgb_to_hsv(srgb);
```

**(c) 8 色带「HSL 面板」**（`apply_hsl_panel`，`shader.wgsl:733-789`）——**直接在线性空间算 HSV**（无 sRGB 往返）：

```738:738:external/RapidRAW/src-tauri/src/shaders/shader.wgsl
    let original_hsv = rgb_to_hsv(safe_color);
```

`safe_color` 是 fs_main 传入的**线性**合成色。该函数对线性 RGB 直接做 `rgb_to_hsv`，再对各色带做色相/饱和/亮度加权，最后用**亮度匹配**把结果拉回目标 luma：

```781:787:external/RapidRAW/src-tauri/src/shaders/shader.wgsl
    let hs_shifted_rgb = hsv_to_rgb(vec3<f32>(hsv.x, hsv.y, original_hsv.z));
    let new_luma = get_luma(hs_shifted_rgb);
    let target_luma = original_luma * (1.0 + total_lum_adjust);
    // ...
    let final_color = hs_shifted_rgb * (target_luma / new_luma);
```

> 注意：它名为 HSL，实为 **HSV（用 `value=max`，而非 HSL 的 L=(max+min)/2）+ 亮度匹配 rescale**。既不是真 HSL，也不是 OKHSL。

### 2.2 Color Grading（阴影/中间调/高光/全局）

`apply_color_grading`（`shader.wgsl:791-822`）用 `hsv_to_rgb(vec3(hue,1,1))` 生成纯色染色，再按 luma 软遮罩加权叠加（813-819）。仍是经典 HSV 色相角，无 OKLab。

### 2.3 8 色带遮罩基于原始 HSV 色相角

`HSL_RANGES`（`shader.wgsl:186-195`）是 8 个固定 HSV 色相中心 + 宽度（红 358 / 橙 25 / 黄 60 / 绿 115 / 青 180 / 蓝 225 / 紫 280 / 品红 330）。`get_raw_hsl_influence`（298-301）按「色相角环形距离」算影响权重——即直接用的是 HSV 色相，非 OKHue（OKLab 的色相在感知上更均匀）。

## 3. 调用顺序与空间不一致（fs_main）

```1865:1870:external/RapidRAW/src-tauri/src/shaders/shader.wgsl
    composite_rgb_linear = apply_highlights_adjustment(composite_rgb_linear, ...);
    composite_rgb_linear = apply_color_calibration(composite_rgb_linear, ...);
    composite_rgb_linear = apply_hsl_panel(composite_rgb_linear, final_hsl, ...);   // 线性 HSV
    composite_rgb_linear = apply_hue_shift(composite_rgb_linear, t_hue);            // sRGB HSV
    composite_rgb_linear = apply_creative_color(composite_rgb_linear, t_saturation, t_vibrance); // sRGB HSV
```

顺序上「HSL 面板」先用线性 HSV，随后的「全局 hue/sat/vib」改用 sRGB HSV。两套 HSV 定义不同（线性 vs gamma），对同一像素的色相/饱和度数值含义不一致——这是 RapidRAW 调色实现里一个隐蔽的隐患。

## 4. Rust 侧与前端

- **Rust 侧**（`tagging.rs:56`）也有 `rgb_to_hsv`，用于缩略图自动打标（把像素 u8/255 当 sRGB 算 HSV 命名颜色），同样是经典 sRGB HSV。
- **数据结构**（`image_processing.rs:1421-1426`）`HslColor { hue, saturation, luminance }`，但 shader 始终按 HSV 使用（见 §2.1c），命名误导。
- **预设转换**（`preset_converter.rs:195-223`）把 Lightroom 的 `HueAdjustmentN`/`SaturationAdjustmentN` 映射进 RapidRAW 的 `hsl`/`colorGrading` JSON——说明 RapidRAW 的色相/饱和度模型就是经典 HSV 语义，与 LR 对齐，未引入 OK 空间。
- **前端**（`ColorWheel.tsx:162`）用 `hsva`（HSV + alpha）做取色；`Color.tsx` 暴露 8 色带 HSL 面板、4 区 Color Grading、Color Calibration（R/G/B 色相/饱和）。UI 层也是 HSV 语义。

## 5. 局限与含义（对 FotLab 的启示）

### 5.1 无感知均匀空间 → 经典 HSV 通病
RapidRAW 的色相旋转/饱和度编辑在 **sRGB（或线性）HSV** 上完成，会继承经典 HSV 的所有缺陷：色相环上各色「感知间距」不均（黄/青区被压缩、红/蓝区被拉伸），饱和度变化会连带改变感知亮度，肤色/天空等常见区域调色易溢出或发灰。OKHSL/OKHSV（Björn Ottosson）正是为解决这些问题而生。

### 5.2 空间不一致（线性 vs sRGB HSV）
「HSL 面板」用线性 HSV，全局 hue/sat 用 sRGB HSV（§3）。两者对同一像素的色相/饱和度数值含义不同，可能导致「用 8 色带面板调过的颜色，再叠全局饱和度时行为出乎意料」。FotLab 若复刻应统一到同一空间。

### 5.3 「HSL」命名误导
面板实为 HSV + 亮度匹配（§2.1c），并非真 HSL，更不是 OKHSL。文档/UI 命名应修正，避免误导用户以为在做感知均匀的 HSL 编辑。

### 5.4 色带遮罩基于原始 HSV 色相角
8 色带按 HSV 色相角环形距离加权（§2.3），边界在感知上不均；若改用 OKHue，色带边界会更贴合人眼对色相的区分。

### 5.5 FotLab 升级建议（低风险、高收益）
- 引入 OKLab/OKHSL/OKHSV 换算（Björn Ottosson 公式约 50 行，纯矩阵/多项式，无依赖）。
- **升级点很干净**：RapidRAW 的 LUT 与显示管线始终在 **线性 RGB**；只需把「线性 RGB → OKLab（线性 sRGB→LMS→OKLab 矩阵）→ 在 OKHSL/OKHSV 上做色相/饱和/亮度编辑 → 回线性 RGB」替换掉现有 `rgb_to_hsv`/`hsv_to_rgb` 那几步即可，不影响 LUT（线性输入，见 000005）与显示编码。
- Color Grading 的染色 `hsv_to_rgb(hue,1,1)` 可换成 OKLab 下的等色相纯色生成，遮罩色相角改用 OKHue，色带边界更均匀。
- 这是 FotLab 「调色质量」相对 RapidRAW 可**直接 leapfrog** 的点，且工程量小。

## 6. 对照表

| 维度 | RapidRAW 现状 | 若 FotLab 升级 |
| --- | --- | --- |
| 色相/饱和空间 | 经典 HSV（sRGB 或线性） | OKHSL / OKHSV（感知均匀） |
| 全局 Hue | sRGB HSV 旋转 | OKHue 旋转 |
| Saturation/Vibrance | sRGB HSV | OKHSL 饱和度 |
| 8 色带 HSL 面板 | 线性 HSV + 亮度匹配，命名「HSL」 | OKHSL + OKHue 遮罩，统一空间 |
| Color Grading 染色 | `hsv_to_rgb(hue,1,1)` | OKLab 等色相纯色 |
| 与 LUT/显示管线关系 | 线性 RGB 中插入 HSV 编辑 | 线性 RGB 中插入 OKLab 编辑（等价替换点） |

## 关键文件索引

| 主题 | 位置 |
| --- | --- |
| 全仓 OKLab 搜索 0 命中（证据） | 搜索 `oklab\|oklch\|okhsl\|okhsv` 于 `external/RapidRAW`：0 |
| 标准 `rgb_to_hsv` / `hsv_to_rgb` | `src-tauri/src/shaders/shader.wgsl:256-284` |
| 全局 Hue（sRGB HSV） | `src-tauri/src/shaders/shader.wgsl:286-296` |
| Saturation/Vibrance（sRGB HSV） | `src-tauri/src/shaders/shader.wgsl:695-730, 703-704` |
| 8 色带 HSL 面板（线性 HSV + 亮度匹配） | `src-tauri/src/shaders/shader.wgsl:733-789, 738` |
| Color Grading 染色 | `src-tauri/src/shaders/shader.wgsl:791-822, 813-819` |
| HSL_RANGES 8 色相带 | `src-tauri/src/shaders/shader.wgsl:186-195` |
| fs_main 调用顺序（空间不一致） | `src-tauri/src/shaders/shader.wgsl:1865-1870` |
| Rust 侧 `rgb_to_hsv`（缩略图打标） | `src-tauri/src/tagging.rs:56` |
| `HslColor` 结构（实为 HSV 使用） | `src-tauri/src/image_processing.rs:1421-1426` |
| LR 预设→hsl/colorGrading 映射 | `src-tauri/src/preset_converter.rs:195-223` |
| 前端取色（HSV）与面板 | `src/components/ui/ColorWheel.tsx:162`、`src/components/adjustments/Color.tsx` |

## 与 FotLab 的关系 / 备注

- RapidRAW 调色**未采用任何感知均匀色彩空间**，全程经典 HSV，且存在「线性 HSV vs sRGB HSV」的空间不一致。这是其调色质量的已知上限。
- 对 FotLab 而言，OKLab/OKHSL/OKHSV 是**低风险、高收益**的升级点：公式小、升级位置干净（线性 RGB 内替换 HSV 那几步），不改 LUT 与显示编码（仍消费线性，见 000005）。FotLab 可借此在「调色保真度」上直接超越 RapidRAW。
