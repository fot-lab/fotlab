# 高光溢出色偏端到端归因 — 归一化无上限钳位、exposure 裁剪语义不一致、双支路分歧

- ID: FOTLAB-RAWLER-000013
- Status: Observation
- Priority: P1
- Created: 2026-09-26
- Owner: —
- Related: FOTLAB-RAWLER-000005 (工作空间锁死 sRGB / 宽色域枢纽提案), FOTLAB-RAWLER-000009 (pre-demosaic 槽位，exposure 抽取为纯函数), FOTLAB-RAWLER-000012 (exposure 移至管线最前), FOTLAB-RAWLER-000004 (as-shot / decode-once 契约)

## Background & Goal

使用方为追求特定效果，拍摄了一批**故意过曝溢出**的照片，症状是"高光带一点颜色，不是纯白"，并且**只在进入 rawalchemy（ProPhoto + log）后显现，Kotlin 预览的 PNG 看上去是正常的白色**。

本轮为纯分析调研（**未改动任何仓库代码**），目标是回答四组问题并定位责任环节：

1. 色偏是不是 demosaic 造成的？在 CFA / 相机空间做钳制能否解决？
2. `develop` 输出到 rawalchemy 的 ProPhoto 数据是否越出 0..1？
3. loader（归一化到 0..1）输出时**是否一定**落在 0..1？会不会溢出？
4. exposure 的 `[0,1]` clamp 会不会改动此前 loader 的数据？

方法：逐行核对源码，并把每一段链路写成自包含的纯 Python 复刻脚本（置于 gitignored 的 `log/`）：

| 脚本 | 复刻对象 |
| --- | --- |
| `log/partial_saturation_chain_sim.py` | 传感器部分饱和 → WB → cam2rgb → clamp → 8-bit |
| `log/prophoto_range_sim.py` | 双支路：sRGB D65 与 ProPhoto D50 → F-Gamut C → F-Log2 → 8-bit |
| `log/loader_range_sim.py` | `correct_blacklevel*` 归一化内核 + 真实相机库扫描 + `apply_exposure` 语义 |

规范约束（`rules/REVIEW.md` §Review Principles 5）：`external/dnglab`（rawler）视为**固定约束**，本文只记录其实际行为与在 first-party 侧的应对方式，**不提出对上游源码的改动**。

## Finding

### F1. 归一化只做**下限**钳位，0..1 是未强制的假设（硬事实）

`external/dnglab/rawler/src/imgop/raw.rs:94`（`correct_blacklevel_channels`）与 `:165`（`correct_blacklevel_cfa`）：

```rust
let max = [whitelevel[i] - blacklevel[i]; CH];
let clip = |v: f32| { if v.is_sign_negative() { 0.0 } else { v } };   // 只判符号位
out = clip(v - blacklevel[i]) / max[i];                              // 无上限
```

`clip` 只把负数抬到 0，**没有任何上限钳位**。`external/dnglab/rawler/src/rawimage.rs:519` `apply_scaling` 的文档注释 "floating point values in range 0.0 .. 1.0" 是对输入的**假设**，不是对输出的强制：只要 `v > whitelevel`，输出就 > 1。

### F2. `whitepoint` 是标称参考白，不是 ADC 满量程 → 溢出真实存在

`whitepoint`（`external/dnglab/rawler/data/cameras/**.toml`）经 `decoders/camera.rs:129` 成为 `Camera.whitelevel`，即 `apply_scaling` 的除数。扫描 417 个**顶层**默认值：

- **189 个（45%）** 恰好是 2ⁿ−1（等于 rail，安全）；
- **228 个（55%）是别的标称值**，其中 221 个"同档位内 ≥80% rail"属高置信（这些值明显属于该位深，却低于满量程）：

| 相机 | whitepoint | 打满像素归一化到 |
| --- | --- | --- |
| canon/30d（12-bit） | 3398 | **1.2051**（+21%） |
| canon/40d（14-bit） | 13600 | **1.2046**（+21%） |
| canon/1ds · kodak/dcs760c · olympus/e-410 | 3500 | 1.1700 |
| olympus/e-5 · e-pl1 | 3604 | 1.1362 |

相机库自身也承认该字段不可靠：`canon/5dsr.toml` 原文 `whitepoint = 52000 # seems to be incorrect for ISO 50`。rawler **没有 dcraw 的 `maximum` 重扫**（全库 grep 无此逻辑），因此两个方向的失配都不被校正：

