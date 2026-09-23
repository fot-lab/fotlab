# First-party RawTherapee demosaic engine — pure-Rust port, rayon-parallel, selectable algorithm surface

- ID: FOTLAB-NATIVE-000004
- Status: Draft
- Priority: P1
- Created: 2026-09-23
- Owner: —
- Related: `rules/STRUCT/detail/RAWTRP-DECODE-000001.md`（算法清单：19 Bayer + 7 X-Trans 标识符 → ~15 真实内核）、`rules/STRUCT/detail/RAWTRP-DECODE-000003.md`（内核 I/O 契约 `array+CFA` + rawler→RT 桥接，本文档实现其"最小 re-host 签名"）、`rules/STRUCT/detail/RAWTRP-DECODE-000004.md`（int→f32 唯一边界 `apply_scaling`）、`rules/STRUCT/detail/DNGLAB-PIPELN-000002.md`（dnglab vs RT demosaic 对比 —— 本文档**推翻**其 §5/§6.4(b) 结论）、`rules/DESIGN/detail/FOTLAB-NATIVE-000001.md`（`external/` 只读 + 绑定代码归属 `app/src/binding/`）、`rules/DESIGN/detail/FOTLAB-PIPELN-000001.md`（loader/develop/process）、`rules/REVIEW/detail/OPTIMZ-PERFRM-000007.md`（自写像素循环 rayon 化）。禁用中的 `app/src/binding/cxx/rawtherapee_fotlab/`（C++ 胶水，需 submodule hook）为前车之鉴，本文档是其"不保留 hook"路线的替代。

> **Note on naming**：`FOTLAB-` 项目码 + `NATIVE` 类别（原生集成），序号 `000004`（NATIVE 下个序号）。`NATIVE` 是 DESIGN 现有最贴合"第一方原生引擎"的类别，未新开类别。

> **Scope**：**规划 + 分步实施**。本文档定义 (a) 新 crate 边界与数据契约、(b) 内核 ABI 与算法目录、(c) 与 `develop.rs`/Kotlin 的集成面、(d) 分步落地顺序与验收标准。**包含** rayon 并行（对齐上游 OpenMP 分片点）与 SIMD 加速——二者**在本次移植中一次到位**，不设独立延后阶段。**不含**多帧 pixel-shift（超出单帧 develop）。上游 `external/RawTherapee` 保持只读，**不产生任何 `.patch`**；移植是**纯 Rust 重写**，不是链接 GPL C++。

## Background & Goal

**动机（内容缺口）**：rawler 在 develop 路径上**可达**的 demosaic 只有 PPG（Bayer）与 XTransBilinear（X-Trans），可选数量为 **0**（按 CFA 类型硬编码，`DNGLAB-PIPELN-000002` §2）。高质量方向自适应去马赛克（AMAZE / RCD / AHD / LMMSE / DCB / IGV / VNG4 / EAHD / HPHD 类）在 rawler 里**完全缺失**。RawTherapee 提供 19 Bayer + 7 X-Trans 标识符（去重后 ~15 真实内核，`RAWTRP-DECODE-000001` §3），是现成的质量参照。

**目标**：把 RT 的解马赛克内核**全部用 Rust 重写**（rayon 并行 + SIMD），作为一个**可被外部 include/调用的第一方 crate**，输入兼容 rawler 的 mosaic（数组 + CFA），输出 **rawler 兼容的 `Intermediate` 中间态**，从而在 demosaic 之后**继续用 rawler 做 calibrate**；并把统一候选列表暴露给 Kotlin。**默认算法保持不变**。

**与既有决策的冲突（必须显式记录，见 §6）**：`DNGLAB-PIPELN-000002` §5/§6.4(b) 判定"adopting RawTherapee's demosaics is not on the table"。该结论基于三个当时的前提：(a) 只能**链接** GPL-3.0 C++；(b) RT 内核是 `RawImageSource` 成员、深度耦合类状态；(c) `external/` 只读。**纯 Rust 重写同时绕开这三条**，且许可证兼容（见下）。故本文档推翻该结论——依据人工直接指令。

