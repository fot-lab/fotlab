# 我方色彩管线有两条互不一致的矩阵路径 — 白平衡乘子按 mired 插值、渲染矩阵按 find_first+Bradford；且我方矩阵锚定工作空间固定白点，与上游 RT 锚定实际场景白点的架构根本不同

- ID: FOTLAB-RAWLER-000015
- Status: Observation
- Priority: P2
- Created: 2026-09-29
- Owner: —
- Related: FOTLAB-RAWLER-000014 (上游 DCP apply 在 ForwardMatrix1/2 之间做 1/T 插值 — 本条为其“我方现状”侧对照，二者结论互相印证), FOTLAB-RAWLER-000005 (工作空间锁定与 gamut 裁剪 — `calibrate` 侧改造的前序), FOTLAB-RAWLER-000004 (decode-once / develop-reuse — as-shot CCT 经 `wb.rs` 暴露给 UI), FOTLAB-NATIVE-000005 (RawTherapee DCP/LCP parser + Camera/Lens Profile correction), FOTLAB-RAWLER-000019 (wb_coeffs 来源对照 — 上游解码路径与我方派生路径), FOTLAB-RAWLER-000018 (OKLab 旁路插在 wb 乘之后 — F9 实现)

## Background & Goal

`FOTLAB-RAWLER-000014` 调研了**上游** RawTherapee 在 DCP apply 阶段如何插值色彩矩阵，结论是"双矩阵按 1/T 插值，权重由白平衡 neutral 反算"。但那只覆盖了上游的一半；要判断我们自己的实现是否自洽，必须先回答另一半：**我们自己的管线是如何插值与适配的？**

本轮为**纯调研（未改动任何仓库代码，未做数值验证）**。目标是把我方 `rawler_fotlab` 侧与色彩矩阵/白平衡相关的全部插值与色适应路径完整梳理出来，并与上游对照，回答三个问题：

1. 我方在多个色彩矩阵之间**是否插值**？若插值，用什么空间？
2. Bradford 色适应在我方处于什么位置、作用在什么对象上？
3. 与上游相比，我方的架构差异**是否使上游的某些语义无法表达**？

除另行注明，行号均指 `app/src/binding/rust/rawler_fotlab/src/` 下的文件。

## Finding

### F1. 我方存在两条**完全独立**的矩阵选择路径

色彩矩阵在我方被选取了两次，且两次的策略不同：

| | 路径 A：白平衡乘子 | 路径 B：渲染矩阵 |
|---|---|---|
| 入口 | `wb.rs:284` `wb_from_color_temp` | `calibrate.rs:212` `resolve_xyz_to_cam` |
| 矩阵来源 | `raw_color_matrices()` 自行收集全部 illuminant-tagged 矩阵 | `color_matrix_find_first([...])` 按优先级取第一个 |
| 插值 | **有**，mired（1/T）空间 | **无** |
| Bradford | **无** | **有** |
| 用途 | 算 `wb_coeffs` 乘子 | 算 `cam2rgb` 渲染矩阵 |

两条路径互不通信：`wb.rs` 不调用 `resolve_xyz_to_cam`，`calibrate.rs` 也不调用 `matrix_for_cct`。

### F2. 路径 A：mired 插值，逐元素线性混合，**不做** Bradford

`matrix_for_cct`（`wb.rs:177-199`）：

```rust
0 => None,                    // 无 tagged 矩阵 → 回退 image.xyz_to_cam
1 => Some(matrices[0].m),     // 单个矩阵 → 直接用，永不适配
_ => {
    let hi = matrices.iter().position(|m| m.cct >= kelvin);
    match hi {
        Some(0) => matrices[0].m,                    // 低于全部 → 取最低
        Some(i) if matrices[i].cct == kelvin => matrices[i].m,
        Some(i) => {
            let g = ((1.0 / lo.cct) - (1.0 / kelvin))
                  / ((1.0 / lo.cct) - (1.0 / hi_m.cct));
            blend_matrices(g.clamp(0.0, 1.0), &lo.m, &hi_m.m)
        }
        None => matrices[matrices.len() - 1].m,      // 高于全部 → 取最高
    }
}
```

`blend_matrices`（`wb.rs:162`）是**逐元素线性加权** `m = (1-g)·lo + g·hi`，不是几何/极分解插值。

完整链路（`wb.rs:284-307`）：`cct_to_xy(kelvin)` → `white_xyz` → `matrix_for_cct` → `neutral = M(T)·XYZ_white(T)` → `neutral_to_multipliers`（倒数、绿归一、floor、clamp[1/16, 16]）。

