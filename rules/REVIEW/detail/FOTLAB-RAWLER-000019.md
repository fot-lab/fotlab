# wb_coeffs 来源对照 — 上游 rawler 三条解码路径 vs 我方 fotlab 三条派生路径；白平衡乘子与渲染矩阵解耦，Kelvin 路径与 as-shot 路径数学同构但方向相反

- ID: FOTLAB-RAWLER-000019
- Status: Observation
- Priority: P2
- Created: 2026-10-04
- Owner: —
- Related: FOTLAB-RAWLER-000015 (我方两条矩阵路径不一致 — 本条给出 wb 乘子侧的完整来源对照), FOTLAB-RAWLER-000016 (耦合矩阵一步映射、normalize=白点锚定), FOTLAB-RAWLER-000018 (OKLab 旁路插在 wb 乘之后)

> 注：按 REVIEW 原则 5，`external/dnglab` 属固定约束，本条只记录上游实际行为与 first-party 侧应对，不提议改上游。

## Background & Goal

`wb_coeffs`（白平衡乘子）是色彩管线里唯一逐像素、逐图像的量：它在渲染矩阵**之前**乘到相机像素上（见 `FOTLAB-RAWLER-000015` F3/F5、`FOTLAB-RAWLER-000016` F5b）。但要回答"这个乘子到底是怎么来的"，必须同时看上游 rawler 的解码逻辑和我们的 `wb.rs` 派生逻辑——两者来源并不相同。本轮目标：把 `wb_coeffs` 在上游与我方的全部来源列清，并确认它们与渲染矩阵解耦这一架构事实。

本轮为**纯静态代码核对**，未改动任何仓库代码，亦未做数值验证。

## Finding

### F1. 上游 rawler 的 `wb_coeffs` 基本是"解码"而非"计算" —— 三种来源

每个格式解码器各自实现 `get_wb()`，`calibrate` 收到的 `image.wb_coeffs` 即来自此。分三类：

**(a) DNG `AsShotNeutral` —— 直接取倒数（`decoders/dng.rs:291-293`）**
```rust
if let Some(levels) = self.tiff.get_entry(DngTag::AsShotNeutral) {
  Ok([1.0 / levels.force_f32(0), 1.0 / levels.force_f32(1), 1.0 / levels.force_f32(2), f32::NAN])
}
```
文件里存的是"中性灰在相机空间的响应 `(R,G,B)`"，乘子 = 它的倒数。**相机固件已把场景白点中性化过**，此处完全不碰色彩矩阵。绿通道恒为 1（倒数后）。

**(b) DNG `AsShotWhiteXY` fallback —— 唯一用矩阵算的路径（`decoders/dng.rs:294-303` → `imgop/xyz.rs:201-213`）**
```rust
// dng.rs:299
let wb_coeff = xy_whitepoint_to_wb_coeff(levels.force_f32(0), levels.force_f32(1), &colormatrix);
// xyz.rs:201-213
let as_shot_white = xy_to_XYZ(x, y);
for i in 0..3 {
  let c = colormatrix[i][0]*as_shot_white[0] + colormatrix[i][1]*as_shot_white[1] + colormatrix[i][2]*as_shot_white[2];
  if c > 0.0 { result[i] = 1.0 / c; }
}
```
即 `wb = 1 / (ColorMatrix_D65 · XYZ_white)`。rawler 这里**只用单一 D65 矩阵**（代码注释 `TODO: improve once AnalogBalance and CC is properly implemented`），不对非 D65 场景白点做 CAT。

**(c) 专有格式读 maker WB（如 ARW `decoders/arw.rs:653-673, 739-751`）**
```rust
// arw.rs:653-673
let grbg_levels = sr2.get_entry(SR2SubIFD::SonyGRBG);
... Ok(normalize_wb([levels.force_u32(1), levels.force_u32(0), levels.force_u32(3), levels.force_u32(2)]))
// arw.rs:739-751
fn normalize_wb(raw_wb: [f32;4]) -> [f32;4] {
  let div = raw_wb[1]; // G1 应为 1024，作除数
  ... *v /= div
  [norm[0], (norm[1]+norm[2])/2.0, norm[3], f32::NAN] // G1/G2 合并
}
```
从 SR2 makernotes 读相机固件的自动/as-shot WB levels，以绿（=1024）为除数归一、合并 G1/G2。同样是相机固件的白平衡读数，**与色彩矩阵无关**。

> 公共内核：上游客观化 `wb = 1 / (相机对场景白点的响应)`，且**绿通道归一为 1**。它是相机"量"出来的，不是矩阵算出来的（除 (b) 这条 fallback 用矩阵把"场景白点 xy"转成"相机响应"）。

### F2. 我方 fotlab 的 `wb` 乘子三种来源

`calibrate` 收到 `wb: Option<[f32;4]>`（`calibrate.rs:87`）。三种来源：

**(a) 默认 = 继承上游 `image.wb_coeffs`（`calibrate.rs:97-106`）** —— `params.wb = None` 时直接用 rawler 解码的 as-shot 值（即 F1 那套）。**我们不重算 as-shot 白平衡**。

