# 调研 — RapidRAW 前端（WebView）UI 的适配策略，及「安卓专用 WebView」可行性

- ID: RAPIDR-SURVEY-000011
- Status: Draft
- Priority: P2
- Created: 2026-09-18
- Owner: —
- Related: `rules/STRUCT/detail/RAPIDR-SURVEY-000009.md`（后端↔WebView 数据传递）、`rules/STRUCT/detail/RAPIDR-SURVEY-000010.md`（原生 Kotlin UI 可行性，待写入）、`rules/STRUCT/detail/RAPIDR-SURVEY-000001.md`（Android/WebView 适配）、`rules/STRUCT/detail/RAPIDR-SURVEY-000007.md`（非 RAW 解码与色彩管理）

> **范围声明**：基于 `external/RapidRAW` 的 shallow clone（`--depth 1`，`v1.6.4`）。只做文档化，不改上游。聚焦两点：(1) RapidRAW 当前的 WebView UI 是「针对安卓有专门 UI」还是「自动响应适配」？(2) 在安卓上做一套「安卓专用 WebView UI」（移动优先前端）是否可行？对 FotLab 的启示。

## TL;DR（结论先行）

| 问题 | 结论 |
| --- | --- |
| 当前 UI 是自适应还是安卓特化？ | **混合体**：以响应式自适应为主（Tailwind 断点、`orientation: portrait` 媒体查询、viewport 元标签、触摸事件），但 JS 层有**显式 `osPlatform === 'android'` 分支**（隐藏标题栏、紧凑编辑器布局、Content URI 导入、内置库根目录、小缩略图递归库）。不是纯自适应，也不是另写一套独立安卓 UI。 |
| 安卓专用 WebView 是否可行？ | **高度可行（所有方案里风险最低）**。Tauri 安卓壳已承载 WebView，前端 `dist` 由 Vite 打包进 APK；命令/事件/资产协议层平台无关且已在安卓验证；最重的图像画布（蒙版/裁剪/笔刷）本就用 konva 跑在 WebView 内，专用 WebView 可零成本复用。 |
| 与「原生 Kotlin UI」(000010) 的关系 | 专用 WebView = 快/低险/复用 konva 画布，但 UX 上限止于「Web 质量」；原生 Kotlin = UX/性能最佳，但需重做整套编辑器（含 konva 画布）并桥接后端，工作量巨大。折中是「原生壳 + 仅编辑器画布嵌 WebView」。 |

---

## 1. 当前前端 UI：响应式自适应 + 安卓显式分支（混合）

RapidRAW 前端是 React（`src/App.tsx`、`main.tsx`、`.tsx` 组件），单一代码树跨平台共用，但既有通用响应式，也有安卓专属分支。

### 1.1 自适应（响应式）部分 —— 对任意屏幕自动生效

- **Tailwind CSS**：`styles.css:1` 的 `@import 'tailwindcss';`，组件大量使用 `md:`/`lg:` 断点类（`App.tsx`、`Editor.tsx`、`MainLibrary.tsx` 等 15 处命中），靠 flex/grid + 断点自动重排。
- **竖屏媒体查询**：`styles.css:355-401` 的 `@media (orientation: portrait)` —— 弹窗预览纵向堆叠、库选项菜单变全宽。这是**任何竖屏设备通用**的响应式规则，不专属安卓。
- **移动端 viewport**：`index.html:6` 设 `width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no`，并加触摸事件（`touch-action`/pointer）支持——对任意手机生效。

```6:6:external/RapidRAW/index.html
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
```

### 1.2 显式安卓分支 —— 仅 `android` 时触发

平台判定来自 Tauri 的 `platform()`（`TitleBar.tsx:32`），多处用 `osPlatform === 'android'` 走不同逻辑：

- **隐藏自定义标题栏**：`TitleBar.tsx:85-88` `if (!osPlatform || isMobile) return null;` —— 安卓上完全不渲染桌面那套窗口栏（安卓用系统导航栏）。

```85:88:external/RapidRAW/src/window/TitleBar.tsx
  const isMobile = osPlatform === 'android';
  if (!osPlatform || isMobile) {
    return null;
  }
```

