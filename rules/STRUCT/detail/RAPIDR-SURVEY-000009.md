# 调研 — RapidRAW 后端管线（Rust/Tauri）与 WebView（前端）之间的数据传递机制

- ID: RAPIDR-SURVEY-000009
- Status: Draft
- Priority: P2
- Created: 2026-09-18
- Owner: —
- Related: `rules/STRUCT/detail/RAPIDR-SURVEY-000003.md`（屏幕适配/加速渲染）、`rules/STRUCT/detail/RAPIDR-SURVEY-000007.md`（非 RAW 解码与色彩管理）、`rules/STRUCT/detail/RAPIDR-SURVEY-000001.md`（Android/WebView 适配）、`rules/STRUCT/detail/RAPIDR-SURVEY-000004.md`（处理管线）、`rules/STRUCT/detail/RAPIDR-SURVEY-000005.md`（LUT）、`rules/STRUCT/detail/RAPIDR-SURVEY-000006.md`（内存表示）

> **范围声明**：基于 `external/RapidRAW` 的 shallow clone（`--depth 1`，`v1.6.4`）。只做文档化，不改上游。聚焦：RapidRAW 的后端管线与 WebView 之间如何传递数据？主预览、裁剪预览、缩略图、各功能模块各走什么通道？Android 与桌面有何差异？对 FotLab 的启示。

## TL;DR（结论先行）

| 问题 | 结论 |
| --- | --- |
| 后端持有状态是否跨端 | **不跨端**。`AppState` 持有原始图 `Arc<DynamicImage>`、`GpuContext`、各级缓存，从不上传 IPC；前端每次 `invoke` 把 `adjustments` JSON 作为参数传入，后端用自己持有的原图渲染后只回传**成品像素/结果**。 |
| 主预览（桌面开 GPU 渲染） | **不传字节**。WGPU 直接 `present()` 到原生窗口 `wgpu::Surface`，WebView 是透明覆盖层；后端仅回 `WGPU_RENDER` 标记 + 发 `wgpu-frame-ready` 事件（`lib.rs:580-591`）。 |
| 主预览（安卓/Linux/关渲染） | GPU 纹理**读回 CPU → mozjpeg 编码 JPEG 字节 → IPC `Vec<u8>` → 前端 `Blob` → `blob:` URL**（`gpu_processing.rs:213-214,1684` + `lib.rs:593-653` + `useImageProcessing.ts:178-260`）。 |
| 裁剪预览 | 后端 base64 成 `data:image/jpeg;base64,...` **字符串**直接返回，前端当 `src` 用（`lib.rs:903-911` + `useImageProcessing.ts:315-319`）。 |
| 缩略图 / 临时文件 | 后端写盘 → 发事件带路径 → 前端 `convertFileSrc(path)` 转 `tauri://localhost/...` **资产协议 URL**（`useTauriListeners.ts:105-114`）。 |
| 其余模块（预设/HDR/接片/联机/连拍） | 统一返回 JPEG/PNG **字节或 base64** → 前端 `Blob`（`PresetsPanel.tsx:714`、`CollageModal.tsx:155`、`CullingView.tsx:266`、`TetheringPanel.tsx:309`、`lib.rs:1257-1258`）。 |
| 拖动热路径优化 | 拖动滑块时后端只回**可见子矩形 patch**（24 字节 `u32 LE` 头 + JPEG），前端 `DataView` 解析定位，避免传全图（`lib.rs:617-632` + `useImageProcessing.ts:208-232`）。 |
| Android 差异 | 强制 `surface_opt=None`（无原生呈现），主预览走 A2 字节回传；仅「文件 I/O」层多一道 JNI/Content-URI 转换（`android_integration.rs`）。 |

## 1. 总体架构：状态不跨端，只有「像素/结果」跨端

