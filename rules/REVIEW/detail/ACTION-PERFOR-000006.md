# Library 里 RAW 没有真实缩略图 — Coil 解不了 RAW，落到 MIME 图标，内嵌预览没被使用

- ID: ACTION-PERFOR-000006
- Status: Observation
- Priority: P2
- Created: 2026-09-21
- Owner: —
- Related: `rules/REVIEW/detail/ACTION-PERFOR-000001.md`（总览）、`rules/REVIEW/detail/ACTION-LIBRND-000001.md`（Library 单层渲染与视图隔离）、`rules/REVIEW/detail/FOTLAB-RAWLER-000001.md`（preview 曾为未处理 dump）、`rules/REVIEW/detail/DNGLAB-RAWLER-000001.md`（同一问题的早期记录）

## Background & Goal

Studio 的渲染链路之外，Library 网格还有一条完全独立的、高频触发的图片路径：每一行/每一格都要出一张小图。本条目记录这条路径目前在 RAW 上的实际行为，以及为什么它是"感知性能"最便宜的一个优化点。

## Finding

### 1. Library 缩略图只走 Coil，而 Coil 解不了 RAW

`app/src/main/kotlin/io/github/fotlab/fotlab/feature/library/LibraryThumbnail.kt`：

- `:92` `isMedia(mime)` 对 `image/*` 与 `video/*` 返回 true；
- `:56-68` 命中后直接 `AsyncImage(model = uri, ...)`，`placeholder` 与 `error` 都是 `Icons.Filled.Image`；
- 也就是说：**解码失败时静默落到一个通用图片 glyph**，用户看到的是"没有缩略图"，而不是错误。

这一点与 sniff 层的既有结论一致：`app/src/main/kotlin/io/github/fotlab/fotlab/media/FormatSniffer.kt:227` 的注释写明"Coil 无法解码 RAW"（CoilSideSniffer 底层是 `BitmapFactory` 的 `inJustDecodeBounds`，RAW 拿不到 `outMimeType`）。

**结论：RAW 文件在 Library 里现在根本没有缩略图。**

### 2. 可用的内嵌预览路径没有被接上

rawler 侧的解码器 trait 已经提供 Extract embedded JPEG preview 的接口：

- `external/dnglab/rawler/src/decoders/mod.rs:345` `fn thumbnail_image(...) -> Result<Option<DynamicImage>>`
- `external/dnglab/rawler/src/decoders/mod.rs:356` `fn preview_image(...) -> Result<Option<DynamicImage>>`
- `external/dnglab/rawler/src/decoders/mod.rs:351` `fn full_image(...)`

三者都是 `Decodable` trait 上的 **`pub` 默认方法**（默认实现返回 `Ok(None)`，例如 ARW 已实现 `preview_image`）。配合 `RawSource::new_from_shared_vec`（`external/dnglab/rawler/src/rawsource.rs`）可以直接在内存数据上取预览，不需要先对全幅做完整的 RAW 解码。

这些能力只是"已经写了但没人调用"——与任何删减无关。

### 3. 为什么它的性价比最高

- 内嵌 JPEG 预览是 **KB 级**，而走完整 develop 是 **200 MB 级**；
- 网格滚动时对每一格都重复触发，是全 app 里调用次数最多的图像路径；
- 它顺带给 Studio 提供"首帧先出图，再后台精修"的可能性。

## Impact / Conflict

- 覆盖率未知：trait 默认实现返回 `None`，各厂商格式是否随处可得（`external/dnglab/rawler/src/decoders/*.rs` 逐个实现了多少）**未核实**，需要实测抽样。
- 分层问题：Library 缩略图需要一条 RAW 专属路径，而现在的 `NodeThumbnail` 完全不知道 native 世界的存在。要不要让它感知 `RawlerFotlabBridge`（`app/src/binding/kotlin/io/github/fotlab/fotlab_rawler/RawlerFotlabBridge.kt`）牵涉到 Library 层是否可以依赖 media / binding 层 —— 属分层决策。
- 与 `ACTION-LIBRND-000001` 相关：那里的结论是 Recycle 视图必须镜像 one-level 查询、切换时重置导航，本条目要加的渲染分支必须在同样的隔离边界内，**两个视图不得混用缓存**。

## Recommendation

1. 先做覆盖率抽样：拿一批真实 RAW（CR2 / NEF / ARW / RAF / CR3 / DNG）试 `preview_image` / `thumbnail_image`，确认能拿到的比例，再决定是否值得接线。
2. 若覆盖率可接受，在 media 层新增一个"取内嵌预览"的入口（同样经 `RawlerFotlabBridge`，不破坏唯一边界的规矩），由 Library 的缩略图路径优先尝试、失败再回落到现有 MIME glyph。
3. 缩略图结果必须进 Coil 的磁盘缓存（现成的 bitmap pooling 与 memory cache 机制在 `LibraryThumbnail.kt:35-36` 已说明），不要自建缓存。

## Change History

- 2026-09-21 — 创建。确认 RAW 在 Library 中因 Coil 无法解码而落到通用图片 glyph（`LibraryThumbnail.kt:56-68`、`FormatSniffer.kt:227` 的注释），而 rawler 的 `thumbnail_image` / `preview_image` / `full_image`（`decoders/mod.rs:345/351/356`）是可调用但未被接线的 `pub` trait 方法；标记"格式覆盖率未抽样"与"Library 层是否可依赖 binding 层"两个开放问题。
