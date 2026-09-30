# ProPhoto 分支在 X⁻¹ 与 XYZ_TO_PROFOTORGB_D50 之间确实夹着一次 Bradford（建造期 X·T(D65→D50)、经伪逆后在 cam→XYZ 侧表现为其逆 T(D50→D65)）；调用 adapt_bradford 的传参顺序与签名一致、无需改动；中性色被钉死、非中性色有差异；SrgbD65 分支无此项致两分支多该因子；4 通道（RGBE）相机在编辑路径直接返回 Err

- ID: FOTLAB-RAWLER-000017
- Status: Observation
- Priority: P1
- Created: 2026-09-29
- Owner: —
- Related: FOTLAB-RAWLER-000016 (耦合矩阵一步映射 / 三条等价性边界 / F8–F11 的矩阵键语义与目标白点 — 本条是它在"我方 ProPhoto 分支"上的具体落地与数值化), FOTLAB-RAWLER-000015 (两条矩阵路径不一致 — 给出两分支相差一个 T(D50→D65) 的精确定位), FOTLAB-RAWLER-000005 (工作空间双分叉与单点 clip), FOTLAB-RAWLER-000013 (高光溢出色偏 — 同属"中性点被钉死、偏差只在非中性色暴露 CAT")

## Background & Goal

`FOTLAB-RAWLER-000016` 确定了三件事：上游与我方都是"耦合矩阵一步映射"、XYZ 中间态从不物化、以及矩阵键（A/D65）是标定光源而非白点。但那一系列结论是"概念层"——没有回答一个可以直接对照代码的问法：

> 在现行 `develop` / `calibrate` 管线里，当输出目标是 ProPhoto 时，**制作矩阵的过程中**是否存在"XYZ 步骤里先 D65 转 D50、然后才转 ProPhoto"这样的过程？

目标：

1. 逐行追踪我方 ProPhoto 分支从 `WorkingSpace` 到 `cam2rgb` 的完整矩阵构造过程；
2. 定位那次色适应项，确定它的**位置**（在相机侧还是 `M` 侧）与**方向**；
3. 用数值实验量化中性色与非中性色的影响；
4. 顺带确认是否存在其它未被注意的 D65→D50 步骤。

本调研为**纯调研 + 一次性数值实验（脚本未入库），未改动任何仓库代码**。`P1` 的依据不是色偏本身（那部分属 P2），而是 F5：4 通道相机在编辑路径会直接 `Err`，属功能性失败。

路径约定：`app/src/binding/rust/rawler_fotlab/src` 记为 `rawler_fotlab/...`，`external/dnglab/rawler/src` 记为 `rawler/...`。

## Finding

### F1. ProPhoto 分支的矩阵构造：四步，XYZ 步骤里确实有一次 Bradford

`rawler_fotlab/calibrate` 的 `calibrate()`：

```rust
let target_illu = space.illuminant();                    // :92  → Illuminant::D50 (ProPhotoD50)
let xyz2cam     = resolve_xyz_to_cam(image, target_illu)?; // :93
   // 内部：find_first([D65, A, B, C, D50, …]) → 绝大多数相机命中 (D65, M_D65)  (:217-229)
   //       illu(D65) != illuminant(D50) → adapt_bradford(&D65, &D50, &X)        (:234)
let rgb2cam = normalize(multiply(&xyz2cam, &space.to_xyz_matrix()));  // :116
   //        to_xyz_matrix() = pseudo_inverse(XYZ_TO_PROFOTORGB_D50)  (:71)
let cam2rgb = pseudo_inverse(rgb2cam);                   // :117
```

设 `X` = 数据库矩阵（键 D65）、`T = bradford_adaption_matrix(D65, D50)`、`X' = X·T`、`M = pseudo_inverse(XYZ_TO_PROFOTORGB_D50)`（ProPhoto→XYZ(D50)）、`D = diag(1/各行和)`。n=3 且可逆时：

