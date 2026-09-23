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

### R10 — 三层解耦：输入解析 / 输出解析 / 算法文件，以及 CFA 适配层

移植库必须**与 rawler 的具体类型解耦**，只依赖「数组 + CFA」这一最小语义，才能被任何管线接入（`RAWTRP-DECODE-000003` §3.1）。因此三层各自独立，跨层接口全部是本库自有类型：

1. **输入解析层**（`cfa.rs` + `array2d.rs`）—— 外部管线（rawler，或将来的其它 loader）负责把它的 mosaic 与 CFA **翻译**成我们的 `Array2D<f32>` + `CfaDesc`。内核层**永远看不到** `rawl::RawImage` / `rawl::CFA` / `CFAConfig` 等任何外部类型。
2. **输出解析层**（`bridge.rs`）—— 内核只产出 `Rgb`（三个 split-planar `Vec<f32>`）；转成 rawler `Intermediate::ThreeColor` 是**唯一**一处外部耦合，且集中在**单个文件**里。
3. **算法层**（`bayer/*.rs`、`xtrans/*.rs`）—— **一个算法一个 `.rs`**，彼此不互相 `use`，只共享 crate 内基础设施（`cfa`/`array2d`/`math`/`border`）。新增算法 = 新增一个文件 + 目录 `mod.rs`/`algo.rs` 各挂一行 + `IMPLEMENTED_*` 放开一项，**不改动任何既有内核**。

**CFA 适配层（关键）**：rawler 的 CFA 与 rawtrp/dcraw 的 CFA **不是同一个表示**，差异是**语义性的、不是格式性的**，必须显式转换，不能因为"2×2 tile 长得差不多"就直接传：

| | rawler `CFA` | rawtrp / RT / dcraw `CfaDesc` |
| --- | --- | --- |
| 颜色码 | `0=R, 1=G, 2=B` —— **单绿** | `0=R, 1=G1, 2=B, 3=G2` —— **双绿**（`dcraw.cc:173`） |
| Bayer 载体 | 2×2 tile（`color_at(r,c)`） | 32-bit dcraw 掩码，且**两张**：`prefilters`（未折叠）+ `filters`（折叠后） |
| X-Trans 载体 | 6×6 tile | `xtrans[6][6]` |
| 绿通道语义 | 一个 G | `G1`/`G2` 两个电平，`color & 1` 判绿、`color ^= 2` 互换 |

- 转换入口：`CfaDesc::bayer_from_2x2(tile)` / `xtrans_from_6x6(tile)`，内部按 dcraw 格点展开并**同时**产出两张掩码 —— `prefilters` 保留 G1/G2 之分，`filters` 复刻 `set_prefilters()` 的 `3→1` 折叠（D6(b)）。
- **为什么必须双绿**：`vng4` 等内核读**未折叠**掩码，靠 `color == 3` 识别第二个绿通道。若把 rawler 的"单绿"当 RT 掩码直接喂进去，channel 3 恒为空 → 内核**静默退化**成错误图像（不 panic、不报错）。这就是"rawler CFA ≠ rawtrp CFA 必须加一层适配"的**具体后果**。
- 适配层**单向、无状态、无副作用**：**不**改 rawler（C1）、**不**改 RT（C2），只在边界做一次表示转换。
- 这样"继承自 dcraw 的灵活性"（四色 CFA、双绿、未折叠语义）与"与 rawler 管线的兼容性"（下游只看到三色 `Intermediate`）**同时成立**。落点与数据流见 D7。

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
    │   ├── vng4.rs            # vng4_demosaic_RT.cc（410 行）✅ 已落地
    │   ├── rcd.rs             # rcd_demosaic.cc（348）✅ 已落地
    │   ├── igv.rs             # demosaic_algos.cc:609（IGV，**标量**分支）✅ 已落地
    │   ├── lmmse.rs           # lmmse_demosaic.cc（830）✅ 已落地
    │   ├── dcb.rs             # demosaic_algos.cc:963-1548（DCB，13 个函数）✅ 已落地
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

内核源规模（实测）：`amaze 1610 / demosaic_algos 1559 / xtrans 1095 / lmmse 830 / fast_demo 498 / eahd 447 / vng4 410 / hphd 364 / rcd 348 / green_equil 252 / ahd 235 / dual 141 / bayer_bilinear 56`，合计 **~9.3k 行**。SIMD（simde/SSE2）命中：amaze 186、xtrans 52、demosaic_algos 50、lmmse 21、hphd 18、vng4 4 —— 这些是本次 SIMD 需要覆盖的内核。**⚠️ 其中 `lmmse 21` 是过估**：该文件只有 **1 处**真正的向量内建（`lmmse_demosaic.cc:514` 的 `_mm_storeu_ps`），且它只是把同一张 9 元 median 网络**按 4 道并行**，标量等价物逐位相同 —— 故 LMMSE 与 RCD 一样**无"待补的 SIMD"**，标量 + rayon 即完整移植（见 rev 8）。

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

### D6 — dcraw 参照语义调查结论（移植依据与应对）

RT 的这些内核是 **dcraw 的直接后代**（`vng4` = dcraw `vng_interpolate`，`dcraw.c:4422`），因此**必须按 dcraw 的语义读**，否则会得到"能编译、能出图、但数值错"的移植。以下结论均已对源码**机械核对**（表格 diff / 脚本复现上游过滤条件），是移植的**权威参照**；每条都给出我们的应对，并已落进 `bayer/vng4.rs` 的文件头 `//! Fidelity notes`。

**(a) Bayer CFA 是四色，不是三色。** dcraw 的颜色码是 `0/1/2/3 = R/G1/B/G2`（`dcraw.cc:173`）—— **两个绿是不同电平**。RGGB 的**原始**掩码是 `0xb4b4b4b4`，RT 自己注明 `// R G1 B G2`（`rawimage.cc:1373`）。`ri->get_colors()` 才是"传感器几个色"（Bayer 恒为 **3**，RGBE 类四色 CFA 才 >3）——**与"未折叠掩码里有没有 3"是两回事**。
→ **应对**：`CfaDesc` 加 `colors: u8`；`bayer_from_2x2` 按"奇数行绿 = G2"构造原始掩码，再折叠；`has_fourth_colour() = colors > 3`。

**(b) `set_prefilters()` 把 G2 折进 G1。** `rawimage.h:50-56`：`prefilters = filters; filters &= ~((filters & 0x55555555) << 1);` → RGGB `0xb4b4b4b4` → `0x94949494`（仅当 `isBayer() && get_colors()==3`）。
→ **应对**：`fold_prefilters()` 逐位复刻该行（幂等）；`CfaDesc` **两张掩码都存**。单测对四种 Bayer 排布 pin 住折叠结果 = dcraw 常数：`0x94949494`(RGGB) / `0x16161616`(BGGR) / `0x61616161`(GRBG) / `0x49494949`(GBRG)。

