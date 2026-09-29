# DCP apply 在 ForwardMatrix1/2 之间做 1/T 插值而非取单个矩阵 — 插值权重由白平衡 neutral 反算，Bradford 只存在于无 ForwardMatrix 的退化分支

- ID: FOTLAB-RAWLER-000014
- Status: Observation
- Priority: P2
- Created: 2026-09-29
- Owner: —
- Related: FOTLAB-NATIVE-000005 (RawTherapee DCP/LCP parser + Camera/Lens Profile correction，本条为其 apply 侧的行为依据), FOTLAB-RAWLER-000009 (pre-demosaic 槽位，DCP BaselineExposure 落在此处), FOTLAB-RAWLER-000011 (rawtrp_correct — RT CA_correct_RT 移植，同为 RT 移植的语义对齐问题), ACTION-RAWLER-000007 (上游只读、shim 单一映射表契约)

## Background & Goal

`rawtherapee_fotlab` 已完成 vendor 化改造：DCP/LCP 的**解码**部分从 `external/RawTherapee` 裁剪进 `app/src/binding/cxx/rawtherapee_fotlab/cxx/vendor/`，**apply** 部分由我们用 Rust 重写（CI run `36497895172` 全绿）。分工既定，那么 Rust 侧重写 apply 时就必须精确知道上游在 apply 阶段到底做了什么。

本轮为**纯调研（未改动任何仓库代码）**，起因是一个具体的疑点：`app/src/binding/cxx/rawtherapee_fotlab/src/dcp.rs:112` 把 `forward_matrix` 取成了 `fm2`——

```rust
has_forward_matrix: [has_fm1, has_fm2],
forward_matrix: flat_to_3x3(fm2.as_slice()),
```

而 `has_forward_matrix` 同时携带 fm1/fm2 两个标志。要判断"取 fm2 是否可接受"，就必须回答：

1. 上游在两个 ForwardMatrix 之间**做 1/T 插值**，还是只取其中一个？
2. 如果插值，**插值权重从哪来**——是外部给的白平衡色温，还是别的？
3. Bradford 色适应在整个流程里处于什么位置？是 ForwardMatrix 的前置/后置步骤，还是另一条互斥的分支？

规范约束（`rules/REVIEW.md` §Review Principles 5）：`external/RawTherapee` 视为**固定约束**，本文只记录其实际行为与在 first-party 侧的应对方式，**不提出对上游源码的改动**。所有行号均指 `external/RawTherapee/rtengine/dcp.cc`（除另行注明）。

## Finding

### F1. 唯一入口，且 apply 是两阶段

全仓只有一个调用点 `external/RawTherapee/rtengine/rawimagesource.cc:3251`：

```cpp
dcpProf->apply(im, cmp.dcpIlluminant, cmp.workingProfile, wb, pre_mul_row, cam_matrix, cmp.applyHueSatMap);
```

`DCPProfile::apply()`（dcp.cc:1404）是**第一阶段**：色彩矩阵（相机空间 → 工作空间）+ hue/sat map。

第二阶段是 `setStep2ApplyState()`（dcp.cc:1519）+ `step2ApplyTile()`（dcp.cc:1571），承担 tone curve / look table / baseline exposure，调用点 `rawimagesource.cc:1104`、`rtthumbnail.cc:1464`、`improcfun.cc:2521`。

两阶段的划分对我们有直接意义：我们在 CFA 阶段应用的 `2^offset`，属于**第二阶段**的东西。

### F2. 核心链路 `makeXyzCam`（dcp.cc:1747）——五步

`apply()` 在 dcp.cc:1417 调 `makeXyzCam(white_balance, pre_mul, cam_wb_matrix, preferred_illuminant)`，其内部：

**第 1 步 · neutral（dcp.cc:1753-1780）**：RT 的白平衡乘子存在 sRGB 空间，DCP 代码要的是相机空间的乘子，故先经 `invert3x3(cam_wb_matrix) · xyz_srgb` 换算，再除以 `pre_mul` 并按最大值归一化。注释明确其等价于 DNG 的 `AsShotNeutral`。

**第 2 步 · white_xy（dcp.cc:1786）**：

```cpp
const std::array<double, 2> white_xy = neutralToXy(neutral, preferred_illuminant);
```

`neutralToXy`（dcp.cc:1714）是 **30 次不动点迭代**（`MAX_PASSES = 30`）：以 D50 为初值，反复 `findXyztoCamera(last_xy)` → 求逆 → 乘 neutral → 转 xy，直到收敛；若到达上限仍在两点间振荡，取最后两次估计的平均值。