```
cam2rgb = M⁻¹ · X'⁻¹ · D⁻¹ = XYZ_TO_PROFOTORGB_D50 · T⁻¹ · X⁻¹ · D⁻¹

逐像素： cam --D⁻¹--> cam' --X⁻¹--> XYZ --T⁻¹--> XYZ' --XYZ_TO_PROFOTORGB_D50--> ProPhoto(D50)
```

即：**"XYZ 步骤中的色适应"存在**，位置就夹在 `X⁻¹`（相机→XYZ）与 `XYZ_TO_PROFOTORGB_D50`（XYZ→ProPhoto）之间，与 000016 F5a 的分解式一致。它当然是**预乘进单个 3×3 的，从不物化为 XYZ 缓冲**。

### F2. 调用传参与签名一致；渲染链上的 T(D50→D65) 是伪逆的必然结果

`T⁻¹ = bradford_adaption_matrix(D50, D65)`，对角 **(0.9556, 1.0099, 1.3299)**；而"D65→D50"的对角应是 **(1.0478, 0.9905, 0.7521)**。两者互为逆（数值验证：`‖T·T⁻¹ − I‖ < 1e-6`）。

**先澄清一个先前写错的判断**：我此前称"调用 `adapt_bradford` 的参数顺序相反"。这是错的。签名是 `adapt_bradford(src_illu, dst_illu, src_matrix)`（`rawler/imgop/chromatic_adaption.rs:78-81`），而 `:234` 调用为 `adapt_bradford(&illu /*D65*/, &illuminant /*D50*/, &X)`——**位置逐一对应**（源=illu、目标=illuminant、矩阵=X），函数按设计返回 `X · T(illu→illuminant) = X · T(D65→D50)`。调用方忠实地用对了，**不存在错排，也不应交换两个实参**（交换反会构成传参错误并改变结果）。

```rust
pub fn adapt_bradford(src_illu, dst_illu, src_matrix) -> [[f32;3];3] {
  multiply(src_matrix, &bradford_adaption_matrix(src_illu, dst_illu))   // 右乘：X' = X · T(src→dst)
}
```

渲染链（cam→XYZ）取 `cam2rgb = pinv(normalize(X'·M))`；`X' = X·T(D65→D50)` 的伪逆把建造期的 T 翻成其逆，于是 **T(D50→D65) 出现在 cam→XYZ 侧（即 ProPhoto 矩阵的输入侧）**。这是"正确调用一个右乘函数 + 随后取伪逆"的**确定性数学后果**，不是调用错位、也不是方向写反。

再退一层：按 000016 F9 的判定（矩阵 XYZ 端是**绝对 scene-referred**），这一项**根本不该存在**——那才是真正的根因（属于"该不该做这次适应"的语义问题，见 000016 R7/R8），与"传参顺序"无关。

### F3. 数值后果：中性色完全一致，非中性色差 2.6×~3.3×（canon/1000d 的 D65 矩阵）

三方案对比（`A`=现行 `X·T(D65→D50)`；`B`=若写成 `X·T(D50→D65)`；`C`=裸 `X` 不作适应）。相机值由 `X·XYZ(patch)` 生成，白平衡取 `1/(X·w_D65)`：

| 测试色 | A 现行 | B 假设 | C 裸矩阵 |
|---|---|---|---|
| 中性 `w_D65` | (1.0000, 1.0000, 1.0000) | (1.0000, 1.0000, 1.0000) | (1.0000, 1.0000, 1.0000) |
| sRGB 红原色 | (0.5392, 0.1054, **0.0445**) | (0.5293, 0.0984, **0.0169**) | (0.5337, 0.1016, **0.0283**) |
| sRGB 蓝原色 | (0.1440, **0.0937**, 0.8812) | (0.1406, **0.0282**, 0.8654) | (0.1432, **0.0654**, 0.8738) |