**(c) 谁读哪张掩码 —— 一个内核里两张都用。** `RawImage::FC`/`ISGREEN`/`ISBLUE`/`ISRED` 读**折叠**掩码（`rawimage.h:268-283`，三值）；但 `vng4_demosaic_RT.cc:62` 有一个**局部** `#define fc(row,col)` 直接读 `prefilters`（**未折叠**，四值）。
→ **应对**：`CfaDesc` 暴露两套方法 —— `fc*`/`is_*`（折叠）与 `fc_pre*`（未折叠），**逐处对照上游、不统一**。vng4 里两者同现：`interpolate_row_redblue` 走 `ISGREEN`/`ISBLUE`（折叠），而 scatter / 第一遍 / VNG 主循环的 `color` 走局部 `fc`（未折叠）。**这是本次移植最容易写错的一处**（初稿即错，已修）。

**(d) VNG4/RCD 的四色守卫是*活的*，不是死代码。** `vng4_demosaic_RT.cc:67-76` 与 `rcd_demosaic.cc:56-65` 都是 `if (FC(i,j) == 3)` → 回落 `igv_interpolate`。`FC` 读**折叠**掩码 `filters`（`rawimage.h:280-283`），但 `set_prefilters()` **仅当 `isBayer() && get_colors() == 3` 才折叠**（`rawimage.h:50-56`）⇒ **四色 CFA 的 `filters` 保持未折叠、`3` 仍在 ⇒ 守卫命中**。（`dcraw.cc:5025-5034` 的 `four_color_rgb`/`half_size` 分支同样把 `colors` 抬到 4 而不折叠。）也就是说上游对四色 CFA **确实**回落 IGV，并不会"直接跑进去产出垃圾"。
→ **应对**：本库用 `has_fourth_colour()`（`get_colors() > 3`）表达同一属性 —— 它与上游的字面判据对 `CfaDesc` 能描述的一切 CFA **等价**，语义更直白（"这块 CFA 是不是三色 RGB"）。IGV 未移植期间返回 `UnsupportedCfa`，而非静默出图。⚠️ 反例陷阱仍在：普通 Bayer 的**未折叠**掩码**含 3**，所以绝不能拿 `fc_pre == 3` 当四色判据（单测 `a_normal_bayer_is_not_a_four_colour_cfa` 钉住）。
⚠️ 本条原写作"守卫是死代码"，**是错的**：错在把"三色 Bayer 的折叠掩码永不含 3"（真，且与守卫无关）当成了"任何情况下都不含 3"。已在 **rev 6** 更正。

**(e) 梯度解析器只吃 ≤2 个梯度位。** 上游一项占 5 个字 + 最多 1 个可选梯度（`ip += 5`，再条件 `ip++`），而 dcraw 会遍历**全部**梯度位。
→ **应对**：机械校验 —— 对四种 Bayer 排布 × 全部 16 个 `(row & 7, col & 1)` 类，**没有任何存活项带 ≥3 个梯度位（0 例）**。故 ≤2 解析是安全的；第三位只在 dcraw 会越读的地方被我们忽略。单测 `no_surviving_term_needs_more_than_two_gradients` 钉住。

**(f) 常量表与 dcraw 逐字节相同。** `TERMS`(64×6) 与 `CHOOD`(8×2) 与 dcraw 一致（机械 diff 通过）。
→ **应对**：**原样转录，不改一个数字**。

**(g) 存活项数恒为 32。** 16 个类在四种排布下**全部**是 32 项（脚本复现上游的过滤条件得 `counts=[32]×16`）。上游给每类 `1280 B = 320 int32` 预算，最坏消耗 `32×6 + 8×2 + 1 = 209` 字，**不会溢出**。
→ **应对**：`assert_eq!(code.terms.len(), 32)`。项数是移植正确性的**直接探针**：CFA 电平映射一错，存活项数立刻偏移。（脚本留档 `log/vng4_termcount.py`。）

**(h) 权重是 int→float 转换，不是位重解释。** 上游写 `*reinterpret_cast<float*>(ip++) = 1 << weight;` —— 左值类型是 `float`，故走 **int→float 转换**（结果 `1.0` 或 `2.0`）；SSE 注释"省掉 int→float 转换"指的是**读回**时按 float 直读。
→ **应对**：`weight: (1i32 << weight) as f32`。**若误解为 bit-cast**，权重变成 ~1e-45 的非规格化数，阈值 `thold` 随之塌成 0，VNG 平均只剩极少邻居、图像退化成近似最近邻 —— 一个"不报错但明显错"的陷阱，已写进文件头。

**(i) 没有 `-ffast-math`，NaN 必须显式处理。** RT 用 `RTENGINE_CXX_FLAGS="-ftree-vectorize"`（**无** fast-math），故 `0*(1/0) = NaN` 真实可能：VNG 邻居平均除以 `num`，而 `num` 可为 0。
→ **应对**：`math::max0(x)` 显式复刻 libstdc++ 的 `std::max(0.f, NaN) = 0.f`（**不**依赖 `f32::max` 的巧合），单测钉住 NaN→0。

**(j) 首遍"读邻居原生通道"，故可拆相。** 上游把 scatter 与线性插值放进同一行循环做软件流水，并配 `firstRow`/`lastRow` 补算以掩盖分块竞态。但第一遍**只读**邻居的**原生**通道、**只写**当前像素的三个**非原生**通道，二者**不相交**。
→ **应对**：拆成"先全量 scatter、再全量线性插值"两相（均 `par_chunks_mut`），并**省略** `firstRow`/`lastRow`：结果与单线程上游逐位相同，且天然行并行（落进 R4 的"能拆相就拆"纪律）。

### D7 — 三层解耦的落点与 CFA 适配器（数据流）

```
外部管线（rawler / 将来的其它 loader）
        │  ① 输入解析（唯一外部表示 → 本库表示的转换点）
        │     mosaic → Array2D<f32>；rawler CFA(2×2/6×6, 三色) → CfaDesc(双掩码, 四色)
        ▼
CfaDesc + Array2D<f32>          ← 本库自有类型；内核层只认这两个
        │  ② 算法层：bayer/<algo>.rs │ xtrans/<algo>.rs（一算法一文件，互不 use）
        ▼
Rgb { red, green, blue }        ← 本库自有类型
        │  ③ 输出解析（唯一外部耦合点，集中在 bridge.rs）
        ▼
rawler Intermediate::ThreeColor  →  develop 后续（calibrate …）不变
```