后端 `AppState`（`app_state.rs`）持有 `original_image: Arc<DynamicImage>`、`gpu_context`、`GpuImageCache`、各类取消令牌——这些**从不在 IPC 上传输**。前端用 Zustand store 维护 `adjustments` 等参数，每次 `invoke` 把参数作为命令参数传过去；后端用自己持有的原图 + GPU 上下文处理，再把**成品像素/结果**回传。

一次编辑预览的往返：

```
前端(adjustments JSON) ──invoke──▶ 后端(用原始图+GPU渲染) ──bytes/事件──▶ 前端(<img src=blob>)
```

Tauri 的命令入口在 `src-tauri/src/lib.rs`，标注 `#[tauri::command]`；前端经 `@tauri-apps/api` 的 `invoke()` 调用，二进制响应在 JS 侧得到 `ArrayBuffer`。

## 2. 机制 A：主预览（两条互斥路径）

### 2.1 A1 — 桌面 `use_wgpu_renderer=true`（默认 Win/macOS）：直接渲染到原生表面，不传字节

`apply_adjustments`（`lib.rs:741`）在 GPU 处理完成后，若 `use_wgpu_renderer` 为真：

```580:591:external/RapidRAW/src-tauri/src/lib.rs
if let Ok(final_processed_image) = final_processed_image_result {
    if use_wgpu_renderer {
        let _ = context.device.poll(wgpu::PollType::Wait {
            submission_index: None,
            timeout: Some(std::time::Duration::from_millis(500)),
        });
        let _ = app_handle.emit(
            "wgpu-frame-ready",
            serde_json::json!({ "path": loaded_image.path }),
        );
        return Ok(b"WGPU_RENDER".to_vec());   // 不返回图像字节
    }
    ... // 否则编码成 JPEG 字节返回
}
```

此处 **WGPU 直接 `present()` 到窗口的 `wgpu::Surface`**（`gpu_processing.rs:140` `output.present()`），WebView 为透明覆盖层叠在其上。后端只回一个 `WGPU_RENDER` 标记 + 发 `wgpu-frame-ready` 事件，前端据此知道「新帧已上屏」，**主预览图像字节完全不进 WebView DOM**。

### 2.2 A2 — Android / Linux / 桌面关渲染：GPU 读回 → 编码 JPEG → 字节走 IPC

关键事实：**Android 与 Linux 上 `surface_opt` 被强制为 `None`**（无原生呈现表面）：

```213:214:external/RapidRAW/src-tauri/src/gpu_processing.rs
#[cfg(any(target_os = "android", target_os = "linux"))]
let surface_opt: Option<wgpu::Surface> = None;
```

同样的 `#[cfg(...)]` 把 `display_opt` 也置 `None`（`gpu_processing.rs:422-423`）。因此 `output_to_display=false`，GPU 把结果纹理**读回到 CPU buffer**（`gpu_processing.rs:1684` `read_texture_data_roi`），再走编码分支（`lib.rs:593-653`）：用 `mozjpeg_rs` 编码；整图直接返回 JPEG `Vec<u8>`，拖动时先写 24 字节头（patch X/Y/W/H + 全图 W/H 的 `u32 LE`）再接 JPEG 字节。

前端收到 `ArrayBuffer` 后：

```178:235:external/RapidRAW/src/hooks/useImageProcessing.ts
const buffer: ArrayBuffer = await invoke(Invokes.ApplyAdjustments, {
    jsAdjustments: payload, isInteractive: dragging, targetResolution, roi,
    requestAnalytics, computeWaveform, activeWaveformChannel,
});
...
if (prefix === 'WGPU_RENDER') { ...; return; }   // A1 路径直接返回，不建 blob
...
const blob = new Blob([buffer], { type: 'image/jpeg' });
const url = URL.createObjectURL(blob);
setEditor({ finalPreviewUrl: url });   // ImageCanvas 用它做 <img src>
```

即 **Android 主预览也是「后端读回 + JPEG 字节 + blob URL」**，与桌面关渲染时的回退路径一致（印证 `000003` §5.3「安卓走字节回退」）。

## 3. 机制 B：裁剪预览 —— 直接 base64 data URL