注意 `findXyztoCamera`（dcp.cc:1666）**只用 ColorMatrix**（`has_col_1 && has_col_2` 时按 mix 混合），与 ForwardMatrix 无关。这是 DNG 参考实现的做法：白点必须用 ColorMatrix 这一侧反解。

**第 3 步 · wbtemp（dcp.cc:1818）**：

```cpp
const double wbtemp = xyCoordToTemperature(white_xy);
```

`xyCoordToTemperature`（dcp.cc:285）查 DNG 参考的色温表，末尾 `res = 1.0e6 / (temp_table[index-1].r * f + temp_table[index].r * (1.0 - f))`——表内存的是 `1e6/T`，输出是**绝对开尔文**。

**这里是本条最关键的一点**：`wbtemp` 是**由 neutral 反算出来的**，不是 `white_balance.getTemp()`。也就是说插值权重是**自洽反解**的结果，与用户在 UI 上设的白平衡 K 值不是一个量。

**第 4 步 · mix（dcp.cc:1814-1828）**：

```cpp
if ((has_col_1 && has_col_2) || (has_fwd_1 && has_fwd_2)) {
    const double wbtemp = xyCoordToTemperature(white_xy);
    if (wbtemp <= temperature_1)      { mix = 1.0; }
    else if (wbtemp >= temperature_2) { mix = 0.0; }
    else {
        const double& invT = 1.0 / wbtemp;
        mix = (invT - (1.0 / temperature_2)) / ((1.0 / temperature_1) - (1.0 / temperature_2));
    }
}
```

**确认是 1/T 空间线性插值**。`temperature_1/2` 来自 dcp.cc:1156-1157 的 `calibrationIlluminantToTemperature(light_source_1/2)`（dcp.cc:197），照抄 DNG SDK 的 Exif LightSource → K 表（A/Tungsten = 2850、D50 = 5000、D65 = 6504 ……）。

**第 5 步 · 按 preferred_illuminant 收缩可选集（dcp.cc:1793-1809）**：`preferred_illuminant == 1` 时若 fm1/cm1 存在就把 2 路置为不存在，反之亦然。这给了用户"强制只用某个 illuminant、不做插值"的开关（RT 里即 `cmp.dcpIlluminant`）。默认 0 = 自动插值。

### F3. ForwardMatrix 优先，双矩阵按 mix 线性混合（直接回答问题）

dcp.cc:1860-1889：

```cpp
if (has_fwd_1 || has_fwd_2) {
    // Always prefer ForwardMatrix to ColorMatrix
    Matrix fwd;
    if (has_fwd_1 && has_fwd_2) {
        if      (mix >= 1.0) { fwd = forward_matrix_1; }
        else if (mix <= 0.0) { fwd = forward_matrix_2; }
        else                 { fwd = mix3x3(forward_matrix_1, mix, forward_matrix_2, 1.0 - mix); }
    } else if (has_fwd_1)    { fwd = forward_matrix_1; }
    else                     { fwd = forward_matrix_2; }
    ...
}
```

**答案：是插值，不是取单个。** 且 `mix3x3`（dcp.cc:112）是**逐元素线性加权**（`a[i][j]*mul_a + b[i][j]*mul_b`），不是极分解或几何插值。

ColorMatrix 侧（dcp.cc:1833-1846）是完全同构的逻辑——同样按同一个 `mix` 在 cm1/cm2 之间插值。**注意 `mix` 是两路共用的一个值**：只要 col 或 fwd 任意一路是双矩阵，就按上面的公式算 mix。

### F4. ForwardMatrix 分支仍然依赖 ColorMatrix

dcp.cc:1879-1889（注释写着 "adapted from dng_color_spec::SetWhiteXY"）：

```cpp
const Triple camera_white = multiply3x3_v3(color_matrix, white_xyz);
const Matrix white_diag = {{ {camera_white[0],0,0}, {0,camera_white[1],0}, {0,0,camera_white[2]} }};
cam_xyz = invert3x3(multiply3x3(fwd, invert3x3(white_diag)));
```

即 **只有 ForwardMatrix 是不够的**：必须先用（插值后的）ColorMatrix 把 `white_xyz` 映到相机空间得到 `camera_white`，才能构造对角矩阵完成 ForwardMatrix 的求逆链。想"只暴露 fm"在数学上走不通。

