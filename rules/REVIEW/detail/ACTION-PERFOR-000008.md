# 渲染后端路线评估 — WebView/RapidRAW 不成立；真正的对应物是 GPU，但受 minSdk 26 约束

- ID: ACTION-PERFOR-000008
- Status: Observation
- Priority: P2
- Created: 2026-09-21
- Owner: —
- Related: `rules/REVIEW/detail/ACTION-PERFOR-000001.md`（总览）、`ACTION-PERFOR-000003.md`（降分辨率，收益可替代一部分 GPU 收益）、`ACTION-PERFOR-000005.md`（缓存已显影 buffer，是 GPU 常驻纹理的 CPU 侧对应物）、`rules/REVIEW/detail/ACTION-ROLLBK-000001.md`（架构变更须人工确认）

## Background & Goal

触发这次调研的问题是："除了把 RapidRAW 的网页渲染直接在 Android 的 WebView 里绘制，还有什么办法能加速渲染管线？" 本条目专门回答这一问——先判断 WebView 方案是否成立，再列出它在 Android 上真正的对应物。

## Finding

### 1. RapidRAW 快的原因不是"它是网页"，而是它用 GPU

RapidRAW 的技术栈是 Tauri + Rust + **wgpu**：Web 前端只是 UI 壳。它做到"拖动参数实时可见"的机制是

```
解码一次 → 线性数据上传为 GPU 纹理并保持常驻 → 每次调参只改 shader uniform → 重绘
```

即：**全分辨率只在首次解码时付一次代价，之后的参数变化是 <16 ms 的一次 draw call**，不存在"改一次参数重跑一遍 CPU 全图"这件事。把"网页"误认为原因，会得到错误的迁移方案。

### 2. 照搬进 WebView 会反向增加一个数量级的搬运

若现在的 native 管线照旧，只是把最后一跳换成 WebView：

- 像素必须先从 native 进 WebView 一侧 —— 可选通道只有 blob URL / base64 data URI / `MessagePort`，每一种都是一次**全量拷贝**（base64 还额外膨胀 33%）；
- 再额外背一个 WebView 进程的内存开销与 GPU 上下文；
- 它要解决的本项目瓶颈（`ACTION-PERFOR-000002`：全分辨率 CPU 计算 + 数百 MB 的像素搬运）一个都没动，只是把最后一跳换了个地方。

**结论：WebView 不成立。** 它既不像 RapidRAW 那样有 wgpu 做底座，也不解决我们的瓶颈。

### 3. Android 上真正的对应物

| 路线 | 做法 | 约束 |
|---|---|---|
| **GLES3 着色器链**（最贴近 RapidRAW） | 解码后的线性数据一次上传为 `RGBA16F` 纹理（ES3 可 filter、可 render）；calibrate 的 3×3 矩阵 / boost / gamut / log / LUT 全部写成 fragment shader，渲到 FBO；每次调参 = 改 uniform + 一次 draw call。宿主用 Compose `AndroidView` 挂 `GLSurfaceView` / `TextureView`（`app/src/main/kotlin/io/github/fotlab/fotlab/ui/ZoomableImage.kt` 的缩放/平移逻辑可复用） | minSdk 26 下 **ES 3.0 本身可用**，但要自己写 GLES 代码；AGSL `RuntimeShader`(API 33) / `RenderEffect`(API 31) 用不了 |
| **LUT 走 `GL_TEXTURE_3D`** | ES 3.0 起支持 3D 纹理，硬件三线性插值 | 这一项的收益最确定：直接替掉 `external/RawAlchemyCpp/src/grading_fused.cpp` 里每像素 8 次 gather 的 CPU 插值 |
| **Vulkan** | `AHardwareBuffer` + `VK_ANDROID_external_memory_android_hardware_buffer` 做到 native↔GPU 零拷贝，compute shader 连 demosaic 都能上 GPU | 工作量大；minSdk 26 档的老设备驱动兼容性风险高 |
| 已废弃 / 不适用 | RenderScript（deprecated）、OpenCL（Android 上基本不可用）、NNAPI（任务形态不匹配） | — |

### 4. minSdk 26 是这条路的硬门槛

`app/build.gradle.kts:40` 是 `minSdk = 26`。因此：

- `Bitmap.wrapHardwareBuffer` — 需要 API **28**
- `android.os.SharedMemory` — 需要 API **27**
- `RenderEffect` — 需要 API **31**
- AGSL `RuntimeShader` — 需要 API **33**

minSdk 26 这条线本身在项目其它位置也已经反复约束过实现（例如 `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioEngine.kt:511` 明确写了不能用 API 33 的 `InputStream.readNBytes`）。

## Impact / Conflict

- **上 GPU 后端 = 架构变更**，按 `rules/REVIEW/detail/ACTION-ROLLBK-000001.md` 必须先经人工确认。
- **两条路互斥，需要人拍板**（总览 C1）：
  - 写原生 GLES3 —— 不抬 minSdk，但要把 developed 管线的每一段重写成 shader；
  - 抬 minSdk —— 换取更简单的实现（SharedMemory / HardwareBuffer / AGSL），代价是放弃一部分设备覆盖，属产品决策。
- GPU 路线与 `ACTION-PERFOR-000003`（降分辨率）、`ACTION-PERFOR-000005`（缓存已显影 buffer）的动机完全一致——都是让"全分辨率重算"只发生一次。差别在于：那两条仍在 CPU 上，成本低得多；GPU 路线是把整条管线换掉。**在打点数据出来之前，不应直接跳到 GPU 路线。**

## Recommendation

1. **不采纳 WebView 方案**，理由记录如上（增加搬运、不解决瓶颈、且拿不到 RapidRAW 的 wgpu 底座）。
2. GPU 路线的落地顺序应该是：先 `ACTION-PERFOR-000003` + `ACTION-PERFOR-000005`（让全分辨率只算一次），如果仍然不够，才评估 GLES3。
3. 其中 **`GL_TEXTURE_3D` 替掉 CPU LUT 插值**这一项收益最确定、范围最小（只动 grading 的 LUT 环节），可作为 GPU 路线的第一个试点。
4. 是否抬 minSdk：**纯产品决策，不由 Agent 侧决定**。

## Change History

- 2026-09-21 — 创建。结论：RapidRAW 的性能来自 wgpu/GPU（常驻纹理 + uniform），搬进 WebView 会在现有搬运上再加一跳（blob/base64 +33%），不成立。Android 上的对应物是自写 GLES3 着色器链或 Vulkan；AGSL `RuntimeShader`(33) / `RenderEffect`(31) / `HardwareBuffer`(28) / `SharedMemory`(27) 在 minSdk 26（`app/build.gradle.kts:40`）下全部不可用 → "自写 GLES3" 与 "抬 minSdk" 两条路需人工拍板。给出 `GL_TEXTURE_3D` 替 LUT 作为 GPU 路线的最小试点。