- **① 输入解析点**：`CfaDesc::bayer_from_2x2([[u8;2];2])` / `xtrans_from_6x6([[u8;6];6])`。rawler 侧只提供 `cfa.color_at(r,c)`（三色）→ 双绿、未折叠语义在**本函数内部**补齐。新接一条管线（DNG tile、别的 loader）时只需再写一个**同形状**构造函数：内核层与输出层**零改动**。
- **② 算法层**：目录 `bayer/`、`xtrans/` **仅用于消歧同名算法**（`bayer/fast.rs` vs `xtrans/fast.rs`）；`algo.rs` 持三张解耦字典。新增内核 = 新文件 + `mod` 一行 + `IMPLEMENTED_*` 放开一项。
- **③ 输出解析点**：`bridge.rs` 是**唯一** `use rawler` 的文件；换输出目标只替换这一个文件，`Rgb` 与内核不动。
- **边界纪律**：CFA 适配**只发生在 ①**，且单向、无状态、无副作用 —— 内核内部一律按 dcraw **四色**语义工作；折回三色视图由内核自己用 `fc()` 完成（`border_interpolate`/`bilinear`/`igv`/`dcb` 读**折叠**掩码，天然三色）。

## Phases（分步实施）

每批**一次到位**：rayon（对齐上游 OpenMP 分片点）+ 该内核上游有的 SIMD + 逐段语义比对 + 单测。批次只划分**内核覆盖面**，不划分"先标量后加速"。

| 批次 | 内容 | 出口标准 |
| --- | --- | --- |
| **B0 骨架** ✅ | crate + `CfaDesc`/`Array2D`/`Rgb`/`math`/`border`/`algo`(字典+candidates)/`bridge`(→`Intermediate`) + **bilinear** 内核 + 单测；registrar 为 `rawler_fotlab` 的 path dep | 已落地（本批随 CI 首验编译） |
| **B1 打通** | **VNG4** 内核 ✅ + `lib.rs::demosaic_bayer` 分发 ✅ + `IMPLEMENTED_BAYER` 放开 ✅；**待做**：`rawler_fotlab::demosaic.rs` 分发 + `demosaic_candidates()` 暴露 + Kotlin 菜单动态化 | 选 `RAWTRP VNG4` 能出图；`DEFAULT` 逐像素不变；UI 候选可见 |
| **B2 质量层** | **RCD** ✅、**IGV** ✅、**LMMSE** ✅、**DCB** ✅ —— 四个内核 + 分发 + 候选全部落地 | 单测均已落地；**与 RT golden 数值比对仍待做**（Q1），故 B2 的出口标准只算完成一半 |
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
- 2026-09-23 — **rev 3：补入 dcraw 参照语义调查结论 + 三层解耦与 CFA 适配层**（人工指令；dcraw 结论作为移植的权威参照）。
  1. 新增 **R10 — 三层解耦（输入解析 / 输出解析 / 算法文件）+ CFA 适配层**：库只依赖「数组 + CFA」，内核层不见任何 rawler 类型；**rawler CFA（单绿、2×2/6×6 tile）≠ rawtrp CFA（双绿 G1/G2、两张 dcraw 掩码）**，差异是**语义性**的，必须显式转换（`bayer_from_2x2`/`xtrans_from_6x6`），否则 vng4 类内核 channel 3 恒空、**静默**退化。
  2. 新增 **D6 — dcraw 参照语义调查结论（a–j）**，每条给出应对：(a) Bayer 是**四色** `0/1/2/3 = R/G1/B/G2`（RGGB 原始 `0xb4b4b4b4`）；(b) `set_prefilters()` 折叠 G2→G1 得 `0x94949494`；(c) `FC`/`ISGREEN`/`ISBLUE` 读**折叠**掩码，而 vng4 局部 `fc` 宏读**未折叠** `prefilters` —— **一个内核里两张掩码同现**（初稿即错在此，已修）；(d) vng4 的 `if (FC==3)` 四色守卫是**死代码**（折叠掩码永不返回 3），改测 `has_fourth_colour()`；(e) 梯度解析器只吃 ≤2 个梯度位，机械校验"无存活项带 ≥3 位"（0 例）故安全；(f) `TERMS`/`CHOOD` 与 dcraw 逐字节相同；(g) 存活项数恒为 **32**（四种排布 × 16 类全 32），最坏 209 字 < 上游 320 字预算；(h) 权重是 **int→float 转换**（`1.0`/`2.0`）而**非** bit-cast —— 误读会得 ~1e-45 非规格化数、阈值塌成 0；(i) 无 `-ffast-math` ⇒ `0*(1/0)=NaN`，需 `max0` 复刻 `std::max(0.f,NaN)=0.f`；(j) 首遍只读邻居**原生**通道 ⇒ 可拆"先 scatter 后插值"两相、省略 `firstRow/lastRow`。
  3. 新增 **D7 — 三层解耦落点与数据流图**（①输入解析 ②算法层 ③输出解析／`bridge.rs` 为唯一 `use rawler` 处）；CFA 适配只发生在 ①，单向无状态。
  4. 落地进度：`bayer/vng4.rs` 内核已按 (a)–(j) 完成（含 (c) 的掩码修正、(d) 的守卫替换、(h)/(i) 的数值处理），术语表与解析器已机械校验；校验脚本留档 `log/vng4_termcount.py`。
- 2026-09-23 — **rev 4：首次真正跑到单测（新增 CI 单测 job）+ 两处修正**。
  1. **补 CI 单测 job**：原先 CI 只有 `cargo ndk build`，而它**不编译** `#[cfg(test)]` —— 也就是说内核单测从未被编译过，更没跑过（正是 `rules/ACTION.md` 记的"CI 转绿 ≠ 用例跑了"）。`build_rust.yaml` 新增独立 `unit-tests` job（host 目标、`cargo test --manifest-path app/src/binding/rust/rawtrp_demos/Cargo.toml`），与 native 构建并行、不拖慢它。首次运行即 17 passed / 2 failed：**整个 crate（含测试）编译通过**，且暴露了两个真问题（下）。
  2. **bilinear 内核补全（真问题）**：上游 `bayer_bilinear_demosaic` 的列循环是 `j = 2 - (FC(i,1) & 1)` 起、步长 2、`while j < W-2`，当起点为 2 时配对是 (2,3),(4,5)…，**列 `1` 与列 `W-2` 永不写入**。上游之所以没事，是因为它**只**被 `dual_demosaic_RT`（`dual_demosaic_RT.cc:115`）在**已填满**的基础算法平面上调用，那两列保留基础算法的值。本库把 bilinear 当**独立**候选暴露，必须输出完整图，故改为**逐列**遍历 `1..W-1`（两个分支体是上游 `j`/`j+1` 体原样、同四项同求和顺序，逐列形式天然补齐那两列）。另：上游**根本没有** border 填充，本库补 `border_interpolate(…, 1, …)` 属**新增**而非移植行 —— 两条都写进 `bayer/bilinear.rs` 的 `//! Fidelity notes`。
  3. **ramp 测试的前提修正**：`border_interpolate` 用的是**带裁剪**的邻域均值，且左右边测试故意不对称，因此**不**保线性斜坡（(0,0) 的绿 = `(ramp(0,1)+ramp(1,0))/2 = 0.2575` ≠ 0.25）。线性 ramp 断言据此收敛到"纯内核输出"的内部矩形（行 `3..h-4`、列 `3..w-4`）。
  4. 记录：vng4 的偏移**全部在界内**（VNG 窗口恰等于最大表偏移 ±2），**无需 padding** —— 原 B1 行里"含 padding 处理"的说法已删。