- **专属紧凑编辑器布局**：`App.tsx:273-284` 定义 `ANDROID_COMPACT_MAX_WIDTH = 600`、`ANDROID_FULL_MIN_WIDTH = 1000`，据此算 `layoutMode`：安卓下 `width ≥ 1000` → `full`，否则竖屏且 `width < 600` → `compact`，其余 → `wide`。`layoutMode` 与 `isAndroid` 透传给 `Editor`/`MainLibrary`（`App.tsx:921, 956`）。即**安卓会切换到一套紧凑（竖屏友好）编辑布局**，与桌面不同。

```273:284:external/RapidRAW/src/App.tsx
  const isAndroid = osPlatform === 'android';
  const COMPACT_EDITOR_MAX_WIDTH = 900;
  const ANDROID_COMPACT_MAX_WIDTH = 600;
  const ANDROID_FULL_MIN_WIDTH = 1000;

  const isPortraitViewport = viewportSize.width > 0 && viewportSize.height > viewportSize.width;

  type LayoutMode = 'compact' | 'wide' | 'full';
  const layoutMode: LayoutMode = isAndroid
    ? viewportSize.width >= ANDROID_FULL_MIN_WIDTH
      ? 'full'
      : isPortraitViewport && viewportSize.width < ANDROID_COMPACT_MAX_WIDTH
        ? 'compact'
        : 'wide'
    : viewportSize.width < COMPACT_EDITOR_MAX_WIDTH
      ? 'compact'
      : 'wide';
```

- **安卓专属默认值**：`useAppInitialization.ts:132-134` 安卓默认缩略图 `Small`、库视图 `Recursive`，桌面是 `Medium`/`Flat`。
- **文件/库访问走 Content URI**：`useFileOperations.ts:294-358`、`useEditorActions.ts:133`、`useAppNavigation.ts:514-555` —— 安卓用 `resolve_android_content_uri_name` 解析 `content://` URI、用 `GetOrCreateInternalLibraryRoot` 取内置库根目录，而非桌面那套 `open({directory:true})` 文件夹对话框（移动端无此 API）。

### 1.3 小结

它**不是**「完全自动适应、对安卓无特殊处理」（否则不会写 600/1000 宽度阈值与 `compact` 布局、不会单独隐藏标题栏、不会单独处理 Content URI）；也**不是**「为安卓单独做了一套原生级 UI」（仍是同一套 React 组件树，加平台分支 + 响应式重排）。对 FotLab 的含义：若要做原生 Kotlin 重构，安卓端当前 UX（紧凑竖屏编辑、隐藏系统栏、Content URI 导入、内置库根、小缩略图递归库）正是需原生复刻的行为，且它由 `osPlatform === 'android'` + 视口宽度 `layoutMode` 驱动，与后端命令层解耦——原生可调用同一套后端并重做这套紧凑交互。

---

## 2. 新增调研：安卓专用 WebView 的可行性

### 2.1 定义

「安卓专用 WebView UI」= **保持 Tauri 安卓壳 + WebView + Rust 后端完全不动**，仅把前端换成一套**移动优先（mobile-first）的 Web UI**（为触摸/竖屏优化，如底部 Tab、手势导航、纵向编辑器），作为安卓构建的 `dist` 发出。**不改动任何 Rust**——只改前端。

### 2.2 可行性：高（所有方案里风险最低）

证据：

1. **安卓壳已承载 WebView，构建链路现成**。前端经 Vite 构建入 `dist/`，由 Tauri 打包进 APK：`tauri.conf.json:8` 的 `frontendDist: "../dist"`；`MainActivity.kt` 即 `TauriActivity`。无需改任何安卓原生/壳代码。

```8:8:external/RapidRAW/src-tauri/tauri.conf.json
    "frontendDist": "../dist",
```

2. **命令/事件/资产协议层平台无关，且已在安卓验证**。依赖含 `@tauri-apps/api`、`@tauri-apps/plugin-os`（`platform()` 检测）、`plugin-dialog` 等（`package.json:24-28`）；`tauri.conf.json:27-29` 已启用 `assetProtocol`（缩略图经 `convertFileSrc` → `tauri://`）。**当前这套「自适应 UI」今天就已经在安卓上构建并运行**——专用 WebView 只是在其之上换成移动布局，机制完全复用。

