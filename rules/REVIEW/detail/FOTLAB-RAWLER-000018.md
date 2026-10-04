# sRGB 出口偏色深化归因 — clamp 已就位非漏钳、双支路架构、normalize 时序与 rawalchemy 无高光滚降、负 cross-term 数学与 OKLab 拆解可行性

- ID: FOTLAB-RAWLER-000018
- Status: Observation
- Priority: P1
- Created: 2026-10-04
- Owner: —
- Related: FOTLAB-RAWLER-000013 (高光溢出色偏端到端归因 — cam2rgb 是作者、clamp 是执行者、双支路分歧), FOTLAB-RAWLER-000016 (耦合矩阵一步映射、normalize=白点锚定、矩阵分解), FOTLAB-RAWLER-000005 (工作空间锁定 sRGB / 宽色域枢纽), FOTLAB-RENDER-000001 (OKLab 高光压缩旁路设计文档 — F10 实现落地的权威来源)

> 注：按用户要求，本条目不含 Recommendation 与待办事项，仅记录调研结论。全部结论来自纯静态代码核对与真实相机矩阵复算，未改动任何仓库代码，亦未做本地编译/运行。

## Background & Goal

用户观察到：ProPhoto 支路钳制到 0..1 后无偏色，而 develop 输出的 sRGB PNG 仍有偏色，因而高度怀疑 sRGB + gamma 链路存在未正确施加的 clamp（即"超过 1 的 sRGB 值被跳过 clamp 输入进 gamma"）。本轮调研目标为逐项核验下列各疑点并给出结论：

1. sRGB 出口是否真的漏钳？gamma 的输入/输出钳制是否就位？
2. ProPhoto 是否经 sRGB 中转，还是 cam 直出？
3. `normalize` 发生在哪两步之间、作用对象是什么、是否会因冲高的红蓝影响归一化判断？
4. rawalchemy 是否做了高光滚降 / 去饱和？
5. `encode_srgb` 与 cross-term 如何造成偏色？具体矩阵值是多少？
6. 同一过曝中性白，为何 ProPhoto 可全通道 >1 而 sRGB 有 g<1？
7. 若将来要做 OKLab / 感知空间高光压缩，现行"烘焙矩阵"结构是否够用？

规范约束（`rules/REVIEW.md` 原则 5）：`external/dnglab`、`RawAlchemyCpp` 视为固定约束，本文只记录其实际行为与 first-party 侧应对。

## Finding

### F1. clamp 已就位，sRGB 出口偏色非漏钳所致（硬事实）

`app/src/binding/rust/rawler_fotlab/src/bound.rs`：

```rust
// :113-114  输出钳（rayon 并行内）
fn shrink_f32(v: f32) -> u8 {
    (v.clamp(0.0, 1.0) * 255.0) as u8
}
// :130-131  输入钳 → gamma → 输出钳
fn encode_srgb(v: f32) -> u8 {
    shrink_f32(srgb_apply_gamma(v.clamp(0.0, 1.0)))
}
```

- `encode_srgb` 在 gamma 前对输入 `v.clamp(0.0, 1.0)`，再经 `shrink_f32` 在 gamma 后再次钳到 0..1。两处钳制均存在。
- `calibrate.rs:143-154` 的逐像素映射注释为 `// No clamp`，那是工作空间内不钳（宽色域保留负 / 超 1 分量，符合设计），与展示支路在 `bound.rs` 编码处的 clip 是两回事。
- 结论：用户"超过 1 的 sRGB 值被跳过 clamp 输入进 gamma"的假设不成立。偏色是结构性色度学产物，不是漏钳。

### F2. sRGB 预览支路与 ProPhoto 编辑支路是 `calibrate` 内兄弟分支，ProPhoto 不经 sRGB 中转