**许可证（关键结论：兼容）**：RawTherapee 为 **GPL-3.0**，本项目 `LICENSE.md` 亦为 **GPL-3.0**（`Copyright (C) 2026 fot-lab`）。因此移植属 GPL-3.0 → GPL-3.0，**许可兼容**，仅需**保留上游版权与许可声明**（每个移植文件头部 + crate 级 NOTICE，见 R8）。这与 `RAWTRP-DECODE-000001` 的"record, never modify upstream"不冲突：我们不修改上游，只按其算法写出第一方 Rust。

## Requirement

### R1 — Crate 位置与边界

- 新 crate：`app/src/binding/rust/rawtrp_demos`（`rawler_fotlab` 的**兄弟目录**，非其子模块）。
- **一个 crate 承载全部算法**：不为每个算法单开 crate，也不把算法塞进 `rawler_fotlab` 内部模块。算法之间只共享 crate 内的基础设施（`cfa`/`array2d`/`math`/`border`），不互相依赖。
- 作为 `rawler_fotlab` 的 **path 依赖**（与 `rawler`/`rawalchemy_fotlab` 同构），`rawler_fotlab` 仍是唯一出品的 `librawler_fotlab.so`。
- **纯 Rust**：不链接 `librtengine`、不含 C++、不需要任何 submodule hook。禁用中的 `binding/cxx/rawtherapee_fotlab` 保持禁用（本 crate 取代其用途）。
- 不放进 `external/`（`FOTLAB-NATIVE-000001` R1：第一方绑定代码属 `app/src/binding/`）。

### R2 — 输入契约（兼容 rawler / RawlerImageLoaded）

- 核心入口吃**数组 + CFA**，与 `RAWTRP-DECODE-000003` §3.1 的最小签名同构：
  ```rust
  pub struct CfaDesc { /* filters: u32, prefilters: u32, xtrans: [[u8;6];6] */ }
  pub fn demosaic_bayer(algo: BayerAlgo, cfa: &CfaDesc, mosaic: &Array2D<f32>, params: Option<&BayerParams>) -> Result<Rgb, Error>;
  pub fn demosaic_xtrans(algo: XTransAlgo, cfa: &CfaDesc, mosaic: &Array2D<f32>) -> Result<Rgb, Error>;
  ```
- `mosaic`：单通道、行主序、**0..1 线性 f32**（= rawler `apply_scaling` 之后、RT `rawData` 的量级），长度 `w*h`。
- 两个数据来源都直接可用：**(a)** stateless `develop(raw, params)`（`apply_scaling` → `take_scaled_pixels` → `Vec<f32>` + `photometric` 里的 CFA，`develop.rs:246-253`）；**(b)** `RawlerImageLoaded` 的缓存 decode（`FOTLAB-RAWLER-000004`）。
- **CFA 必须两张掩码都带上**（`RAWTRP-DECODE-000003` §3.2）：`filters`（`set_prefilters()` 折叠过 `3→1`，`FC`/`ISGREEN`/`ISBLUE`/`border_interpolate`/`igv`/`dcb`/`bilinear` 用）与 `prefilters`（**未**折叠，只有 `vng4` 的局部 `fc()` 宏用，靠 `fc==3` 检四色 CFA 并回落 IGV）。Bayer → 32-bit dcraw 掩码（RGGB = `0x94949494`）；X-Trans → 6×6 单色表。由 rawler `CFA` 2×2/6×6 tile 现编，不查相机表。

### R3 — 输出契约（转回 rawler `Intermediate`，calibrate 无缝续接）

- 内核输出 **三个 split-planar `Vec<f32>`**（R/G/B，各 `w*h`，与 RT 一致）。
- crate 边界处**立即**组装为 `rawler::imgop::develop::Intermediate::ThreeColor(Color2D<f32,3>)`——正是 rawler `develop_intermediate` 的 Demosaic 步产物、calibrate 步的输入（`DNGLAB-PIPELN-000002` §7.1）。**calibrate 只依赖 `Intermediate` 的形状**，因此对它而言"生产者是谁"不可见。
- 不引入新中间类型跨 FFI；`Intermediate` 是唯一交接面。

