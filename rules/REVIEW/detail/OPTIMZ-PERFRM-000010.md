# 预览降分辨率开关（superpixel 1/4）— drawer 偏好、demosaic 阶段分支、crop 抽取修正与路径无关契约

- ID: OPTIMZ-PERFRM-000010
- Status: Implemented
- Priority: P1
- Created: 2026-09-22
- Owner: —
- Related: `rules/REVIEW/detail/OPTIMZ-PERFRM-000001.md`（总览）、`rules/REVIEW/detail/OPTIMZ-PERFRM-000003.md`（本条目实现其 Recommendation 2，并接续其「native 无降采样路径」的 Finding）、`rules/REVIEW/detail/FOTLAB-RAWLER-000003.md`（§A/§C 的 superpixel 设计；本条目的开关形状与之不同，见 Impact）、`rules/REVIEW/detail/OPTIMZ-PERFRM-000004.md`（PNG 载荷同比变小）、`rules/REVIEW/detail/OPTIMZ-PERFRM-000005.md`（每次 re-develop 的成本同比变小）、`rules/REVIEW/detail/OPTIMZ-PERFRM-000009.md`（收益量化仍待打点）

## Background & Goal

`OPTIMZ-PERFRM-000003` 的结论是：Studio 的每一次显影都处理全分辨率像素，而 rawler 现成的 quarter-resolution superpixel 原语从未接线。人工拍板把它做成一个 **Kotlin 侧可配置的降采样开关**，落在 Studio 的 drawer 中作为用户偏好持久化，并明确三条契约：

1. 开关状态变化**不触发渲染**；
2. 下一次 `rawler → exposure → demosaic/superpixel` 时才作为参数生效，管线内分支决定走 demosaic 还是 superpixel；
3. 两条路径在 calibrate/白平衡之前**汇聚为同一结构**，且进入 rawalchemy 的对象必须是**路径无关**的标准数据结构。

人工另外点名一条：「注意 crop 的陷阱」。

## Finding

### 1. superpixel 是 demosaic 的替代实现，不是前后处理

`Superpixel3Channel` / `Superpixel4Channel`（`external/dnglab/rawler/src/imgop/sensor/bayer/superpixel.rs:24`/`:86`）实现 `Demosaic<f32,3>` / `Demosaic<f32,4>`，入参形状与 `PPGDemosaic` 完全一致（单通道 mosaic + CFA + colors + roi）；出参 `Color2D` 的维度写作 `roi.d.w >> 1, roi.d.h >> 1`（`:73`）—— **边长各减半、像素数 1/4**（`FOTLAB-RAWLER-000003.md:111` 记的 "cuts pixel count 4×" 即此）。

所以「分支」落在 demosaic 这一格本身，而不是在它前后插一个缩放步骤：`DemosaicAlgorithm` 仍描述"用哪个算法"，新增的布尔量描述"要不要换成 quarter-resolution 的那个算法"。`FOTLAB-RAWLER-000003` §C 的 `ScaleMode::Quarter`（全分辨率 demosaic 之后再 2×2 box bin）是**另一条**路线，本条目不涉及。

### 2. 三个硬性守卫——它们是上游原语的输入约束，不是策略选择

- `Superpixel3Channel` 拿 CFA **名字**去匹配四种 RGGB 家族模式，其它一律 `unreachable!()` panic（`:41-47`）；
- `Superpixel4Channel` 要求 4 个平面，否则 panic（`:91-93`）；
- Fuji 旋转的传感器不能用：`rotate_45cw` 用绝对量 `fuji_rotation_width` 与**源宽**一起算输出尺寸，而这条路径的源宽是半尺度（若放宽守卫，旋转会在半尺寸图上算出错误的裁剪框）。