- 展示分支：`calibrate(WorkingSpace::SrgbD65)` → `bound::rawlerimagedeveloped_to_png`（gamma + clamp）→ 8-bit PNG 给 Kotlin。
- 编辑分支：`calibrate(WorkingSpace::ProPhotoD50)` → rawalchemy（grade）→ log / 3D LUT。
- 两者均为 `cam × WB → 矩阵直出`（`cam2rgb · [r, g, b]`，见 `calibrate.rs:143-154`），**不存在 ProPhoto 经 sRGB 中转**。反驳了"ProPhoto 是否通过 sRGB 转出"的疑问：两条路都是 cam 直出，仅锚定的工作空间基色不同（见 `calibrate.rs:107-117` 注释与 `FOTLAB-RAWLER-000005` / `000016` F4）。

### F3. `normalize` 的时序与输入性质：在 sRGB 基色之后、pseudo_inverse 之前，且作用于固定矩阵系数而非像素

`app/src/binding/rust/rawler_fotlab/src/calibrate.rs`：

```rust
// :26
use rawler::imgop::matrix::{multiply, normalize, pseudo_inverse};
// :116-117
let rgb2cam = normalize(multiply(&xyz2cam, &space.to_xyz_matrix()));
let cam2rgb = pseudo_inverse(rgb2cam);
```

- `multiply(&xyz2cam, &space.to_xyz_matrix())` 已把工作空间基色（`SrgbD65` 的 `SRGB_TO_XYZ_D65`，或 `ProPhotoD50` 的 `XYZ_TO_PROFOTORGB_D50` 逆）烘焙进前向矩阵；`normalize` 作用其上，**即"转成 sRGB 基色之后、求伪逆之前"**。
- 关键：`normalize` 的输入是相机标定矩阵经空间基色变换后的**固定系数**，与图像内容（像素灰度、是否过曝、冲高的红蓝）零关系。故"冲高的红蓝影响 normalize 对归一化最大值的判断、进而导致偏色"的假设不成立——归一化对象不是像素，不存在"最大值判断被过曝像素影响"这一回事。

### F4. `normalize` 按行求和、不管 cross-term 不引入偏置

`normalize` 仅将每行除以其行和使行和=1，是纯尺度变换，不修改任何 cross-term 的符号或相对大小。cross-term 的符号由相机矩阵与空间基色的乘积决定，`normalize` 只是整体重标定，不会因"按行求和"而丢失或扭曲 cross-term 信息。故"按行求和会忽略 cross-term 引发问题"的担心无据。其与偏色的正交性亦见 `FOTLAB-RAWLER-000016` F5b（normalize=白点锚定，只改尺度、保留 cross-term 符号）。

### F5. rawalchemy 的评级/着色通路无高光滚降（roll-off）与去饱和；高光重建在 demosaic 前的 CFA 域、不在 grade 通路

`external/RawAlchemyCpp/src/grading_fused.cpp:104-106`：

```cpp
r = logEncode(std::max(r, 1e-6f), curve);   // 仅低侧钳到 1e-6，高侧不钳
g = logEncode(std::max(g, 1e-6f), curve);
b = logEncode(std::max(b, 1e-6f), curve);
```

- 该文件内评级顺序（gain → sat/contrast → gamut matrix → logEncode → 3D LUT）中**无任何高光滚降 / 去饱和**步骤；当工作空间未选 log 空间时整段跳过。
- 高光"重建"（inpaint-opposed / segmentation-based，见 `nn_highlight_recon.cpp`、`nn_highlight_segbased.cpp`、`demosaic_nn_xveon.cpp`）位于 **pre-demosaic CFA 阶段**，与 `rawler_fotlab` 的 cam→RGB 后着色/评级通路不是同一环节，不解决本调研的 sRGB 出口偏色。
- 含义：ProPhoto 支路"干净"不是因为 rawalchemy 做了高光保护，而是 (a) 其 cam2rgb 的 cross-term 幅度更小，(b) 编辑支路不在出口做逐通道硬裁、未被冻结成品色相。与 `FOTLAB-RAWLER-000013` F8 一致。

### F6. 负 cross-term 的数学：以真实 Nikon D850 矩阵复算

