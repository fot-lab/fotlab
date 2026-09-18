# 调研 — RapidRAW 安卓端：Kotlin 极少、Rust 如何生成 App、交互优化与 KT 重写可行性

- ID: RAPIDR-SURVEY-000003
- Status: Draft
- Priority: P2
- Created: 2026-09-18
- Owner: —
- Related: `rules/STRUCT/detail/RAPIDR-SURVEY-000001.md`（Android 构建链路与渲染管线）、`rules/STRUCT/detail/RAPIDR-SURVEY-000002.md`（Android 导入/存储/非破坏性模型）、`rules/REVIEW/detail/FOTLAB-UNIFFI-000001.md`（FotLab 已规划的 UniFFI 0.28 Kotlin 绑定方向）、`rules/STRUCT/detail/FOTLAB-STUDIO-000001.md`（Studio 前端渲染，Coil/WebView 对照）

> **范围声明**：基于 `external/RapidRAW` 的 **shallow clone（`--depth 1`，`v1.6.4`）**。本调研只做文档化，不修改上游源码。聚焦：(1) Kotlin 很少很短，Rust 究竟如何「生成」安卓 App；(2) 当前安卓交互设计为何差、如何优化；(3) 是否/能否重写 KT 部分。

## TL;DR（结论先行）

| 问题 | 结论 |
| --- | --- |
| Kotlin 很少很短，Rust 如何生成 App | 运行时 Kotlin **只有 1 个文件 `MainActivity.kt`（58 行）**；其余 `.kt` 是 Gradle 构建脚本（`RustPlugin.kt`/`BuildTask.kt`）。Rust **不「生成」App UI**——Tauri 把 Rust 后端编成 `jniLibs/*.so`（cargo-ndk 交叉编译），安卓 App 本质是一个 **标准 Gradle + WebView 外壳**：`MainActivity`（继承 `TauriActivity`）加载 WebView，WebView 里的 React SPA 通过 Tauri IPC 调 Rust `.so`。交互 99% 在 React/TS 层，不在 Kotlin。 |
| 安卓交互差，如何优化 | 根因是「整 App 是 WebView + 大量 `isAndroid` 硬编码分支」。优化三档：① 保留 Tauri/WebView，补原生能力（预测返回手势、SAF 文件夹树浏览、分享面板、触觉反馈）+ 改 React 为响应式/触控优化（低风险、保跨平台）；② 用 Compose 做更厚的原生壳仍嵌 WebView（中）；③ 用 UniFFI 把 Rust 核心暴露给 **原生 Compose App** 整体重写安卓端（高投入、交互最佳，且契合 FotLab 的 UniFFI/Kotlin 方向）。 |
| 能否重写 KT 部分 | **能重写，但对交互质量几乎无帮助**——因为 Kotlin 只有 58 行且只做「WebView 壳 + 返回键桥接」。重写 KT 不等于改善交互；真正杠杆在 React 层（方案①）或换原生 Compose 前端（方案③）。 |

## 1. Rust 如何「生成」安卓 App（Kotlin 极少的事实）

### 1.1 Kotlin 文件清单（确实极少）

`src-tauri/gen/android` 子树下仅 **1 个运行时 `.kt`**：`app/src/main/java/.../MainActivity.kt`（58 行）。其余 Kotlin 是 Gradle 构建脚本：
- `buildSrc/.../RustPlugin.kt` — 为各 ABI 建 product flavor 与 `rustBuild<Arch>` 任务（见 000001）。
- `buildSrc/.../BuildTask.kt` — 执行 `tauri android android-studio-script --target <rust-target>`（cargo-ndk 编译）。

即安卓端**原生运行时代码 ≈ 58 行 Kotlin**。

### 1.2 `MainActivity.kt` 只做了三件事

```12:57:src-tauri/gen/android/app/src/main/java/io/github/CyberTimon/RapidRAW/MainActivity.kt
class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()                       // 1) 边到边
    ...                                     //    设置 window insets padding
  }
  override fun onWebViewCreate(webView: WebView) {
    ...
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        this@MainActivity.webView?.evaluateJavascript("window.__handleAndroidBack()", null)  // 2) 返回键→JS
      }
    })
  }
}
```

它只是 `TauriActivity` 子类：启用边到边、处理 insets、**把系统返回键桥接成 `window.__handleAndroidBack()` JS 调用**。没有任何业务/交互逻辑。

### 1.3 「生成 App」的真实机制（Rust → `.so` → WebView 壳）

