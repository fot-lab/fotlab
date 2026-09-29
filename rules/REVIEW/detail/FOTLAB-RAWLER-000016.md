# 相机空间到工作空间是"耦合矩阵一步映射" — XYZ 中间态只存在于矩阵乘法中、从不物化；上游 rawler 只接通 sRGB D65（ProPhoto/AdobeRGB 常量为零使用点），我方接通 SrgbD65 + ProPhotoD50 并复用同一套耦合矩阵

- ID: FOTLAB-RAWLER-000016
- Status: Observation
- Priority: P2
- Created: 2026-09-29
- Owner: —
- Related: FOTLAB-RAWLER-000015 (我方两条矩阵路径不一致 — 本条给出"为什么两条路径都能被压成一个矩阵"的机制，以及矩阵与白平衡解耦后无法表达上游语义的根因), FOTLAB-RAWLER-000014 (上游 DCP 在 ForwardMatrix1/2 间做 1/T 插值 — 需要"随白平衡变化的矩阵槽位"，与 R5 直接相关), FOTLAB-RAWLER-000005 (工作空间锁定与 gamut 裁剪 — 双分叉的既有约定), FOTLAB-RAWLER-000013 (高光溢出色偏 — ProPhoto 越界与 clip 时机), FOTLAB-RAWLER-000004 (decode-once / develop-reuse — 缓冲复用的内存约束)

## Background & Goal

前两轮调研分别覆盖了**上游 RawTherapee 的 DCP apply 插值**（`FOTLAB-RAWLER-000014`）和**我方自己的插值与色适应路径**（`FOTLAB-RAWLER-000015`）。但两者都预设了一个未加检验的前提：色彩转换是"相机空间 → 某个中间态 → 输出空间"的分段链路。本轮要回答的是更底层的问题：

1. 上游 `dnglab/rawler` 从相机 rawimage 到 RGB **到底有没有 D50 XYZ 中间态**？还是直接到 sRGB / ProPhoto？
2. 上游代码里**存在但未接通**的色彩空间有哪些？
3. 我方 `rawler_fotlab` 实际接通了哪些空间、用什么方法？
4. 与"显式物化 XYZ 中间态"相比，现行做法是更好还是更差？什么时候必须改成显式？

本轮为**纯调研：未改动任何仓库代码，未做数值验证**（所有数值性结论均标注为静态推断）。

路径约定：`external/dnglab/rawler/src/` 下的行号记为 `rawler/...`，`app/src/binding/rust/rawler_fotlab/src/` 下的行号记为 `rawler_fotlab/...`。

## Finding

### F1. 上游 rawler：一步耦合矩阵直达**线性 sRGB(D65)**，XYZ 从不落内存

主链路是 `rawler/imgop/develop.rs::RawDevelop::develop_intermediate` → `rawler/imgop/raw.rs` 的 `map_3ch_to_rgb` / `map_4ch_to_rgb`（两者都是 `pub(crate)`，外部无法复用）。矩阵在**建矩阵阶段就被预乘合并**（`raw.rs:194-195`、`raw.rs:221-222`）：

```rust
let rgb2cam  = normalize(multiply(&xyz2cam, &SRGB_TO_XYZ_D65));  // 工作空间 → 相机
let cam2rgb  = pseudo_inverse(rgb2cam);                          // 相机 → 工作空间
```

逐像素只有一次矩阵乘加（`raw.rs:202-213`）：先 `px *= wb_coeff`，再 `cam2rgb · px`，最后 `clip_euclidean_norm_avg`。后续 `ProcessingStep::SRgb`（`develop.rs:318-324`）只调 `srgb_apply_gamma`，**没有任何矩阵**。

即：数学链路是 `cam → XYZ → sRGB`，但计算链路只有一个 3×3。`XYZ 中间态存在，但从未物化为缓冲`。

### F2. 上游的目标光源是 **D65**，不是 D50

`develop.rs:233-259` 用 `color_matrix_find_first([D65, A, B, C, D50, D55, D75, Daylight, Flash])` 取第一个存在的矩阵；若不是 D65 则 `adapt_bradford(&illu, &Illuminant::D65, ...)` 适配到 D65。**D50 只是候选表第 5 位**，仅当 D65/A/B/C 全缺时才会被选中。

### F3. 上游"存在但未接通"的色彩资产清单

| 资产 | 位置 | 是否接通 |
|---|---|---|
| `SRGB_TO_XYZ_D65` | `rawler/imgop/xyz.rs:102` | **是** — 唯一被 develop 使用的锚定矩阵 |
| `XYZ_TO_SRGB_D65` | `xyz.rs:130` | 否 — rawler 内零使用点 |
| `XYZ_TO_SRGB_D50` | `xyz.rs:123` | 否 — 仅 `bin/dnglab/dnglab-lib/src/makedng.rs:605` 当矩阵字面量 |
| `XYZ_TO_ADOBERGB_D65` | `xyz.rs:109` | 否 — 仅 `makedng.rs:608` 字面量 |
| `XYZ_TO_ADOBERGB_D50` | `xyz.rs:116` | 否 — 仅 `makedng.rs:607` 字面量 |
| `XYZ_TO_PROFOTORGB_D50` | `xyz.rs:137` | 否（rawler 内零使用点）— **我方 `rawler_fotlab/calibrate.rs:71` 接通** |
| `cam_to_xyz` / `cam_to_xyz_normalized` | `rawler/rawimage.rs:603/609` | 否 — 全 rawler 无调用点（死 API） |
| `xy_whitepoint_to_wb_coeff` | `xyz.rs:201` | 仅 `decoders/dng.rs:299`（DNG `AsShotXY` → 乘子），develop 不用 |
| `ProPhotoRGB = 5` | `formats/tiff/mod.rs:107` | 否 — 只是 TIFF 标签枚举值，不是转换实现 |

