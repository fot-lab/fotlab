# LoCA 紫边/绿边矫正失效根因 — 判据在 pre-WB 相机空间评估，与白平衡解耦后才能生效

- ID: FOTLAB-RAWLER-000020
- Status: 等待用户决定
- Priority: P1
- Created: 2026-10-07
- Owner: —
- Related: [`FOTLAB-RAWLER-000011`](FOTLAB-RAWLER-000011.md) (pre-demosaic LCA/CA 同级相邻阶段，LoCA 紧随其后)、[`FOTLAB-RAWLER-000019`](FOTLAB-RAWLER-000019.md) (wb_coeffs 三条来源对照 — 本条给出 LoCA 应折哪种 WB 的判定)、[`FOTLAB-RAWLER-000009`](FOTLAB-RAWLER-000009.md) (pre-demosaic 槽位约定)、[`FOTLAB-RENDER-000003`](../../DESIGN/index.md) (LoCA 设计文档 — 域为 pre-WB)。

> 本轮为**纯静态代码核对 + 根因分析**，未改动任何仓库代码。LoCA 已随 RC `v2026.10.06.10.50-rc` 落地并由人工测试，测试结论为「启用 LoCA 却毫无效果」。本文记录失效根因与修复方向，待用户决定是否实施修复。

## Background & Goal

LoCA（纵向色差 / 紫边绿边矫正）已于 `FOTLAB-RAWLER-000011` 的相邻槽位落地：在去马赛克之前的线性单通道 mosaic 上、紧接 LCA 之后运行（`develop.rs:657` `correct_loca`），只改 G 平面、R/B 平面从不写入（用户硬性约束）。RC `v2026.10.06.10.50-rc` 经人工测试后反馈：**打开 LoCA 矫正后，图像看上去完全没有变化**。

此前同一轮调查已修复两处会致管线崩溃 / 色偏的问题（与本失效无关，记录于此避免混淆）：

- 总开关 `enabled` 原默认 `true` 且与 Kotlin「关传 `null`」语义错配 → 改为 Rust 侧 `enabled` 默认 `false`（短路关闭），Kotlin 仅在开启时显式传 `enabled=true`（提交 `98ff414`）。
- apply 阶段原 `clamp(0,1)` 截断了 exposure-EV 后合法 >1 的高光 → 去掉该 clamp，mosaic 保持 raw-linear、可 >1（提交 `1552dae`）。

本轮目标：解释「开关已正确打开、参数已正确下传，但矫正无效果」的根因，并给出可实施的修复方向。**只分析、不修改。**

## Finding

### F1. 管线分段顺序 —— LoCA 跑在白平衡（WB）之前（`develop.rs`）

```
apply_scaling(507) → take_scaled_pixels(514)
  → Deprofile DCP/LCP(554-597)
  → apply_exposure(603)           // 通道一致 2^EV 增益，post-EV 值可 >1
  → denoise(614) / dehaze(628)
  → correct_ca(650)               // LCA，不接收 wb
  → correct_loca(657)             // LoCA，不接收 wb  ← 此处
  → demosaic(665)
  → calibrate(687)               // r*=wb[0]; g*=wb[1]; b*=wb[2] 才在此乘 WB
```

`calibrate`（`calibrate.rs:193-195`）逐像素施加 `wb`：`r = px[0]*wb[0]; g = px[1]*wb[1]; b = px[2]*wb[2]`。而 `correct_loca` 在 `develop.rs:657` 调用，**早于** `calibrate` 的 WB 乘法，且 `correct_loca` 的签名（`loca.rs:83-89`）与 `rawtrp_correct::correct_loca_bayer` 的核（`ca_correction_aca.rs:42`）都明确标注运行于 **pre-WB raw-linear mosaic domain**。即 LoCA 看到的是白平衡尚未施加的相机空间 mosaic。

### F2. 判据依赖「白平衡后的通道可比性」，pre-WB 下关系被系统性倒置

LoCA 两对 peer 分支的判据均为通道幅度比较（`ca_correction_aca.rs:149-205`）：

- **紫边（去紫边）**：`magenta = min(r,b) − g > 0` 且 `lum > purple_lum_min` 且 on-edge。
- **绿边（去绿边）**：`excess = g − max(r,b) > 0` 且 `lum > green_lum_min` 且 on-edge。

其中 `r`/`b` 由当前 G 位置的**4 个正交 CFA 邻居**估计（RGGB 瓦片中，G 的正交邻居必为 2 个 R + 2 个 B），`g` 取中心 G 本身（`ca_correction_aca.rs:276-301`）。