传感器侧事实（`imgop/sensor/mod.rs:38-46` `SensorType::from_cfa`）：CFA 为 2×2 且 `unique_colors() ≥ 3` → `Bayer`；6×6 → `Xtrans`。相机定义的 `color_pattern` 取值就是 `RGGB/BGGR/GBRG/GRBG`（如 `external/dnglab/rawler/data/cameras/sony/a7r.toml`）；`plane_color` 默认是 `RGB`（3 平面，`cfa.rs:365` `impl Default for PlaneColor`），只有 3 个 RGBE 机型显式写 `plane_color = "RGBE"`（`sony/f828.toml` 等）。⇒ 守卫可以直接由 `sensor + cfa.name + colors.plane_count()` 判定，`X-Trans`、`RGBE`、以及任何畸形 CFA 都能正确分流或回退，不会走到 panic 分支。

### 3. crop 陷阱（人工点名的那一个）

改动前 `develop.rs` 的 `crop_default` **明确省略**了 rawler 的 0.5 缩放（原注释："Superpixel 1/2 scaling is omitted because we never use superpixel demosaic"）。`RawImage.crop_area` 是全传感器坐标，先 `crop.adapt(active_area)` 重基到活动区坐标；而 quarter-resolution 的 intermediate 把这些坐标**整体缩小了一半**。不随之缩放，就会拿全分辨率的偏移去切半尺寸 buffer → 越界 panic → 被 `catch_unwind` 变成 Decode error → UI 显示 "Unsupported Format"，与历史上那个 crop 重基 bug（`develop.rs` 的 CRITICAL 注释）**症状完全相同**。

以语料里的 Sony ILCE-7R 实测（`data/cameras/sony/a7r.toml`：`active_area` 右边裁 26，`crop_area` 左/上各 4、右 28、下 4；`Rect::new_with_borders` 语义，见 `src/decoders/arw.rs:213-215`）：7360×4912 的全画幅 → 活动区 7334×4912 → `crop_area` 7328×4904 → 全分辨率出图 7328×4904；半尺度应为 3664×2452 = `floor(crop/2)`，正好是超采样后 buffer（3667×2456）内部的一个合法矩形。若按 (4,4,7328,4904) 去切 3667×2456，必然越界。

**修正方式（人工复核后定稿）：抽取倍数由"实际 buffer 尺寸"推导，且用整数除法**。`crop_default` 取 demosaic 实际收到的 ROI（`active_area`，无则整帧）与**实际返回的 buffer 维度**，交给 `decimation_factor(roi_w, roi_h, buf_w, buf_h)` 求出整数抽取倍数；倍数 > 1 时把 crop 矩形的 `p.x/p.y/d.w/d.h` 各**除以**该倍数。三点理由写进代码注释：

- 不传"是否用了 superpixel"的标志、也不写死 `0.5`——尺寸是唯一事实源，矩形与 buffer 不可能不一致；日后换抽取比例无需改这里。
- 映射是 `out = in / factor`（**整数**除法），正是抽取器自身的截断方式（`roi.d.w >> 1` 丢掉奇数列）。**不能**乘"实际比例 `buf/roi`"：ROI 为奇数时它会偏一像素（`3664 × (1833/3667) = 1831`，正确答案是 `1832`）。所以「半尺度并非整数」不构成问题——半尺度是**整数倍数 2**（`2×2` 合成），非整数的是那个**比值**；除以整数倍数即可，比值根本不该出现在算式里。
- 倍数识别用**回除校验** `roi_w / f == buf_w`（而非整除校验）：3667 宽的 ROI 出 1833 宽 buffer，`1833 × 2 ≠ 3667`，但倍数确定为 2。两轴必须一致；识别不出时倍数取 1，由新增的**边界检查**（`x + cw > buf_w || y + ch > buf_h`）显式返回带数字的 Decode 错误，而不是让切片 panic 再被泛化成 "Unsupported Format"。

### 4.「汇聚为统一结构」不需要新结构

`demosaic()` 的返回值 `Intermediate`（`Monochrome`/`ThreeColor`/`FourColor`）本来就是它唯一的产物类型，而 `calibrate(intermediate, image, wb, space) -> RawlerImageDeveloped` 是它唯一的消费者。两条路径产出同一个枚举、同一套不变量，分支天然止步于 calibrate **之前**；进入 rawalchemy 的 `RawlerImageDeveloped { width, height, rgb }`（`grade(&dev.rgb, dev.width, dev.height, …)`）因此天然是路径无关的。

## Impact / Conflict

