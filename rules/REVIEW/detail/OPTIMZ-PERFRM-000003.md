# 预览始终走全分辨率 — 没有降采样路径，且 Coil 未被给出解码尺寸

- ID: OPTIMZ-PERFRM-000003
- Status: Observation
- Priority: P1
- Created: 2026-09-21
- Owner: —
- Related: `rules/REVIEW/detail/OPTIMZ-PERFRM-000001.md`（总览）、`rules/REVIEW/detail/FOTLAB-RAWLER-000003.md`（superpixel 1/4 已设计未接线）、`rules/REVIEW/detail/DNGLAB-RAWLER-000001.md`（preview 曾是未处理 dump）、`rules/REVIEW/detail/ACTION-LIBRND-000001.md`（Library 单层渲染）

## Background & Goal

Studio 的每一次显影——无论是首帧、还是用户改一次 demosaic 算法 / 曝光 / 白平衡——都在处理**全分辨率像素**，最终生成一张全分辨率 PNG 交给 Coil。本条目记录两处独立却叠加的缺失：native 侧没有任何"降分辨率输出"路径，UI 侧也没有告诉 Coil 目标尺寸。

## Finding

### 1. Rust 侧：全仓库不存在任何降采样 / 缩放 / 缩略图路径

在 `app/src/binding/rust/rawler_fotlab/src/` 下检索 `superpixel|downscal|resize|scale|thumb|preview`，命中的全是注释、`develop.rs:210` 的 `apply_scaling`（黑/白电平缩放，**不是几何缩放**）和 `develop.rs:270` 的一句注释"Superpixel 1/2 scaling is omitted because we never use superpixel demosaic"。

也就是说：**我们从不使用 superpixel，因此也从不降分辨率出图。**

而 rawler 里这个原语是现成的，`rules/REVIEW/detail/FOTLAB-RAWLER-000003.md` §Finding 2 已经精确记录过：

- `external/dnglab/rawler/src/imgop/sensor/bayer/superpixel.rs:16` `Superpixel3Channel`
- `external/dnglab/rawler/src/imgop/sensor/bayer/superpixel.rs:78` `Superpixel4Channel`
- 每 2×2 块合成一个 RGB(E) 像素 → **输出尺寸 1/4**（原文件注释 `:27`、`:89` 写明 "result image is 1/4 of size"）
- 两者都是 `pub` 结构体，实现 `Demosaic<f32,3>` / `Demosaic<f32,4>`，可被绑定直接调用；`external/dnglab/rawler/src/imgop/develop.rs:301-304` 是 `develop_intermediate` 里唯一提到 superpixel 的地方，且只是尺寸检测，**从不在主路径里调用它们**。

该条目把 superpixel 1/4 设计成了 `ScaleMode { Full, Quarter }`，**但目前没有接线**。

### 2. Kotlin 侧：Coil 三处调用都没有指定解码尺寸

| 调用点 | 代码 |
|---|---|
| `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioScreen.kt:248` | `ImageRequest.Builder(context).data(result.model).build()` — 无 `.size(...)` |
| `app/src/main/kotlin/io/github/fotlab/fotlab/ui/ZoomableImage.kt:203` | `rememberAsyncImagePainter(model = model)` — 无 size 参数 |
| `app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryViewerDialog.kt:134` | `ImageRequest.Builder(LocalContext.current).data(uri).build()` — 无 `.size(...)` |

Coil 在无尺寸约束时按图片原始分辨率解码（这一点尚未实测，列为打点项）。50 MP 的 ARGB_8888 Bitmap 约 200 MB，并且这是最容易撞上 `GL_MAX_TEXTURE_SIZE`（多数 GLES 设备为 4096 或 8192）的位置。

### 3. 为什么这一项的收益是最大的

分辨率是唯一的乘法因子。像素数 ÷4 之后（边长各减半，见 Finding 1），下面这些环节全部同比变快：

- `app/src/binding/rust/rawler_fotlab/src/calibrate.rs:136,159` 的逐像素矩阵；
- `app/src/binding/rust/rawler_fotlab/src/bound.rs:123,163` 的 gamma + RGBA 展开；
- `app/src/binding/rust/rawler_fotlab/src/bound.rs:128,174` 的 PNG deflate；
- Coil 的 inflate 与 Bitmap 分配；
- 纹理上传。