判据成立的前提是：在白平衡后的幅度空间里，紫边处 `min(r,b) > g`、绿边处 `g > max(r,b)`。但 pre-WB 的 as-shot 相机空间里，机内中性白平衡乘子把 R、B 通道**向上**归一、G 通道恒为 1（见 `FOTLAB-RAWLER-000019` F1：上游 `wb = 1 / (相机对场景白点响应)`，绿通道归一为 1）。因此**原始 R、B 数值系统性低于 G**：

- 紫边判据 `min(r,b) > g` 在整帧近似**恒假** —— 即便真实紫边处 R/B 相对 G 偏高，也往往不足以翻越 as-shot 的通道落差，紫边分支整帧 inert（即「开了没效果」的主要表现）。
- 绿边判据 `g > max(r,b)` 反而会在**普通中性灰边缘**整片触发（中性灰的 raw 里 G 本来就最大），把正常边缘当成绿边去「压低 G」，产生假色 / 暗边，与「矫正真实色边」的意图相反。

结论：LoCA 的判据定义于「白平衡后幅度空间」，却被放在「白平衡前相机空间」评估，导致它依赖的通道幅度关系被倒置 —— 紫边分支失效、绿边分支误触发。

### F3. 不应把 LoCA 移到 demosaic / WB 之后 —— G 中心邻域结构不在 RGB 域

LoCA 是 G 中心算法：它在 mosaic 的每个 G 光位上、用其正交 R/B 邻居估计、就地改 G 平面（`ca_correction_aca.rs:264-358` 只在 `fc(row,col)==1`（G）处写入）。这套 CFA 邻域结构在 demosaic 之后不存在 —— 去马赛克后是每像素 R/G/B 三平面，正交「R/B 邻居」语义消失，边缘检测也依赖对角 G 邻居（`ca_correction_aca.rs:306-311`）。把 LoCA 后移等于重写整套 G 中心算法，违反「pre-demosaic 邻域质量阶段」的架构约定（`FOTLAB-RAWLER-000009`、设计文档 `FOTLAB-RENDER-000003`），属架构降级，须人工确认，本分析不取此路。

### F4. 修复方向 —— 在 LoCA 之前把**中性（as-shot）WB** 折进 mosaic，仍位于 demosaic 之前

正确且最小的修复：在 `correct_loca`（`develop.rs:657`）调用 `rawtrp_correct::correct_loca_bayer` 之前，把 **`image.wb_coeffs`**（机内中性 as-shot WB）逐 photosite 折进 mosaic：

```
R ← R · wb_coeffs[0]
G ← G · wb_coeffs[1]   (机内中性下 = G·1)
B ← B · wb_coeffs[2]
```

即「每 photosite 乘其通道增益」，仍发生在 demosaic 之前的 mosaic 空间。折完后通道幅度与「白平衡后」一致，F2 的判据才成立：紫边处 `min(r',b') > g` 能真正触发，中性灰 `min(r',b') − g = 0`（不满足 `>0`，不误修）。

**关键约束 —— 必须折中性 WB，禁止折创作 WB：**

- 折进 LoCA 的 WB 必须来自 `image.wb_coeffs`（相机中性 as-shot，由上游解码得到，`calibrate.rs:100-104` 在 `params.wb=None` 时即用此值）。
- 必须与 `params.wb` 解耦 —— `params.wb` 是用户 Kelvin 创作 WB，由 `wb_from_color_temp`（`wb.rs:284`）按色温模型推算。若把创作 WB 折进 LoCA，判据中的 `min(r,b)/g` 共同因子**不能约掉**（创作 WB 的 G 也参与缩放，且 R/B-vs-G 的相对比值随 Kelvin 旋钮变化），判据会随用户色温旋钮漂移 —— 对 CA 矫正而言是错误行为。
- 折中性 WB 则：as-shot 乘子只取决于拍摄光源（相机已把场景白点中性化），与用户创作色温无关；`min(r,b)/g` 的比值约去色温相关因子，判据成为**场景不变（scene-invariant）** —— 这正是 CA 矫正想要的属性（`FOTLAB-RAWLER-000019` F4：场景白点全进 wb 乘子、与矩阵解耦的同一架构事实）。

> 注意：折中性 WB 进 mosaic 是 LoCA 阶段内部的临时白平衡，仅为让判据可比，不改变下游 `calibrate` 仍按 `params.wb`（用户创作 WB）做最终色彩映射 —— 两者解耦，互不影响。