- 2026-09-23 — **rev 5：B2 首个内核 RCD 落地**（`bayer/rcd.rs`）。移植中确认的 5 条结论，均已在源码 `//!` 里交叉引用：
  1. **数值域可精确省略**：上游载入 `LIM01(rawData / 65536)`、写出 `rgb * 65536`，即它**内部就在 [0,1] 域**运算。本库 mosaic 已是归一化值，两侧换算**同时省略** —— 因 65536 = 2^16 为精确幂次，故内核仍在**上游同一个数值域**里跑，`eps=1e-5`/`epssq=1e-10` 的相对含义不变；且内部与 `border_interpolate`（直接读 `raw`）**单位一致**，不会出现"内部 ×65536、边界不乘"的错位。这是**必须**成对省略的一处，单边省略即静默错图。
  2. **RCD 的分块是内存约束、不是 cache 技巧**：逐像素工作集 ≈ 6.5 张全分辨率 `f32` 平面（`cfa`+`rgb[3]`+`VH_Dir`+`lpf`/`PQ_Dir`+`P/Q_CDiff_Hpf`），45MP 下 > 1.1GB。故**保留**上游 194×194/步进 176/边距 9 的分块，峰值内存降到 O(tile²×线程数)。
  3. **`tileBorder == rcdBorder == 9`**（`rcd_demosaic.cc:81-82`）⇒ 上游四处 `(tr == 0) ? rcdBorder : tileBorder` 三元式**全是恒等**，写区就是"块内缩 9"。移植按此简化并留档，不改语义。
  4. **并行分片点 = 块行**：上游 `omp for collapse(2)` 按**块**并行；本库按**块行**分片 —— 一个块行独占输出行 `[rowStart+9, rowEnd-9)`，这些区间**两两不交且首尾相接**（`tile_row_bands` + 单测钉死），因此每个 rayon 任务拿到的是真实 `&mut` 切片，**无需 unsafe**、也无需"自己证明不相交"。块内保持串行，与上游粒度一致。
  5. **`intp` 语义再确认**：RT 的 `intp(a,b,c) = a*(b-c)+c`（`rt_math.h:114-122`）是**连续混合**，**不是** dcraw 的 `intp(a,b,c) = a>=0.5 ? b : c` 硬选择。RCD 传的是连续权重 `VH_Disc`/`PQ_Disc`，若按 dcraw 语义移植会得到完全不同的图；本库既有的 `math::intp` 与 RT 逐字一致（`a*(b-c)+c`，求值顺序亦同），RCD 直接复用、无需新增。
  另：**scratch 必须每块清零**（复刻上游 `calloc`）。这不是防御性写法 —— 步骤 4.3 在行 `r` 读 `rgb[c]` 的行 `r-3`，顶部若干行读到的是任何阶段都还没写过的元素，该值会**传播进本块真正写出的行**；上游靠 `calloc` 把它定为 0，复用 scratch 则会变成上一块的残留值（形成静默的"块位置依赖"）。`cfa` 是唯一例外（载入循环重写全部可达元素）；`bufferV`/`bufferH` 上游是栈数组、写后读，同样无需清零。
  另：**RCD 无手写 SIMD**（`rcd_demosaic.cc` 内 `_mm_`/NEON intrinsics 计数为 0），靠 `-ftree-vectorize` 自动向量化 —— 故本内核**没有**可移植的 SIMD 路径，标量 + rayon 即完整移植（对照 Q3）。
- 2026-09-23 — **rev 6：更正 D6(d) —— vng4/RCD 的四色守卫是*活的*，此前记为"死代码"是错的**。
  1. **错在哪**：D6(d) 原写"`FC` 读折叠掩码 ⇒ 永不返回 3 ⇒ 守卫是死代码 ⇒ 四色 CFA（RGBE）会直接跑进去产出垃圾"。实际 `set_prefilters()`（`rawimage.h:50-56`）**仅当 `isBayer() && get_colors() == 3` 才折叠**；四色 CFA 的 `get_colors() != 3` ⇒ `filters` **保持未折叠** ⇒ `FC(i,j)` **确实会返回 3** ⇒ 守卫**命中**并 `return igv_interpolate(W, H)`。`dcraw.cc:5025-5034`（`filters > 1000 && colors == 3` 下的 `four_color_rgb`/`half_size` 分支）同样会把 `colors` 抬到 4 而**不**折叠。错因：把"普通三色 Bayer 的折叠掩码不含 3"（真，但与守卫无关）**推广**成了"任何情况下都不含 3"。
  2. **影响面**：本文件 D6(d)（已就地改正 + 加警示）、`rules/STRUCT/detail/RAWTRP-DECODE-000003.md` 的 §3.3 与 Change History（已追加更正条）、`bayer/vng4.rs`、`bayer/rcd.rs` 的 `//!`、`src/lib.rs` 的 VNG4 分支注释、以及 `.workbuddy/memory/MEMORY.md`。**可观测行为不变**：`has_fourth_colour()`（`get_colors() > 3`）与上游字面判据对 `CfaDesc` 能描述的一切 CFA 等价，四色 CFA 仍被拒（`UnsupportedCfa`）；变的只是**理由** —— 从"复刻上游本意、绕过死代码"改为"复刻上游本意，守卫本来就是活的"。IGV 未移植期间不回落，仍是**已记录的行为缺口**（`bayer/igv.rs` 落地后两处守卫应改为调用它）。
  3. **通则（写在这里以免重犯）**：断言"上游某分支永不触发"必须同时给出**它所测的那个量在该分支下取值的完整推导**，而不是只推导一种输入。守卫类代码尤其危险 —— 一旦判成死代码，就会诱使移植者**删掉**它或**改用别的条件**，两者都在改变可观测行为（本例差一点就把"四色 CFA 回落 IGV"这条真实路径抹掉）。