### R4 — 并行与加速（本次一次到位）

- **rayon 对齐上游 OpenMP 分片点**：RT 每个内核里 `#pragma omp parallel for` 的位置即我们的 rayon 切分位置；按**行/行带**切分（`par_chunks_mut` / `par_rows_mut`），禁止逐元素 `par_iter_mut`（调度开销主导）——沿用 `OPTIMZ-PERFRM-000007` 的纪律。
- **能消掉跨行依赖就拆相**：如 vng4 先全量 green（只读 `image`）、再全量 red/blue，结果确定性且等于单线程；上游 `firstRow/lastRow` 补算只为掩盖分块竞态，我们不需要。
- **SIMD 在本次移植内完成**，不设独立延后阶段：上游有 SIMD 路径的内核（amaze 186 / xtrans 52 / demosaic_algos 50 / lmmse 21 / hphd 18 / vng4 4 处命中），移植时提供与该内核一并落地的 SIMD 实现（`std::arch` NEON，`cfg(target_arch)` 门控 + 标量回退），并以标量路径为基准逐位/容差校验一致。上游无 SIMD 的内核不臆造 SIMD，只做 rayon。
- 复用**同一个 rayon 全局池**（pin 同一 major 版本），避免嵌套池。

### R5 — 算法目录与名称解耦（字典映射 + 拼接）

- **每个算法一个 `.rs` 文件**（同名算法靠目录区分：`bayer/fast.rs` vs `xtrans/fast.rs`）。
- **两层解耦，各自用字典映射**，互不耦合：
  - **rawler 沿用算法**：包装其原本算法名（`Ppg`/`Bilinear4`/`XTransBilinear`/`Superpixel*`），用**字典**映射到标准候选名。
  - **RT 移植算法**：保留其**原本算法名**（`vng4`/`rcd`/`amaze`/`igv`…，含 Bayer 与 X-Trans 两个命名空间），用**字典**映射到标准候选名。
- **标准候选名前缀（含空格）**：RT 移植算法一律 `"RAWTRP "` 开头；rawler 沿用算法一律 `"RAWLER "` 开头。
- `candidates()` = **两边的标准名列表直接拼接**后吐出给 Kotlin，UI 候选菜单由它驱动。
- **枚举扩展也走列表拼接**：扩展原有 `DemosaicAlgorithm`（不新开并行枚举），RT 侧变体由「列表拼接」的方式并入，与候选名列表一一对应。
- **只广告已实现且已接线的内核**：`IMPLEMENTED_BAYER` / `IMPLEMENTED_XTRANS` 白名单随内核落地逐个放开，保证 UI 永不出现「选了会返回 `UnsupportedAlgo`」的项（`fast` 在 Bayer/X-Trans 同名，靠 `kind` 消歧 + X-Trans 的 `rawtrp:xtrans_fast` 需折回 `fast`）。

### R6 — 默认行为不变

- `DemosaicAlgorithm::Default` 仍解析为 rawler 的 CFA 驱动选择（PPG / Bilinear4 / XTransBilinear），**逐像素与今天一致**。
- 用户不选移植算法时，管线走原路径，零回归。

### R7 — 行为等价（验收）

- **逐算法语义比对**：每个移植文件对着上游源码逐段核对（索引算术、边界分支、回退分支、常量表），差异必须在文件头 `//! Port notes` 里显式记录（含"上游 UB 原样复现 + 缓冲 padding"这类处理）。
- 每个内核带**单元测试**；CFA 自检：内核入口拒绝不支持的 CFA（如 VNG4 遇四色 → 镜像 RT 的回落分支）。
- 对代表性 RAW 图，与 RT 参考输出（离线生成，作为 golden 数值）比对，容差在 `max abs diff` 阈值内。

### R8 — 归因（GPL 合规）