3. **最重的图像画布本就跑在 WebView 内**。蒙版/裁剪/笔刷等编辑画布用 konva / react-konva 实现（`package.json:34,42`）。这正是「原生 Kotlin 重构」(000010) 最该头疼、需重做的部分；而专用 WebView 把整块画布**零成本复用**。这是本方案相对原生路线最大的优势。

4. **平台检测已存在**。`platform() === 'android'` 在代码中可用（`TitleBar.tsx:32`），因此专用 UI 既可（A）在同一 `src/` 内运行时分支，也可（B）构建期切独立入口。

### 2.3 实现草图

- **方案 A（运行时分支，最简单）**：保留单一 `src/`，在 `App.tsx`/`useAppInitialization` 中当 `osPlatform === 'android'` 时渲染 `<MobileAppShell>`（底部 Tab、手势导航、纵向编辑器），复用全部 `invoke` 调用、Zustand store、组件与 i18n。改动最小、风险最低。
- **方案 B（构建期独立入口）**：Vite 配多入口，`npm run build -- --mode android` 产出移动端 `dist`。隔离更干净，但需保证共享的 Rust `invoke` 层被复用（经 `@tauri-apps/api` 天然满足）。
- **构建**：`npm run build` + `npm run tauri android build` 即得 APK。

### 2.4 优劣对比

| 维度 | 安卓专用 WebView | 原生 Kotlin UI (000010) |
| --- | --- | --- |
| 改动范围 | 仅前端 | 原生 UI + 后端桥接（隐藏 WebView 或 JNI） |
| 风险/工期 | 低/短 | 高/长 |
| 编辑器画布（konva） | 直接复用 | 需原生重做 |
| UX 上限 | Web 质量（WebView 限制） | 原生级（手势/120Hz/系统控件/触感） |
| 包体/内存 | 含 WebView + JS bundle，不可精简 | 可精简（若走 JNI 彻底去 WebView） |
| 原生 OS 集成 | 受限（分享面板、系统选图器等经 Tauri 插件） | 天然支持 |

**劣势（相对原生）**：仍是 WebView——启动/内存更重、缺少原生手感（滚动/物理/系统字体/ripple/触感）、难达 120Hz 丝滑、受 Android 系统 WebView 版本碎片化影响；APK 无法瘦身；原生系统能力（系统分享、全分辨率选图）受限。

### 2.5 折中：原生壳 + 仅编辑器画布嵌 WebView

「增加安卓专用 webview」也可读作：在**原生 Kotlin 壳**内，**只为图像编辑器画布嵌入一个专用 WebView**（因为 konva 画布在 WebView 内最成熟），其余（导航/库浏览/设置/弹窗）走原生；后端经桥（隐藏 WebView 的 JSBridge 或 JNI）驱动。这样拿到「原生外壳手感 + 成熟 WebView 画布 + 后端复用」，是工程上最务实的落点。

### 2.6 对 FotLab 的启示

- 若目标是**最快上线、最低风险**的移动端 RapidRAW，优先做「安卓专用 WebView UI」（方案 A/B），复用全部后端与 konva 画布；它比原生 Kotlin 路线便宜得多，且当前代码已证明 WebView 在安卓可用。
- 若目标是**原生级 UX**，则走 000010 原生 Kotlin + 桥接，或采用 2.5 的「原生壳 + 编辑器 WebView」折中，避免重做 konva 画布。
- 无论哪条路，**后端命令/事件/资产协议层无需改动**——它是平台无关的，已用 `tauri.conf.json:8,27-29` 与 `package.json:24-28` 验证可在安卓工作。前端的安卓分支（`App.tsx:273-284`、`useAppInitialization.ts:132-134`、Content URI 处理）可直接作为专用 WebView 的行为基线。

## 3. 待跟进

- [ ] 确认 `App.tsx:281-284` 的 `compact` 布局在安卓具体如何排布面板（是否需要补读 `Editor.tsx` 的 `layoutMode` 分支）。
- [ ] 评估 konva 画布在安卓 WebView 上的性能（大图蒙版/笔刷帧率），以确认「专用 WebView」是否够用。
- [ ] 若走原生壳 + 编辑器 WebView 折中，需原型验证后端桥（隐藏 WebView 的 `evaluateJavascript` 桥 vs JNI）在安卓的字节传输开销。
- [ ] 将「原生 Kotlin UI 可行性」正式写入 RAPIDR-SURVEY-000010（见 2.4 对比表所需）。