- **★ 2026-10-05 人工改判：独立布尔开关已废除，superpixel 改为平级算法项。** 上面 Background 记录的「独立布尔开关」是 2026-09-22 的人工决策；本次人工明确要求 superpixel 与其它解马赛克算法平级、可在 Kotlin 侧解马赛克菜单中选用，**默认算法仍为 rawler 的 CFA 默认（不改动）**。本条目因此改回 `FOTLAB-RAWLER-000003` §C 的原始建模，`DemosaicAlgorithm` 新增 `Superpixel` 变体，`DevelopParams.downsample: bool` **已删除**，Studio drawer 的降采样开关行**已删除**。
  - **Rust**：`rawtrp_demosaic::algo` 的 `RAWLER_NAMES` 增加 `Superpixel`（label `RAWLER Superpixel`），`candidates()` 铸 id `rawler:superpixel`（`SensorKind::Bayer`）；`demosaic.rs` 的 `DemosaicAlgorithm` **末尾追加** `Superpixel` 变体（既有变体位置不动），`effective_algorithm` 新增 `image` 形参以便该变体经 `superpixel_algo()` 解析；`demosaic()` 去掉 `downsample` 形参与「开关优先」的分支；`develop.rs` 删除 `DevelopParams.downsample`。
  - **守卫不变**：`superpixel_algo()` / `supports_downsample()` 一个字未改，所以「X-Trans / Fuji 旋转 / 非 RGGB 族 CFA / 预着色输入回落到 CFA 默认」与「能力答复与管线行为不可能漂移」这两条契约原样保留。区别只是**回落的落点**：从「保留所选算法」变成「回落 CFA 默认」——与 `Ppg` 在同一传感器上的回落行为一致，且这是唯一自洽的选择（否则菜单里的 superpixel 与用户所选算法会两个都生效）。
  - **crop 抽取的修正方式完全不变**：`decimation_factor` 依旧由实际 ROI 与 buffer 维度推导，不依赖任何标志位（Finding 3 的全部理由仍然成立）。
  - **偏好从 boolean 改为候选 id**：`StudioDevelopPreference` 存 `studio_develop_demosaic_id`（字符串），`StudioEngine.DEFAULT_DEMOSAIC_ID = "rawler:default"` 为新装默认。**不迁移**旧的 `studio_develop_downsample` 键——旧键变成孤儿，新装/升级后按默认走全分辨率 CFA 默认。人工已确认「默认为 RAWLER default，然后持久化用户最近一次选择的算法」。
  - **Kotlin**：`RawDecoder` / `RawlerFotlabDecoder` / `StubRawDecoder` 的 `developToPng` 去掉 `downsample` 形参；`StudioEngine.develop` 改收 `DemosaicCandidate`（同时拿到 id 与 algorithm，持久化 id、派发 algorithm，不必维护反向表）；`downsampleSupported` 更名 `superpixelSupported`，用于把 superpixel 菜单项**置灰**（能力为 `false` 时）而不是禁用一个开关。
  - **选择语义的变化（重要）**：旧契约「拨动开关不触发渲染」随开关一起消失。superpixel 现在是菜单项，选取即触发一次 develop——与 PPG、vng4 等所有其它算法项一致。