而内存峰值同比下降约 4× —— 按 `OPTIMZ-PERFRM-000001` 记的 50 MP 构成：f32×3 中间 buffer 约 600 MB → 约 150 MB，RGBA8 展开约 200 MB → 约 50 MB。这会显著减轻 low-memory-kill 与 GC 抖动的风险（但不会消除：单帧仍在数百 MB 级）。

## Impact / Conflict

- **画质契约属产品决策**：交互时用 1/4、导出/放大到 >100% 时才跑全分辨率，需要人工确认（总览 C4）。在契约确认前不能擅自把Studio 的输出降级。
- `FOTLAB-RAWLER-000003` §C 已把 1/4 设计好但未接线，本条目的实现应顺着既有设计走，而不是另立一套。
- 与 `OPTIMZ-PERFRM-000005`（缓存已显影 buffer）存在收益重叠：两者都减少"重跑全量像素"的次数，但解决的是不同问题（前者减小单次成本，后者消除重复执行）。

## Recommendation

按风险递增：

1. **先给 Coil 指定尺寸**（三处调用各加一个基于视图像素的目标 size，放大场景可用 2× 余量）。这是纯 UI 侧改动，不动 native 契约，也不需要画质决策。
2. **再接 superpixel 1/4**：沿 `FOTLAB-RAWLER-000003` §C 的 `ScaleMode`，让 native 在预览时直接出 1/4。只有在人工确认了"交互 1/4、导出全分辨率"的契约之后才实施。
3. 以上都需要先有 `OPTIMZ-PERFRM-000009` 的打点数据作为基线。

## Change History

- 2026-09-21 — 创建。确认 `rawler_fotlab` 全仓库无降分辨率路径（`develop.rs:270` 的注释反而记录了"我们绝不使用 superpixel"），rawler 的 `Superpixel3/4Channel`（`superpixel.rs:16/78`，1/4 输出）仍未接线；Coil 三处调用点均未指定解码尺寸。
- 2026-09-22 — 修正 Finding 3 与 Impact 里的像素数倍率。原文写「像素数 ÷16」，与本案事实不符：superpixel 是每 2×2 块合成 1 个 RGB(E) 像素（`external/dnglab/rawler/src/imgop/sensor/bayer/superpixel.rs:27/73`，输出 `roi.d.w >> 1, roi.d.h >> 1`）⇒ **边长各减半，像素数 ÷4**（不是 ÷16）；`FOTLAB-RAWLER-000003.md:111` 亦记「Superpixel 1/4 cuts pixel count 4×」。连带把 Finding 3 末句的「内存峰值从 1 GB 级降到 100 MB 级」改为按 ÷4 推算的构成（沿用 `OPTIMZ-PERFRM-000001.md:52-53`：f32×3 约 600 MB → 约 150 MB，RGBA8 约 200 MB → 约 50 MB），并把「消除 low-memory-kill」软化为「显著减轻风险」——单帧仍在数百 MB 级。本次只改这两处数字与措辞，**未重新测算峰值总量**（那属于 `OPTIMZ-PERFRM-000009` 的打点范围）。同源的错误表述另见 `OPTIMZ-PERFRM-000007`，已一并修正。
- 2026-09-22 — **Finding 1 指出的"未接线"已部分闭合**：Superpixel3/4Channel 现已接入 demosaic 阶段，但**不是**按本条目 Finding 1 设想的 `ScaleMode { Full, Quarter }` 建模，而是以独立的 `downsample: bool`（`DevelopParams.downsample`）+ Kotlin Studio drawer 的用户偏好驱动。因此本条目 Finding 1 的"从不使用 superpixel"、Recommendation 2 的"沿 §C 的 `ScaleMode`"两处描述**仅剩历史意义**（未改正文，保留原始诊断）。本条目 Finding 2（Coil 三处调用未指定解码尺寸）与 Recommendation 1 **仍未做**，依然成立。详见 `OPTIMZ-PERFRM-000010`。