`cct_to_xy`（`wb.rs:63`）分段：<4000 K 用 Krystek(1985) Planckian 近似（CIE 1960 UCS 转 xy），≥4000 K 用 Wyszecki–Stiles daylight 多项式——daylight 多项式在 ~4000 K 以下无效，这是分段的原因。

**与上游数学等价但方向相反。** 上游 `mix = (1/T − 1/T₂)/(1/T₁ − 1/T₂)`，权重给低温端 fm1；我方 `g = 1 − mix`，代入 `m = (1-g)·lo + g·hi` 后与上游的 `mix·fm1 + (1−mix)·fm2` **完全相同**。真正的差异在**权重来源**：

- 上游：T 由 neutral 经 30 次不动点迭代（`neutralToXy`）**反解**得出，方向是 neutral → T → mix。
- 我方：T 是 UI 直接给的 Kelvin，方向是 T → neutral。

### F3. 路径 B：find_first 取单个矩阵，Bradford 适配到工作空间白点

`resolve_xyz_to_cam`（`calibrate.rs:212-249`）：

```rust
let (illu, matrix) = image
    .color_matrix_find_first([
        Illuminant::D65, Illuminant::A, Illuminant::B, Illuminant::C,
        Illuminant::D50, Illuminant::D55, Illuminant::D75,
        Illuminant::Daylight, Illuminant::Flash,
    ])
    .unwrap_or_else(|| (illuminant, vec![1.,0.,0., 0.,1.,0., 0.,0.,1.]));  // identity 兜底

let target_matrix = if illu == illuminant { matrix }
    else { adapt_bradford(&illu, &illuminant, &transform_1d_3x3(&matrix)) };
```

`color_matrix_find_first`（`external/dnglab/rawler/src/rawimage.rs:724`）就是按给定顺序返回**第一个命中的**，不插值。

`adapt_bradford`（`external/dnglab/rawler/src/imgop/chromatic_adaption.rs:78`）为 `M ← M_stored · Bradford(stored→target)`，其中 `bradford_adaption_matrix`（同文件 :62）是标准 `B⁻¹·diag(LMS_dst/LMS_src)·B`。

target 由 `WorkingSpace::illuminant()`（`calibrate.rs:56`）决定：`SrgbD65 → D65`、`ProPhotoD50 → D50`——**都是工作空间的固定白点，与场景白平衡无关**。

随后 `rgb2cam = normalize(xyz2cam · space.to_xyz_matrix())`、`cam2rgb = pseudo_inverse(rgb2cam)`，逐像素 `px *= wb` 后 `cam2rgb · px`，ProPhoto 路径刻意不 clamp（`calibrate.rs:116-117, 143-154`）。

### F4. 两条路径的矩阵**不配对**

以相机同时提供 A(2856 K) 与 D65(6504 K) 两个矩阵为例：

| 目标 Kelvin | 乘子所用矩阵（路径 A） | 渲染所用矩阵（路径 B） | 是否一致 |
|---|---|---|---|
| 6504 | D65（g=1，取 hi 端点） | D65（find_first 优先命中） | 一致 |
| 2856 | A（g=0，取 lo 端点） | **D65**（find_first 优先命中） | **不一致** |
| 4000 | A↔D65 的 mired 插值矩阵 | **D65**（与 kelvin 无关） | **不一致** |

路径 B 的矩阵**完全不随 Kelvin 变化**，而路径 A 的矩阵随 Kelvin 连续变化。只有当 Kelvin 恰好落在 `find_first` 选中的那个矩阵的 CCT 上时两者才重合。

`wb.rs:17-35` 的模块头注释声称该做法是为了 "keep the multipliers consistent with the fixed matrix `calibrate` renders through"，但由上表可见，这一致性只在端点处成立。

**后果（静态推断，未做数值验证）**：暖光/冷光偏移的场景下，白点存在残余色偏——乘子是按"插值矩阵"算出的中性化量，却作用在按"D65 矩阵"映射的像素上。色偏量级取决于 A 与 D65 两个矩阵的差异程度，通常不大但非零。Adobe DNG 参考实现中 ColorMatrix 与 ForwardMatrix 是**用同一个 mix 一起插值**的，故路径 B 不插值属于我方简化。

### F5. 根本架构差异：我方矩阵与白平衡**解耦**，上游是**耦合**的

这是本次调研最重要的结论，也是 F4 的深层原因。

- **我方**：矩阵锚定到**工作空间固定白点**（D65 或 D50，`calibrate.rs:56`）。场景白平衡的影响**只由乘子承担**，矩阵本身与白平衡无关。
- **上游 RT**（无 ForwardMatrix 的分支，`dcp.cc:1890-1894`）：

  ```cpp
  cam_xyz = color_matrix * mapWhiteMatrix(white_d50, white_xyz);
  ```

  矩阵锚定到**实际场景白点** `white_xyz`——它由 neutral 反解而来，**随白平衡变化**。

