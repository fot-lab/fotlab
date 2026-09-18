# 调研 — RapidRAW LUT 子系统（UI 入口 + 算法管线）

- ID: RAPIDR-SURVEY-000005
- Status: Draft
- Priority: P2
- Created: 2026-09-18
- Owner: —
- Related: `rules/STRUCT/detail/RAPIDR-SURVEY-000004.md`（处理管线总览 / `fs_main` stage 顺序）、`rules/STRUCT/detail/RAPIDR-SURVEY-000003.md`（安卓交互/WebView）、`rules/STRUCT/detail/RAPIDR-SURVEY-000002.md`（非破坏性编辑 JSON）

> **范围声明**：基于 `external/RapidRAW` 的 **shallow clone（`--depth 1`，`v1.6.4`）**。仅文档化，不修改上游。聚焦 RapidRAW 的 **LUT（Look-Up Table）部分**：WebView 界面的操作入口在哪里、算法管线如何流转（从 UI → 编辑 JSON → Rust 解析 → GPU 3D 纹理 → 着色器采样）、以及「场景参照 vs 显示参照」两种应用时机。

## TL;DR

- **WebView UI 入口**：`src/components/ui/LUTControl.tsx`，挂载在 `src/components/adjustments/Effects.tsx:342` 的 **Effects（效果）** 面板区块。提供内置胶片模拟 + 自定义 LUT 的色卡网格、导入按钮、0–100 强度滑块、悬停预览、右键删除。选择后写入编辑 JSON 的 `lutPath / lutName / lutSize / lutIntensity / lutIsSceneReferred` 字段。
- **后端解析**（`lut_processing.rs`）：支持 **`.cube` / `.3dl` / HALD 图像（`.png/.jpg/.jpeg/.tiff`）** 三种来源，解析为 `Lut { size, data: Vec<f32> }`，按路径缓存于 `state.lut_cache`；内置胶片 LUT（SpektraFilm，CC BY-SA 4.0）随包发布于 `resources/film_luts`。
- **GPU 上传与采样**：`lut.data` 转 **RGBA-f16** 上传为 `texture_3d<f32>`（`gpu_processing.rs:1275-1304`），着色器 `sample_lut_tetrahedral`（`shader.wgsl:1505`）做**四面体插值**；`AllAdjustments` 携带 `has_lut / lut_intensity / lut_is_scene_referred`。
- **算法管线中的两个时机**（详见 000004 `fs_main`）：
  - **场景参照（`lutIsSceneReferred=true`，内置胶片模拟默认）**：在**色调映射之前**，`linear_to_vlog(linear)` → 采样 → 与 `default_tonemapped` 按 `lut_intensity` 混合（`shader.wgsl:1926-1934`）。
  - **显示参照（=false，自定义 LUT 默认）**：在**曲线之后、颗粒之前**，`sample_lut_tetrahedral(final_rgb)` → 按 `lut_intensity` 混合（`shader.wgsl:1959-1962`）。
  - `lut_intensity` 为混合因子（UI 0–100 → /100），0=无、1=全量。
- **相机 Log LUT 的局限（S-Log / N-Log / F-Log）**：「场景参照」路径**仅提供 V-Log 一种对数编码**（`linear_to_vlog`，`shader.wgsl:248-254`），**无** Sony S-Log、Nikon N-Log、Fujifilm F-Log 实现；且 `lut_is_scene_referred` 由 `isBuiltIn` 派生（内置=1、自定义=0），**UI 无开关**可让导入的 `.cube` 走场景参照。因此为 S/N/F-Log 设计的转换型 LUT 直接导入**会偏色/无效**，需预处理（烘焙为 display LUT，§8 方案 A）或改码（新增 S/N/F 编码，§8 方案 B）。

## 1. WebView UI 操作入口

### 1.1 组件与挂载点