1. `tauri android build` → `beforeBuildCommand: npm run build` 把 React 前端打包进 `../dist`（`tauri.conf.json:5-8`）。
2. Gradle 工程（`gen/android`）经 `rust` 插件 + `cargo-ndk`，把 `src-tauri/src/*.rs` 交叉编译为 **`jniLibs/<abi>/lib*.so`**（每个 ABI 一份），并入 APK。
3. 打包出的 App = **WebView（承载 React）+ Rust `.so`（Tauri IPC 后端）+ 58 行 Kotlin 壳**。

Rust 不是「生成 UI」，而是被编成**原生库**，由 WebView 壳加载并经由 Tauri 的 IPC/bridge 调用（见 000001 的管线调用链）。原生安卓依赖仅为 JNI 桥接所需：`ndk-context`/`jni`/`jni22`（`Cargo.toml:83-86`），以及跨平台 Tauri 插件 `dialog/fs/shell/os/process/single-instance`（`Cargo.toml:18-74`）——**无安卓专属 Tauri 插件**。

## 2. 安卓交互设计为何差（具体实证）

### 2.1 整 App 是 WebView → 原生交互能力缺失

所有 UI/手势/导航都在 React/WebView 内，因此**没有**：原生 Material You 主题、原生滚动/physics、触觉反馈、系统分享面板、原生 Predictive Back 手势、SAF 文件夹树浏览。

### 2.2 返回键 = JS bridge hack（脆弱）

原生返回键只是 `evaluateJavascript("window.__handleAndroidBack()")`；JS 侧 `useAndroidBackHandler.ts` 用**一长串 `if` 逐个关闭 modal**，最后伪造一个 `Escape` keydown：

```10:94:src/hooks/useAndroidBackHandler.ts
(window as any).__handleAndroidBack = () => {
  const ui = useUIStore.getState();
  if (ui.confirmModalState.isOpen) { ...; return; }
  if (ui.isCreateFolderModalOpen) { ...; return; }
  ... // 10+ 个 modal 状态逐一判断
  window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', ... }));
};
```

问题：无原生返回栈、不支持 Android 13+ Predictive Back（`OnBackInvokedDispatcher`）、modal 一多就脆弱、每次新增弹窗都要手动补 `if`。

### 2.3 安卓无原生文件夹浏览（重大 UX 缺口）

桌面用系统目录对话框选真实路径；**安卓 `handleOpenFolder` 直接跳过系统选择，改用内部 `.library` 根**：

```511:519:src/hooks/useAppNavigation.ts
const handleOpenFolder = useCallback(async () => {
    const isAndroid = osPlatform === 'android';
    if (isAndroid) {
      selectedPath = await invoke<string>(Invokes.GetOrCreateInternalLibraryRoot);  // 仅内部 .library
    } else {
      const selected = await open({ directory: true, ... });                       // 桌面：系统目录选择
    }
```

即安卓用户**无法浏览自己设备上的任意文件夹**，只能看导入进 `.library` 的内容（导入走 SAF `content://`，见 000002）。这与桌面体验严重不一致。

### 2.4 显示用 2D Canvas，且 `isAndroid` 分支散布（不一致/降级）

- 安卓显示走 **2D Canvas**（`ImageCanvas.tsx:2073` `getContext('2d')`，见 000001），无 wgpu 上屏；缩放/平移可能卡顿。
- 大量硬编码降级：`EditorView.tsx:138` `showZoomControls={!isAndroid}`（安卓隐藏缩放控件，依赖触控但 Canvas 未必针对触控优化）；`MainLibrary.tsx` 多处 `isAndroid` 走不同布局；`App.tsx:273-289` 用视口宽度手动算 `layoutMode`。约 20 个 TSX 文件各自 `isAndroid` 分支，属**应急式降级**而非自适应设计，导致体验不一致且难维护。

## 3. 如何优化 / 能否重写 KT

### 3.1 能否重写 Kotlin？——能，但治标不治本

Kotlin 运行时代码仅 58 行，重写成本极低；但**重写它不会改善交互**，因为交互逻辑全在 React/WebView 层。KT 唯一与交互相关的是返回键桥接与 insets，已是最简。结论：**「重写 KT」不是解决交互差的杠杆。**

### 3.2 优化方案（按投入/收益分三档）

**档① 保留 Tauri/WebView，smart 增强（低风险、保跨平台，推荐先做）**
- 原生能力补到 Kotlin + Tauri 命令：
  - **预测返回手势**：用 `OnBackInvokedDispatcher` 替 JS bridge，配合真正的导航栈（而非 10+ `if` 关 modal）。
  - **SAF 文件夹树浏览**：新增 Tauri 命令，用 `ACTION_OPEN_DOCUMENT_TREE` 让安卓用户浏览自有文件夹（当前缺失，是最大缺口）。
  - **系统分享面板**（`ACTION_SEND`）、**触觉反馈**（`Vibrator`）、**深色/动态色彩**。