由此推出一个对后续设计有约束力的结论：**我方架构中不存在"随白平衡变化的矩阵"这个位置。** 而 DCP 的 ForwardMatrix 混合权重恰恰依赖运行时白平衡（`FOTLAB-RAWLER-000014` F2/F3）。因此把 DCP 的两个 ForwardMatrix "预先混合成一个矩阵" 在我方架构里**根本无法表达**——这从架构层面独立印证了 `FOTLAB-RAWLER-000014` 的 R1：应删除 `dcp.rs` 中那个会误导人的 `forward_matrix` 字段，而不是把它的取值从 fm2 改成 fm1。

### F6. Bradford 的作用对象在两侧并不相同

两侧都在用 Bradford CAT，但作用对象与方向不同，不可混为一谈：

| | 我方 `adapt_bradford` | 上游 `mapWhiteMatrix` |
|---|---|---|
| 作用对象 | **矩阵**（XYZ→camera） | **矩阵**（D50 白点 → 场景白点的适配矩阵） |
| 方向 | 矩阵从存储光源适配到目标光源 | 白点从 D50 适配到实际场景白点 |
| target 是否随场景变 | 否（固定为工作空间白点） | **是**（随 neutral 变） |
| 参与条件 | 存储光源 ≠ 目标光源即做 | **仅在无 ForwardMatrix 的退化分支** |

### F7. DCP 侧目前实际只接线了 baseline exposure

`develop.rs:502`：

```rust
match rawtherapee_fotlab::parse_dcp(&cp.path) {
  Ok(dcp) => {
    if cp.apply_baseline_exposure && dcp.has_baseline_exposure {
      let factor = 2.0f32.powf(dcp.baseline_exposure_offset as f32);
      pixels.par_iter_mut().for_each(|v| *v *= factor);
    }
  }
  ...
}
```

`parse_dcp` 解析出的 `color_matrix_1/2`、`forward_matrix`、`tone_curve`、`look_table`、`hue_sat_map` **全部无消费方**。也就是说：当前管线的色彩 100% 由 **rawler 解码的 DNG ColorMatrix** 驱动（即 F1–F5 的两条路径），DCP 只贡献一个标量曝光增益。`FOTLAB-RAWLER-000014` 讨论的 fm1/fm2 语义因此**目前对输出零影响**。

### F8. LCP 侧是另一套完全无关的插值（非色彩）

`calcParams`（`cxx/vendor/rtengine/lcp.cc:360-505`）：

1. 先在**焦距**上找 bracketing pair（`bestFocLenLow` / `bestFocLenHigh`）；
2. 再在 **aperture**（vignette，线性）或 **focus distance**（distortion，**log 空间**且带 euler 常数）上找 low/high；
3. 权重 `facLow`：`focLenOnSpot` 时直接取该维权重，否则 vignette 用 `0.5·(facLow + facAperLow)`、distortion 用 `0.8·facLow + 0.2·facDistLow`（lcp.cc:475-488）。

`LCPModelCommon::merge`（lcp.cc:79）用 `rtengine::intp` 逐参数线性插值，且 **`vign_param[4]` 是从插值后的 `param[5]` 重新推导的**（lcp.cc:98-99），并非直接插值 `vign_param`。这一“先插值原始参数、再推导派生参数”的顺序在 Rust 重写时必须保持，否则 vignette 曲线形状会错。

### F9. 场景白点（如 5300K）进入白平衡乘子而非矩阵 —— 代码层印证 F4/F5（2026-10-04 补充）

经对实际代码核对，对“场景白点如何处理”给出确定性结论，作为 F4/F5「我方矩阵与白平衡解耦」的代码层印证：

- `resolve_xyz_to_cam`（`calibrate.rs:254-291`）的 Bradford **目标**仅为工作空间白点（`SrgbD65→D65`、`ProPhotoD50→D50`，由 `space.illuminant()` 决定，见 F3）；其**源**为矩阵键名（见 `FOTLAB-RAWLER-000016` F8/F11，仍属待修正的误读，但目标方向正确）。即**渲染矩阵只锚定到参考白点（D65/D50），与场景白平衡无关**。
- 5300K 这类**非 D65 参考**的场景白点，**全部由白平衡乘子吸收**，逐像素在矩阵之前施加：`calibrate.rs:181-183` `r = px[0]*wb[0]` … 在 `cam2rgb`（`calibrate.rs:189-193`）与 OKLab 旁路（`calibrate.rs:184`）**之前**。两种来源：
  - **as-shot**：`params.wb = None` 时直接用 rawler 解码的 `image.wb_coeffs`（`calibrate.rs:97-106`），是相机固件已把场景白点中性化到参考白点的读数，与色彩矩阵**解耦**（上游三条解码路径见 `FOTLAB-RAWLER-000019` F1）。
  - **显式 Kelvin**：`wb_from_color_temp`（`wb.rs:284`）→ `matrix_for_cct` 在 mired 空间插值得 `M_T`（`wb.rs:177`）→ `neutral = M_T · XYZ_white(T)` → 倒数 + 绿归一 + 地板/钳位（F2）。