- **★ 名称映射补齐（人工要求）**：菜单现在**除默认项外每一项都带算法来源名**。此前 `StudioScreen.demosaicLabel()` 把四个 `rawler:*` id 覆盖成本地化裸名（"PPG"、"双线性 4 通道"），而 RAWTRP 走 `else -> candidate.label` 显示 "RAWTRP vng4"——**同一菜单里只有 RAWTRP 系看得出来源**。现在 RAWLER 系拼上 catalogue label 的来源前缀（`RAWLER PPG`、`RAWLER 双线性 4 通道`、`RAWLER X-Trans 双线性`、`RAWLER Superpixel（1/4 分辨率）`），RAWTRP 系保持原 label。默认项**不加**前缀：它不是算法而是「按 CFA 自动选」，无来源可指。前缀取自 `candidate.label.substringBefore(' ')` 并经 `studio_demosaic_source_prefix`（`translatable="false"`，因为 "RAWLER"/"RAWTRP" 是专名）拼接——来源名保持**一处事实**，不复制进五条翻译串。
- **与 `FOTLAB-RAWLER-000003` §A/§C 的建模分歧（已消解）**：该设计原本就把 superpixel 建模为 `DemosaicAlgo::Superpixel` 枚举分支；本条目 2026-09-22 的「独立布尔开关」是偏离，2026-10-05 人工改判后回归该建模。
- **crop 抽取不再由"标志"驱动，也不写死倍数**：`crop_default` 从**实际 ROI 与 buffer 维度**推导整数抽取倍数（`decimation_factor`），再按整数除法缩放矩形（见 Finding 3）。这是"路径无关"在实现层的落点：尺寸就是唯一事实源，crop 不可能与 buffer 不一致；识别不出倍数时**显式报错**而非静默切成越界。
- **与 `OPTIMZ-PERFRM-000007` 收益互斥**：superpixel 降到 1/4 后，并行化与编译优化带来的绝对收益同比缩小（1/4）。
- **`OPTIMZ-PERFRM-000001` 的 C4（画质契约）**：降分辨率不再是"擅自降级"，而是用户在算法菜单里的一次显式选择。
- **Coil 侧未动**：本条目只减小 native 的产出；`OPTIMZ-PERFRM-000003` 的「三处调用未给解码尺寸」仍独立存在，两者叠加才是完整的预览提速。
- **不可用时"置灰并说明"而不是"静默忽略"**：`RawlerImageLoaded::supports_downsample` 与管线共用同一守卫函数，能力为 `false` 时 superpixel 菜单项 `enabled = false`。
- **回退是静默的**：X-Trans、Fuji 旋转、非 4 平面的非 RGGB 族 CFA、以及非 CFA（预着色）输入都会回落到 CFA 默认（管线内无日志）。UI 侧由 `superpixelSupported` 兜住。
- **`grading` 分支同样生效**：grade fork 与 develop fork 走同一条 develop 管线，选中 superpixel 时 rawalchemy 也是在 1/4 分辨率 buffer 上运算（更少的像素，同样的算法）。


## Recommendation

1. 收益量化接 `OPTIMZ-PERFRM-000009` 的打点：同一张 RAW 在开关两态下的端到端耗时与峰值 RSS（预期 demosaic 之后的所有环节同比变快，而解码不变）。
2. `OPTIMZ-PERFRM-000003` 的另一半（给 Coil 指定解码尺寸）仍待做。
3. ~~若日后确实要"全分辨率 demosaic + demosaic 后 2×2 bin"，它是开关的**第三种取值**，届时应把布尔改成枚举（`Off/Quarter`）~~ —— **2026-10-05 已以另一种方式解决**：布尔已删除，superpixel 本身成为枚举变体。若日后要加"全分辨率 demosaic 之后再 2×2 bin"，那是**另一个算法**（`FOTLAB-RAWLER-000003` §C 的 `ScaleMode::Quarter`），应作为新的 `DemosaicAlgorithm` 变体加入同一列表，**不要再引入任何布尔或正交模式**。
4. Fuji X-Trans 的 1/4 预览（若产品需要）需要上游提供 6×6 的 superpixel 原语，或退化为 demosaic 后的 bin —— 属上游改动，需按 `FOTLAB-NATIVE-000001` R4 走 recorded patch。

## Change History