- React 层改造：
  - 用**响应式/自适应组件**替换散布的 `isAndroid` 硬编码分支（统一布局，避免降级不一致）。
  - 触控优化：pointer events、捏合缩放、惯性滚动；评估安卓上改用 WebGL/更优 Canvas 路径缓解 2D canvas 卡顿。
  - 列表虚拟化、缩略图懒加载（安卓默认已用 Small 缩略图，`useAppInitialization.ts:133`）。

**档② 更厚的原生 Compose 壳仍嵌 WebView（中投入）**
- 用 Compose 提供原生导航/选择器/分享/主题，WebView 仍承载核心编辑 UI。收益有限（核心 UX 仍在 WebView）。

**档③ 原生 Compose App + Rust 核心（UniFFI）（高投入、交互最佳，且与 FotLab 方向一致）**
- 把图像/RAW 处理核心（rawler）经 **UniFFI 0.28** 暴露为 Kotlin 库（FotLab 已在 `FOTLAB-UNIFFI-000001` 规划 Kotlin 绑定约定），**整体用 Compose 重写安卓端**：原生手势、Material You、Predictive Back、SAF、GPU 渲染（Vulkan/AGSL 或 Compose `GraphicsLayer`）、原生分享。
- 代价：放弃 RapidRAW 的跨平台 React 前端，需重建安卓 UI；但可直接复用 RapidRAW 与 FotLab 共同的 Rust 核心（rawler），且对齐 FotLab «Studio / UniFFI» 战略。

### 3.3 建议

- 若目标是「快速改善 RapidRAW 安卓体验」：走**档①**（补 SAF 文件夹浏览 + 预测返回 + 响应式 React），改动局限于现有架构。
- 若安卓是**一等公民**、交互质量是硬指标，且 FotLab 本就要做 UniFFI/Kotlin：优先**档③**——以共享 Rust 核心 + 原生 Compose 重写安卓端，让 RapidRAW 的 Rust 管线在安卓上以原生体验呈现。单纯「重写那 58 行 Kotlin」不在推荐路径上。

## 4. 补充：Windows / Linux / macOS 也是 WebView 交互设计吗？

**是。三端同样基于 WebView，与安卓架构一致——都是「Tauri v2 + OS 原生 WebView 承载同一套 React SPA」。**

### 4.1 实证

- RapidRAW 是 **Tauri v2**（`Cargo.toml:17` `tauri = "2.11"`）。Tauri 的本质就是把前端 HTML/JS 塞进 OS 原生 WebView 引擎；不是某平台特例，而是其跨平台统一模型。
- **各平台 WebView 引擎**（由 Tauri 的 `wry` 封装）：
  - **Windows → WebView2**（基于 Edge/Chromium）
  - **macOS → WKWebView**（WebKit）
  - **Linux → WebKitGTK**（`Cargo.toml:93` `webkit2gtk = "=2.0.2"`）
  - **Android → 系统 WebView**（见 §1，且 `Cargo.toml:83-86` 的 `jni/ndk-context` 仅供 JNI 桥）
- **同一份前端**：`tauri.conf.json:8` `frontendDist: "../dist"` 跨所有平台共享；主窗口 `decorations: false, transparent: true`（`tauri.conf.json:20-21`），即标题栏由 React 自绘（`TitleBar.tsx`），而非系统窗管绘制。

### 4.2 桌面与安卓的差异只在「壳」，不在「交互模型」

前端除 `isAndroid` 外，也有 **`isMac/isLinux/isWindows` 分支**，但都只处理**原生窗体外观/输入**，而非另一套交互范式：
- `TitleBar.tsx:82-157`：mac 显示红黄绿交通灯、Linux 无拖拽区、Windows 加左侧占位——**标题栏样式**差异。
- `keyboardUtils.ts:342-384`：mac 用 `⌘`、Win/Linux 用 `Ctrl`；mac 的 `⌫` 作删除键——**快捷键/符号**差异。
- `useKeyboardShortcuts.ts:579`：mac 用 `Backspace` 作删除——同上。

即：**核心编辑/浏览/交互逻辑全平台同一套 React 代码**，平台分支仅修饰「窗口 Chrome、快捷键、交通灯」等。安卓的 `isAndroid` 分支之所以更刺眼，是因为移动视口被迫做了**布局降级**（隐藏缩放控件、改 `layoutMode`、禁用文件夹浏览），而非桌面那种「壳」层微调。

