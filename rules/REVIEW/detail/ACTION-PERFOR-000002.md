# 渲染瓶颈归因 — FFI 调用开销被误判；真正的成本是像素搬运、重复拷贝与 PNG 编解码

- ID: ACTION-PERFOR-000002
- Status: Observation
- Priority: P1
- Created: 2026-09-21
- Owner: —
- Related: `rules/REVIEW/detail/ACTION-PERFOR-000001.md`（总览）、`rules/REVIEW/detail/ACTION-PERFOR-000003.md`、`rules/REVIEW/detail/ACTION-PERFOR-000004.md`、`rules/REVIEW/detail/ACTION-PERFOR-000009.md`

## Background & Goal

`ACTION-PERFOR-000001` 的调研由一个前提触发："跨越 FFI 传递是比较慢的"。本条目专门验证这个前提，因为一旦它是错的，后续所有优化方向都会被带偏——例如会倾向于"把计算搬到另一个进程/WebView 里以便少跨一次 FFI"，而那样省掉的是 µs，付出的却是一次额外的全量像素搬运。

## Finding

### 1. FFI 传递在这里"贵"，贵在数据规模，不贵在跨界本身

UniFFI 0.28 生成的 Kotlin 绑定在 Android 上通过 JNA 调用（`app/build.gradle.kts:163`）。单次调用的开销是微秒级，与像素数量无关。

现在真正被搬运的东西是**整幅图片本身**：

- `app/src/binding/rust/rawler_fotlab/src/loaded.rs:60,80,116,177,191` 的预览 / 显影 / grading 入口，返回值一律是 `Vec<u8>`（PNG 字节）或 `Vec<f32>`（全分辨率浮点）；
- `app/src/main/kotlin/io/github/fotlab/fotlab/media/RawlerFotlabDecoder.kt:26,39` 的入参是整份 RAW 的 `ByteArray`。

所以"跨 FFI 慢"这个观察成立，但归因错了：慢的是 **O(像素数)** 的部分，不是 O(1) 的调用开销。减少跨界调用次数救不了它。

### 2. 同一份数据被复制多次

| 对象 | 位置（相对仓库根目录） | 说明 |
|---|---|---|
| 整个 RAW 文件的字节 | `app/src/main/kotlin/io/github/fotlab/fotlab/media/RawlerFotlabDecoder.kt:25` — `open().use { it.readBytes() }` | 先整份进 Java 堆（50 MB 级） |
| 同一份字节的 Rust 侧副本 | `app/src/binding/rust/rawler_fotlab/src/decode.rs:29` — `RawSource::new_from_slice` | `new_from_slice` 再复制一份；`rawler::rawsource::RawSource` 另有 `new_from_shared_vec` 支持共享内存源 |
| 缩放后的 f32 马赛克 | `app/src/binding/rust/rawler_fotlab/src/develop.rs:218` — `take_scaled_pixels` | 这一处**已经是零拷贝**（move 而非复制），注释写明 50 MP 下约 210 MB |
| 展开后的 RGBA8 | `app/src/binding/rust/rawler_fotlab/src/bound.rs:45,122,162` | `Vec::with_capacity(w*h*4)` 后逐像素 `extend_from_slice` |
| PNG 字节 → Kotlin `ByteArray` | UniFFI + JNA 的返回路径 | 又一次整块拷贝 |
| Coil 解码出的 Bitmap | `app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioScreen.kt:248` | 200 MB 级 Bitmap |

其中 1、2、6 三行是可以省掉的；第 3 行的零拷贝是已经做对的样板。

### 3. PNG 编解码是交互路径上最确定的纯浪费

`app/src/binding/rust/rawler_fotlab/src/bound.rs:128` 与 `:174` 用 `PngEncoder::new(...)` 对整幅 RGBA8 做无损 deflate，Coil 侧再 inflate 一遍。预览（preview）按定义就是中间结果，既不需要无损也不需要磁盘友好。这一项展开在 `ACTION-PERFOR-000004`。

### 4. 量级排序（估算，待实测）

按 50 MP 估算，一次渲染的时间量级大致是：

```
PNG deflate（200 MB 级无损压缩）  ≳  PNG inflate（Coil 侧）
  >  全分辨率 develop（demosaic + calibrate）
    >  Bitmap 分配 + 纹理上传
      ≫  JNA / FFI 单次调用开销（µs 级）
```

上述排序、`image` crate `PngEncoder::new` 的默认压缩档位、以及 Coil 未指定 `size` 时的解码行为**均未实测**，全部列为 `ACTION-PERFOR-000009` 的打点目标。

## Impact / Conflict

- 优化顺序会被带偏：按"少跨一次 FFI"选型，最容易选中"搬到 WebView / 另起进程"这一类方案，而它会**增加**一次全量像素搬运（见 `ACTION-PERFOR-000008`）。
- `ACTION-PERFOR-000004` 的 NDK 直填 Bitmap 方案会在 UniFFI 之外新增 JNI 层，与 `app/src/binding/kotlin/io/github/fotlab/fotlab_rawler/RawlerFotlabBridge.kt:14-19` 的"唯一跨语言边界"规矩冲突 —— 属人工拍板项（总览 C2）。

## Recommendation

不需要 instrumentation 就能确定的一件事：

- **源数据不要复制两次**：参照项目里已有的落缓存做法（`app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioEngine.kt` 的 `copyLutToCache`），把 SAF 文件一次性落到 app 私有缓存，再用 `RawSource::new(path)` 走 mmap，省掉 `new_from_slice` 的那次复制，后续重复解码也不必再读一遍源文件。

其余（"到底是 PNG 贵还是 develop 贵"）必须由实测决定，打点方案见 `ACTION-PERFOR-000009`。

**明确不建议**：基于"FFI 慢"这个前提去做任何以减少跨界调用次数为目标的改造 —— 它没有省掉任何字节。

## Change History

- 2026-09-21 — 创建。结论：FFI 调用开销（µs）与 O(像素数) 的搬运/编解码不在同一量级，"跨 FFI 慢"的现象成立但归因错误；记录 6 处数据所在位置，其中 3 处重复拷贝可省、`develop.rs:218` 已是零拷贝样板。