- **中性三方案完全相同**。`normalize` 让 `N·(1,1,1) = (1,1,1)`、WB 把实拍中性搬过去，中性点被**结构性钉死**（000016 F5b）。所以灰卡/白平衡测试**测不出**这套差异。
- 非中性色差异显著：蓝原色 G 通道 `0.0937 / 0.0282 = 3.3×`，红原色 B 通道 `0.0445 / 0.0169 = 2.6×`；即便与"完全不作适应"的 C 相比，A 的蓝原色 G 也高出 43%。

**白点沿链路的实际走向（修正先前误判）**：`X' = X·T(D65→D50)` 是把 X **重新标注为"输入按 D50 参照"**（`X'·v_D50 = X·v_D65`）；其逆 `X'⁻¹` 因此产出 **D50 参照**的 XYZ。对一台 D65 照明的**中性**场景，链路算得 `X'⁻¹·(X·w_D65) = w_D50`，再经 ProPhoto 矩阵得到 `(1,1,1)`——即送进 ProPhoto 那一步的数据是 **D50 参照的**，与 D50 版 ProPhoto 矩阵自洽，中性点正确。先前我写成"是 D65 参照的"，是把**分解碎片** `E = XYZ_TO_PROFOTORGB_D50 · T(D50→D65)`（它漏掉了 `X⁻¹` 与 `normalize`，不代表真实管线）当成了整体判据，属输入输出方向的误读，特此更正。

**正确的"D50 自洽"判据应当沿整条链路追白点**：取一台 D65 中性场景，现行管线输出 `ProPhoto = (1,1,1)`，证明 ProPhoto 步收到的是 D50 参照、且 D50 基准成立。非中性色的 2.6×~3.3× 差异才是这次适应的真实残留——在 000016 F9（绝对 scene-referred）成立的前提下，它来自把 T(D65→D50) 烘焙进本无参照的 X 所引入的伪 CAT。

### F4. SrgbD65 分支完全没有这一项 → 两分支的 `cam2rgb` 相差一个 `T(D50→D65)`

`resolve_xyz_to_cam` 的 `:230-231`：`if illu == illuminant { matrix }`——SrgbD65 下 `illuminant()=D65`，与 `find_first` 命中的 D65 相等，**直接走原样返回、不作任何适应**。

因此：

| 分支 | 是否叠加 CAT | 链路上出现的因子 |
|---|---|---|
| `SrgbD65` | 否（627/721 台相机的常见情形） | — |
| `ProPhotoD50` | **是** | `T⁻¹ = T(D50→D65)` |

这给了 000015 F4"两条路径矩阵不配对"一个**精确的机制**：ProPhoto 分支比 SrgbD65 分支**额外多携带一个 T(D50→D65) 因子**（两工作空间的锚定矩阵 M 本身也不同，故整体差异不止这一项）。这也解释了为什么同一 Kelvin 下两个分支的观感差异无法用"矩阵光源不同"一句话解释完。

### F5. 4 通道（RGBE）相机在 ProPhoto 分支**直接 Err**（P1 依据）

`resolve_xyz_to_cam` 的 `:233-239` 只接受 9 元素矩阵：

```rust
match matrix.len() {
    9 => adapt_bradford(&illu, &illuminant, &transform_1d_3x3(&matrix)) …,
    _ => return Err(RawlerFotlabError::Decode("color matrix has unexpected size".to_string())),
}
```

数据库 `external/dnglab/rawler/data/cameras` 中 1355 个矩阵里 **6 个是 12 元素（4×3，RGBE）**，分属 3 台相机：

| 相机 | 矩阵 |
|---|---|
| `canon/g1.toml` | A: 12, D65: 12 |
| `nikon/e5700.toml` | A: 12, D65: 12 |
| `sony/f828.toml` | A: 12, D65: 12 |

它们的 `find_first` 命中 D65 ≠ 目标 D50 → 命中 `_ => Err`。而 SrgbD65 路径（`illu == illuminant`，走 `:230`）**正常**。即：**这 3 台相机能预览、不能编辑**。上游 rawler 同一处是 `_ => unimplemented!()`（panic，`develop.rs:257`），我们至少降级成了 `Err`。