结论：上游**没有"输出色彩空间"这个概念**——`ProcessingStep::SRgb` 是硬编码的 sRGB 终点，ProPhoto / AdobeRGB 只是从别处搬来的常量。

### F4. 我方接通方式与现状

`rawler_fotlab/calibrate.rs` 把"锚定矩阵"参数化，其余与上游同构：

```rust
fn illuminant(self) -> Illuminant { SrgbD65 => D65, ProPhotoD50 => D50 }        // :56
fn to_xyz_matrix(self) -> [[f32;3];3] {                                          // :68
    SrgbD65      => SRGB_TO_XYZ_D65,                    // 与上游完全一致
    ProPhotoD50  => pseudo_inverse(XYZ_TO_PROFOTORGB_D50),  // rawler 只给了 XYZ→ProPhoto，需取逆
}
let rgb2cam = normalize(multiply(&xyz2cam, &space.to_xyz_matrix()));              // :116
let cam2rgb = pseudo_inverse(rgb2cam);                                            // :117
```

- 调用点分布：`loaded.rs:114/173` → `SrgbD65`（UI 预览/导出）；`loaded.rs:209/257/291` 与 `develop.rs:400` → `ProPhotoD50`（编辑/rawalchemy）。
- Bradford 的目标光源随工作空间走（`resolve_xyz_to_cam`，`calibrate.rs:230-240`），因此"矩阵光源"与"输出空间白点"自洽。
- clip 点：编辑分支不 clamp；`develop.rs:621` 的 `clip_to_gamut` 仅在 `params.clip_to_gamut && ProPhotoD50` 时运行（逐通道 clamp，**不是 gamut mapping**）；展示分支的唯一 clip 在 `bound.rs:131` `encode_srgb(v.clamp(0,1))`。

### F5. "耦合矩阵"的等价性有三条精确边界（本条的核心结论）

设 `X = xyz2cam`（n×3，RGBE 行，n=3 或 4），`M = to_xyz_matrix()`（工作空间→XYZ），`A = X·M`（工作空间→相机），`D = diag(1 / 各行和)`，`N = D·A = normalize(A)`，`cam2rgb = pinv(N)`。

**(a) n=3 且可逆时，中间态 XYZ 严格存在，可写出分解**

```
cam2rgb = N⁻¹ = M⁻¹ · X⁻¹ · D⁻¹
rgb_out = M⁻¹ · ( X⁻¹ · ( D⁻¹ · (wb ⊙ cam) ) )
                └─ XYZ ─┘
```

中间态 XYZ 的白点**由 M 决定**（sRGB→D65 路径是 XYZ(D65)，ProPhoto 路径是 XYZ(D50)）。

**(b) `normalize` 不是近似，而是白点锚定**

`A·(1,1,1)` = 相机对工作空间中点的响应（各行和），`D` 除以后 `N·(1,1,1) = (1,1,1)`。即 normalize 把"工作空间中点"锚定为"相机各通道响应均为 1"，再由 WB 乘子把实拍中性点搬过去。它与 `xy_whitepoint_to_wb_coeff`（`1/(M·XYZ_white)`，通道倒数）**数学同源**，区别只在白点来源：前者取工作空间中点，后者取给定 xy。故 3 通道链路"中性 → 工作空间中点"是严格成立的，不是靠归一化蒙对的。

**(c) n=4（RGBE 四色）时退化为最小二乘，中性点不再严格保持**

