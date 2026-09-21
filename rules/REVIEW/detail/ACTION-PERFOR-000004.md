# PNG 作为跨 FFI 的像素载荷 — 预览路径背负一次完整 deflate 与一次完整 inflate

- ID: ACTION-PERFOR-000004
- Status: Observation
- Priority: P1
- Created: 2026-09-21
- Owner: —
- Related: `rules/REVIEW/detail/ACTION-PERFOR-000001.md`（总览）、`rules/REVIEW/detail/ACTION-PERFOR-000002.md`（瓶颈归因）、`rules/REVIEW/detail/FOTLAB-RAWLER-000004.md`（解码一次 / RawlerImageLoaded）、`rules/REVIEW/detail/FOTLAB-RAWLER-000005.md`（双分叉，UI 分支在 `bound.rs` 收尾）

## Background & Goal

Rust 与 Kotlin 之间目前只通过两种载荷传递图像：PNG 字节（`Vec<u8>`）和全分辨率 `Vec<f32>`。本条目评估"用 PNG 当 IPC 格式"这件事本身的成本，以及有哪些替代方案、各自要与项目现有规矩做怎样的取舍。

## Finding

### 1. 三个编码器都对整幅 RGBA8 做 deflate

`app/src/binding/rust/rawler_fotlab/src/bound.rs` 里三个 encoder 的收尾完全相同：

- `fotraw_to_png`（灰度原始预览）→ `:74-76` `PngEncoder::new(&mut out).write_image(...)`
- `rawlerimagedeveloped_to_png`（已显影，sRGB 伽马 + clip）→ `:128-129`
- `graded_to_png`（rawalchemy 输出，直接量化）→ `:173-174`

三者都用 `PngEncoder::new`（**默认压缩档位**，未指定 `CompressionType::Fast`），输入是 `w*h*4` 的 RGBA8——50 MP 即约 200 MB。

而这条链的下游马上就是 Coil（`app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioScreen.kt:248`），它拿到 PNG 后要 inflate 出一张 200 MB 的 Bitmap。也就是说**同一帧的无损压缩被做了两遍**：native 侧压缩一次，UI 侧解压一次，中间还会落到一次 Java `ByteArray`。

预览（preview）是一次性的中间结果，按定义既不需要无损也不需要磁盘友好 —— 这也是 `ACTION-PERFOR-000002` 把它列为"最确定的纯浪费"的原因。

### 2. 可选方案

| 方案 | 做法 | 成本 / 约束 |
|---|---|---|
| **A. 降压缩档位** | `PngEncoder::new_with_quality(..., CompressionType::Fast)` | 一行改动，`bound.rs` 三处；收益取决于当前档位（未实测）。**不改变 UniFFI 边界与返回类型** |
| **B. native 直填 Android Bitmap** | Kotlin `Bitmap.createBitmap(w,h,ARGB_8888)`，经薄 JNI 把 jobject 交给 native，`AndroidBitmap_lockPixels` 拿指针后由 Rust/C++ 直接写 RGBA8 | FFI 载荷从百 MB 降为一个 jobject；**minSdk 26 即可用**。代价：要在 UniFFI 之外新增一层 JNI |
| **C. DirectByteBuffer** | Kotlin `ByteBuffer.allocateDirect`，JNI `GetDirectBufferAddress`，native 原地写 | 同 B；额外好处是没有 Bitmap 分配，但 Compose 侧仍需自己转 `ImageBitmap` |
| **D. ashmem / AHardwareBuffer** | native 建 `ASharedMemory` 写像素，Kotlin mmap 只读 | minSdk 26 下 `android.os.SharedMemory`(27)、`Bitmap.wrapHardwareBuffer`(28) 都不可用，只能自己 mmap fd；收益不如 B，不推荐 |

## Impact / Conflict

- **B / C 与"唯一跨语言边界"的规矩冲突**：`app/src/binding/kotlin/io/github/fotlab/fotlab_rawler/RawlerFotlabBridge.kt:14-19` 写明该 object 是唯一的跨语言边界，其它代码不得直接调用生成的 native 函数。新增 JNI 层属于架构变更，**需人工确认**（总览 C2），并且要一并界定新旧两层边界的管辖关系。
- **A 不改变任何契约**，可以在打点数据出来后立刻做，是最低风险的先手。
- B/C 会改写 `loaded.rs` 五个入口的返回类型（`Vec<u8>` → Bitmap/Buffer），牵动 `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioEngine.kt:504` 处的 `ByteBuffer.wrap(png)` 以及 Studio 的渲染结果模型 `StudioRenderResult.Ready`。

## Recommendation

1. 先把 A 做掉（降压缩档位）——它没有任何取舍，纯粹是有收益。
2. B 是真正的解法，但属于架构变更：**先把量级数据（`ACTION-PERFOR-000009`）摆在人工面前**，确认"是否允许新增 JNI 层"之后再动。
3. D 不建议：minSdk 26 下拿不到封装 API，手写 mmap 的复杂度高于收益。

## Change History

- 2026-09-21 — 创建。记录 `bound.rs` 三处 `PngEncoder::new` 的全幅 deflate（`:74/128/173`），Coil 侧随之做一次完整 inflate；给出四档替代方案，其中 A 无取舍、B/C 属需人工确认的架构变更、D 在 minSdk 26 下不可用。