- crate 级 `NOTICE` + 每个移植文件头部：`ported from external/RawTherapee/rtengine/<file> @ <commit>`，并保留原作者版权行（如 `vng4_demosaic_RT.cc` 的 Ingo Weyrich 声明）。

### R9 — 范围边界

- 单帧 develop 用到的内核全部在范围内；**多帧 pixel-shift**（`pixelshift.cc`，需 `currFrame`/maker）**不在**单帧范围内（`RAWTRP-DECODE-000001` Q3）。
- `MONO`/`NONE` 非插值路径不需要移植（rawler 侧已能表达）。
- `green_equil` / `cfa_linedn`（pre-demosaic 辅助）不在本工程主体，另行决定。

## Design

### D1 — crate 布局

```
app/src/binding/rust/rawtrp_demos/
├── Cargo.toml                 # path dep: rawler（与 rawler_fotlab 同一 upstream）
├── NOTICE                     # GPL-3.0 归因：RawTherapee 作者与许可
└── src/
    ├── lib.rs                 # pub API：Algo 枚举、demosaic_bayer/xtrans、Error、Rgb
    ├── cfa.rs                 # CfaDesc：filters/prefilters(u32) + xtrans[6][6]；fc/is_green/is_blue
    ├── array2d.rs             # Array2D<T>（复刻上游 array2D.h）+ par_rows_mut（omp for 替身）
    ├── math.rs                # intp / sgn / lim / lim01 / clip（rt_math.h）
    ├── border.rs              # border_interpolate（demosaic_algos.cc:46-199）
    ├── algo.rs                # 三张解耦字典 + candidates() 拼接 + IMPLEMENTED_* 门控
    ├── bridge.rs              # R/G/B 平面 → rawler Intermediate::ThreeColor
    ├── bayer/
    │   ├── mod.rs             # BayerAlgo 枚举 + 分发
    │   ├── bilinear.rs        # bayer_bilinear_demosaic.cc（56 行）✅ 已落地
    │   ├── vng4.rs            # vng4_demosaic_RT.cc（410 行）
    │   ├── rcd.rs             # rcd_demosaic.cc（348）
    │   ├── lmmse.rs           # lmmse_demosaic.cc（830）
    │   ├── dcb.rs             # demosaic_algos.cc:1406（DCB）
    │   ├── igv.rs             # demosaic_algos.cc:218（IGV）
    │   ├── amaze.rs           # amaze_demosaic_RT.cc（1610）
    │   ├── ahd.rs             # ahd_demosaic_RT.cc（235）
    │   ├── eahd.rs            # eahd_demosaic.cc（447）
    │   ├── hphd.rs            # hphd_demosaic_RT.cc（364）
    │   └── fast.rs            # fast_demo.cc（498）
    ├── xtrans/
    │   ├── mod.rs
    │   ├── interpolate.rs     # xtrans_demosaic.cc:181（1-pass / 3-pass）
    │   └── fast.rs            # xtrans_demosaic.cc:969
    └── dual.rs                # dual_demosaic_RT.cc（141，对比自适应混合封装）
```

内核源规模（实测）：`amaze 1610 / demosaic_algos 1559 / xtrans 1095 / lmmse 830 / fast_demo 498 / eahd 447 / vng4 410 / hphd 364 / rcd 348 / green_equil 252 / ahd 235 / dual 141 / bayer_bilinear 56`，合计 **~9.3k 行**。SIMD（simde/SSE2）命中：amaze 186、xtrans 52、demosaic_algos 50、lmmse 21、hphd 18、vng4 4 —— 这些是本次 SIMD 需要覆盖的内核。

### D2 — 内核 ABI（与 RT 同构）

RT 内核实为 `RawImageSource` 成员，读 `prefilters`(CFA 掩码)/`W`/`H`/`ri`(CFA 查询)。移植时把这三者参数化：`prefilters` → `CfaDesc.filters`；`fc(row,col)` 宏 / `ri->ISGREEN/ISBLUE` → `CfaDesc` 的方法；`W/H` → 入参。内核签名收敛为 R2 的 `demosaic_*(algo, cfa, mosaic, params) -> Result<Rgb, Error>`。这正是 `RAWTRP-DECODE-000003` §3.1 的"数组 + CFA"，不依赖任何 `RawImageSource`。

