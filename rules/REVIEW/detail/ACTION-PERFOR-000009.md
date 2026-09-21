# 缺少断面度量 — `ACTION-PERFOR-000001` 至 `000008` 的量级判断全部未经实测

- ID: ACTION-PERFOR-000009
- Status: Observation
- Priority: P1
- Created: 2026-09-21
- Owner: —
- Related: `rules/REVIEW/detail/ACTION-PERFOR-000001.md`（总览）、`ACTION-PERFOR-000002.md`（量级排序是估算）、`ACTION-PERFOR-000003.md`（降分辨率收益是估算）、`ACTION-PERFOR-000004.md`（PNG 成本是估算）、`ACTION-PERFOR-000007.md`（并行化收益未知，可能带宽受限）

## Background & Goal

`ACTION-PERFOR-000001` 到 `000008` 里所有的"贵/便宜""数百 ms/数 s"都是读代码得出的**数量级估算**，没有一次真机打点。把它们单独记录成一个阻塞项，是为了避免后续任何性能优化在错误的假设上开工——尤其是 `ACTION-PERFOR-000002` 已经指出，这次调研的起因（"FFI 慢"）本身就是一次误判。

`rules/REVIEW.md` §General Rules 要求"Findings must be verifiable: observable behaviour, measurable threshold, or explicit exclusion"。本条目正是为此而立。

## Finding

### 1. 当前代码里没有任何性能打点

渲染链路（`app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioEngine.kt:463-505` → `app/src/binding/rust/rawler_fotlab/src/loaded.rs` → `develop.rs` → `calibrate.rs` / `bound.rs`）上不存在任何耗时输出。

唯一存在的量化信息是写死在注释里的、与当前职责无关的数量级备注：

- `app/src/binding/rust/rawler_fotlab/src/calibrate.rs:80` — 50 MP 下 ~630 MB 的 f32 buffer（这是内存，不是时间）；
- `app/src/binding/rust/rawler_fotlab/src/develop.rs:214-218` — 50 MP 下 ~210 MB 的缩放后缓冲。

### 2. 未经实测的假设清单（必须在动手前逐个消灭）

| # | 待验证假设 | 出现在 |
|---|---|---|
| 1 | `image` crate `PngEncoder::new` 的默认压缩档位不是 `Fast` | `ACTION-PERFOR-000002` / `000004` |
| 2 | PNG deflate + inflate 合计超过 develop 本身 | `ACTION-PERFOR-000002` |
| 3 | Coil 3 在未指定 `size` 时按原始分辨率解码（而非自身有降级策略） | `ACTION-PERFOR-000003` |
| 4 | 50 MP 位图在当前目标设备上确实超过 `GL_MAX_TEXTURE_SIZE` | `ACTION-PERFOR-000003` |
| 5 | 曝光 / calibrate / gamma 三段循环是 CPU 受限还是内存带宽受限 —— 决定 rayon 化的实际收益 | `ACTION-PERFOR-000007` |
| 6 | `Decoder::preview_image` / `thumbnail_image` 对各厂商格式的覆盖率（trait 默认实现返回 `None`） | `ACTION-PERFOR-000006` |
| 7 | 单次渲染峰值是否真的突破 1 GB、是否真的触发 low-memory-kill | `ACTION-PERFOR-000001` |

### 3. 为什么这一项要排在 T0 之前

`ACTION-PERFOR-000007` 的并行化、`ACTION-PERFOR-000004` 的 JNI 直填 Bitmap、`ACTION-PERFOR-000008` 的 GPU 路线，**都是成本不低改造**。若第 2 节第 5 条的答案是"这些循环其实内存带宽受限"，那么并行化的收益会远低于预期；若第 1、2 条的答案是"PNG 其实没那么贵"，那么为搬走 PNG 而新增一层 JNI（属架构变更）就不值得。**先花一次 CI/真机代价把数据拿到，能省掉后面几次误判。**

## Impact / Conflict

- 打点本身需要在 native 侧引入计时输出（一组六处 断面），短期会拉长日志；这是可接受的、可移除的成本。
- Kotlin 侧计时要用 `SystemClock.elapsedRealtimeNanos`（API 无关，`minSdk 26` 可用）；不要引入 `kotlin.time` 之外的依赖。
- native 侧可以用 rawler 已在用的 `log` crate，不需要新依赖。
- 此项**不需要人工审批**，它是纯读数改动，不触碰任何架构。

## Recommendation

1. 在六个断面各打一个点并输出：
   - **decode**（`app/src/binding/rust/rawler_fotlab/src/decode.rs:28-32` 的 `decode_to_rawimage`）
   - **demosaic / calibrate**（`develop.rs:236`、`calibrate.rs:82-179`）
   - **grade**（`loaded.rs:150-167` 的 `develop_and_grade`）
   - **png-encode**（`bound.rs` 的三个 encoder）
   - **ffi + ui-display**（Kotlin 侧：`StudioEngine.kt:473-505` 的 `runDevelop` 到 Coil 出图）
2. 用例：**真机**（不是模拟器）、一张 45–60 MP 的真实 RAW，走一遍"打开 → 改曝光 → 改 boost"的完整交互。
3. 把结果回写到本条目与各子条目的 Change History，并把被推翻的估算标记为"已实测更正"——**不得静默改掉原来的估算**。
4. 基线数据出来之后，再按总览 §Recommendation 的顺序动 T0。

## Change History

- 2026-09-21 — 创建。渲染链路当前无任何耗时打点；登记 7 条未经实测的假设（PNG 默认档位、PNG vs develop 的相对成本、Coil 无 size 时的行为、`GL_MAX_TEXTURE_SIZE` 超限、并行化是否带宽受限、内嵌预览覆盖率、峰值内存与 LMK），并给出六个断面 + 一个真机 45–60 MP 用例的度量方案。要求结果回写而非覆写原估算。