### F5. Bradford 只出现在无 ForwardMatrix 的退化分支

dcp.cc:1890-1894：

```cpp
} else {
    constexpr Triple white_d50 = {0.3457, 0.3585, 0.2958}; // D50
    cam_xyz = multiply3x3(color_matrix, mapWhiteMatrix(white_d50, white_xyz));
}
```

`mapWhiteMatrix`（dcp.cc:125）注释 "Use the linearized Bradford adaptation matrix"，矩阵为：

```
 0.8951   0.2664  -0.1614
-0.7502   1.7135   0.0367
 0.0389  -0.0685   1.0296
```

它把 D50 白点适配到实际白点 `white_xyz`（并把负值钳到 0）。

**结论：Bradford 与 ForwardMatrix 是互斥的两条分支，不是串联步骤。** 有 ForwardMatrix 时根本不走 Bradford——ForwardMatrix 本身已经隐含了到 D50 的映射。所以"只取一个 fm 再做 Bradford 适配"这个设想与上游语义都不符。

### F6. 两个不同的温度源 — 最易踩的坑

- **矩阵插值**（`makeXyzCam` dcp.cc:1818 与 `findXyztoCamera` dcp.cc:1687）：用 `xyCoordToTemperature(white_xy)`，即**由 neutral 反算**。
- **hue/sat map 插值**（`makeHueSatMap` dcp.cc:1942，尤其 1979-1986）：用 `white_balance.getTemp()`，即**白平衡的设定色温**。

两者不是同一个值，不可互换。若 Rust 侧重写时图省事统一用白平衡 K 值去插值矩阵，会得到与上游不同的 mix，表现为难以归因的轻微色偏。

### F7. 矩阵插值分支缺少 `makeHueSatMap` 有的守卫

`makeHueSatMap`（dcp.cc:1959-1975）显式处理了两种退化：

```cpp
if (temperature_1 <= 0.0 || temperature_2 <= 0.0 || temperature_1 == temperature_2) {
    return deltas_1;
}
const bool reverse = temperature_1 > temperature_2;
// ... 用 t1 = min, t2 = max 计算，最后 if (reverse) mix = 1.0 - mix;
```

而 F2 第 4 步的矩阵插值（dcp.cc:1814-1828）**两者都没有**：既没检查 `T ≤ 0`（未识别的 illuminant 会返回 0，此时 `1/0` 直接产生 inf/nan），也没处理 `T1 > T2`（会算出 `[0,1]` 之外的 mix）。上游能工作是因为绝大多数 profile 满足 `T1 < T2` 且两端都 > 0，但**这是隐含假设，不是不变式**。Rust 重写必须自己补上守卫。

### F8. `will_interpolate` 的真实语义

dcp.cc:1337-1361：只有在 `forward_matrix_1 != forward_matrix_2`、`color_matrix_1 != color_matrix_2`、或 hue/sat 双表都存在时才为 `true`。注释原话 "Common that forward matrices are the same!"——两个 ForwardMatrix 完全相同是常见情况，此时不做插值（取哪个都一样）。

所以 `will_interpolate` 是**"是否需要插值"的提示**，不能直接当作"有几个矩阵"来用；F3 的分支判定用的是 `has_fwd_1 && has_fwd_2`，与之独立。

### F9. 第二阶段：baseline exposure 的数值形式

dcp.cc:1533-1535：

```cpp
if (has_baseline_exposure_offset && apply_baseline_exposure) {
    as_out.data->bl_scale = powf(2, baseline_exposure_offset);
}
```

随后在 `step2ApplyTile`（dcp.cc:1577、1598-1600）对每个像素做 `r/g/b *= exp_scale`，且在 look table / tone curve **之前**。

对照我方 `app/src/binding/rust/rawler_fotlab/src/develop.rs:504-514`：

```rust
if cp.apply_baseline_exposure && dcp.has_baseline_exposure {
    let factor = 2.0f32.powf(dcp.baseline_exposure_offset as f32);
    pixels.par_iter_mut().for_each(|v| *v *= factor);
}
```

**数值形式与上游一致**（`2^offset`）。差异只在位置：我们把它提到 CFA 阶段（pre-demosaic），因为它是标量乘法、与 demosaic 可交换——这是我们的设计选择，不是对上游的偏离。

### F10. 我方现状：`forward_matrix` 取 fm2 且无任何消费方