- **组件**：`src/components/ui/LUTControl.tsx`（355 行）。功能：
  - 色卡网格：内置（`filmEmulations`）与自定义（`customLuts`）分两组渲染（`LUTControl.tsx:208-209,303-348`）。
  - 导入：`handleImport`（`LUTControl.tsx:142-197`）调用 Tauri 命令 `import_luts`；安卓走 SAF `content://` 解析文件名与扩展名校验（`LUTControl.tsx:162-187`）。
  - 强度滑块：仅当选中 LUT 时展开，`min=0 max=100 step=1 defaultValue=100`（`LUTControl.tsx:277-287`）。
  - 悬停预览：`onLutHover` 在色卡上实时切换预览（`LUTControl.tsx:217-219`）。
  - 右键删除：`handleContextMenu` → `remove_lut`（内置不可删，`LUTControl.tsx:59-90`）。
  - 缩略图：展开时调用 `generate_lut_previews` 生成每个 LUT 在当前图上的小样（`LUTControl.tsx:120-140`）。
- **挂载**：`src/components/adjustments/Effects.tsx:342`，位于 Effects 面板卡片内，标题 `adjustments.effects.lut`：
  ```342:351:src/components/adjustments/Effects.tsx
  <LUTControl
    lutPath={adjustments.lutPath || null}
    lutName={adjustments.lutName || null}
    lutIntensity={adjustments.lutIntensity || 100}
    onLutSelect={handleLutSelect}
    onLutHover={onLutHover}
    onIntensityChange={handleLutIntensityChange}
    onClear={handleLutClear}
    onDragStateChange={onDragStateChange}
  />
  ```
  props 全部来自**编辑 JSON 状态**（`adjustments.*`），因此 LUT 与其他调整一样是非破坏性的（见 000002）。

### 1.2 选择流程（UI → 编辑 JSON）

- `handleLutSelect`（`useEditorActions.ts:131-157`）：调用 Tauri 命令 `load_and_parse_lut({ path })` 取得 `{ size }`，然后写入：
  ```131:146:src/hooks/useEditorActions.ts
  const result: { size: number } = await invoke('load_and_parse_lut', { path });
  ...
  lutPath: path,
  lutName: name,
  lutSize: result.size,
  lutIntensity: 100,
  lutIsSceneReferred: isBuiltIn,   // 内置胶片模拟默认场景参照
  ```
  `lutIsSceneReferred` 对**内置 LUT 默认 true**、自定义默认 false（`useEditorActions.ts:146`；`adjustments.ts:580` 默认值 `false`）。
- 强度变更：`handleLutIntensityChange` → 写 `lutIntensity`（0–100）。
- 支持的扩展名：`cube, 3dl, png, jpg, jpeg, tiff`（`LUTControl.tsx:36`）。

## 2. 编辑 JSON 数据模型

`src/utils/adjustments.ts` 定义相关 effect 键（`adjustments.ts:79-84`）与默认值（`adjustments.ts:575-580`）：

| 字段 | 含义 | 默认 |
| --- | --- | --- |
| `lutPath` | LUT 文件路径（null=未应用） | `null` |
| `lutName` | 显示名 | `null` |
| `lutSize` | LUT 立方体边长（来自解析） | `0` |
| `lutIntensity` | 强度 0–100 | `100` |
| `lutIsSceneReferred` | 场景参照模式 | `false` |
| `lutData` | 预留（当前未用于渲染） | `null` |

这些键可被复制/粘贴设置包含（`adjustments.ts:839-846,936-940`）。

## 3. 后端：加载 / 解析 / 缓存（`lut_processing.rs`）

### 3.1 解析（`parse_lut_file` → 按扩展名分派）

- **`.cube`** → `parse_cube`（`lut_processing.rs:184-280`）：解析 `LUT_3D_SIZE` 与 RGB 三元组，校验数据长度 = `size³×3`，否则报错（损坏/不完整）。
- **`.3dl`** → `parse_3dl`（`lut_processing.rs:282-317`）：每行 RGB，按总数开立方推断 `size`，要求为完美立方。
- **HALD 图像**（`.png/.jpg/.jpeg/.tiff`）→ `parse_hald`（`lut_processing.rs:319-349`）：要求正方形，总像素须为完美立方；逐像素归一化到 [0,1]。
- 输出统一为 `Lut { size: u32, data: Vec<f32> }`（RGB 浮点三元组，`lut_processing.rs:25-29`）。
- **安全**：`parse_lut_file`（`lut_processing.rs:351-428`）拒绝 UNC/设备路径、`.` 与 `..` 遍历；安卓 `content://` 经 `read_android_content_uri` 读字节后内存解析。