- 2026-10-05（人工改判）— **superpixel 从独立布尔开关改为平级解马赛克算法**：
  - **决策**：人工要求 superpixel 与其它解马赛克算法平级、可在 Kotlin 侧算法菜单中选用；**默认算法仍为 rawler 的 CFA 默认，不改动**；持久化用户最近一次选择的算法（默认 `rawler:default`）；**删除** `DevelopParams.downsample` 字段。本条目 Background 记录的 2026-09-22「独立布尔开关」决策被本次改判取代，`FOTLAB-RAWLER-000003` §C 的原始建模恢复。
  - **Rust**：`algo.rs` 的 `RAWLER_NAMES` 增 `Superpixel`（`RAWLER Superpixel`）、`candidates()` 铸 `rawler:superpixel`（Bayer）；`demosaic.rs` 末尾追加 `DemosaicAlgorithm::Superpixel`（既有变体位置不动）、`effective_algorithm` 加 `image` 形参并新增该变体臂（经 `superpixel_algo()` 解析，不支持则回落 CFA 默认）、`demosaic()` 去掉 `downsample` 形参与优先级分支；`develop.rs` 删 `DevelopParams.downsample`；`lib.rs` 的 `algorithm_for_candidate` 加 `rawler:superpixel` 映射。**`superpixel_algo` / `supports_downsample` / `decimation_factor` 三者一字未改**。
  - **Kotlin**：`StudioDevelopPreference` 由 boolean 改为 `studio_develop_demosaic_id`（字符串，**不迁移**旧 boolean 键）；`StudioEngine` 新增 `DEFAULT_DEMOSAIC_ID` + `defaultAlgorithm()` / `applyPersistedDemosaic()`，`develop(candidate: DemosaicCandidate)` 改签名并落盘 id，`downsampleSupported` 更名 `superpixelSupported`；`RawDecoder` 及两个实现去掉 `downsample` 形参；`StudioScreen` 删抽屉开关行、删 `DemosaicAlgorithm` import、`DemosaicButton` 增 `superpixelSupported` 置灰 superpixel 项、`demosaicLabel` 改为「除默认项外都带来源名」。文案：新增 `studio_demosaic_superpixel` 与 `studio_demosaic_source_prefix`（两种语言），删除三条 `studio_drawer_downsample*`。
  - **测试**：`RawRoutingTest#downsampleSwitchHalvesBothAxesOnDevelopAndGradeWithoutReRendering` → `#superpixelHalvesBothAxesOnDevelopAndGrade`（改为经候选驱动，去掉「拨开关不换帧对象」一步——选取即重渲，与其它算法项一致）；`fullResolutionDevelopKeepsBothAxesAboveThePreviewFloor` 去掉开关默认值断言；`everyDemosaicMenuOptionRedevelopsOnBayerAndExposureRecomputes` 的四项循环改为按 id 取候选（**故意不含 superpixel**：它按构造就是 1/4 分辨率，无法逐字节复现全分辨率帧，另由上面那条测试覆盖）；`tearDown` 复位改为回默认 pick；新增 `demosaicCandidate(id)` 辅助与 `SUPERPIXEL_ID` 常量。`smoke_emulator.yaml` 的 `test_image_develop` 分片 `TEST_FILTER` 同步改名，用例数 **12 → 12**（改名非新增）。
  - **守卫测试**：`rawler_fotlab` 的 `the_rawler_four_keep_their_original_variants` → `#the_rawler_entries_keep_their_original_variants`（rawler 四项→五项），新增 `#superpixel_is_an_ordinary_rawler_candidate`（label 带来源名 + kind 为 Bayer）。本地 `cargo test --no-default-features --lib` 36 通过、`rawtrp_demosaic` 103 通过。
  - **未做**：Kotlin 侧一行未编译（按人工指令禁止本地 Gradle 编译），正确性来自机械核对（调用点 / 未用 import / 字符串引用全仓扫描）；`develop(candidate)` 签名变更与 UniFFI 新变体 `SUPERPIXEL` 需 CI 首次运行确认。