（未端到端验证：取决于解码器是否真的产出 `Intermediate::FourColor` 且落用数据库矩阵而非文件内嵌矩阵。）

### F6. 范围确认：全仓只有这一处色适应，不存在"别处再补一次"

- `grep` 全 `app/src/binding/rust`：`adapt_bradford` 仅 `calibrate.rs:234` 一处调用。
- `app/src/binding/cxx`（rawalchemy / rawtherapee 绑定）内**没有任何** Bradford / D50 / `XYZ_TO_*` 代码；"ProPhoto D50" 在 cxx 侧只是**注释里的标签**（`rawalchemy_fotlab/src/lib.rs:3/37/83/187`、`cpp/rawalchemy_api.h:31/71`），没有转换实现。
- `bound.rs` 只对 sRGB 分支做 gamma + clamp（`:130-131`）；`ProPhoto→target gamut` 矩阵在 rawalchemy 的 `applyGradingFused` 内部（`bound.rs:182` 注释），不在我方 Rust 侧。

## Impact / Conflict

- **与 000015 F4**：本条把"两分支矩阵不配对"落到了具体因子 `T(D50→D65)` 上，且指出该因子**只出现在 ProPhoto 分支**。
- **与 000016 F5b**：中性点被 `normalize`+WB 钉死 ⇒ 这套非中性色差异**不可能**被白平衡/灰卡类测试发现。任何回归测试若只用中性色，则对 F2/F3 完全不敏感（见 R3）。
- **与 000016 F9/F10/R7**：若接受"矩阵 XYZ 端是绝对 scene-referred"，则 F2 里那次适应只是表象，根因是**不该按标定光源做色适应**。修正方案（源改为场景白点）已登记为 000016 R7，属行为变更，**需人工确认**，本条不重复执行。
- **与 000016 F5c**：F1 的严格分解只在 n=3 成立；n=4 时 `pinv` 是左逆，链路上那次 CAT 的"位置"不再可严格写出。
- **与 000005**：本条不涉及 clip 时机，但再次确认 ProPhoto 分支是"不 clamp"的，故 F3 中的负值/超 1 会原样进入 rawalchemy。
- 上游 `external/dnglab` 属固定约束（`rules/REVIEW.md` 原则 5）：F2（渲染链 T(D50→D65) 是伪逆的确定性后果，调用传参本身正确、无需改动）与 F5（根源在上游 `unimplemented!()`）的根源都在上游，本条只记录如何与之协作，不提议修改其源码。

## Recommendation

- **R1（P1）修 4 通道 `Err`**：`resolve_xyz_to_cam` 需要支持 12 元素矩阵。`adapt_bradford` 的右乘在 4×3 上**完全合法**（`X` 为 4×3、`T` 为 3×3，仍是 4×3），唯一的障碍是 `transform_1d_3x3` 只认 9 元素。方案：把 reshape 泛化为 `n×3`（`components = len/3`），右乘 `T` 后再展平；保留 `_ => Err` 只对"非 3 的倍数"生效。这是**修复功能性失败**，但同时会改变这 3 台相机的 ProPhoto 出图，属行为变更 → **需人工确认后再实施**。
- **R2（P2）不要交换 `adapt_bradford` 的实参（先前此条写错，特此更正）**：调用 `:234` 的 `adapt_bradford(&illu, &illuminant, &X)` 与签名 `(src_illu, dst_illu, src_matrix)` 位置一一对应，**传参正确，不应交换**——交换反会构成传参错误并改变结果。若认为该 CAT 不应存在，正确做法是按 000016 R7（把 Bradford 源白点从键名改为场景白点）或干脆在绝对 scene-referred 前提下移除这一步，**而不是调换参数**。这是会改变出图的改动，**需人工确认**。
- **R3（P2）加"非中性色"回归单测**：现有任何只测中性色的用例对本条完全不敏感。建议钉住两条：① sRGB 三原色（或任一高饱和色）在两分支下的输出值（允许在人工确认前先"钉住现状"以防静默漂移）；② **D50 自洽判据**沿整链追白点——D65 中性场景 → ProPhoto `(1,1,1)`——这是判定"送进 ProPhoto 的数据确实是 D50 参照"的最短断言，可直接作为 R7 的验收条件。
- **R4（P3）统一两分支的色适应策略**：让 SrgbD65 也走"显式 CAT 到工作空间白点"的路径（而不是靠 `illu == illuminant` 的巧合跳过），使两条分支的矩阵差异变成**可解释的、单一来源的**，而不是当前这种"一边有一边没有"。
- **R5（P3）文档层面**：`calibrate.rs:45` 的注释写着 "D50 matches rawalchemy and RawTherapee, so no chromatic-adaptation bridge is needed" —— 但 ProPhoto(D50) 分支**实际调用了 `adapt_bradford`** 去适配到 D50，故该注释对 D50 路径至少是不完整/自相矛盾的，建议随 R7 一并更正（与"传参顺序"无关）。

