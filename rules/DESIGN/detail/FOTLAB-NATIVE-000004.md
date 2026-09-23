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

### D6 — dcraw 参照语义调查结论（移植依据与应对）

RT 的这些内核是 **dcraw 的直接后代**（`vng4` = dcraw `vng_interpolate`，`dcraw.c:4422`），因此**必须按 dcraw 的语义读**，否则会得到"能编译、能出图、但数值错"的移植。以下结论均已对源码**机械核对**（表格 diff / 脚本复现上游过滤条件），是移植的**权威参照**；每条都给出我们的应对，并已落进 `bayer/vng4.rs` 的文件头 `//! Fidelity notes`。

**(a) Bayer CFA 是四色，不是三色。** dcraw 的颜色码是 `0/1/2/3 = R/G1/B/G2`（`dcraw.cc:173`）—— **两个绿是不同电平**。RGGB 的**原始**掩码是 `0xb4b4b4b4`，RT 自己注明 `// R G1 B G2`（`rawimage.cc:1373`）。`ri->get_colors()` 才是"传感器几个色"（Bayer 恒为 **3**，RGBE 类四色 CFA 才 >3）——**与"未折叠掩码里有没有 3"是两回事**。
→ **应对**：`CfaDesc` 加 `colors: u8`；`bayer_from_2x2` 按"奇数行绿 = G2"构造原始掩码，再折叠；`has_fourth_colour() = colors > 3`。

**(b) `set_prefilters()` 把 G2 折进 G1。** `rawimage.h:50-56`：`prefilters = filters; filters &= ~((filters & 0x55555555) << 1);` → RGGB `0xb4b4b4b4` → `0x94949494`（仅当 `isBayer() && get_colors()==3`）。
→ **应对**：`fold_prefilters()` 逐位复刻该行（幂等）；`CfaDesc` **两张掩码都存**。单测对四种 Bayer 排布 pin 住折叠结果 = dcraw 常数：`0x94949494`(RGGB) / `0x16161616`(BGGR) / `0x61616161`(GRBG) / `0x49494949`(GBRG)。

**(c) 谁读哪张掩码 —— 一个内核里两张都用。** `RawImage::FC`/`ISGREEN`/`ISBLUE`/`ISRED` 读**折叠**掩码（`rawimage.h:268-283`，三值）；但 `vng4_demosaic_RT.cc:62` 有一个**局部** `#define fc(row,col)` 直接读 `prefilters`（**未折叠**，四值）。
→ **应对**：`CfaDesc` 暴露两套方法 —— `fc*`/`is_*`（折叠）与 `fc_pre*`（未折叠），**逐处对照上游、不统一**。vng4 里两者同现：`interpolate_row_redblue` 走 `ISGREEN`/`ISBLUE`（折叠），而 scatter / 第一遍 / VNG 主循环的 `color` 走局部 `fc`（未折叠）。**这是本次移植最容易写错的一处**（初稿即错，已修）。

**(d) VNG4 的四色守卫是死代码。** `vng4_demosaic_RT.cc:67-76` 意图是 `if (FC(i,j) == 3) → 回落 igv_interpolate`，但 `FC` 读折叠掩码，**永远不可能返回 3** → 四色 CFA（RGBE）会直接跑进去产出垃圾。
→ **应对**：改测上游**本意**的属性 `has_fourth_colour()`（`get_colors() > 3`），**不复制死代码**。⚠️ 反例陷阱：普通 Bayer 的**未折叠**掩码**含 3**，所以绝不能拿 `fc_pre == 3` 当四色判据（单测 `a_normal_bayer_is_not_a_four_colour_cfa` 钉住）。

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
| **B1 打通** | **VNG4** 内核 ✅（含四色 CFA 回落）+ `lib.rs::demosaic_bayer` 分发 ✅ + `IMPLEMENTED_BAYER` 放开 ✅；**待做**：`rawler_fotlab::demosaic.rs` 分发 + `demosaic_candidates()` 暴露 + Kotlin 菜单动态化 | 选 `RAWTRP VNG4` 能出图；`DEFAULT` 逐像素不变；UI 候选可见 |
| **B2 质量层** | **RCD** ✅（内核 + 分发 + 候选）；**待做**：LMMSE、DCB、IGV | 各自单测 + 与 RT golden 数值比对达标 |
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