按真实 D850 相机矩阵、严格复刻 `normalize(multiply(xyz2cam, to_xyz_matrix()))` + `pseudo_inverse` 得到的 cam2rgb（调研复算值，非逐机保证）：

- sRGB D65：
  ```
  [ 1.4009  −0.3130  −0.0879 ]
  [−0.1249   1.4125  −0.2876 ]
  [ 0.0080  −0.4077   1.3997 ]
  ```
  Gcam 列（绿输入对各输出的系数）= `[−0.3130, 1.4125, −0.4077]`。
- ProPhoto D50：
  ```
  [ 0.7174   0.1412   0.1414 ]
  [ 0.0076   1.1560  −0.1636 ]
  [ 0.0422  −0.2990   1.2567 ]
  ```
  Gcam 列 = `[+0.1412, 1.1560, −0.2990]`。

设中性白在 WB 后出现绿通道缺口 d（输入 `(1, 1−d, 1)`），则输出 `= (1,1,1) − d·Gcam列`：

- sRGB：`(1+0.313d, 1−1.413d, 1+0.408d)` —— 红、蓝越过 1，绿低于 1 → 固定品红色相；红蓝 >1 随后在出口被逐通道硬裁，色相被冻结。
- ProPhoto：`(1−0.141d, 1−1.156d, 1+0.299d)` —— 绿、红低于 1，蓝略过 1 → 近中性；cross-term 幅度小，且编辑支路不在出口硬裁。

sRGB 的"绿→红/蓝为负 cross-term"是**窄 sRGB 基色 + RGGB 绿过采样**的必然数学后果，不是矩阵标定的偶然误差。此与 `FOTLAB-RAWLER-000013` F7（cam2rgb 的 G 行负系数把 G 拽到 1 以下 → 洋红）在定量上对齐；本条给出真实矩阵的量化值。

### F7. 为何同一过曝中性白：ProPhoto 可全通道 >1，而 sRGB 有 g<1

由 F6 的 Gcam 列可见，两空间的绿行系数均 >1（sRGB 1.4125、ProPhoto 1.1560），故在绿缺口 d 下绿输出均 <1。差异在 cross-term：

- sRGB 的"绿→红/蓝"为负（−0.3130 / −0.4077），把红蓝推过 1，形成品红；
- ProPhoto 的"绿→红"为正（+0.1412），红反而被拉低，整体更接近中性，且蓝色仅 +0.2990 小幅越界。

另一层：sRGB 色域更窄、对"超 1"更敏感，过曝时先触顶的通道更早被出口硬裁；ProPhoto 宽色域允许更大线性范围，故同一次过曝在两空间呈现"一边 g<1 且品红、一边各通道全 >1 但近中性"的分歧。该分歧是 `FOTLAB-RAWLER-000013` F8「"部分撑满"是窄色域支路产物」的量化注脚。

注意："ProPhoto 全 >1"是相对其工作空间归一化的表述；其数值（如 000013 F8 的 `[1.769, 1.081, 1.345]`）仍属"向上越界、不裁剪"，与 sRGB 支路被冻成 8-bit 品红是不同结局，非"ProPhoto 不被钳所以正确"。

### F8. `exposure.rs` 无 NaN/inf 处理；NaN 经 `as u8` 成黑点而非偏色（更正）

早前某轮 summary 误述"exposure 已把 NaN→0、inf→1"。经读 `app/src/binding/rust/rawler_fotlab/src/exposure.rs` 全文，其中**没有任何 NaN/inf 特殊处理**；f32→u8 的 `as` 转换在 NaN 时产生 0（黑点），并非偏色来源。此条为对既有记录的事实更正，不改变 F1–F7 的偏色归因。

### F9. OKLab / 感知空间高光压缩需拆解烘焙矩阵（结构性发现，非建议）