## Change History

- 2026-09-29 — 创建。基于对 `app/src/binding/rust/rawler_fotlab/src/calibrate.rs`（`WorkingSpace::illuminant` / `to_xyz_matrix` / `calibrate` / `resolve_xyz_to_cam`）、`rawler/imgop/chromatic_adaption.rs`（`bradford_adaption_matrix` / `adapt_bradford`）、`rawler/imgop/xyz.rs`（`XYZ_TO_PROFOTORGB_D50`）、`bound.rs`、`develop.rs`、`loaded.rs` 的静态追踪，加一次性数值实验（canon/1000d 的 D65 矩阵；脚本未入库）。结论：ProPhoto 分支在 `X⁻¹` 与 `XYZ_TO_PROFOTORGB_D50` 之间确实夹着一次 Bradford，建造期 X·T(D65→D50)、经伪逆后在 cam→XYZ 侧表现为其逆 T(D50→D65)；中性色三方案完全一致（灰卡测不出），非中性色差 2.6×~3.3×；SrgbD65 分支无此项，两分支相差一个 T(D50→D65)；`canon/g1`、`nikon/e5700`、`sony/f828` 三台 4 通道相机在编辑路径直接 `Err`。全仓仅 `calibrate.rs:234` 一处色适应，cxx 侧无 CAT。给出 R1（修 4 通道 Err，需人工确认）、R2（方向修正，并入 000016 R7 评估）、R3（非中性色 + D50 自洽判据单测）、R4（统一两分支）、R5（更正 `calibrate.rs:45` 误导性注释）。未改动任何代码。
- 2026-09-30 — **更正（先前的参数顺序 / 输入输出方向误判）**。先前 F2 称"调用 `adapt_bradford` 的参数顺序相反"、R2 建议"交换 src/dst 实参"、F3 称"送进 ProPhoto 的是 D65 参照"并据此给出错误的 D50 自洽判据——均属误判，本次修正：① 调用 `:234` 的 `adapt_bradford(&illu, &illuminant, &X)` 与签名 `(src_illu, dst_illu, src_matrix)` 位置一一对应，传参正确、不应交换；② 渲染链上的 T(D50→D65) 是"正确调用右乘函数 + 取伪逆"的确定性后果，非调用错位；③ 沿整条链路追白点，中性场景在 ProPhoto 步确为 D50 参照、`(1,1,1)` 成立，先前的"D65 参照"是把漏掉 `X⁻¹`/`normalize` 的分解碎片 `E = M·T(D50→D65)` 当成了整体判据。相应修正标题、F2、F3、F4、Impact、R2、R5 与 index 行。000016 的"绝对 scene-referred"（F9）仍为真根因，与本次传参更正无关。未改动任何代码。