### 3.2 命令与缓存

- `get_or_load_lut(state, path)`（`lut_processing.rs:475-485`）：按路径查 `state.lut_cache`，未命中则解析并 `Arc` 缓存。
- `list_luts`（`lut_processing.rs:487-520`）：内置（`resources/film_luts`，`is_built_in=true`）+ 用户（`app_data/luts`）；安卓额外含缓存目录。
- `import_luts` / `remove_lut`（`lut_processing.rs:570-623`）：导入复制文件到用户目录；删除禁止删内置、且限制在用户/缓存目录内（路径隔离）。
- `load_and_parse_lut`（`lut_processing.rs:714-723`）：解析并写入缓存，返回 `{ size }`（供 UI 显示）。
- `generate_lut_previews`（`lut_processing.rs:661-712`）：对当前原图用 GPU 渲染每个 LUT 的小样（`render_lut_swatch` → `process_and_get_dynamic_image`，强度 100，内置按 `is_built_in` 设 `lutIsSceneReferred`），输出 base64 JPEG。

## 4. GPU：上传与采样（`gpu_processing.rs` + `shader.wgsl`）

### 4.1 上传为 3D 纹理

`process_and_get_dynamic_image_with_precision`（`gpu_processing.rs:1275-1304`）：若 `request.lut` 为 `Some`，将 `lut.data`（RGB f32）每点补 alpha=1 转 **RGBA-f16**（`f16::from_f32`），用 `device.create_texture_with_data` 建 `texture_3d<f32>`，取 view + sampler；否则用 `dummy_lut_texture`/`dummy_lut_sampler`。

### 4.2 绑定与采样

- 绑定：`@group(0) @binding(4) var lut_texture: texture_3d<f32>;` + `@binding(5) var lut_sampler: sampler;`（`shader.wgsl:203-204`）。
- `AllAdjustments` 字段（`shader.wgsl:71-74` / `image_processing.rs:1510-1513`）：`has_lut: u32`、`lut_intensity: f32`、`lut_is_scene_referred: u32`。
- 由 `get_all_adjustments_from_json` 填充（`image_processing.rs:2193-2212`）：仅当 `is_visible("effects")` 时；`has_lut = lutPath 为字符串 ? 1 : 0`；`lut_intensity = lutIntensity/100`；`lut_is_scene_referred = lutIsSceneReferred ? 1 : 0`。
- 采样：`sample_lut_tetrahedral`（`shader.wgsl:1505-1564`）按主对角线选择 4 个角点做**四面体插值**，坐标 clamp 到 [0,1]。

## 5. 算法管线中的位置：两种应用时机

LUT 是 Stage 2（GPU 逐像素）的一环，但其**插入点取决于 `lut_is_scene_referred`**（对应 `fs_main`，详见 000004）：

### 5.1 场景参照（Scene-Referred，`==1`）—— 色调映射之前

```1926:1934:src-tauri/src/shaders/shader.wgsl
let is_scene_lut = (adjustments.global.has_lut == 1u && adjustments.global.lut_is_scene_referred == 1u);
if (is_scene_lut) {
  let vlog_encoded = linear_to_vlog(composite_rgb_linear);   // 线性→V-Log 对数空间
  let lut_color = sample_lut_tetrahedral(vlog_encoded);
  base_srgb = mix(default_tonemapped, lut_color, adjustments.global.lut_intensity);
}
```
- 输入是**线性色彩**（经前述曝光/影调/色彩分级后的 `composite_rgb_linear`），先经 `linear_to_vlog`（`shader.wgsl:248-254`，近似 V-Log 编码）进入对数/场景空间，再查表，结果与「默认色调映射输出」按 `lut_intensity` 混合。
- 语义：**胶片模拟**——在场景线性域施加「底片响应」，之后才做显示色调映射。内置胶片模拟（SpektraFilm）默认走此路径（`useEditorActions.ts:146`）。
- **注意（局限性）**：① `linear_to_vlog` 是全仓**唯一**的对数编码函数，无 S-Log / N-Log / F-Log 可选——若 LUT 期望相机 log 输入，此处喂入的是 V-Log 信号，会偏色；② 该分支把「V-Log 域 LUT 输出」与 sRGB 的 `default_tonemapped` **直接 `mix`**，属于跨空间混合，并非严格的「替换式」log→look 变换（详见 §8）。