**(b) 用户设 Kelvin → `wb_from_color_temp`（`wb.rs:284-307`）** —— 我方自己实现的派生：
- `cct_to_xy(kelvin)`（`wb.rs:63`）取目标温度白点（<4000 K 用 Krystek Planckian，≥4000 K 用 Wyszecki–Stiles daylight 多项式）；
- `matrix_for_cct`（`wb.rs:177`）把文件 `ColorMatrix1(A=2856K)` / `ColorMatrix2(D65=6504K)` 在 **mired（1/T）空间**线性插值得 `M_T`；
- `neutral = M_T · XYZ_white(T)`（相机对 T 白点的响应）；
- `neutral_to_multipliers`（`wb.rs`）取倒数 + 绿归一（`g=1`）+ 近零/负分量 floor（防旧版全黑 bug）+ 钳到 `[1/16, 16]` 摄影带。

**(c) Kotlin 显式传 `params.wb`（`develop.rs:647`）** —— 任意乘子直通，管线原样使用。

### F3. 两条管线在"矩阵派生 wb"上的对照

| | 上游 rawler（矩阵路径，仅 F1b fallback） | 我方 fotlab（Kelvin 路径，F2b） |
|---|---|---|
| 触发 | 仅 DNG `AsShotWhiteXY` 缺失 `AsShotNeutral` 时 | 用户设 Kelvin / 显式温度 |
| 矩阵 | 单一 D65 矩阵 | 两参考矩阵 mired 插值得 `M_T` |
| 公式 | `wb = 1/(M_D65 · XYZ_white)` | `wb = 1/(M_T · XYZ_white(T))` |
| 归一/保护 | 直接倒数，无保护 | 绿归一 + 负/零 floor + 摄影带钳制 |

数学同构（都是 `neutral = Matrix · White_XYZ` 再取倒数），区别在于**我方用 mired 插值覆盖任意 CCT、且加了防全黑与钳制**（旧实现把负分量写成 `0` 导致黑帧，已在 `wb.rs` 注释里记）。而**上游的 as-shot `wb_coeffs` 与色彩矩阵解耦**（直接读数），我们的 Kelvin 乘子则与矩阵强相关——这正是"相机实测" vs "按色温模型推算"的本质差别。

### F4. 白平衡乘子与渲染矩阵解耦 —— 架构事实

`wb_coeffs` 在 `calibrate` 内**先**逐像素乘到相机值（`calibrate.rs:181-183`），**后**才过烘焙 `cam2rgb`（`calibrate.rs:189-193`）；渲染矩阵 `resolve_xyz_to_cam`（`calibrate.rs:254-291`）只锚定工作空间白点（D65/D50），不随场景白点变（与 `FOTLAB-RAWLER-000015` F5 一致）。即：**场景白点（5300K 等）全进 `wb` 乘子，矩阵只承载"白点(D65/D50)适配"**。

normalize 把"工作空间中点"锚定为"相机各通道响应均为 1"（`FOTLAB-RAWLER-000016` F5b），再由 WB 乘子把实拍中性点搬过去——**中性轴是精确的**。把渲染矩阵也按场景 illuminant 重适配是 DNG 严格模型（`dng_color_spec`）做法，现行简化与 rawler 上游同构、对中性白点精确，非 bug（见 `FOTLAB-RAWLER-000015` F9）。

## Impact / Conflict

- 与 `FOTLAB-RAWLER-000015`：本条是 F1（两条矩阵路径）中"路径 A = 白平衡乘子"的**上游侧补全**——F1/F2 只写了我方 `wb.rs` 的派生，未覆盖上游 `get_wb` 的三条解码路径；F4 与之共享"乘子与矩阵解耦"的架构结论。
- 与 `FOTLAB-RAWLER-000016`：F4 的"中性轴精确"依赖 `000016` F5b（normalize=白点锚定），本条给出 wb 乘子在 pixel 级的实际施加位置（`calibrate.rs:181`）。
- 与 `FOTLAB-RAWLER-000018`：OKLab 旁路插在 `wb` 乘之后（`000018` F10），入口值已是 white-balanced 相机值，故旁路无需为场景白点额外处理——F4 是该结论的前置事实。
- 上游 `external/dnglab` 属固定约束（REVIEW 原则 5）：本条只记录实际行为与 first-party 侧应对，不提议改上游。

## Change History

- 2026-10-04 — 创建。纯静态代码核对，未改动任何仓库代码，亦未做数值验证。记录：F1（上游 rawler `wb_coeffs` 三条解码路径：DNG AsShotNeutral 取倒数、DNG AsShotWhiteXY→`xy_whitepoint_to_wb_coeff` 用单一 D65 矩阵、专有格式读 maker WB 并绿归一）、F2（我方 `wb` 乘子三来源：继承上游 as-shot、Kelvin→`wb_from_color_temp`+`matrix_for_cct` mired 插值、Kotlin 显式传）、F3（两条管线矩阵派生 wb 的数学同构与差异对照）、F4（wb 乘子与渲染矩阵解耦、场景白点全进乘子、中性轴精确）。