- `app/src/binding/cxx/rawtherapee_fotlab/src/dcp.rs:112` 取的是 `fm2`。
- 全仓 grep：`forward_matrix` 字段**没有任何读取方**（`develop.rs` 只读 `has_baseline_exposure` 与 `baseline_exposure_offset`）。

所以按上游语义它是**错的**（应为"双矩阵按 1/T 插值，单矩阵取存在的那个"），但**当前对输出零影响**。这是一个潜伏 bug，不是正在造成损害的行为偏差。

## Impact / Conflict

1. **`forward_matrix` 字段的语义是错的，但零影响** — 修正它风险极低；不过真正的结论不是"把 fm2 改成 fm1"，而是这个字段**根本不该是单个矩阵**（见 Recommendation R1）。

2. **B5（色彩矩阵阶段）的接口设计被本条约束** — 若将来要在 Rust 侧实现真正的 DCP 色彩变换，FFI 必须暴露：4 个矩阵（cm1/cm2/fm1/fm2）、4 个 has 标志、2 个 temperature、加 `will_interpolate` 与 `light_source`。只暴露"一个已经混合好的矩阵"在数学上不成立，因为混合需要 `white_xyz` 与迭代反解（F2/F4），这些只能在**拿到相机白平衡之后**才能算，不可能在解码期固化。

3. **温度源混淆是最贵的坑** — F6 的两套温度若被统一，产生的偏差是"轻微色偏"，在没有参考图对比的情况下极难归因。这类错误不会让 CI 变红。

4. **缺守卫会产生 inf/nan** — F7 在 `T ≤ 0`（未识别的 illuminant，如 `OTHER = 255`）时直接 `1/0`。上游靠数据分布幸免，Rust 侧若要"忠实移植"就必须把守卫一起移植，否则在少数 profile 上会输出全 nan。

5. **不修改任何既有规则** — 本条与 `FOTLAB-NATIVE-000005` 是设计与行为依据的关系，不冲突；与 `ACTION-RAWLER-000007` 同守"上游只读"原则。

## Recommendation

**R1. 不要"把 fm2 改成 fm1"，而是去掉这个字段或改为暴露完整矩阵组。** 正确语义是"双矩阵按 1/T 插值、单矩阵取存在的那个"，且插值依赖运行时的白平衡。在 B5 真正接线之前，最诚实的做法是不导出一个会误导人的单矩阵。

**R2. B5 实施时按下列清单移植 `makeXyzCam`**（顺序即依赖顺序）：

1. WB 乘子 → camera 空间 → 除 `pre_mul` → 按最大值归一化，得 `neutral`；
2. `neutralToXy` 不动点迭代（30 次上限，收敛阈值 `|Δx|+|Δy| < 1e-7`，达上限取最后两次均值）；
3. `xyToXyz`（dcp.cc:174，注意其把 xy 钳到 `[1e-6, 1-1e-6]` 并处理 `x+y > 1`）；
4. `xyCoordToTemperature`（表为 `1e6/T`，输出绝对 K）；
5. `mix` 的 1/T 插值，**并补 F7 的两个守卫**（`T ≤ 0` 与 `T1 > T2` 的 reverse）；
6. cm / fwd 各自按 `mix3x3` 线性混合；
7. 有 fwd 走 `inv(fwd · inv(diag(cm · white_xyz)))`，否则走 `cm · mapWhiteMatrix(D50, white_xyz)`。

**R3. 严格区分两个温度源。** 矩阵插值用 neutral 反算值；hue/sat map 插值才用白平衡设定值。建议在 Rust 侧用不同的类型或命名（如 `wb_temp_from_neutral` vs `wb_temp_setting`）把二者在源码层面隔开。

**R4. tone curve / look table 属于第二阶段**，若将来要支持，需独立走 F1 的 step2 路径（HSV LUT + 曲线），不在第一阶段的矩阵里。我们当前只接了 baseline，与上游数值一致，无需改动。

## Change History

- 2026-09-29 — 初稿（`Observation`）。纯调研、未改动仓库代码。起因是 `app/src/binding/cxx/rawtherapee_fotlab/src/dcp.rs:112` 的 `forward_matrix` 取 `fm2`；结论为上游在两个 ForwardMatrix 之间做 1/T 插值、权重由 neutral 反算、Bradford 仅存在于无 ForwardMatrix 的退化分支，并记录了两套温度源差异与矩阵插值分支缺失的守卫。
