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