- whitepoint **低于** rail → 打满像素归一化到 > 1.0；
- whitepoint **高于** rail → 数据永远到不了 1.0（画面到不了白）。

统计陷阱（后续复用脚本时注意）：`whitepoint` 也出现在 `[[cameras.modes]]` 子模式里（Canon sRaw 的 30000 / 52000 是 16-bit 尺度），**只有顶层默认值**才是默认路径；跨档位的值（fuji/x-a10 = 4096、panasonic/gh5s = 8000）方向可能相反，不能当作溢出样本。

### F3. DNG 路径有三条退化，最坏是除零

`external/dnglab/rawler/src/decoders/dng.rs:60` `whitelevel = get_whitelevels(raw)?.or(WhiteLevel::new_bits(bits, cpp))`，`:339` 用 `levels.force_u32(i)` 读 tag：

| tag 状态 | 得到的 whitelevel | 后果 |
| --- | --- | --- |
| 无 WhiteLevel tag | `2^bits − 1` | 安全（等于 rail） |
| SHORT/LONG，值正常 | 正常 | 安全 |
| 写成 FLOAT（`value.rs:667` 是**截断 cast**） | 如 `1.0 → 1` | 未缩放数据被放大 65535× |
| float DNG，无 tag，`BitsPerSample = 32` | `new_bits(32) = 4294967295` | 数据塌缩到 ~2e-5 |
| tag 缺失/类型不可解析 | `force_u32` 打日志后返回 `Default::default()` = **0** | `max = 0` → **除零 → inf/NaN** |

### F4. exposure 的 `[0,1]` clamp 是 **UI 值**的属性，不是数据的属性

`app/src/binding/rust/rawler_fotlab/src/exposure.rs:47`：

```rust
if ev_scale == 1.0 && clip_lo <= 0.0 && clip_hi >= 1.0 { return pixels; }   // 短路
...
*p = (*p).clamp(clip_lo, clip_hi) * ev_scale;                              // :56 先裁剪后乘增益
```

三个后果，均可验证：

1. **EV = 0（默认）时一个像素都不碰**，loader 的 1.205 原样进 demosaic；EV = +0.001 就把 > 1 压回 1.0。**同一张图，EV 差 0.001 渲染结果不同。**
2. `f32::clamp` 把 **NaN → `clip_lower`（0）、inf → `clip_upper`（1）**，恰好静默吞掉 F3 的除零产物。
3. 顺序是**先裁剪、后乘 `2^ev`**，故该阶段输出上界是 `clip_upper × 2^ev` 而非 1（EV +3 ⇒ 8.0）。Kotlin 侧 `StudioEngine.kt:703-704` 另把 bounds `coerceIn(0f, 1f)`。

### F5. 溢出是**统一缩放**，通道比不变 —— 它不是色偏的来源

所有 CFA 通道共用同一除数（相机库只携带单个 `whitepoint`），故归一化是一个统一的线性缩放。以日光乘数 `[1.9495, 1.0, 1.3777]`、全饱和白为例，WB 后向量只差一个常数倍：

| whitelevel | WB 后相机空间 |
| --- | --- |
| 16383（rail） | `[1.950, 1.000, 1.378]` |
| 13600 | `[2.348, 1.205, 1.660]` |
| 12000 | `[2.662, 1.365, 1.881]` |

色相完全相同。它只在**非线性**环节改变结果，而且方向反直觉 —— **让 PNG 支路更干净**：

| | 线性 sRGB | 8-bit |
| --- | --- | --- |
| whitelevel 正确（×1.0） | `[2.399, 0.922, 1.382]` | (255, **246**, 255) |
| 轻度溢出 ×1.056 | `[2.533, 0.974, 1.459]` | (255, **252**, 255) |
| Canon 40D ×1.2046 | `[2.890, 1.111, 1.665]` | (255, **255**, 255) |

G 被抬过 1.0 后三通道全部裁掉 → **纯白**。这正解释了"PNG 看着是正常白"那一半现象。

### F6. 传感器"部分撑满"在相机空间会变成"全部 ≥ 1" → CFA / 相机空间钳制无解

白色表面各通道原始电平 ∝ `1/m_c`，乘数最小的先饱和（G → B → R）。但白平衡之后 `cam_c = min(k, m_c)`：