### 4.3 为什么「同样 WebView」，安卓体验更差、桌面却能接受？

关键不在 WebView 本身，而在**原生壳集成度与视口形态**：

| 维度 | 桌面（Win/mac/Linux） | 安卓 |
| --- | --- | --- |
| 窗口/标题栏 | 真实原生窗口 + 自绘标题栏（`decorations:false`） | 全屏 WebView，无原生窗体 Chrome |
| 系统对话框 | Tauri `dialog` 插件 → 真实 OS 文件/目录选择器 | 导入用 SAF `content://`；**文件夹浏览被禁用**（§2.3） |
| 返回/导航 | 系统窗口管理 + 键盘快捷键（原生） | 返回键靠 JS bridge hack（§2.2） |
| 视口 | 大屏，走完整 `full` 布局 | 小屏，被迫 `compact/wide` 降级分支 |
| 渲染 | WebView2/WKWebKit 性能强 | 2D Canvas 上屏（§2.4），可能卡顿 |
| 手势/物理 | 原生滚动/惯性（桌面鼠标/触控板） | 触控手势在 WebView 内，缺原生 Predictive Back/触觉 |

结论：**「WebView 交互」是 RapidRAW 全平台统一事实**，桌面体验可接受是因为原生窗体/对话框/快捷键/大视口补位；安卓暴露的问题（返回 hack、无文件夹浏览、布局降级、2D canvas）是**移动端原生集成缺失**所致，不是 WebView 模型独有。

### 4.4 对优化方案的影响

- 对**桌面**：档① 已足够（WebView 模型对桌面专业工具普遍成立，VS Code/Electron 类应用同理）；无需换原生。
- 对**安卓**：问题集中在移动端原生集成缺失，档① 的「补 SAF 文件夹浏览 + 预测返回 + 响应式 React」仍是低成本首选；若交互要达专业级，档③（UniFFI + Compose 原生）依然最优。
- 引申：**「重写 58 行 Kotlin」对安卓无效的结论，放到桌面同样成立**——桌面根本没有 Kotlin，交互也全在 React/WebView 层。真正的杠杆始终是 React 层增强（档①）或换原生前端（档③）。

## 5. 渲染与 WebView 交互的对接：参数（adjustment）变更后如何触发渲染

> 本节回答「调一个参数后，渲染是立刻触发、异步展示、还是别的什么方式」。结论：**都不是单纯的「立刻」或「异步轮询」，而是一套「事件驱动 + 防抖/拖动合并 + 异步 IPC 请求/响应 + 后端记忆化缓存 + 乱序保护」的组合机制**；且后端有两条不同的「上屏」路径（桌面走原生 GPU surface 直绘，Linux/安卓走 JPEG 字节回传）。

### 5.1 触发层（React/WebView 侧，`useImageProcessing.ts`）

调参不“立即”重渲，而是经过 **防抖 + 拖动合并**：

- **拖动滑块过程中（live）**：`applyAdjustments(adj, dragging=true)` 把请求写入 `pendingApplyRef.current`，再调 `flushPipeline`（`useImageProcessing.ts:294-307, 277-292`）。`flushPipeline` 限制**最多 3 个在途渲染**（`inFlightCountRef >= 3` 即丢弃），每完成一个用 `requestAnimationFrame` 排空下一个——即**拖动时只保留「最新待渲值」并串行限流**，避免每像素触发一次全图渲染。
- **提交（松手 / 离散控件）**：监听 `adjustments` 的 `useEffect`（`useImageProcessing.ts:421-497`）在**非拖动**时先 `setTimeout(…, 50)` **50ms 防抖**，再 `applyAdjustments(adj, false, targetRes)` 触发一次全分辨率渲染。
- **缩放高精渲染**：`requestHiFiZoom`（`useImageProcessing.ts:380-391`）对 zoom 级别变化再做 **50ms 防抖**，按 `calculateTargetRes` 重新选 `targetResolution`。
- 另有 `throttledUncroppedPreview`（30ms throttle，`useImageProcessing.ts:309-328`）为裁剪预览单独出一张未裁剪图。

### 5.2 传输：异步 IPC，不阻塞 UI 线程

每次渲染都是一次 **Tauri `invoke("apply_adjustments", …)`**（`useImageProcessing.ts:178-186`），前端 `await` 返回 `ArrayBuffer`。因为走 Tauri 命令的 Promise，**WebView 的 JS/UI 线程不被阻塞**——这是「异步」的部分，但**不是轮询/独立显示线程**，而是一次 request/response。