`generate_uncropped_preview`（`lib.rs:780`）把 JPEG 字节 **base64 后拼成字符串**返回：

```903:911:external/RapidRAW/src-tauri/src/lib.rs
let base64_str = general_purpose::STANDARD.encode(&bytes);
let data_url = format!("data:image/jpeg;base64,{}", base64_str);
return Ok(data_url);
```

前端直接 `setEditor({ uncroppedAdjustedPreviewUrl: dataUrl })`（`useImageProcessing.ts:315-319`），无需 Blob——裁剪遮罩层直接 `src={dataUrl}`（`ImageCanvas.tsx:3417`）。

## 4. 机制 C：缩略图 / 临时文件 —— 资产协议（`convertFileSrc`）

缩略图不在命令返回值里，而是后端**写盘后发事件**，前端用 `convertFileSrc` 把路径转成 `tauri://localhost/...` 资产 URL：

```105:114:external/RapidRAW/src/hooks/useTauriListeners.ts
const { path, thumbnailPath, previewPath, ... } = event.payload;
if (thumbnailPath && previewPath) {
    thumbnailBuffer.current[path] = convertFileSrc(thumbnailPath.replace(/\\/g, '/'));
    mediumThumbnailBuffer.current[path] = convertFileSrc(previewPath.replace(/\\/g, '/'));
    refs.current.markGenerated(path);
}
```

`save_temp_file`（`lib.rs:1212`）是「字节 → 临时文件 → 返回路径」的桥，前端拿到路径再做 `convertFileSrc`（如 `CommunityPage.tsx:84`）。这是 Tauri 的 **asset protocol**：WebView 通过受控 `tauri://` scheme 直接读本地文件，避免把整张图塞进 IPC。

## 5. 机制 D：其余功能模块 —— JPEG/PNG 字节或 base64

- **预设预览 / 社区预设 / 连拍(Culling) / 接片(Collage) / 联机(Tethering)**：均返回 `Vec<u8>` JPEG，前端统一 `new Blob([new Uint8Array(bytes)], {type:'image/jpeg'})` → `URL.createObjectURL`（`PresetsPanel.tsx:714`、`CollageModal.tsx:155`、`CullingView.tsx:266`、`TetheringPanel.tsx:309`）。
- **HDR 合成预览**：返回 **PNG base64 data URL**（`lib.rs:1257-1258` `data:image/png;base64,...`）。
- **Collage 保存**：接收前端传来的 `data:image/png;base64,...` 字符串，后端 base64 解码回字节再写盘（`lib.rs:1320-1329`）。

## 6. 拖动热路径优化（ROI patch 而非整图）

A2 路径下，拖动滑块时后端只回**可见子矩形 patch**：`lib.rs:617-632` 把 `patchX/Y/W/H + fullW/H` 以 `u32 LE` 写入响应头，前端用 `DataView` 解析头并定位 patch（`useImageProcessing.ts:208-232`），避免每次拖动都传全分辨率图。全图（非拖动）则整段 JPEG 直接当 blob。

## 7. Android 专属补充

- **源文件读取**：Android 用 Content URI，后端走 JNI（`android_integration.rs` `read_android_content_uri`、`resolve_android_content_uri_name`）把 `content://` 读成字节再喂 loader；保存走 `save_image_bytes_to_android_gallery` 等 JNI 写 MediaStore。
- **预览像素的跨端传递仍是机制 A2**（GPU 读回 → JPEG 字节 → blob），与桌面关渲染时完全相同；只是「文件 I/O」这一层多了一道 JNI/Content-URI 转换。`surface_opt` 的强制 `None` 决定了 Android 永远走字节回传，不存在 A1 原生表面路径。

## 8. 通道汇总