- 现行管线把相机→工作空间的多个矩阵**烘焙为单一逆 3×3 `cam2rgb`**（`FOTLAB-RAWLER-000016` F1/F4）。
- OKLab（及 Jzazbz）含逐通道立方根非线性（lightness 分支），**无法并入单一 3×3 烘焙矩阵**——任何"在 cam2rgb 之后做感知空间压缩"的设想都必须先有 XYZ 或线性 RGB 中间态。
- 两条可行结构（仅描述，非建议）：
  - **Method A（分叉前统一压缩）**：把 `cam2rgb` 拆为 `cam2xyz` + 各空间转换，在 XYZ / 线性 RGB 上做压缩后再进工作空间；优点是预览/编辑一致，代价是 `FOTLAB-RAWLER-000016` F6 指出的"零缓冲"收益需重新评估（tile 级物化而非整帧）。
  - **Method B（sRGB 出口后单独压）**：不拆矩阵，仅在展示支路 `encode_srgb` 之后追加感知压缩；代价是牺牲预览/编辑一致性（两支路色相不同）。
- 与 `FOTLAB-RAWLER-000016` 的关系：000016 F5/F7 已指出“耦合矩阵遇非线性 / 跨像素操作即表达不了、必须物化”；本条给出“OKLab 压缩”这一具体场景，证实该边界确实成立且需拆解。

### F10. OKLab 高光压缩旁路已按 F9 的 Method A 实现并落地（实现注记，非建议）

F9 的 Method A（分叉前在 XYZ/线性 RGB 上做感知空间压缩）已由 `FOTLAB-RENDER-000001` 设计并通过 commit `7112766` 落地；Method B 未采用（牺牲预览/编辑一致性）。

实现要点（均来自 `app/src/binding/rust/rawler_fotlab/src/calibrate.rs`、`develop.rs`）：

- **插入位置与白平衡的关系**：旁路插在 `calibrate` 的 `wb` 逐像素乘之后（`calibrate.rs:181-183`）、烘焙 `cam2rgb` 之前（`calibrate.rs:184-188`）。入口值是**已 white-balanced 的相机值**——故 5300K 等场景白点早已随 `wb` 进像素（见 `FOTLAB-RAWLER-000015` F9），旁路只需复用同一套 D65 矩阵因子，无需为场景白点额外处理。
- **camera↔XYZ 重建刻意采用正向一致分解**：`cam2xyz = to_xyz · cam2rgb_eff`、`xyz2cam_eff = cam2rgb_eff⁻¹ · to_xyz⁻¹`（`calibrate.rs:130-146`），其中 `cam2rgb_eff` 是 RGB 三通道臂实际相乘的 3×3（丢弃未用的 E 列）。这等价于 `FOTLAB-RAWLER-000016` F5a 的理论分解 `cam2rgb = M⁻¹·X⁻¹·D⁻¹`，且严格保证 `xyz2cam_eff · cam2xyz = I`（bit-exact 往返），使“关闭开关 ≡ 恒等”短路成立。
- **刻意偏离早前「坑①须用 `pinv(xyz2cam)` 重建」的启发式**：`xyz2cam` 是 RGBE 4×3，`pinv(xyz2cam)` 是 camera→XYZ 的**原始最小二乘逆**，不等于流水线真实正向 `to_xyz·cam2rgb`。用它做旁路会让 `pinv(xyz2cam)·cam2rgb ≠ I`，破坏恒等短路、且中段 XYZ 不再是真 D65。故采用正向一致分解（设计文档 C3 / step 1·4）。
- **开关参数化（保留 Kotlin 关闭能力）**：`DevelopParams.oklab_highlight_compress` 为 `#[uniffi(default = true)]` 字段；Kotlin 既有调用点因 default 无需改动即默认 on，将来可传 `false` 关闭而无 Rust 改动。仅 `SrgbD65` 支生效（`bypass_active = oklab_compress && space == SrgbD65`），`ProPhotoD50` 直通，off 时为 bit-for-bit 恒等。
- **压缩曲线**：lightness-driven chroma smoothstep roll-off（L∈[0.92, 1.0]），与 `partial_saturation_chain_sim.py` 方向一致；knee 常量可调。
- **单测**：`oklab_round_trip`、`bypass_identity_*`（non-highlight/neutral 恒等）、`highlight-chroma-reduction`。