- 后端命令 `apply_adjustments`（`lib.rs:740-` `async fn`）接收 `js_adjustments / is_interactive / target_resolution / roi / request_analytics / compute_waveform`（`lib.rs:741-`）。
- **后端记忆化**：以 `transform_hash + preview_dim + interactive_divisor` 为键查 `state.cached_preview`（`lib.rs:393, 416-482`）；相同参数直接复用，**不再跑 GPU/CPU 渲染**。
- **交互期降质**：`is_interactive` 时按 `interactive_divisor` 用更小的预览图 + 更低 JPEG 质量（65–75），提交时用全图 + 94 质量（`lib.rs:405-409, 486-494`）。

### 5.3 两条「上屏」路径（关键差异）

`apply_adjustments` 渲染完成后，按 `use_wgpu_renderer` 分叉（`lib.rs:399-402, 580-591`）：

- **路径 A — 桌面（wgpu 原生 surface 直绘，`use_wgpu_renderer=true`）**：
  GPU 渲染**直接呈现到窗口的原生 wgpu surface**（surface 在 `gpu_processing.rs:183-195` 由 `app_handle.get_webview_window("main")` 创建），WebView 在图像区是透明叠层（呼应 §4.1 的 `transparent:true`）。命令随即 `device.poll(Wait, 500ms)`，`emit("wgpu-frame-ready", {path})`，并 `return b"WGPU_RENDER"`（仅 11 字节哨兵）——**不回传图像字节**。前端收到 `WGPU_RENDER` 前缀即清空交互 patch、不更新 `<img>`（`useImageProcessing.ts:199-205`）。即**桌面预览像素由 GPU surface 直接送到屏幕，绕开了 React DOM 图像**。
- **路径 B — Linux / 安卓（或关闭 wgpu）**：命令把结果 `DynamicImage` 编码为 **JPEG 字节**（交互期还会在头部加 24 字节 ROI patch 描述：patchX/Y/W/H + fullW/H，`useImageProcessing.ts:207-232`），通过 Tauri IPC 回传 `ArrayBuffer`；前端 `new Blob([buffer], {type:'image/jpeg'})` → `URL.createObjectURL` → 写入 `finalPreviewUrl`（整图）或 `interactivePatch`（仅 ROI 覆盖层，`useImageProcessing.ts:233-260`）。即**非桌面端用 blob URL 在 WebView 内 `<img>` 上屏**。

> 这意味着：**同一套 React 前端，在不同平台「图像到底怎么显示」的底层路径不同**——桌面是原生 GPU surface，安卓/Linux 是 JPEG blob。这与 §4 的「统一 WebView 壳、差异在壳」一致，但补上了一层「渲染上屏」的差异。

### 5.4 乱序 / 失效保护（为什么不是简单「异步显示最新」）

异步渲染可能后发先至。前端用**单调 jobId** 保护（`useImageProcessing.ts:174, 194, 237`）：
- 每次请求 `jobId = ++previewJobIdRef.current`；只有 `jobId >= latestRenderedJobIdRef.current` 的结果才被采用，旧在途结果直接丢弃；
- 同时校验 `currentPath === selectedImagePathRef.current`（切图则丢弃，`useImageProcessing.ts:192, 237`）；
- 旧的 `blob:` URL 在替换时延迟 `revokeObjectURL`（`useImageProcessing.ts:242-259`），避免闪烁与内存泄漏。

### 5.5 一句话回答用户原问

> 调参后渲染**不是「立刻同步」也不是「后台轮询展示」**，而是：**React 侧先防抖（提交 50ms）/ 拖动合并（最多 3 并发、rAF 排空）→ 异步 IPC 调 Rust `apply_adjustments` → 后端按 `transform_hash` 记忆化、交互期降质 → 桌面经 wgpu surface 直绘（仅回 `WGPU_RENDER` 哨兵），Linux/安卓回传 JPEG 字节由 WebView `<img>` 上屏 → 前端用 jobId + 路径做乱序/失效保护后「即时」替换显示**。

此机制与 000004（管线）、000005（LUT，消费 `ImageRgba32F` 线性输入）、000006（`Intermediate→DynamicImage::ImageRgba32F→Arc<DynamicImage>→Rgba16Float` 的内存/显存表示）共同构成「调参→渲染→上屏」的完整闭环。

## 6. 针对「当前显示屏幕」的加速渲染：分辨率/DPR→缩放上屏、ROI、与 demosaic/LUT 的先后