`pinv(N) = (NᵗN)⁻¹Nᵗ` 是左逆。构造上 `N·(1,1,1) = (1,1,1,1)` 仍成立，但反向 `pinv(N)·(1,1,1,1)` 一般 **≠ (1,1,1)`**——因为 `(1,1,1,1)` 未必落在 N 的列空间内。即四色路径的中性点存在残余偏差。**（静态推断，未数值验证）**

**(d) 前提：相机矩阵必须先 Bradford 到与 M 相同的光源**

两个矩阵相乘只有在同一参考白下才有物理意义。这正是 `develop.rs` 先适配到 D65、我方 `resolve_xyz_to_cam` 按工作空间选 D65/D50 的原因。

### F6. 现行做法的收益：零额外缓冲

耦合矩阵让整条链路只在**已有的 RGB 缓冲上原地变换**（`calibrate.rs:139-161` 的三通道分支是 `par_iter_mut` 原地写回 + `flatten_rgb3` 零拷贝展平）。曾因多分配一个 ~630 MB f32 缓冲（50 MP）触发 mid-develop OOM（`FOTLAB-RAWLER-000005`），物化一帧 XYZ 会直接回退这项成果。

### F7. 现行做法的代价与盲区

1. **线性/逐像素是硬前提**：一旦 cam→RGB 之间需要非线性或跨像素操作（tone curve、DCP hue/sat map、真正的 gamut mapping、需 Lab 判别的 AHD/EAHD），耦合矩阵就表达不了，必须物化。
2. **无法表达"随白平衡变化的矩阵"**：矩阵在建矩阵期就固定了，运行时 Kelvin 只影响 WB 乘子。这与上游 RT 把矩阵锚到实际场景白点、并让 ForwardMatrix 混合权重随白平衡变化的设计根本冲突（呼应 `FOTLAB-RAWLER-000015` F4 / `000014` R1）。
3. **四色相机中性点漂移**（F5c）。
4. **不可观测**：无法在 XYZ 层 dump/断言，出偏色时只能端到端反推。

## Impact / Conflict

- 与 `FOTLAB-RAWLER-000005` 的"双分叉 + 单点 clip"一致，本条补上了"为什么能省掉 XYZ 缓冲"的理论依据与它的适用边界。
- 与 `FOTLAB-RAWLER-000013`：ProPhoto 越界（如 `[1.769, 1.081, 1.345]`）是"一步映射 + 编辑分支不 clamp"的**设计结果**，不是 bug；`clip_to_gamut` 是逐通道 clamp，色相不守恒，不能当 gamut mapping 用。
- 与性能条目（`OPTIMZ-PERFRM-000007` 一带）：任何"显式 XYZ 中间态"的提案都必须先过内存这一关，否则等于回退已解决的中途 OOM。
- 上游 `external/dnglab` 属固定约束（`rules/REVIEW.md` 原则 5）：本条只记录如何与之协作，不提议修改其源码。

## Recommendation

- **R1（P2，默认保留耦合矩阵）把 XYZ 层上升为"契约"而不是"缓冲"**：在 `calibrate` 中把 `xyz2cam`、`M`、`rgb2cam`、`cam2rgb` 写成具名中间量，并对 3×3 情形加 O(1) 断言：`cam2rgb · rgb2cam ≈ I`、`cam2rgb · (1,1,1) ≈ (1,1,1)`。既保留零缓冲，又让中间态在测试里可观测。
- **R2（P2，四色相机）为 n=4 路径加单测**钉住 `(1,1,1,1) → (1,1,1)` 的偏差；若偏差超阈值，改为"先降到 RGB 三通道 + E 单独处理"或改用带权伪逆，而不是让偏差静默存在。
- **R3（P3，未接通空间）不再新增 AdobeRGB**——色域小于 ProPhoto 且无编辑收益；`XYZ_TO_SRGB_D50` 只属于 `makedng`，不得接到 develop。若将来确实需要 D50 的 sRGB 输出，应显式新增 `WorkingSpace::SrgbD50` 并**同步 Bradford 目标光源**为 D50，而不是复用 `XYZ_TO_SRGB_D50` 常量（否则矩阵光源与空间白点不一致，违反 F5d）。
- **R4（P2，何时必须物化）判定标准**：只要 cam→RGB 之间出现非线性或跨像素操作就物化；真要接 DCP hue/sat map 或 Lab 类 demosaic（需矩阵的内核如 AHD/EAHD 目前按人工指令停放，未进 `IMPLEMENTED_BAYER`）时，按 **tile 物化** XYZ/Lab，不要整帧 f32。
- **R5（P2，多 illuminant 插值）**：`FOTLAB-RAWLER-000015` R2 提出的"让 `resolve_xyz_to_cam` 支持 CCT 插值"与耦合矩阵**并不冲突**——插值发生在建矩阵期，每个 Kelvin 重算一次 3×3 乘法仍是 O(1)，无需物化任何缓冲。这是"耦合矩阵"与"按白平衡插值"可以共存的关键，勿以"要插值就得物化"为由否决。

## Change History

- 2026-09-29 — 创建。基于对 `external/dnglab/rawler`（`imgop/develop.rs`、`imgop/raw.rs`、`imgop/xyz.rs`、`imgop/matrix.rs`、`imgop/chromatic_adaption.rs`、`rawimage.rs`、`dng/convert.rs`、`dng/writer.rs`）与 `app/src/binding/rust/rawler_fotlab`（`calibrate.rs`、`develop.rs`、`loaded.rs`、`bound.rs`）的静态源码调研；未改动代码、未做数值验证。结论：上游与我方均为"耦合矩阵一步映射"，XYZ 中间态只在矩阵乘法中存在、从不物化；上游只接通 sRGB D65，ProPhoto/AdobeRGB 常量为零使用点；我方接通 SrgbD65 + ProPhotoD50。给出三条等价性边界（3×3 严格可分解且含白点锚定、4×3 退化为最小二乘、矩阵光源须与空间白点一致）与五条建议（R1 契约化断言、R2 四色单测、R3 不新增 AdobeRGB、R4 tile 化物化判据、R5 插值与耦合矩阵可共存）。