| 曝光 k | 相机空间（WB 后） | 在相机空间钳位 |
| --- | --- | --- |
| 1.2 | `[1.200, 1.000, 1.200]` | `[1,1,1]` → 纯白 |
| 2.0 | `[1.950, 1.000, 1.378]` | `[1,1,1]` → 纯白 |

**一旦任何通道溢出，相机空间三通道就全都 ≥ 1**（撑满的钉在 `m_c`，未撑满的随 k 上升）。所以在 CFA / 相机空间做钳位只能得到纯白 —— 这是"CFA 钳制无解"的机理根源。demosaic 在均匀块上是恒等的，也不是成因。

### F7. 色偏的**作者**是 cam2rgb 矩阵，clamp 只是**执行者**

矩阵把不均匀的超出量翻译成 sRGB 里的部分越界（G 行的负系数把 G 拽到 1 以下）：

| k | 线性 sRGB（未钳） | 撑满 | 8-bit |
| --- | --- | --- | --- |
| 1.2 | `[1.294, 0.981, 1.209]` | R·B | (255, 252, 255) |
| 1.5 | `[1.736, 0.955, 1.391]` | R·B | (255, 249, 255) |
| ≥1.95 | `[2.399, 0.922, 1.382]` | R·B | (255, 246, 255) |

Lab：`C = 5.5`、`h = 324°`（淡紫/洋红）。去掉矩阵后相机向量处处 ≥ 1 → 纯白，即**中性白高光的洋红 100% 由矩阵产生**。三者缺一不可：传感器提供不均匀超出量 → 矩阵决定哪些通道过 1 → clamp 把这个格局固化。

两个区间：**部分饱和带** `1 < k < 1.95` 色度随曝光漂移（C 1.3 → 5.5，同一面白墙相邻像素可能不一致）；**k ≥ 1.95 全爆**后相机向量冻结在 WB 乘数向量上，色相不再变 —— 且暖色表面 `[1, 0.62, 0.35]` 在 k = 5 时输出 `(255,246,255)`，与中性白**完全相同**：**过曝高光收敛到相机固定指纹，被摄物颜色被彻底抹掉**。

### F8. 双支路分歧：PNG 裁掉色偏，ProPhoto + log 完整保留

`calibrate.rs` 的 `normalize` 行和归一带来 `cam2rgb · [1,1,1] = [1,1,1]`，故 ProPhoto 中 1.0 即 D50 白。全爆后相机向量冻结在 WB 乘数向量：

| 场景 | 线性 ProPhoto D50 |
| --- | --- |
| 部分饱和 | `[1.18, 1.02, 1.18]` |
| 全爆（EV 0） | **`[1.769, 1.081, 1.345]`**（R +0.82 档、B +0.43 档） |
| Studio +3 EV | `[14.2, 8.65, 10.8]` |

**答"ProPhoto 是否越界"：是，且只向上越界**（`exposure.rs` 先裁剪后乘 `2^ev`，故 EV 成倍放大越界量）。同一像素在 sRGB 是 `[2.399, 0.922, 1.382]`（G 掉到 1 以下），在 ProPhoto 里**三通道全 > 1，一个都不用裁** —— 所谓"部分撑满"是**窄色域支路的产物**。

rawalchemy 侧：入参只有 `std::max(r, 1e-6f)` 卡下限（`external/RawAlchemyCpp/src/grading_fused.cpp:104`），**没有任何上限钳位**；F-Log2 把 1.0 白映射到 0.568，全爆像素落在 `[0.626, 0.579, 0.599]`，**全在 [0,1] 内**，故 `graded_to_png`（`bound.rs`，裸 clamp、无传递函数）一个都没裁。

关键度量：**两条支路的 Lab 色度几乎相同（C ≈ 5.5），真正变的是 L\* —— log 支路 62，PNG 支路 98**，色相另从 324.6° 漂到 349.1°（逐通道非线性映射改变了通道比）。即 **log 没有放大色偏，它只是不再隐藏**：白在 log 里本来就是中灰，那点色差从"白里的微痕"变成"中灰上的可见色块"。

### F9. 饱和信息在归一化当场被销毁

`rawimage.rs:538-539` 在归一化之后立刻把 `blacklevel` 置 0、`whitelevel` 置 1。此后 demosaic → calibrate → 双支路**再无任何手段**区分"这个像素打满了"和"这个像素只是很亮"。这是所有高光修复方案缺信息输入的根因。