- 2026-09-23 — **rev 7：B2 第二个内核 IGV 落地**（`bayer/igv.rs`，`demosaic_algos.cc:609-865`）。11 条结论，均已在源码 `//!` 里交叉引用：
  1. **同一个文件里有*两份完整实现*，且不是"标量+向量化"关系。** `demosaic_algos.cc` 用 `#if defined(__SSE2__) || defined(RT_SIMDE)`（`:217`）与 `#else`（`:608`）各给一份 `igv_interpolate`；SSE2 那份把工作缓冲重排成半尺寸交织平面（`rgb[2]`、`chr[4]`，`:225-237`），索引体系整体不同。本库移植**标量**分支 —— 这正是我们的目标架构会编译的那一份（aarch64 无 `__SSE2__`；`WITH_SIMDE` 默认 **OFF**，`external/RawTherapee/CMakeLists.txt:203`）。x86-64 桌面构建走 SSE2 分支，两份应只差浮点舍入，但那是**待验证**命题，需要 golden 图 —— 已记入 B2 的出口标准。
  2. **`epssq` 是 `1e-5`，不是 `1e-10`。** 上游注释仍写 "mod epssq -10f =>-5f"，`-10` 只活在注释里（`:611`）。移植必须读**值**，不是读注释。
  3. **`calloc` 是承重的 —— 且只对 `chr`/`vdif`/`hdif` 承重。** 第一条对角 chroma pass 读它写出的行**之外**三行，靠近画幅边缘的行根本不会被写；上游让那里的 0 传播进最外圈存活的输出行，再靠 `border_interpolate(…, 8, …)`（`:854`）覆盖画幅。复用未清零的 scratch 会让结果取决于"这块缓冲上一世是谁用的"。`rgb` 同样清零只为保持初始状态一致（它被读到的每个元素此刻都已写过）。
  4. **`>> 1` 偏移换算是本内核最容易静默写错的一处。** `vdif[(indx - v2) >> 1]`（`v2 = 2 * width`）在**半尺寸**平面里是 `width` 个槽位（跳 2 个打包行），**不是** `width / 2` —— 因为 `>> 1` 作用在整个**差**上。故 `v2`/`v4`/`v6` → `width`/`2 * width`/`3 * width`。写成 `half_w` 编译照样过、切出的形状也"看着对"，只是读到错误的行。（本批初稿即错在此，已修，并留了对照注释。）
  5. **step 4 与 step 5 必须是两个 pass。** 它们写同两个 chroma 平面的**不同行奇偶**，且各自通过 `±1`/`±3` 行偏移读对方的输出；上游因此把它们拆成两个 `#pragma omp for`（`:729`/`:757`），中间那道屏障是**正确性**要求。把两者合成一个行循环（函数体逐字节相同）会引入竞争。
  6. **`FC(row, 1)` 与 `FC(row, 0)` 不可互换。** 差分层/绿层/R@B 层的列起点取 `FC(row, 1)`（`:667`/`:699`/`:730`/`:758`），绿点色度层取 `FC(row, 0)`（`:786`/`:809`）。两者都落在"非绿"像素上，但文本不同。
  7. **⚠️ 更正计划：IGV 并*不能*关闭"四色 CFA 回落"这个缺口。** 此前 B1/B2 记"IGV 落地后两处守卫应改为调用它"。但 `igv_interpolate` 自己声明 `float* rgb[3]`（`:615`）并按 `FC` 载入 `rgb[c]`（`:649-650`）；四色 CFA 下 `FC` **确实**会返回 3（见 rev 6）⇒ `rgb[3]` 越过三元素**指针**数组的末尾、读到栈上下一个槽。也就是说上游这条"回落"本身就不是可用路径。可移植的选项只有两个：原样复现 UB（做不到，也不该做），或拒绝该 CFA。本库拒绝，故 `vng4`/`rcd` 的 `UnsupportedCfa` **就是最终行为**，该缺口**按设计保留**，不再标记为"待 IGV 关闭"。
  8. **并行分片：7 相中 4 相并行，chroma 四相（4/5/6/7）串行 —— 有界缺口，非疏漏。** 这四相都读**自己写的那个平面**、且在自己拥有的行**之外一到三行**，任何 `par_chunks_mut` 切分都无法安全表达（安全 Rust 不能对同一缓冲的重叠区域同时给出 `&` 与 `&mut`）。两条出路都比收益贵：每通道加一张备用 chroma 平面会把 IGV 从 6 张全分辨率平面推到 8 张（45MP 下 1.44GB，而它本身已需 1.08GB）；`unsafe` 不是本移植使用的工具。**已并行**的是 1.1（载入）、1.2（梯度 + 高阶插值 + 色差）、1.3（IGV 绿通道 + R/B 点色度）、8（内部写出）—— 上游 `#pragma omp for` 的同一粒度，相边界即上游的隐式屏障。**派生修法**（留档）：把两张 chroma 平面按**行奇偶分缓冲**后，step 4 只读偶行缓冲、只写奇行缓冲，读写集合天然不交，即可全并行；或加备用平面做"拷贝—并行写—交换"。
  9. **两处 `Shape` 拒绝（有意偏离，均因上游此处无保护）。** ① 宽必须为**偶** —— 半尺寸色差平面按 `indx >> 1` 打包时，"一行恰占 `width / 2` 槽位"只在偶宽成立，本库依赖这一点把该平面按行分片；上游无此检查（它会建一个形状不同的半平面继续出图）。真实传感器的 2×2 CFA 两轴均为偶数，故实际不可达。② 需要 `width > 8 && height >= 8` —— `border_interpolate` 的首道只测**左**边界（`j1 > -1`），右侧不留一列会读到行外；上游那处是无保护的 `red[i][j] = …`。两处都在 `bayer/igv.rs` 的 `//!` 与函数文档里写明。
  10. **`math` 新增/修正四处**（本内核所必需；都是忠实性问题，不是风格）：① `lim` 改为上游的**组合式** `max(low, min(val, high))`（`rt_math.h:90-93`）—— 与区间判断对有限值等价，对 NaN 不同：`min2(NaN, high)` 保留 NaN，`max2(low, NaN)` 回落到 **`low`**；单测 `lim_collapses_a_nan_to_the_low_bound` 钉住。② 新增 `min2`（libstdc++ `std::min` 的 NaN 不对称，与既有 `max2` 镜像）。③ 新增 `median3`：复刻 `rtengine::median(a, b, c)` —— 它经 `std::array` 走 `nth_element`，而 libstdc++ 对 ≤3 元素短路成 `__insertion_sort`，结果即中位数；**分支结构照抄**（含"无界扫描靠前一次失败比较作哨兵"）而非换成三比较器网络，因为含 NaN 时两者不同，单测 `median3_reproduces_upstreams_nan_behaviour` 钉住。**⚠️ 本条的*推导*已被 rev 8 更正**：选中的重载不是 `nth_element` 路径，而是 `std::array<T,3>` 的专用比较器网络 `max(min(a,b), min(c, max(a,b)))`。结论的*方向*（"不能换成随手写的三比较器网络、含 NaN 时不同"）仍然成立，但具体网络写错了；原文保留留档，见 rev 8 第 5 条。④ 新增 `sqr`：上游是**函数**不是宏，`SQR(a + b + c) = (a + b + c)²`。
  11. **本分支无手写 SIMD 可移**：标量实现里 intrinsics 计数为 0（SSE2 是另一份实现，见 1）。标量 + rayon 即该分支的完整移植，与 RCD 同理。