| 通道 | 触发条件 | 后端→WebView 载体 | 典型用途 |
| --- | --- | --- | --- |
| A1 原生表面 | 桌面开 GPU 渲染 | **不传字节**（WGPU `present` + `wgpu-frame-ready` 事件） | 桌面主预览 |
| A2 字节回传 | 安卓/Linux/关渲染 | `Vec<u8>` JPEG → IPC `ArrayBuffer` → `Blob` → `blob:` URL | 安卓主预览、ROI patch |
| B data URL | 裁剪层 | `data:image/jpeg;base64,...` 字符串 | 未裁剪预览 |
| C 资产协议 | 缩略图/临时文件 | 路径 → `convertFileSrc` → `tauri://` URL | 库缩略图、临时图 |
| D 字节/base64 | 功能模块 | `Vec<u8>` 或 base64 | 预设、HDR、接片、联机、连拍 |

## 关键文件索引

| 主题 | 位置 |
| --- | --- |
| Tauri 命令入口（apply_adjustments 等） | `src-tauri/src/lib.rs:741` |
| 主预览 A1：`WGPU_RENDER` 直接上屏分支 | `src-tauri/src/lib.rs:580-591` |
| 主预览 A2：JPEG 编码 + ROI 头回传 | `src-tauri/src/lib.rs:593-653` |
| 裁剪预览 B：base64 data URL | `src-tauri/src/lib.rs:903-911` |
| 临时文件桥（save_temp_file） | `src-tauri/src/lib.rs:1212` |
| 接片保存（base64 入参解码） | `src-tauri/src/lib.rs:1320-1329` |
| HDR：PNG base64 data URL | `src-tauri/src/lib.rs:1257-1258` |
| GPU 上下文初始化（surface 创建） | `src-tauri/src/gpu_processing.rs:145-211` |
| Android/Linux 强制 `surface_opt=None` | `src-tauri/src/gpu_processing.rs:213-214, 422-423` |
| GPU 读回（`read_texture_data_roi`） | `src-tauri/src/gpu_processing.rs:1684` |
| WGPU `present()` 到原生表面 | `src-tauri/src/gpu_processing.rs:140` |
| 前端：apply_adjustments 调用 + blob 化 | `src/hooks/useImageProcessing.ts:178-260` |
| 前端：`WGPU_RENDER` 前缀识别 | `src/hooks/useImageProcessing.ts:197-205` |
| 前端：ROI patch `DataView` 解析 | `src/hooks/useImageProcessing.ts:208-232` |
| 前端：裁剪预览 data URL 直接使用 | `src/hooks/useImageProcessing.ts:315-319` |
| 缩略图事件 + `convertFileSrc` | `src/hooks/useTauriListeners.ts:105-114` |
| 各模块 blob 化（预设/接片/连拍/联机） | `src/components/panel/right/PresetsPanel.tsx:714`、`src/components/modals/CollageModal.tsx:155`、`src/components/panel/library/CullingView.tsx:266`、`src/components/panel/right/TetheringPanel.tsx:309` |
| Android JNI 文件 I/O | `src-tauri/src/android_integration.rs` `read_android_content_uri`、`save_image_bytes_to_android_gallery` |

## 与 FotLab 的关系 / 备注

- RapidRAW 的「状态留后端、只传像素」模型很适合桌面端直接 GPU 上屏；但 **FotLab 若要跨平台（尤其 Web/移动端），A1 原生表面路径不可用**，必须走 A2 字节回传——这恰好是其默认回退路径，可复用。
- **资产协议（C）与 data URL（B）** 是 WebView 显示本地/内嵌图像的标准做法，FotLab 若基于 Web 技术栈（WebView/Tauri）可直接借鉴 `convertFileSrc` 模式，避免大图走 IPC 序列化。
- 拖动时的 **ROI patch 优化** 是高价值参考：用「24 字节头 + 子矩形 JPEG」替代全图回传，显著降低交互延迟；FotLab 做实时调色预览时应采用类似分块策略。
- Android 的「文件 I/O 走 JNI/Content-URI」是平台特例，与像素传输解耦——FotLab 在 Android 集成时只需替换该层，预览通道（A2）可保持一致。