## Impact / Conflict

- **正确性（F1–F4）**：loader 输出不保证 ≤ 1；exposure 的钳制依赖 UI 值且默认短路。二者叠加，同一文件在 EV 0 与 EV 0.001 下渲染不同。
- **鲁棒性（F3）**：DNG 的 `force_u32` 失败路径会除零；产物又被 exposure 的 clamp 静默吞成 0/1，故障不可见。
- **诊断（F5 + F8）**：PNG 支路因溢出而**变白**，会**掩盖**问题；用户看到的"预览正常、编辑后色偏"是两支路的必然分歧，不是 rawalchemy 的缺陷。
- **修复口径（F6 + F8）**：只改 `rawlerimagedeveloped_to_png`（`bound.rs:114`）**无效** —— rawalchemy 支路根本不走那个编码器；在 CFA / 相机空间钳位对中性高光已被证明无效（F6）。
- **冲突**：本文 F7 指出色偏由 cam2rgb 后的 clamp 固化，因此与 `FOTLAB-RAWLER-000005`（宽色域枢纽）目标一致但落点不同 —— 000005 换工作空间可缓解 sRGB 支路的"部分撑满"，**不能**消除 ProPhoto 支路的色偏（那里本来就全 > 1）。两者不互相替代。
- 上游 `external/dnglab` 按规范不作改动要求，以下建议均落在 first-party `rawler_fotlab` 侧。

## Recommendation

1. **把饱和掩码建在 loader 边界**：在 first-party 侧包住 `apply_scaling`（不修改上游），归一化前后比对每个 photosite 与 whitelevel，产出一张"此像素触顶"的掩码并随 `RawlerImageLoaded` 传递。这是 F9 之后唯一还能拿到该信息的位置，也是唯一能区分"白面爆掉"与"真亮彩色"的输入。
2. **钳制放回数据侧，不放 UI 侧**：exposure 的 clip 语义应改为"数据钳制"而非"曝光参数"，消除 `exposure.rs:47` 的 identity 短路造成的不一致；如保留短路，须在文档中明确它使同一数据在 EV 0 与非 0 下行为不同。
3. **防除零**：在调用 `apply_scaling` 前校验 `whitelevel > blacklevel`，失败时回落到位深满量程并 `log::warn!`，而不是让 `max = 0` 传播 inf/NaN。
4. **色域压缩须在 cam2rgb 之后、感知空间做**（保 L、按超出量收 chroma，渐近纯白）；逐通道 clamp 只应作为最后兜底。此项同时作用于两条支路，是 F7/F8 指出的真正修复点。
5. **不要靠 PNG 预览判断高光**：按 F5/F8，溢出会让预览支路变白。验收高光渲染必须看 rawalchemy 支路。
6. 上述任一项落地时，用 `log/loader_range_sim.py` 的三个断言钉住：归一化在无上限时确实 > 1、不同 whitelevel 下通道比不变、exposure 在 EV 0 与 EV 0.001 下输出不同。

## Change History

- 2026-09-26 — 以 Observation 建立，汇总 2026-09-26 全天的高光溢出色偏调研（纯分析，未改动仓库代码）。记录 F1（归一化只做下限钳位，0..1 为未强制假设）、F2（`whitepoint` 为标称参考白，55% 非 2ⁿ−1，高置信样本溢出至 1.205）、F3（DNG `force_u32` 三条退化，最坏除零）、F4（exposure clip 是 UI 值属性：identity 短路 + NaN/inf 静默 + 先裁剪后乘增益）、F5（溢出为统一缩放，通道比不变，且使 PNG 支路更白）、F6（传感器部分饱和在相机空间变为全 ≥1，CFA 钳制无解，demosaic 非成因）、F7（cam2rgb 矩阵是色偏作者，clamp 只是执行者；全爆后色相冻结且被摄物颜色被抹除）、F8（ProPhoto 越界至 `[1.769, 1.081, 1.345]`，log 支路不裁剪故保留色偏，两支路 L\* 62 vs 98 而 C 均为 5.5）、F9（`apply_scaling` 后 whitelevel 被重置为 1，饱和信息当场销毁）。建议在 loader 边界建立饱和掩码、把钳制移出 UI 语义、防除零、并在 cam2rgb 之后的感知空间做色域压缩。