- 2026-09-23 — **rev 8：B2 第三个内核 LMMSE 落地**（`bayer/lmmse.rs`，`lmmse_demosaic.cc` 830 行；提交 `be15ab6`）。12 条结论，均已在源码 `//!` 里交叉引用：
  1. **数值域：`rawData` 域，但省略方式与 RCD *不同*。** RCD 是「载入 ÷65536、写出 ×65536」**成对省略**（它内部本来就在 [0,1]）。LMMSE 不用比值 —— 它把 0..65535 的值**直接当 LUT 索引**用（`gamtab[rawData]`），所以省略的只是那对换算本身：本库取 `SCALE = 65536`，令 `clip(x * SCALE) / SCALE` 与上游 `LIM01(rawData / 65536)` 逐位一致（65536 = 2^16 精确幂次，同 RCD 的理由）。**两个必须记住的后果**：① CFA 采样点直通的是 `CLIP(rawData)`，即饱和样本 = **65535/65536 ≈ 0.99998**，**不是** 1.0（单测 `a_saturated_sample_is_65535_over_65536` 钉住）；② 输出**不上钳** —— 上游只在 `igammatab` 查表后 `std::max(0.f, …)`，没有任何上界，实测 iter=2 峰值 ~1.54、iter=6 ~2.64 ⇒ 下游（`bridge` → `Intermediate::ThreeColor` → calibrate）必须能接受 **>1 的线性值**。
  2. **LMMSE 是唯一*不*调用 `border_interpolate` 的 Bayer 内核。** vng4/rcd/igv/bilinear 都靠它补画幅边缘；LMMSE 完全不补，只有 `ba = 10` 的**零环**（上游 `qix[]` 用 `calloc`）。步骤 4/6 的核够到 ±4（色差核 ±4、绿核 ±1/tap 距离），故零环被**读**进最外 ~7px 并**留在输出里**。这是**复现，不是缺陷** —— 上游就是这样，"顺手"补边反而偏离。
  3. **`iterations` 状态机有*三个*活分支，不是两个**（`:79-88`）：`1..=4 → iter = it-1, passref = 0`；`5|6 → iter = 3, passref = it-4`；`7|8 → iter = 3, passref = it-6`。⇒ **7/8 ≡ 5/6**（`iter` 与 `passref` 双双相同），`> 8` 落回第一支（`it-1`），`< 1` 亦然（负数被 `iter = it-1` 拉成负值，循环 `0..iter` 不执行）。另 `iterations == 0` **额外**把 tone 换成 `makeIdentity`。GUI 范围是 **0..=6、默认 2**（`rtgui/tools/bayerprocess.cc` / `params/raw.cc`）⇒ 7/8 **GUI 不可达但 profile 可达**，必须实现。抽成 `iteration_state()` 以便直接测；`iteration_table_matches_upstream` + `equivalent_iteration_counts_produce_identical_output` 从两个方向钉住。
  4. **tone 曲线：索引单位是 `rawData`（0..65535），不是 [0,1]。** 正变换 `(*gamtab)[rawData]` 直接吃 0..65535；反变换 `(*igamtab)[65535.f * x]` 吃 0..65535，除数由调用方补。上游用的是 `operator[](float)`（`LUT.h:462-485`，**不是** `getVal01`），三条易丢细节：① `idx = (int)index` 是**向零截断**（源码注释明写"别换 floor"），且后面的分支会**重新赋值** `idx`；② 上界比较对象是 `maxsf = 65534.0f`（= `size - 2`，`LUT.h:131-133`），故最后一个可插值区间是 `[65534, 65535)`，`> maxsf` 时要么钳到 `data[upperBound = size-1]`（`LUT_CLIP_ABOVE`）、要么从**末段外推**；③ 缺 `LUT_CLIP_BELOW` 时负索引**外推**到 `data[0]` 之下 —— 这正是 `igammatab_24_17`（构造时 `clip == 0`）需要调用方 `std::max(0.f, …)` 的原因，若"顺手"改写成 `1/(1+x)` 式钳位会**静默改变高光**。`makeIdentity` 两张表是精确线性的，用 `identity_lut()` 闭式求值（省 256 KB × 2）。
  5. **⚠️ 更正 rev 7 第 10③：`median(a, b, c)` 选中的是 `std::array<T,3>` 的*专用网络*，不是 `nth_element` 路径。** `rtengine::median` 是**可变参数包装**（`median.h:6240-6244`），转发到 `median(std::array<float,3>)`；而 `std::array<T,3>` 有**两个**重载 —— 通用 `nth_element` 版（`median.h:41-51`）与**专用**比较器网络版（`median.h:53-57`，实现为 `max(min(a,b), min(c, max(a,b)))`）。两者在**部分序**下专用版更特化 ⇒ **专用版胜出**。有限值下两版一致（所以此前没被发现），**含 NaN 时不同**：网络版 = NaN 在首 → NaN、在第二/第三 → 取有限的那个；插入排序版 = NaN 在首 → 回落成有限中位数。本库改为按**专用网络**实现，单测改为**三例**（NaN 在首/中/尾）。**影响面**：`median3` 被 **IGV 的色度限幅**与 **LMMSE 的 median 步骤**共用 ⇒ 两个内核都因此受益。**通则**：复刻 `math` 里带重载/包装的函数，必须确认**实际选中的那个重载**，不能只看调用点写了几个实参 —— "可变参数包装函数 + `std::array` 形参 + 模板特化"是 libstdc++ 里极常见的组合，光数实参个数一定会选错实现体（rev 7 第 10③ 就是这么错的）。
  6. **`xdiv2f`（`sleef.h:1278-1288`）= 位技巧"除以 2"，不是 `* 0.5`。** 实现是 `bits = (int)d; if (bits & 0x7FFFFFFF) bits -= 1 << 23;`。有限正常数上它等于 `d * 0.5`（且精确）；对 **±inf / NaN / 非规格化数**则与乘法**不同**（本库按位复刻并单测 `xdiv2f_decrements_the_exponent` 钉住分歧点）。LMMSE 的 8 处 `xdiv2f(...)`（`:188/189/200/201/217/218/444/447`）全部换用它 —— 这是"上游用 sleef 就地减指数"而不是"随手写 ×0.5"的典型。
  7. **`median9`：9 元网络逐字复刻 `median(std::array<T,9>)`**（`median.h:175-215`），依赖 `min2`/`max2` 与 libstdc++ `std::min`/`std::max` **同向的 NaN 不对称**（`min2(a,b) = b < a ? b : a`、`max2(a,b) = a < b ? b : a`，已单测钉住），故网络里 NaN 的**传播路径**与上游一致。若就地换成"排序取第 5 个"，有限值等价、NaN 不等价。
  8. **LMMSE 的"SIMD"只是同一张网络的分道并行 ⇒ 标量等价物*逐位相同*（并更正 D1 的 `lmmse 21`）。** 上游在步骤 7 用 `#if defined(__SSE2__) || defined(RT_SIMDE)` 给出一条 `vfloat` 路径（`std::array<vfloat,9>` + `_mm_storeu_ps`，`:510-514`），`#else` 给出一条 `float` 路径（`:526`）。**两条调的是同一个 `median(std::array<T,9>)` 网络**，向量版只是 4 道同时算 ⇒ 本库的标量 `median9` 与**任一**分支逐位一致（是"同一算法"，不是"近似等价"）。该文件真实向量内建**只有这 1 处** ⇒ 与 RCD 同理：**没有"待补的 SIMD"**，标量 + rayon 即完整移植（对照 Q3；D1 的 SIMD 命中表已就地更正）。
  9. **并行分片：能分的全分，不可分的是*有界串行* —— 不是疏漏。** 上游 OpenMP 分片点共 8 处（`:156/178/236/260/403/437` …）。本库按**行**分片（`par_chunks_mut`）：凡"只读自己*没在写*的平面"的相（载入、差分、低通、混合、CFA 回填、median 差分、内部写出）**全部并行**；**步骤 6/7（`chroma_at_green_serial` / `chroma_at_rb_serial`）与 `refinement` 保持串行** —— 它们要读**自己正在写的那个平面**、且在所拥有行之外 1~2 行。上游能写成 `#pragma omp for`，是因为它按**像素点类别**分工（绿点 / R-B 点各写各的、列奇偶错开），读写集合天然不交；而安全 Rust 的借用检查器**看不到**这层"类别不相交"。两条出路都比收益贵（加备用平面 ×2 ⇒ 45MP 下再多 ~360MB；或 `unsafe` —— 不是本移植用的工具），故停在有界串行并留档。**这是"上游并行 ≠ 本库并行"的第三例**（RCD 是块行、IGV 是 chroma 四相），模式一致、可预期。
  10. **`refinement` 只在 `iterations > 4` 跑，且上游*先释放 scratch*。** `:649-662` 先 `delete[]` 五张平面**再** `refinement(passref)`；45MP 下那是 ~900MB，本库用 `drop(planes)` 依同序释放再精修。`refinement` 自身按 `PassCount` 做多轮 R/B 校正（沿用 `qix` 语义），故 7/8 与 5/6 的"完全等价"是**含精修**的等价。
  11. **分配失败 ⇒ 回落 IGV，与上游同（且这与四色守卫*不同*）。** `:103-133` 在 plane 分配失败时 `return igv_interpolate(W, H)`。本库 `Planes::try_new` 返回 `None` 即 `return super::igv::bayer_igv_demosaic(...)`。**注意别和四色守卫混为一谈**：四色 CFA 的那条回落**本身不可用**（IGV 自己就会越界，见 rev 7 第 7 条）⇒ 那里是**拒绝**；这里 IGV 拿到的是**三色 Bayer**，回落**可用** ⇒ 忠实复现。
  12. **四色 CFA / 非 Bayer CFA ⇒ `UnsupportedCfa`**（与 vng4/rcd 一致，理由同 rev 7 第 7 条）。
  另：本批同时修正 `math.rs` 的 `median3`（第 5 条）、新增 `xdiv2f`（第 6 条）与 `median9`（第 7 条），共 **11 个单测**；另留一份 Python 参照模型（`log/lmmse_ref.py`、`log/lmmse_check.py`，`log/` 已 gitignore）做结构校验：平坦场在 iter=0/2 的内部区域恒为 0.5、7/8 与 5/6 输出逐位相同、iterations 0..8 全程有限且非负、四种 Bayer 排布在平坦场上一致 —— 全部通过。**golden 数值比对（B2 出口标准）仍未做**，仍挂在 Q1 下。