### D3 — 名称与候选层（`algo.rs`）

```
RAWLER_NAMES        : [(original, standard)]   // Ppg→"RAWLER PPG"、Bilinear4→"RAWLER Bilinear4"…
RAWTRP_BAYER_NAMES  : [(original, standard)]   // bilinear→"RAWTRP Bilinear"、vng4→"RAWTRP VNG4"…
RAWTRP_XTRANS_NAMES : [(original, standard)]   // fast→"RAWTRP X-Trans Fast"…
candidates() = RAWLER_NAMES ⧺ RAWTRP_BAYER_NAMES ⧺ RAWTRP_XTRANS_NAMES   // 仅已实现项
```
`Candidate { id, label, kind }`：`id` 是稳定机器串（如 `rawtrp:bayer_vng4`），`label` 是上表标准名，`kind` ∈ {Bayer, XTrans} 供 UI 按当前传感器灰化。

### D4 — 与 `develop.rs` 的集成

- `demosaic.rs` 的 `demosaic(image, data, algo, downsample)` 增加分支：rawler 变体 → 现有 `Algo::{Ppg,Bilinear4,XTrans,Superpixel*}`；RT 变体 → 调 `rawtrp_demos::demosaic_bayer/xtrans` 得 R/G/B 平面 → `Intermediate::ThreeColor(Color2D)`。
- **Fuji 旋转 + active-area 裁剪**沿用 `demosaic.rs` 现有逻辑（RT 内核本身不含几何）。
- `downsample` 开关优先于算法选择（不变）：quarter-res 仍走 rawler superpixel；RT 算法仅在 full-res 路径。
- 失败（不支持的 CFA）→ 回落到 CFA 默认（沿用 `effective_algorithm` 的既有回落 + 日志）。

### D5 — 与 Kotlin 的集成

- 扩展 `DemosaicAlgorithm`（列表拼接并入 RT 变体）；`DemosaicParams`/`RawlerFotlabDecoder`/`StudioEngine` 透传不变。
- 新增 UniFFI 函数 `demosaic_candidates() -> Vec<DemosaicCandidate{ id, label, kind }>`；`StudioScreen.kt` 的菜单由它动态构建（去掉硬编码 `DemosaicButton` 列表）。
- `StudioEngine.currentAlgorithm` 的默认仍是 `DEFAULT`。

## Phases（分步实施）

每批**一次到位**：rayon（对齐上游 OpenMP 分片点）+ 该内核上游有的 SIMD + 逐段语义比对 + 单测。批次只划分**内核覆盖面**，不划分"先标量后加速"。

| 批次 | 内容 | 出口标准 |
| --- | --- | --- |
| **B0 骨架** ✅ | crate + `CfaDesc`/`Array2D`/`Rgb`/`math`/`border`/`algo`(字典+candidates)/`bridge`(→`Intermediate`) + **bilinear** 内核 + 单测；registrar 为 `rawler_fotlab` 的 path dep | 已落地（本批随 CI 首验编译） |
| **B1 打通** | **VNG4** 内核（含 padding 处理四色 CFA 回落）+ `demosaic.rs` 分发 + `demosaic_candidates()` 暴露 + Kotlin 菜单动态化 | 选 `RAWTRP VNG4` 能出图；`DEFAULT` 逐像素不变；UI 候选可见 |
| **B2 质量层** | RCD、LMMSE、DCB、IGV | 各自单测 + 与 RT golden 数值比对达标 |
| **B3 高端层** | AMAZE、AHD、EAHD、HPHD（AMAZE 为质量基准，含 SIMD） | 同上 |
| **B4 X-Trans** | `xtrans_interpolate`(1/3-pass)、`xtrans/fast`、`dual` 混合封装 | X-Trans 图可选；CFA 自检 |