### F5. 已静态核验的边界事实（支撑上述结论）

- mosaic 接受 exposure-EV 后 >1 的合法值（`ca_correction_aca.rs:44-47`）：所有除法（`magenta/lum`、`excess/lum`、`rsum/rc`、`bsum/bc`）均被判据门控保证分母 >0，`smoothstep`/`smootherstep` 分母为常量 ≠0；apply 阶段无 sqrt/log/pow，且已无 clamp（F1 第二点）→ >1 不会崩溃，仅可能过度触发（见 F2 绿边误触发，属算法调参而非崩溃）。
- `correct_loca` 在 `settings=None` 或 `!settings.enabled` 两处短路（`loca.rs:90-97`），与 `DevelopParams.loca=None`（Kotlin 关）共同保证默认即「关」。

## Impact / Conflict

- **功能性缺陷（P1）**：LoCA 作为已发 RC 的功能，在默认 pre-WB 评估下紫边分支整帧失效，绿边分支误触发普通边缘 —— 用户侧观察为「开启无效果」或「产生假色」，与功能意图不符。
- 与 `FOTLAB-RAWLER-000019`：本条是 `000019`「wb 乘子与渲染矩阵解耦」在 LoCA 域的具体应用 —— 必须取 as-shot 中性乘子、且不可取创作 WB，结论与 `000019` F4 完全一致。
- 与 `FOTLAB-RAWLER-000011`：LoCA 是 `000011` 同级相邻阶段；修复不改变 staged 顺序（LoCA 仍在 demosaic 前、紧接 LCA），仅在其入口多一步「折中性 WB」的前处理。
- 不触及 `external/dnglab`（REVIEW 原则 5）：修复全部在 first-party `rawler_fotlab` / `rawtrp_correct` 内。
- 测试覆盖缺口：CI 的 `test_image_develop` / `test_image_alchemy` 长期 `skipped`，LoCA 开启态无真机渲染自动化；本失效只能靠人工测试发现，修复后仍需人工验证 + 考虑补单测（构造已知紫边 mosaic、断言 G 被抬起）。

## Recommendation

**推荐修复（待用户拍板）**：在 `correct_loca` 入口、调用核之前，把 `image.wb_coeffs`（中性 as-shot）折进 mosaic（R←R·wb[0]、B←B·wb[2]，G·1 不变），仍位于 demosaic 之前；**绝不**折 `params.wb`（创作 WB），以保持判据场景不变。折完再跑现有核，紫边判据即可在真实紫边处触发。实现须同步：
1. `loca.rs::correct_loca` 增加 `wb: Option<[f32;4]>` 形参（取自 `develop_image` 内的 `image.wb_coeffs`，NaN 时用 `[1,1,1,1]` 等价不折）；
2. `develop.rs:657` 透传 `image.wb_coeffs`；
3. non-Bayer / `enabled=false` 短路路径保持不变（不折 WB）；
4. 补一个单测：给定「白边左 / 黑边右、紫边注入 R/B 高于 G」的 mosaic，断言折中性 WB 后紫边 G 被抬起、且中性灰处 `min(r,b)−g=0` 不被误修。

**明确不取**：把 LoCA 移到 demosaic / WB 之后（F3 理由 —— 重写 G 中心算法、架构降级）；折创作 WB（F4 —— 判据随色温漂移、对 CA 错误）。

## Change History

- 2026-10-07 — 创建（状态：等待用户决定）。纯静态代码核对 + 根因分析，未改动仓库代码。记录：F1（管线分段顺序 —— LoCA 在 `develop.rs:657` 早于 `calibrate` 的 WB 乘法 `687`，且核明确 pre-WB）、F2（判据依赖白平衡后通道可比性，pre-WB as-shot 下 R/B 系统性低于 G 致紫边分支整帧 inert、绿边分支误触发中性边缘）、F3（不应后移 LoCA —— G 中心 CFA 邻域结构在 RGB 域消失）、F4（推荐修复：在 LoCA 入口折 `image.wb_coeffs` 中性 WB、禁止折创作 WB `params.wb`，判据方可 scene-invariant）、F5（>1 不崩仅可能过度触发；两处短路保证默认关）。背景：RC `v2026.10.06.10.50-rc` 人工测试「开启 LoCA 无效果」；同轮此前两修复（总开关默认短路 `98ff414`、apply 去 clamp `1552dae`）与本失效无关，仅作区分记录。