- 2026-09-22 — 创建并实现。Rust：`demosaic.rs` 新增 `Algo::Superpixel`/`Algo::Superpixel4` 与守卫 `superpixel_algo`/`supports_downsample`，`demosaic()` 增加 `downsample` 形参（开关优先于算法选择，不支持时回退并保留所选算法）；`develop.rs` 的 `DevelopParams` 增加 `downsample: bool`（`#[uniffi(default = false)]`），`crop_default` 增加由实际维度推导的半尺度 crop 缩放（修掉越界陷阱），`RawlerImageDeveloped` 补写路径无关契约；`loaded.rs` 新增 `supports_downsample` 能力查询。Kotlin：新增 `StudioDevelopPreference`（独立 DataStore `studio_develop_prefs`，`studio_prefs` 已被 `MediaPreference` 占用，同名会抛异常），`StudioEngine` 暴露 `downsample`/`downsampleSupported` 与 `setDownsample`（只写偏好、**不触发渲染**），在 open-time develop 与 `runDevelop`/`runGrade` 全部传入该值，`RawDecoder` 接口同步加 `downsample` 形参。UI：Studio drawer 新增开关行（不支持时禁用并说明），新增 3 条字符串。测试：`RawRoutingTest#downsampleSwitchReRendersNothingAndHalvesBothAxesOnNextDevelop`（能力查询为真、拨开关不换帧对象、下一次 develop 两轴各减半且仍为彩色帧、关掉后逐字节回到 as-shot），并入 `smoke_emulator.yaml` 的 `test_image_develop`（该分片 10 → 11 个用例）；`tearDown` 复位开关，避免持久化偏好影响同进程内后续用例。
- 2026-09-22（同日人工复核后改写）— **crop 抽取方式与测试口径按人工意见重做**：
  - **crop**：把"探测 `linear.width == full_scale_w / 2` 然后 `scale(0.5)`"改为"**由实际 ROI 与 buffer 维度推导整数抽取倍数**（`decimation_factor`），再整数除以该倍数"。理由是人工的三点质询：① 为什么不直接按降采样后的实际 buffer 尺寸——采纳，倍数由尺寸推导（不传标志、不写死 `0.5`）；② 半尺度若非整数怎么办——半尺度是**整数倍数 2**（2×2 合成），非整数的是**比值** `buf/roi`，而乘比值会在 ROI 为奇数时偏一像素（`3664 × (1833/3667) = 1831` vs 正确 `1832`），故用**整数除法**，与抽取器自身的 `>> 1` 截断一致；③ 越界不能靠 panic——新增边界检查，`crop_default` 签名由 `RawlerImageDeveloped` 改为 `Result<RawlerImageDeveloped, RawlerFotlabError>`（私有函数，接口不外泄），失败时 Decode 错误带 `crop rect / buffer / roi / factor` 四个数字。
  - **测试**：原单条 `downsampleSwitchReRendersNothingAndHalvesBothAxesOnNextDevelop` 拆为两条，口径统一为「**无降采样 → 两轴均 > 3000**；**有降采样 → 两轴均 < 同一 fork 无降采样基线的 55%**（另加 > 40% 的废帧地板，防"截断成细条"通过单边判据）」：
    - `fullResolutionDevelopKeepsBothAxesAboveThePreviewFloor` — 开关持久化默认 OFF、能力查询为真、两轴 > 3000、仍为彩色帧。
    - `downsampleSwitchHalvesBothAxesOnDevelopAndGradeWithoutReRendering` — **develop 与 rawalchemy grade 两条 fork 各自前后对比**：先取两态（develop、grade）的开关 OFF 基线，再 ON 分别 develop / grade，逐条判定两轴 < 55%；拨开关不换帧对象；关掉后逐字节回到全分辨率 as-shot。
    - 降采样测试**只用 Sony ILCE-7R 一张 ARW**（人工指定：开关是 debayer 阶段的分支而非逐品牌行为，其余 4 张只增 emulator 分钟数）。
  - **测试脚手架**：`waitForDevelopedFrame` 新增 `minWidth` 形参（默认 `FULL_FRAME_MIN_WIDTH`）——降采样帧不该被强制满足"全画幅地板"；36..50 MP 语料的半尺寸恰好>3000 才没暴露这个语义错误，小传感器会误判。新增 `openResident`/`boundsOf`/`assertDownsampledBothAxes` 与常量 `DOWNSAMPLE_MAX_EDGE_RATIO=0.55`、`DOWNSAMPLE_MIN_EDGE_RATIO=0.40`。
  - **CI**：`smoke_emulator.yaml` 的 `test_image_develop` 分片 `TEST_FILTER` 换成上述两个新名字，分片用例数 **11 → 12**，分片注释同步改写。
  - 仍未做：**一行未编译**（无本地 Rust/Android 工具链），正确性来自静态核对；`crop_default` 的 `Result` 化与 `decimation_factor` 需在 CI 首次运行时确认。