### 5.2 显示参照（Display-Referred，`==0`）—— 曲线之后

```1959:1962:src-tauri/src/shaders/shader.wgsl
if (adjustments.global.has_lut == 1u && adjustments.global.lut_is_scene_referred == 0u) {
  let lut_color = sample_lut_tetrahedral(final_rgb);          // 已是显示/sRGB 空间
  final_rgb = mix(final_rgb, lut_color, adjustments.global.lut_intensity);
}
```
- 输入是**显示域**（`final_rgb` 已 tonemap + 胶片曝光 + 曲线），直接查表后按 `lut_intensity` 混合。
- 语义：常规「调色 Look」——在最终像素上叠加风格化，自定义 `.cube` 默认走此路径。

### 5.3 强度

`lut_intensity ∈ [0,1]`（UI 0–100 → `image_processing.rs:2200` `/100`）。`mix(base, lut, intensity)`：`0`=原样，`1`=全量 LUT。

## 6. 预览一致性

- 选中 LUT 后字段进入编辑 JSON，GPU 渲染（桌面 `WgpuDisplay`、安卓 2D Canvas，见 000003）经同一 `shader.wgsl` 路径应用 → **所见即所得**。
- `generate_lut_previews` 用 `lutIsSceneReferred: request.is_built_in` 在缩略图上分别走两种时机，保证色卡与正式渲染一致。

## 7. 与 FotLab 的关系 / 复用价值

1. **可整体复用的 LUT 子系统**：`lut_processing.rs`（cube/3dl/hald 解析 + 缓存 + 路径隔离）、`gpu_processing.rs` 的 f16 3D 纹理上传、`shader.wgsl` 的四面体采样与「场景/显示」双模式——是高质量、可移植的实现，建议抽成共享 Rust crate，供 RapidRAW 与 FotLab Studio 共用。
2. **`lut_is_scene_referred` 的双模式是核心经验**：胶片模拟必须在**场景线性域（经 log 编码）**施加再 tonemap，普通 Look 在显示域施加。FotLab 做 LUT 支持时必须保留这一区分，否则胶片类 LUT 会偏色/对比异常。
3. **UI 与前端绑定**：`LUTControl` 嵌在 React `Effects.tsx`。按 000003 档③，原生 Compose 前端需重做 `LUTControl` 交互，但**后端 `lut_processing.rs` 与着色器可原样复用**（管线与前端解耦）。
4. **安全边界可借鉴**：`parse_lut_file` 拒绝 UNC/遍历、安卓 `content://` 内存解析、删除路径隔离——FotLab 处理用户导入资源时应采用同等约束。

## 8. 相机 Log LUT（S-Log / N-Log / F-Log）的适配局限与对策

### 8.1 现状：仅 V-Log，无 S/N/F-Log

全仓搜索 `slog|nlog|flog` 仅命中德语 `de.json` 误报，无真正实现；着色器里唯一的对数编码函数是 `linear_to_vlog`（`shader.wgsl:248-254`，近似 Panasonic V-Log）。

- **场景参照路径把 log 曲线写死成 V-Log**：`linear_to_vlog(composite_rgb_linear)` 之后才查表，无法选择 S-Log / N-Log / F-Log。
- **自定义导入 LUT 默认走显示参照**：`lut_is_scene_referred` 由 `isBuiltIn` 派生（`useEditorActions.ts:146`、默认值 `adjustments.ts:580`），仅为内置胶片模拟置 1；`LUTControl` 的 props 与 `Effects.tsx` 中**均无 UI 开关**可把导入的 `.cube` 切到场景参照。即你的 S/N/F-Log LUT 必然落在「已 tonemap 成 sRGB 的画面」上叠加（§5.2），与它们期望的「相机 log 编码输入」错位。

### 8.2 工作空间说明

RAW 经 imgop/rawler 解码为**线性场景参照**，§5 的全部影调/色彩分级都在线性域完成，末尾才 `linear_to_srgb` / AGX tonemap。因此 RapidRAW 内部**不产出任何相机 log 信号**——既没有「解码到 S-Log」的概念，也不在管线里保留 log 中间态。