> 核心结论：**加速的「开关」是「把渲染分辨率压到与屏幕可见像素匹配」，且这一步发生在 demosaic 之后、LUT 之前**。demosaic 永远只跑一次全分辨率（产出 `original_image`），但昂贵的 GPU 色彩/LUT 管线只在「屏幕适配分辨率」上跑；放大时再按 zoom 提升分辨率（封顶原图），拖动时用 ROI 只渲可见块。安卓的「屏幕感」来自更低的基准预览分辨率与更小的缓存，而非独立代码路径。

### 6.1 显示屏分辨率是如何传入的？（无任何安卓专属 API）

- **来源是 DOM 容器，不是原生分辨率调用**：`useImageRenderSize`（`src/hooks/useImageRenderSize.ts:28-73`）用 `container.clientWidth/Height`（`getBoundingClientRect` 等价）测「图片视口 div」的 CSS 像素尺寸，再用 `ResizeObserver` 监听变化；按图像宽高比算出「适配该容器」的 `width/height/scale/offsetX/offsetY/containerWidth/containerHeight`。
- **写入 store**：`Editor.tsx:391` 调用该 hook，`handleDisplaySizeChange`（`Editor.tsx:285-301`）把 `size.width/height` 写进 `displaySize`，把含 `containerWidth/containerHeight/offsetX/offsetY` 的 `baseRenderSize` 写进 store。
- **DPR 另算**：`window.devicePixelRatio` 在 `calculateTargetRes` 与缩放处理里读取（`useImageProcessing.ts:349,353` 等），**没有**走 Tauri 的 `getCurrentWindow().innerSize()` 之类原生命令。
- **安卓即「全屏 WebView 容器 ≈ 屏」**：安卓 WebView 占满屏幕，容器尺寸就是屏幕 CSS 尺寸，乘 `devicePixelRatio` 即逻辑像素密度。所以**安卓分辨率是 WebView DOM 测量得到的，与桌面走同一套前端代码**，没有 `isAndroid` 分支去读屏幕物理分辨率。

### 6.2 如何「针对当前屏幕」加速？（屏幕适配分辨率）

`calculateTargetRes`（`useImageProcessing.ts:343-378`）算出的 `targetResolution` 即「本次渲染的目标边长」：

```
targetRes = max(displaySize.w, displaySize.h) * effectiveDpr * sharpnessFactor(1.25) * zoomMultiplier
targetRes = clamp(targetRes, 512, originalMaxRes)          // 封顶原图，最低 512
```

- 即**预览只渲染到「屏幕上可见像素数 × DPR × 1.25 锐化余量」**，几乎从不全传感器分辨率跑。
- 后端 `apply_adjustments` 把该值当 `preview_dim`：`preview_dim = target_resolution.unwrap_or(editor_preview_resolution)`（`lib.rs:397-398`）。
- **安卓专属默认值（加速的「屏适配」本质）**：`editor_preview_resolution` 安卓默认 **1280**（桌面 1920，`app_settings.rs:551-552`）、`high_res_zoom_multiplier` 安卓 **0.75**（桌面 1.0，`app_settings.rs:589-590`）、`image_cache_size` 安卓 **2**（桌面 5，`app_settings.rs:614-615`）。即移动端用更低基准分辨率 + 更小缓存来匹配弱 GPU/小内存——这是「按屏幕加速」在安卓的具体体现，而非独立渲染路径。

### 6.3 放大（zoom）时的渲染管线

- `handleZoomChange`（`useEditorActions.ts:351`）更新 `zoom` → 触发视口 reflow，`displaySize/baseRenderSize` 随之变。
- `useEffect`（`useImageProcessing.ts:393-419`）对 zoom 变化调 `requestHiFiZoom(finalRes)`（**50ms 防抖**，`useImageProcessing.ts:380-391`）：把 `targetResolution` 抬到更高（仍封顶原图），再 `applyAdjustments(..., false, targetRes)` → 后端 `generate_transformed_preview` 在**更高 `preview_dim`** 上重算整图 → GPU 全图色彩/LUT 一遍。即**放大 = 渐进提升渲染分辨率，上限原图，并非无限放大**。
- **拖动滑块且已放大时**：`calculateROI`（`useImageProcessing.ts:56-110`）在 `scale > 1.01` 时算「当前可见子矩形」（含 2px padding），作为 `roi` 传入；后端 GPU 只渲该 patch（见 6.4），前端收到 24 字节头 + JPEG 的 ROI patch（`useImageProcessing.ts:207-232`），写 `interactivePatch` 作为局部覆盖层。