- **结论**：渲染矩阵固定锚定参考白点，场景白点全进 `wb` 乘子，与 F5「我方矩阵与白平衡解耦、上游（RT）耦合」完全一致。把渲染矩阵也按场景 illuminant 重适配 + 再做 Bradford 到参考白点是 DNG 严格模型（`dng_color_spec`）做法；现行简化与 rawler 上游同构、对中性白点精确（normalize 把中性点钉死，见 `000016` F5b），摄影上可忽略。故不是 bug，不需要补一步。
- **顺带解释 OKLab 旁路为何正确**：旁路插在 `wb` 乘之后（`FOTLAB-RAWLER-000018` F10），入口值已是 white-balanced 相机值——5300K 早已随 `wb` 进像素，故旁路复用同一套 D65 矩阵因子即正确，无需为场景白点额外处理。

## Recommendation

### R1. 先用数值实验确认 F4 的色偏量级，再决定是否修

F4 是静态阅读得出的推断，未经数值验证。建议先做一个廉价验证：取一部同时具备 A 与 D65 矩阵的相机 RAW，在 2856 K / 4000 K / 6504 K 三档渲染同一帧，测量中性灰卡区域的 RGB 残余偏差。若偏差在不可察范围内，则 F4 降级为文档说明即可；若可察，再按 R2 修。**不要跳过验证直接改渲染路径**——那会改变所有已有渲染的输出。

### R2. 若要修 F4，正确方向是让路径 B 也支持 CCT 插值，而不是砍掉路径 A 的插值

Adobe DNG 参考实现中 ColorMatrix 与 ForwardMatrix 共用同一个 mix。自洽的做法是让 `resolve_xyz_to_cam` 接受一个可选的目标 CCT，在 CCT 已知时走与 `matrix_for_cct` **完全相同**的插值（同一份权重函数，避免两份实现漂移），仅在 CCT 未知（如 as-shot 无 Kelvin 覆盖）时回退到当前的 find_first + Bradford。这样两条路径在任何 Kelvin 下都取到同一个矩阵。

### R3. 两条路径的矩阵选择逻辑必须收敛到一处

现状是 `wb.rs::raw_color_matrices + matrix_for_cct` 与 `calibrate.rs::resolve_xyz_to_cam` 各自独立选矩阵，任何一方改动另一方都不知道，F4 就是这种分离的直接产物。建议抽出一个单一的"为给定 CCT 解析 XYZ→camera 矩阵"函数，两条路径都调它，并配合单测钉住"两条路径在同一 Kelvin 下返回同一矩阵"这一不变量。

### R4. 接 DCP 色彩矩阵前，先解决 F5 的架构缺口

`FOTLAB-RAWLER-000014` R1 与本文 F5 从两个方向指向同一结论：DCP 的 ForwardMatrix 插值依赖运行时白平衡，而我方矩阵与白平衡解耦。因此将来真正要接 DCP 色彩时，需要的是**在 `calibrate` 中引入一个随白平衡变化的矩阵槽位**（即让矩阵成为 Kelvin 的函数），而不是把 DCP 的矩阵在解码期固化。这个槽位的设计应先于任何 DCP 色彩矩阵接线工作。

### R5. LCP 侧保持"先插值原始参数、再推导派生参数"的顺序

见 F8。Rust 重写若图省事直接插值 `vign_param`，vignette 曲线形状会与上游偏离，且这种偏差在视觉上表现为"暗角强度不对"而非明显错误，很难归因。

## Change History

- 2026-09-29 初始版本。纯调研，未改动任何代码；未做数值验证（见 R1）。
- 2026-10-04 — 补充 F9（代码层印证 F4/F5）：经实际代码核对，“场景白点（5300K）进入白平衡乘子而非矩阵”——`resolve_xyz_to_cam`（`calibrate.rs:254-291`）只锚定工作空间白点（D65/D50），场景白点全由 `wb` 乘子吸收（`calibrate.rs:181` 在矩阵之前施加），as-shot 用上游解码读数、Kelvin 用 `matrix_for_cct` 派生；确认现行简化与上游同构、对中性白点精确、非 bug。Related 增补 `FOTLAB-RAWLER-000019`、`FOTLAB-RAWLER-000018`。