### 8.3 对「为 S-Log / N-Log / F-Log 设计的 LUT」的含义

这类 LUT 多为**转换型 LUT**（相机 log 输入 → 目标风格/显示），依赖特定 log 编码作为查表输入。在 RapidRAW 当前实现下直接导入会偏色/无效，原因有二：（a）场景参照路径喂的是 V-Log 而非 S/N/F-Log；（b）自定义 LUT 默认在 sRGB 上叠加，与 log 输入假设错位。

### 8.4 两条对策

- **方案 A（无需改码，推荐先用）—— 烘焙为 display LUT**：在 LUT 制作工具中，将你的 LUT 前接「S-Log→线性」、后接「线性→sRGB」，合并成一个吃 **sRGB 进、出 sRGB** 的创意 LUT，再导入 RapidRAW 保留 display 模式（§5.2）。即业界标准的「把 IDT/ODT 烘进 LUT」，与 RapidRAW 的显示参照分支完全契合。
- **方案 B（改 RapidRAW，真正的 log→LUT 工作流）**：在 `shader.wgsl` 增加 `linear_to_slog / linear_to_nlog / linear_to_flog`；给 `LUTControl.tsx` / `Effects.tsx` 增加 log 曲线下拉选择器，使自定义 LUT 支持 `lut_is_scene_referred=1` 并选定曲线；顺手修复 §5.1 的跨空间 `mix`（应让 LUT 输出再过一次 tonemap 再混合，保持空间一致）。属中量级的 WGSL+Rust+TS 改动。

### 8.5 对 FotLab 的启示

若 FotLab 计划支持「相机 Log + LUT」工作流，应**原生提供 S-Log / N-Log / F-Log 等多种 IDT/log 编码选择器**（而非仅 V-Log），并允许每个导入 LUT 单独声明其输入 log 空间与场景/显示参照模式；同时避免 RapidRAW 这种 sRGB 与 log-LUT 输出跨空间混合的实现瑕疵。

## 关键文件索引

| 关注点 | 文件:行 |
| --- | --- |
| LUT UI 组件 | `src/components/ui/LUTControl.tsx` |
| UI 挂载点（Effects 面板） | `src/components/adjustments/Effects.tsx:342-351` |
| 选择回调（写编辑 JSON） | `src/hooks/useEditorActions.ts:131-157` |
| 导入（含安卓 content URI） | `src/components/ui/LUTControl.tsx:142-197` |
| 编辑 JSON 字段/默认 | `src/utils/adjustments.ts:79-84,575-580` |
| 支持扩展名 | `src/components/ui/LUTControl.tsx:36` |
| cube/3dl/hald 解析 | `src-tauri/src/lut_processing.rs:184-349` |
| 路径安全校验 | `src-tauri/src/lut_processing.rs:351-428` |
| 解析+缓存入口 | `src-tauri/src/lut_processing.rs:475-485` |
| list/import/remove 命令 | `src-tauri/src/lut_processing.rs:487-623` |
| 预览渲染 | `src-tauri/src/lut_processing.rs:625-712,714-723` |
| GPU 上传为 f16 3D 纹理 | `src-tauri/src/gpu_processing.rs:1275-1304` |
| dummy LUT 纹理 | `src-tauri/src/gpu_processing.rs:1016-1021` |
| 着色器绑定（lut_texture/sampler） | `src-tauri/src/shaders/shader.wgsl:203-204` |
| AllAdjustments LUT 字段 | `src-tauri/src/shaders/shader.wgsl:71-74`；`src-tauri/src/image_processing.rs:1510-1513` |
| JSON→AllAdjustments 填充 | `src-tauri/src/image_processing.rs:2193-2212` |
| 四面体采样 | `src-tauri/src/shaders/shader.wgsl:1505-1564` |
| 场景参照应用（tonemap 前） | `src-tauri/src/shaders/shader.wgsl:1926-1934` |
| 显示参照应用（曲线后） | `src-tauri/src/shaders/shader.wgsl:1959-1962` |
| linear_to_vlog 编码 | `src-tauri/src/shaders/shader.wgsl:248-254` |