与 F9 的关系：F9 给出“OKLab 压缩须拆解烘焙矩阵”这一边界并描述 Method A/B 两条结构；本条确认 Method A 已按该边界实现，且因复用真实正向因子，未破坏 `000016` F6 的零缓冲收益（旁路在 tile 内 in-place 改写，不新增整帧缓冲）。

## Impact / Conflict

- 与 `FOTLAB-RAWLER-000013`：F1 直接反驳"漏钳"假设，证实 000013 的定性归因（cam2rgb 是作者、clamp 是执行者）在代码层有完整钳制支撑——偏色是结构性的而非缺失所致；F6/F7 是 000013 F7/F8 的定量补强。
- 与 `FOTLAB-RAWLER-000016`：F3/F4 从"normalize 时序与输入性质"角度补强 000016 F5b（normalize=白点锚定、与像素无关）；F9 是 000016 F5/F7（耦合矩阵边界）的一个具体实例。
- 与 `FOTLAB-RAWLER-000005`：宽色域枢纽（ProPhoto 作枢纽）可缓解 sRGB 支路的"部分撑满"品红，但不能消除 ProPhoto 支路自身的越界（000013 F8）；本调研进一步说明两支路出口行为分歧的根源在 cross-term 幅度与是否硬裁。
- 上游 `external/dnglab` / `RawAlchemyCpp` 属固定约束（REVIEW 原则 5）：本条只记录实际行为与 first-party 侧应对，不提议改上游。
- 本条目不含建议与待办（按用户要求：仅阐述调研结论）。

## Change History

- 2026-10-04 — 创建。纯静态代码核对 + 真实相机矩阵复算，未改动任何仓库代码，亦未做本地编译/运行。记录：F1（bound.rs:114/131 输入输出双钳就位，偏色非漏钳）、F2（sRGB 预览与 ProPhoto 编辑为 calibrate 兄弟分支、ProPhoto 不经 sRGB 中转、均 cam 直出）、F3（normalize 在 sRGB 基色之后、pseudo_inverse 之前，作用于固定矩阵系数而非像素，故“冲高红蓝影响归一化判断”假设不成立）、F4（按行求和不扭曲 cross-term）、F5（rawalchemy grading_fused 仅低侧钳、无高光滚降/去饱和，高光重建在 pre-demosaic CFA 域不解决本偏色）、F6（真实 D850 矩阵量化负 cross-term → 品红数学）、F7（ProPhoto 可全 >1 而 sRGB 有 g<1 的 cross-term 机理）、F8（更正 exposure.rs 无 NaN/inf 处理、NaN→黑点非偏色）、F9（OKLab 压缩须拆解烘焙矩阵，Method A/B 结构描述）。按用户要求，本条不含 Recommendation 与待办事项。
- 2026-10-04 — 补充 F10（实现注记）：OKLab 高光压缩旁路已按 F9 的 Method A 由 `FOTLAB-RENDER-000001` 设计并通过 commit `7112766` 落地。记录实现要点——插入位置在 `wb` 乘之后/烘焙 `cam2rgb` 之前；camera↔XYZ 用正向一致分解 `cam2xyz=to_xyz·cam2rgb_eff`/`xyz2cam_eff=cam2rgb_eff⁻¹·to_xyz⁻¹`（等价于 000016 F5a，bit-exact 往返）；刻意偏离 `pinv(xyz2cam)` 启发式；`oklab_highlight_compress_srgb`（`#[uniffi(default=true)]`，sRGB 呈现支）与 `oklab_highlight_compress_prophoto`（`#[uniffi(default=false)]`，ProPhoto 编辑支）两个独立字段，门控在 `develop_image` 由 `space` 选择，任一门 off 为该支 bit-for-bit 恒等；压缩曲线为 L∈[0.92,1.0] 的 lightness-driven chroma smoothstep roll-off；含 round-trip/identity/chroma-reduction 单测。Related 增补 `FOTLAB-RENDER-000001`。