- 2026-09-23 — **rev 9：B2 第四个内核 DCB 落地（B2 内核覆盖面就此齐全）**（`bayer/dcb.rs`，`demosaic_algos.cc:963-1548` —— 13 个函数：`dcb_initTileLimits` 963、`fill_raw` 987、`fill_border` 998、`copy_to_buffer` 1043、`restore_from_buffer` 1054、`dcb_hid` 1065、`dcb_color` 1081、`dcb_hid2` 1123、`dcb_map` 1154、`dcb_correction` 1177、`dcb_pp` 1200、`dcb_correction2` 1257、`dcb_refinement` 1297、`dcb_color_full` 1340、`dcb_demosaic` 1406；提交 `f747c7e`）。11 条结论，均已在源码 `//!` 里交叉引用：
  1. **数值域：必须显式往返，且理由与 RCD 相反。** RCD 载入归一化、写出反归一化 ⇒ 一对换算**自动抵消**；DCB 则是 `cache[indx][fc] = rawData[y][x]` **原样读入**、写出 `std::max(0.f, …)` 原样送出 ⇒ 内核内部**就是 `rawData` 域**。故本库在载入乘 `SCALE = 65536`、写出除回（`SCALE` 与 RCD 同一个，同源于"mosaic = rawData/65536"这条 crate 约定）。**这不能当风格问题**：`dcb_refinement` 六处除数是 `1.f + 2.f*currPix`、`1.f + image[..][c] + currPix`，`dcb_color_full` 也全是 `1.f / (1.f + Σ|差|)` 加常数 `1.325/0.175/0.075/0.875` —— `1.f` 是**绝对**常数，喂 0..1 会让它压过全部信号。输出**不上钳**（只有 `std::max(0.f, ·)`），故与 LMMSE 同理允许 >1。
  2. **`fill_border` 不是"补一圈边"**，三处易丢细节都已复现：① `col = W - border` 那一跳使它在**画幅内部行上直接跳过整段内部**，合起来只访问「`row < border` / `row >= H - border` / `col < border` / `col >= W - border`」这条 **6px 画幅边环**；② 邻域求和**没有下界判断** —— 画幅第 0 行/列时 `row-1`/`col-1` = `-1`，cache 索引 `(y-y0+TILEBORDER)*CACHESIZE + TILEBORDER + (x-x0)` 恰好落在 **cache 第 9 行/列（零环）**，于是**样本读成 0.0，而 `sum[f+4]` 照样 +1** ⇒ 均值被零环**稀释**，这是上游输出的一部分；③ `FC` 也在那些**负坐标**上求值，按 C 的补码回绕（`fc_i`）—— 一个"不存在的行/列"的颜色。
  3. **DCB 没有收尾的 `border_interpolate`。** 上游每个像素都由**它自己那个 tile** 写出（`for(y=0;y<TILESIZE&&y0+y<H;y++)` 全写，不缩边）⇒ **tile 行恰好划分整幅**（区间两两不交**且**首尾相接、无缝无叠）。这正是本库能用 `par_chunks_mut(TILESIZE*w)` 把三张平面切成 tile 行、把**真实 `&mut` 切片**交给每个 rayon 任务而**无需 `unsafe`** 的原因，也是为什么这里**没有** RCD/IGV 那样的收尾补边调用。该性质由单测 `tile_row_chunks_partition_every_row` 钉住。
  4. **`buffer` 与 `chrm` 是*同一块内存*。** 上游 `float (*chrm)[2] = (float(*)[2]) (buffer);`（`:1435`，注释"No overlap in usage of buffer and chrm means we can reuse buffer"）。本库合成**一个**字段 `Tile::rbuf`。拆成两个字段看着更整洁，却会**静默改变行为**：enhance 分支依赖 `memset(chrm, …)` 清掉 `restore_from_buffer` **刚读过**的那块缓冲。
  5. **`dcb_color` 第二个循环的列奇偶取自 `FC(absRow, absColMin + 1)` —— 全文件独一份的"绿列"。** 其余所有循环（`dcb_hid`/`dcb_hid2`/第一个 `dcb_color`/`dcb_correction`/`dcb_correction2`/`dcb_refinement`）都用 `FC(absRow, absColMin)` 起跳（红/蓝列），只有它多算一列；而且它的颜色索引取 `FC(absRow, col + 1)` —— 取**邻居**的颜色，不是本站点的。写错任一者都会把通道接反且不报错。
  6. **`dcb_color_full` 第一个 pass 走的是*整个 cache*（`1..CACHESIZE-1`），不看 `dcb_initTileLimits`。** 这是全文件**唯一**忽略 tile 限界的循环，而且是**必须**的：后面两个 pass 的核够到 ±3 行/±1 列，超出 tile 限界。本库照抄（并用注释标出这个例外）。
  7. **`dcb_pp` 是就地、且*顺序相关*的**：它写出的 `image[indx][0]/[2]` 会被**后面的**像素当作邻域读回，所以不能并行、也不能改成先算后写。上游用一个 `float (*pix)[3]` 指针在 8 邻域上按**行主序**走（`indx-u-1, -u, -u+1, -1, +1, +u-1, +u, +u+1`）—— 本库按**同一顺序**列出偏移，因为每个累加器的浮点舍入取决于加数顺序。
  8. **`min`/`max` 是 RT 自己的模板**（`rt_math.h:60/73`：`b < a ? b : a` 与 `a < b ? b : a`），**恰好就是**本库既有的 `math::min2`/`math::max2`（当初为 IGV 复刻 libstdc++ 语义时加的）。`dcb_map`、`dcb_refinement` 直接复用，无需新增。（若误用 `f32::min`/`f32::max`，NaN 传播方向会不同 —— 与 rev 7 第 10①② 是同一类坑。）
  9. **DCB *完全没有* SIMD。** 其源区（`:963-1548`）内 `_mm_`/`__m128`/`vld1`/`simde`/`LVFU` 计数为 **0**（`demosaic_algos` 的 50 个命中全在 IGV 那两段）。⇒ 与 RCD 同理**无"待补的 SIMD"**，标量 + rayon 即完整移植（对照 Q3）。
  10. **并行粒度：上游只在 tile 循环上并行**（`:1423` 的 `omp parallel` + `:1437` 的 `omp for schedule(dynamic) nowait`，外加 `:1543` 一个进度计数器 atomic），**所有 helper 都是串行**。本库按 **tile 行**分片（tile 之间互不依赖 ⇒ 粒度等价），tile 内保持串行 —— 与上游同一粒度，且完全在安全 Rust 内。**这是"上游并行 ≠ 本库并行"的第四例**（RCD 块行 / IGV chroma 四相 / LMMSE 步骤 6-7 / DCB tile 行），四例模式一致：**凡上游靠"类别不相交"或"任务天然独立"而并行的地方，本库优先找*结构上可切分*的维度**，找不到就留在有界串行并留档。
  11. **`iterations <= 0` 走同一条路**（上游 `for (int i = iterations; i > 0; i--)`），GUI 默认 `dcb_iterations = 2`、`dcb_enhance = true`。`dcb_enhance` 切换的是**最后一级**（`dcb_color` ↔ `dcb_refinement` + `dcb_color_full`），不是装饰开关 —— 单测 `enhance_is_not_a_no_op` 与 `zero_and_negative_iterations_take_the_same_path` 分别钉住两端。
  另：DCB 的 `tile_row_bands`/`Tile` 结构与 RCD 同族但更简单（无收尾补边、无 `tileBorder != kernelBorder` 的三元式），`Tile::clear()` **每块 tile 都清零**（复刻上游每块 `memset`，因为外层零环会被 `fill_border` 的邻域和读到）；单测用**分段常数马赛克**（红站 0.2 / 绿站 0.5 / 蓝站 0.8）作 DCB 的**不动点**做精度断言 —— 该输入下所有 pass 只在**同一通道内部**做差或加权，故四种 Bayer 排布都应**精确**重建（1e-4 容差），任何奇偶/通道接反都会立刻失败。**golden 数值比对仍未做，B2 出口标准只完成一半。**