### 6.4 ROI 在后端如何生效（部分渲染加速）

`process_and_get_dynamic_image_with_analytics` 内：

```1229:1236:src-tauri/src/gpu_processing.rs
        let bounds = request.roi.unwrap_or(Roi {
            x: 0, y: 0, width, height,
        });
        let out_width = bounds.width;
        let out_height = bounds.height;
```

即 **`roi` 存在时，GPU 仅对可见子区域执行整条色彩 pass（含 LUT）并回读该 patch**；无 `roi` 时跑整图。这是「拖动时只渲看得到的那块」的加速点。注意：`roi` 仅在 `is_interactive=true`（拖动）时由前端传入（`lib.rs:498-507`，`roi=None` 当非交互），故 ROI 局部渲染与「提交态整图渲染」互斥。

### 6.5 加速发生在 demosaic 前还是后？LUT 前还是后？

**答：分辨率压降（主加速）发生在 demosaic 之后、LUT 之前；ROI 局部渲染与 LUT 同处一个 GPU pass。**

- **demosaic 之后**：demosaic 由 rawler 在解码阶段一次性跑全分辨率，产出 `state.original_image`（`DynamicImage::ImageRgba32F`，见 000006）；`generate_transformed_preview`（`lib.rs:151-195`）拿到的 `loaded_image` 已是 demosaic 后的图。`compute_patched_and_warped`（`lib.rs:233-255`）在 `loaded_image.image`（即 post-demosaic）上做 warp/lens-blur，再做几何变换，最后 **`downscale_f32_image` 到 `preview_dim`**（`lib.rs:182-186`）。
- **LUT 之前**：降分辨率后的 `final_preview_base` 才喂给 GPU 色彩管线 `process_and_get_dynamic_image_with_analytics`（`lib.rs:437-438` → `fs_main` 含 WB/exposure/tonemap/**LUT**），见 000004/000005。**即 LUT 是在「已降采样到屏幕分辨率」的图上应用的**——加速（不去全分辨率跑）发生在 LUT 之前。后端 `state.cached_preview` 还按 `transform_hash+preview_dim+interactive_divisor` 记忆化（`lib.rs:416-482`），相同参数直接复用，进一步省去重渲。
- **没有「demosaic 前」的降采样**：RapidRAW **不在解码/demosaic 阶段就降分辨率**；demosaic 始终全分辨率跑一次（只为建 `original_image`）。真正的成本节省在于「昂贵的 GPU 色彩/LUT 管线永不在全分辨率上跑，只在屏幕适配分辨率上跑」。
- **ROI 与 LUT 的关系**：ROI 局部渲染发生在 GPU pass 内部，该 pass 同时完成色彩 + LUT；所以「局部渲染加速」与「LUT 应用」是同一趟，但「分辨率决策（降采样）」在其上游、LUT 之前。

### 6.6 一句话总结

> 屏幕加速 = **量屏定分辨率**（DOM 容器尺寸 × DPR × 1.25 × zoom，封顶原图；安卓用更低基准 1280/0.75× 与更小缓存）→ **demosaic 全分辨率只产一次 `original_image`** → 后端把图按 `preview_dim` **降采样（demosaic 后、LUT 前）** → GPU 色彩/LUT 只在屏幕分辨率上跑（记忆化复用）→ 放大时抬 `targetResolution` 渐进提清（封顶原图）、拖动时以 **ROI 只渲可见块**（与 LUT 同 pass）。整条链路**无安卓专属渲染分支**，平台差异仅体现在默认分辨率/缓存与「桌面 wgpu 直绘 vs 安卓 JPEG 回传」（见 §5.3）。

## 与 FotLab 的关系 / 备注

1. **共享 Rust 核心是桥梁**：RapidRAW 与 FotLab 都用 `rawler` 做 RAW 解码/处理。若 FotLab 走 UniFFI/Kotlin（档③），可**复用 RapidRAW 验证过的管线**，只换前端为原生 Compose——这正是 RapidRAW 当前 Kotlin 极薄所「暗示」的架构：原生壳越薄，换前端成本越低。
2. **交互差的root cause 对 FotLab 也是警示**：FotLab Studio 当前是 WebView/Coil 渲染（`FOTLAB-STUDIO-000001`）。若要在安卓给专业 RAW 编辑体验，迟早要面对「WebView 交互上限」，与档③一致。
3. **可借鉴的安卓原生补丁**：RapidRAW 的 JNI 桥（`android_integration.rs` 的 ContentResolver/LUT 缓存/MediaStore 导出）已是成熟的安卓原生能力样本，做档①/③ 时可直接参考其 `content://` 处理。

## 关键文件索引

| 关注点 | 文件:行 |
| --- | --- |
| 运行时 Kotlin 仅 MainActivity（58 行） | `src-tauri/gen/android/app/src/main/java/io/github/CyberTimon/RapidRAW/MainActivity.kt:1-58` |
| Gradle 构建脚本（Rust 插件/任务） | `src-tauri/gen/android/buildSrc/.../RustPlugin.kt`、`BuildTask.kt`（见 000001） |
| Tauri 构建/前端打包 | `src-tauri/tauri.conf.json:5-8` |
| 安卓 JNI/原生依赖 | `src-tauri/Cargo.toml:83-86`；跨平台插件 `Cargo.toml:18-74` |
| 返回键 JS bridge（原生侧） | `MainActivity.kt:52-56` |
| 返回键 JS bridge（前端侧，长 if 链） | `src/hooks/useAndroidBackHandler.ts:5-94` |
| 安卓无系统文件夹浏览（仅内部 .library） | `src/hooks/useAppNavigation.ts:511-519` |
| 安卓导入走 SAF content://（Tauri dialog） | `src/hooks/useFileOperations.ts:308-366` |
| 布局按视口手算 layoutMode | `src/App.tsx:273-289` |
| 安卓降级分支示例（隐藏缩放控件） | `src/components/views/EditorView.tsx:138`；`src/components/panel/MainLibrary.tsx`（`isAndroid`） |
| 安卓 2D Canvas 显示 | `src/components/panel/editor/ImageCanvas.tsx:2073`（见 000001） |
| 安卓默认 Small 缩略图/Recursive 视图 | `src/hooks/useAppInitialization.ts:133-134` |
| **渲染↔WebView 桥：调参触发渲染** | `src/hooks/useImageProcessing.ts`（见 §5） |
| 拖动合并 / 限流（max 3 在途, rAF 排空） | `src/hooks/useImageProcessing.ts:277-307` |
| invoke apply_adjustments + 结果解析（jobId/ROI patch/WGPU_RENDER） | `src/hooks/useImageProcessing.ts:112-275` |
| 提交 50ms 防抖 + live 拖动 | `src/hooks/useImageProcessing.ts:421-497` |
| zoom 高精 50ms 防抖 | `src/hooks/useImageProcessing.ts:380-391` |
| 后端命令 apply_adjustments（async，两条上屏路径） | `src-tauri/src/lib.rs:740-` |
| transform_hash 记忆化缓存 | `src-tauri/src/lib.rs:393,416-482` |
| wgpu surface 直绘：返回 WGPU_RENDER + emit wgpu-frame-ready | `src-tauri/src/lib.rs:580-591` |
| 桌面 wgpu surface 创建（get_webview_window） | `src-tauri/src/gpu_processing.rs:183-195` |
| JPEG 字节 / ROI patch 编码（非桌面回传） | `src-tauri/src/lib.rs:593-`（见 000006 §5） |
| **显示分辨率传入（DOM 容器测量，无安卓专属 API）** | `src/hooks/useImageRenderSize.ts:28-73` |
| displaySize/baseRenderSize 写入 store | `src/components/panel/Editor.tsx:285-301,391` |
| **屏幕适配分辨率 calculateTargetRes**（屏像素 × DPR × 1.25 × zoom，封顶原图） | `src/hooks/useImageProcessing.ts:343-378` |
| zoom 高精 50ms 防抖 + HiFi 重渲 | `src/hooks/useImageProcessing.ts:380-419` |
| ROI 计算（scale>1.01 时可见子矩形） | `src/hooks/useImageProcessing.ts:56-110` |
| 非桌面 ROI patch（24 字节头 + JPEG）覆盖层 | `src/hooks/useImageProcessing.ts:207-232` |
| **降采样点：generate_transformed_preview（demosaic 后、LUT 前）** | `src-tauri/src/lib.rs:151-195`（downscale `lib.rs:182-186`） |
| 几何/变换/warp（作用于 post-demosaic 图） | `src-tauri/src/lib.rs:233-255` |
| GPU 仅渲 ROI bounds（`roi.unwrap_or(full)`） | `src-tauri/src/gpu_processing.rs:1229-1236` |
| 安卓加速默认值（1280 / 0.75× / 缓存 2） | `src-tauri/src/app_settings.rs:551-552,589-590,614-615` |
| preview_dim = target_resolution ∥ editor_preview_resolution | `src-tauri/src/lib.rs:397-398` |
