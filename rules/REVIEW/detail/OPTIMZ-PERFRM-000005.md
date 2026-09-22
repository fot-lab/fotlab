# 每次调参都重跑整条 develop 链 — 缓存的粒度停在"已解码"，没到"已显影"

- ID: OPTIMZ-PERFRM-000005
- Status: Observation
- Priority: P1
- Created: 2026-09-21
- Owner: —
- Related: `rules/REVIEW/detail/OPTIMZ-PERFRM-000001.md`（总览）、`rules/REVIEW/detail/FOTLAB-RAWLER-000004.md`（decode-once，`RawlerImageLoaded`）、`rules/REVIEW/detail/FOTLAB-RAWLER-000008.md`（boost 四参数语义 / §Impact 已提出缓存已显影 buffer）、`rules/DESIGN/detail/FOTLAB-PIPELN-000001.md`（FotRaw / FotDev IR）

## Background & Goal

`FOTLAB-RAWLER-000004` 已经把最贵的"解码"这一步做对了：RAW 只解码一次，Kotlin 持有 `RawlerImageLoaded` 这个 UniFFI handle。本条目记录剩下的那一层——**每次参数变化仍在重复 demosaic + calibrate**，也就是说缓存粒度停在 "decode"，没到 "develop"。

## Finding

### 1. 缓存里只有 `RawImage`

`app/src/binding/rust/rawler_fotlab/src/loaded.rs:30-32`：

```rust
pub struct RawlerImageLoaded {
    inner: Arc<RawImage>,
}
```

而它的每一个入口在被调用时都要**克隆** `RawImage` 再从零跑一遍 develop：

| 入口 | 位置 | 每次重做 |
|---|---|---|
| `preview_png` | `loaded.rs:60-71` | 原始马赛克 dump 转灰度 PNG（无 demosaic） |
| `develop_to_png` | `loaded.rs:80-91` | rescale → 曝光 → demosaic → calibrate → crop → gamma → PNG |
| `develop_to_png_at_kelvin` | `loaded.rs:116-139` | 同上 |
| `develop_and_grade` | `loaded.rs:150-167` | 同上 + grading |
| `develop_and_grade_to_png(_at_kelvin)` | `loaded.rs:177-219` | 同上 + grading + PNG |

克隆本身也是必须的：`app/src/binding/rust/rawler_fotlab/src/develop.rs:203-204` 的注释写明 develop 管线会就地修改 `image`，所以调用方若要保住缓存就得先克隆——**每帧克隆一份 210 MB 级的缩放后浮点缓冲**（`develop.rs:214-218` 的注释）。

### 2. Kotlin 侧没有任何去抖或增量

`app/src/main/kotlin/io/github/fotlab/fotlab/feature/studio/StudioEngine.kt`：

- `setExposureEv` → `:457-460` 直接 `reDevelop()`；
- `reDevelop` → `:463-471` 每次都把状态置成 `Loading`，然后 `scope.launch` 跑一次全量 `runDevelop`；
- `runDevelop` → `:486-503` 走 `RawlerFotlabBridge.developRawlerImage(...)`，即上表中的 `develop_to_png`。

**没有任何去抖、合并、或阶段级别的增量。** 这意味着改一次 boost（一个逐像素的廉价操作）也要把 demosaic 和 calibrate 从头重算一遍。

### 3. 这一点已被既有条目指出过

`rules/REVIEW/detail/FOTLAB-RAWLER-000008.md` §Impact 已经写明：改 boost 只需要重跑 grading，正确修法是缓存已显影 buffer。本条目把它正式提升为一个独立的阻塞项，因为它同时是其它优化的前置条件——不缓存 `FotDev`，`OPTIMZ-PERFRM-000003`（降分辨率）之外的每一次改造都还得从头跑。

## Impact / Conflict

- **收益与其它条目重叠但不等价**：`OPTIMZ-PERFRM-000003` 降低单次成本，本条目消除重复执行。两者互补。
- **工作空间未决**（与既有设计的开放问题重合）：`rules/DESIGN/detail/FOTLAB-PIPELN-000001.md` 的开放问题里，"FotDev 到底活在 D65 还是 D50"仍未拍板，而 `FOTLAB-RAWLER-000005` 确立的双分叉正好是：`SrgbD65`（显示）与 `ProPhotoD50`（交给 rawalchemy）两条路。缓存 `FotDev` 必须先决定缓存的是哪一支，或者两支都缓存。
- UI 侧 readiness：这正是未来上滑杆（拖动式调参）的前置改造，目前 Studio 的 API 结构还不能承载连续调参。

## Recommendation

1. 在 `RawlerImageLoaded` 里再上一层缓存：按 (demosaic 算法, 工作空间) 缓存 `develop_image` 的产物（`RawlerImageDeveloped`），让 boost / log / LUT 只触发 grading + encode 这两段。
2. 缓存 key 与失效边界必须尊重 `FOTLAB-RAWLER-000004` §lifecycle（每个当前文件一个 handle；换文件即丢弃）。
3. 是否缓存 D65 与 D50 两支、`FotDev` 的工作空间归属 —— **属人工决策**，依赖 `FOTLAB-PIPELN-000001` 的开放问题先落定。
4. 顺带加一层去抖 / 合并：这是将来滑杆交互的必要前置，而不是性能优化本身。

## Change History

- 2026-09-21 — 创建。确认缓存止于 `Arc<RawImage>`（`loaded.rs:30-32`），五个入口每次都克隆并重跑 demosaic + calibrate（`develop.rs:203-204`、`:214-218`），Kotlin 侧无去抖无增量（`StudioEngine.kt:457-503`）；与 `FOTLAB-RAWLER-000008` §Impact 的结论一致，并与 `FOTLAB-PIPELN-000001` 的 FotDev 工作空间开放问题相关联。
