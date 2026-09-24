# REVIEW Index

Master index of architecture review issues.

- Entry point and write rules: [`rules/REVIEW.md`](rules/REVIEW.md)
- Detail files: [`rules/REVIEW/detail/`](rules/REVIEW/detail/)

This file contains **only** the item table. No statistics, no changelog — git tracks history.

| ID | Title | Category | Status | Priority | Detail |
| --- | --- | --- | --- | --- | --- |
| `ACTION-PREPIN-000001` | Preflight toolchain caching audit — SDK/NDK/Gradle cached; Rust NDK & python-for-android pending | `PREPIN` | Observation | P3 | [detail](rules/REVIEW/detail/ACTION-PREPIN-000001.md) |
| `ACTION-LIBRND-000001` | Library one-level rendering audit — current view renders only direct children; Recycle must mirror one-level queries and reset navigation on switch so the views never mix | `LIBRND` | Observation | P2 | [detail](rules/REVIEW/detail/ACTION-LIBRND-000001.md) |
| `DNGLAB-RAWLER-000001` | rawler_fotlab PNG preview is an unprocessed full-sensor dump — no black level, white balance, demosaic, colour mapping or gamma | `RAWLER` | Observation | P2 | [detail](rules/REVIEW/detail/DNGLAB-RAWLER-000001.md) |
| `DNGLAB-RAWLER-000002` | Rewriting external/dnglab (rawler) in Kotlin — cost / benefit assessment, triggered by a Rust-library-invocation crash | `RAWLER` | Observation | P2 | [detail](rules/REVIEW/detail/DNGLAB-RAWLER-000002.md) |
| `ACTION-KOTLIN-000001` | First-party Kotlin / Compose code audit — scope, method and the complete finding index (30 findings, 6 nesting sites, 14 duplication groups) | `KOTLIN` | Observation | P2 | [detail](rules/REVIEW/detail/ACTION-KOTLIN-000001.md) |
| `ACTION-KOTLIN-000002` | Library refresh is a no-op — `uriExists` tests `count >= 0`, which is always true | `KOTLIN` | Observation | P0 | [detail](rules/REVIEW/detail/ACTION-KOTLIN-000002.md) |
| `ACTION-KOTLIN-000003` | Compose state and lifecycle deviations — navigation state not observable, startup blocking, no state holder | `KOTLIN` | Observation | P1 | [detail](rules/REVIEW/detail/ACTION-KOTLIN-000003.md) |
| `ACTION-KOTLIN-000004` | Duplicated Compose and data blocks — ten extraction groups across the two library screens and the data layer | `KOTLIN` | Observation | P2 | [detail](rules/REVIEW/detail/ACTION-KOTLIN-000004.md) |
| `ACTION-KOTLIN-000005` | Control flow — six nesting sites, three non-exhaustive `when`, and one concept modelled three ways | `KOTLIN` | Observation | P2 | [detail](rules/REVIEW/detail/ACTION-KOTLIN-000005.md) |
| `ACTION-KOTLIN-000006` | Localization and formatting — English hard-coded in the viewer detail panel, unsafe and locale-implicit date formatting | `KOTLIN` | Observation | P1 | [detail](rules/REVIEW/detail/ACTION-KOTLIN-000006.md) |
| `ACTION-KOTLIN-000007` | Platform API and data layer — Media3, N+1 queries, nullable primary key, duplicated plumbing | `KOTLIN` | Observation | P2 | [detail](rules/REVIEW/detail/ACTION-KOTLIN-000007.md) |
| `FOTLAB-RAWLER-000001` | External C/Rust library internal-call detail differences — lesson from the rawler_fotlab binding | `RAWLER` | Observation | P2 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000001.md) |
| `FOTLAB-RAWLER-000002` | RawImage already carries resolved calibration (color_matrix/cfa/wb); data↔camera match done inside rawler for all formats incl. CR2 via camera-DB lookup | `RAWLER` | Observation | P2 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000002.md) |
| `FOTLAB-RAWLER-000003` | Extending rawler_fotlab with selectable demosaic algorithm, optional superpixel 1/4, and external color matrix — design | `RAWLER` | Proposal | P2 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000003.md) |
| `FOTLAB-RAWLER-000004` | Decode-once / develop-reuse across the Kotlin↔Rust FFI — hold the decoded RAW as a UniFFI auto-handle (`RawlerImageLoaded`), reusing it for preview + repeated develop without re-decode or re-crossing the pixel buffer | `RAWLER` | Approved | P1 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000004.md) |
| `DNGLAB-RAWLER-000005` | RAW decode cost is set by container and encoding, not by vendor — CR3 is parallel, CR2 and lossless NEF are not | `RAWLER` | Observation | P2 | [detail](rules/REVIEW/detail/DNGLAB-RAWLER-000005.md) |
| `FOTLAB-RAWLER-000005` | Working space is locked to sRGB and irreversibly gamut-clipped in `calibrate` — switch to a wide-gamut (ProPhoto D50) hub | `RAWLER` | Proposal | P1 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000005.md) |
| `FOTLAB-RAWLER-000006` | Handoff rawler ProPhoto-D50 linear → RawAlchemyCpp: add `rawalchemy_fotlab` cxx bridge (no submodule patch); Kotlin is the hub, consumes graded output as-is | `RAWLER` | Approved | P1 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000006.md) |
| `FOTLAB-RAWLER-000007` | rawalchemy D50 ProPhoto → Log already performs gamut (primaries + CAT02 white-point) transform; graded output leaves ProPhoto — develop→grade is also a color-space boundary, retain D50 ProPhoto handle | `RAWLER` | Observation | P2 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000007.md) |
| `FOTLAB-RAWLER-000008` | rawalchemy boost = 4 params (`enableBoost`/`saturation`/`contrast`/`pivot`); saturation anchors on the per-pixel ProPhoto luma (dynamic, affine-linear `sat·c+(1−sat)·lum`), contrast on the global `pivot` 0.18 (static); both scene-linear in linear ProPhoto D50, before the gamut+log boundary | `RAWLER` | Observation | P2 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000008.md) |
| `ACTION-ROLLBK-000001` | Architecture downgrade must require explicit human confirmation before execution — lesson from the HorizontalOperationBar incident (`d686788`/`4d40bec`) | `ROLLBK` | Approved | P1 | [detail](rules/REVIEW/detail/ACTION-ROLLBK-000001.md) |
| `OPTIMZ-PERFRM-000001` | 渲染管线性能审计总览 — 瓶颈归因、加速手段分级与五个待拍板项（8 个子条目的索引） | `PERFRM` | Observation | P1 | [detail](rules/REVIEW/detail/OPTIMZ-PERFRM-000001.md) |
| `OPTIMZ-PERFRM-000002` | 渲染瓶颈归因 — FFI 调用开销被误判；真正的成本是像素搬运、重复拷贝与 PNG 编解码 | `PERFRM` | Observation | P1 | [detail](rules/REVIEW/detail/OPTIMZ-PERFRM-000002.md) |
| `OPTIMZ-PERFRM-000003` | 预览始终走全分辨率 — native 侧无降采样路径（rawler superpixel 1/4 未接线），Coil 三处调用均未指定解码尺寸 | `PERFRM` | Observation | P1 | [detail](rules/REVIEW/detail/OPTIMZ-PERFRM-000003.md) |
| `OPTIMZ-PERFRM-000004` | PNG 作为跨 FFI 的像素载荷 — 预览路径背负一次完整 deflate 与一次完整 inflate | `PERFRM` | Observation | P1 | [detail](rules/REVIEW/detail/OPTIMZ-PERFRM-000004.md) |
| `OPTIMZ-PERFRM-000005` | 每次调参都重跑整条 develop 链 — 缓存粒度停在"已解码"，未到"已显影"（`FotDev`） | `PERFRM` | Observation | P1 | [detail](rules/REVIEW/detail/OPTIMZ-PERFRM-000005.md) |
| `OPTIMZ-PERFRM-000006` | Library 里 RAW 没有真实缩略图 — Coil 解不了 RAW 落到 MIME 图标，rawler 的内嵌预览接口未被接线 | `PERFRM` | Observation | P2 | [detail](rules/REVIEW/detail/OPTIMZ-PERFRM-000006.md) |
| `OPTIMZ-PERFRM-000007` | 原生侧算力未被利用 — 自写像素循环是单线程标量，rawalchemy 的 OpenMP 未开启，release profile 未调优 | `PERFRM` | Observation | P2 | [detail](rules/REVIEW/detail/OPTIMZ-PERFRM-000007.md) |
| `OPTIMZ-PERFRM-000008` | 渲染后端路线评估 — WebView/RapidRAW 不成立；真正的对应物是 GPU，但受 minSdk 26 约束 | `PERFRM` | Observation | P2 | [detail](rules/REVIEW/detail/OPTIMZ-PERFRM-000008.md) |
| `OPTIMZ-PERFRM-000009` | 缺少断面度量 — `OPTIMZ-PERFRM-000001` 至 `000008` 的量级判断全部未经实测，附 7 条待验证假设清单 | `PERFRM` | Observation | P1 | [detail](rules/REVIEW/detail/OPTIMZ-PERFRM-000009.md) |
| `ACTION-RAWLER-000007` | 对数空间枚举的别名契约 — 展示名与引擎名由 shim 内的单一映射表对齐，上游保持只读 | `RAWLER` | Implemented | P2 | [detail](rules/REVIEW/detail/ACTION-RAWLER-000007.md) |
| `OPTIMZ-PERFRM-000010` | 预览降采样开关 — Kotlin 侧偏好驱动 `DevelopParams.downsample`，在 demosaic 阶段分支为 rawler superpixel 1/4；两路在 calibrate 前汇聚，crop 需半尺度修正 | `PERFRM` | Implemented | P1 | [detail](rules/REVIEW/detail/OPTIMZ-PERFRM-000010.md) |
| `FOTLAB-RAWLER-000009` | Pre-demosaic 阶段槽位 — exposure 抽取为纯函数 + RT 风格 CFA impulse denoise（同色平面 8 邻域越界检测 + 软膝）/ 直方图雾底去雾基线 | `RAWLER` | Implemented | P2 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000009.md) |
| `FOTLAB-RAWLER-000010` | CFA 域 guided filter 可行性调研 — 生产实现均为去马赛克后 RGB；原生 mosaic 不可直接用（guide 需局部平滑）；须按颜色子栅格分别滤波，Bayer 自然拆为 R/G1/G2/B 四规则栅格 | `RAWLER` | Observation | P3 | [detail](rules/REVIEW/detail/FOTLAB-RAWLER-000010.md) |
<!-- Next sequence per category: PREPIN 000002, LIBRND 000002, RAWLER 000009, KOTLIN 000008, FOTLAB-RAWLER 000011, ROLLBK 000002, PERFRM 000011. Append one row per new item; never reuse or renumber IDs. -->
