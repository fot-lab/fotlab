# 原生侧算力未被利用 — 自写像素循环是单线程标量，rawalchemy 的 OpenMP 未开启，release profile 未调优

- ID: ACTION-PERFOR-000007
- Status: Observation
- Priority: P2
- Created: 2026-09-21
- Owner: —
- Related: `rules/REVIEW/detail/ACTION-PERFOR-000001.md`（总览）、`rules/REVIEW/detail/ACTION-PERFOR-000003.md`（降分辨率会削弱本条收益）、`rules/REVIEW/detail/DNGLAB-RAWLER-000005.md`（解码并行度随编码而定，不随厂商）

> 注：`Related` 只列真实相邻的条目：`rules/REVIEW/detail/ACTION-PERFOR-000001.md`、`ACTION-PERFOR-000003.md`、`DNGLAB-RAWLER-000005.md`。`FOTLAB-CRASH-000001` 在本文件内以正文形式引用（其内容可从 `app/src/binding/rust/rawler_fotlab/src/loaded.rs:36,79,114` 等处的注释读到），该 ID 尚未在 `rules/REVIEW/index.md` 中登记。

## Background & Goal

上游 rawler 的 demosaic 内部已经用 rayon 并行（这也是它能扛住 50 MP 的原因之一）。本条目记录我们自己写的那几段像素循环、以及 rawalchemy 的 grading 主循环的并行度现状，外加 CI 上 Rust 的编译配置。

## Finding

### 1. 我们自己写的三段全分辨率循环都是单线程标量

| 阶段 | 位置（相对仓库根目录） | 形态 |
|---|---|---|
| 曝光 `2^ev` | `app/src/binding/rust/rawler_fotlab/src/develop.rs:228-232` — `for p in pixels.iter_mut() { *p *= ev_scale; }` | 单线程标量 |
| calibrate（WB + 3×3 矩阵） | `app/src/binding/rust/rawler_fotlab/src/calibrate.rs:136-147`（ThreeColor 就地映射）、`159-171`（FourColor） | 单线程标量 |
| gamma / clip + RGBA 展开 | `app/src/binding/rust/rawler_fotlab/src/bound.rs:123-125`、`163-170` | 单线程标量 |
| `crop_default` 逐行拷 | `app/src/binding/rust/rawler_fotlab/src/develop.rs:300-303` | 单线程 memcpy 循环 |

全部是逐像素、无跨像素依赖的纯函数映射，**天然可并行**。rawler 已经依赖 rayon，`par_chunks_mut` 可以直接用。

### 2. rawalchemy 的 grading 主循环当前编译成单线程

`external/RawAlchemyCpp/src/grading_fused.cpp:69-70`：

```cpp
#ifdef RA_USE_OPENMP
    #pragma omp parallel for schedule(static, 8192)
```

而 `RA_USE_OPENMP` 在我们的桥接里**从未定义**：

- `app/src/binding/cxx/rawalchemy_fotlab/cpp/CMakeLists.txt:21-29` 的注释明确说明：上游只在 `find_package(OpenMP)` 成功后才 `target_compile_definitions(raw_alchemy_core PUBLIC RA_USE_OPENMP)`（见 `external/RawAlchemyCpp/CMakeLists.txt:592-595`），我们这里刻意不启用；
- `app/src/binding/cxx/rawalchemy_fotlab/build.rs:118` 同样写明"No OpenMP link-lib here on purpose"。

后果是 `grading_fused.cpp` 里的三线性 LUT 插值（每像素 8 次 gather）在小核上也按单线程跑 50 MP。同一模式还出现在 `external/RawAlchemyCpp/src/log_transform.cpp:20` 等位置。

**这是一个已经记录在案、有意为之的决定**（避免 NDK 下的 libomp 交叉编译依赖），本条目只是把它的性能代价显式化。

### 3. CI 上 Rust 用的是默认 release profile

`.github/workflows/build_rust.yaml:205` 是 `build --release`，仓库中没有 `[profile.release]` 覆盖，`external/dnglab/rawler/Cargo.toml` 与 `app/src/binding/rust/rawler_fotlab/Cargo.toml` 里也没有。即：**opt-level 3、codegen-units 16、无 LTO**，也没有针对 aarch64 的 target-feature 设置。

## Impact / Conflict

- 本条目的收益**依赖于** `ACTION-PERFOR-000003`：一旦预览降到 1/4，像素数 ÷16，并行化与编译优化带来的绝对收益会同比缩小。这也是总览把它放在 T0 之后的原因。
- **`panic = "abort"` 不可用**：项目靠 `catch_unwind` 把 rawler panic 挡在 FFI 边界之内（见 `app/src/binding/rust/rawler_fotlab/src/loaded.rs:36,79,114` 等处的注释 `FOTLAB-CRASH-000001`）。任何 profile 改动都必须保留 unwind。
- OpenMP 路线需要引入 `libomp.so`：APK 体积增加，CI 的 jniLibs 拷贝步骤要补一份（跟现状里的 `libc++_shared.so` 一样）。
- `-C target-feature=+neon` 一类改动涉及 ABI / 设备兼容性，不能盲目启用。

## Recommendation

1. **先 rayon 化我们自己的三段循环**（`develop.rs:229`、`calibrate.rs:136/159`、`bound.rs:123/163`）。逐像素无依赖，8 核上接近线性；不引入任何新构件。
2. **grading 的并行优先在 Rust 侧切片**：按行把 buffer 切成 N 份、用 rayon 并发调用 `grade`，可以不引入 `libomp.so`。是否宁可接受新增一个 .so 去换 OpenMP 的写法 —— **属人工决策**（总览 C3）。
3. profile：加 `lto = "thin"`、`codegen-units = 1`，**保留 unwind**。aarch64 的 target-feature 需要真机兼容性验证后再决定。
4. 以上都要先有 `ACTION-PERFOR-000009` 的基线数据，否则无法判断并行化是否值得（尤其是这几段循环有可能是内存带宽受限而非 CPU 受限）。

## Change History

- 2026-09-21 — 创建。确认四段自写像素循环为单线程标量（`develop.rs:228-232/300-303`、`calibrate.rs:136-171`、`bound.rs:123-170`）；`grading_fused.cpp:69-70` 的 OpenMP pragma 因 `RA_USE_OPENMP` 未定义而被编译掉（这是 `cpp/CMakeLists.txt:21-29` 记录的有意决定）；CI 的 `--release` 无 profile 覆盖（`.github/workflows/build_rust.yaml:205`）。明确 `panic = "abort"` 与 `catch_unwind` 边界冲突，禁止使用。