## Constraints

- C1 — 上游 `external/RawTherapee`/`external/dnglab` 只读，**不产生 `.patch`**。
- C2 — 纯 Rust，不链接 GPL C++、不加 submodule hook；禁用中的 `rawtherapee_fotlab` cxx crate 保持禁用。
- C3 — 默认 demosaic 行为不变（R6）。
- C4 — 输出必须经 rawler `Intermediate::ThreeColor` 交接，calibrate 不改（R3）。
- C5 — GPL-3.0 归因必须随源码交付（R8）。
- C6 — 一个内核一次落地，带单测与语义比对说明；不一次性提交全部内核（可评审、可回退）。
- C7 — **禁止本地编译**（`rules/ACTION.md`）：本机无 Rust/Android/C++ 工具链，验证只能走云 CI（push → 读日志）。

## Impacted Modules

- 新 crate `app/src/binding/rust/rawtrp_demos/`
- `app/src/binding/rust/rawler_fotlab/{Cargo.toml,src/demosaic.rs,src/develop.rs,src/lib.rs}`
- Kotlin：`StudioScreen.kt`（菜单）、`StudioEngine.kt`、`RawlerFotlabDecoder.kt`、`RawDecoder.kt`
- string resources（算法标签）
- CI（新 crate 进 native 构建；`.md` 不触发，代码会触发）

## Open Questions

- Q1 — **golden 数据来源**：RT 参考输出如何离线生成并入库（体积/可复现性）？是否只存小尺寸 tile 的数值。
- Q2 — **X-Trans 6×6 朝向**：`RAWTRP-DECODE-000003` Q1 未决（可能需要 transpose/shift），B4 前需相机样张确认。
- Q3 — **SIMD 覆盖面确认**：上游 SIMD 是 SSE2（经 simde 转译到 ARM）。我们按 `std::arch` NEON 等价实现，以标量路径为正确性基准；若某内核的 NEON 化会偏离上游语义，则停在该内核的标量+rayon 并上报，不擅自降低正确性。

## Change History

- 2026-09-23 — 初始规划。确立：纯 Rust 移植 RT 解马赛克内核（rayon 并行）为新 crate；输入契约 = 数组+CFA（`RAWTRP-DECODE-000003` §3.1）、输出 = rawler `Intermediate::ThreeColor`（calibrate 无缝续接）；统一候选列表暴露给 Kotlin，默认算法不变。**推翻** `DNGLAB-PIPELN-000002` §5/§6.4(b)"RT 解马赛克不可采纳"——纯 Rust 重写绕开其三条前提（不链接 GPL C++、不耦合 `RawImageSource`、不改 `external/`），且 GPL-3.0 许可兼容（本项目亦 GPL-3.0）。记录 ~9.3k 行内核源规模、SIMD 命中与分阶段表。Filed as `FOTLAB-NATIVE-000004`；row appended to `rules/DESIGN/index.md`。
- 2026-09-23 — **rev 2：按人工指令修订规划**（人工覆盖初稿的 4 项设计选择，原 Q1–Q4 随之结清）：
  1. crate 名 `rawtherapee_rawler_fotlab` → **`rawtrp_demos`**（单一 crate，不塞进 `rawler_fotlab`、不每算法一 crate）。
  2. 布局：每个算法一个 `.rs`（`bayer/`、`xtrans/` 目录仅用于消歧同名算法）。
  3. 枚举形态：**扩展原 `DemosaicAlgorithm`，以列表拼接并入 RT 变体**（不新开并行枚举）。
  4. SIMD 时机：**rayon + SIMD 本次一步到位**，取消原 P4"标量先行、SIMD 延后"。
  另确立名称解耦规范：标准候选名 RT 侧前缀 `"RAWTRP "`、rawler 侧前缀 `"RAWLER "`；两侧各用字典映射，`candidates()` 为两列表拼接（新增 R5/D3）。B0 已落地（crate 骨架 + 共享设施 + bilinear 内核）。
