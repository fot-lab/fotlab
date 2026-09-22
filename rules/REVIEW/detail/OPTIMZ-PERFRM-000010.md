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

- **与 `FOTLAB-RAWLER-000003` §A/§C 的建模分歧（人工已按本条实现拍板）**：该设计把 superpixel 建模为 `DemosaicAlgo::Superpixel` 枚举分支 + 正交的 `ScaleMode`。人工本次明确要求**独立布尔开关**（drawer 偏好），因此 `DemosaicAlgorithm` **没有**新增变体：「降采样」与「算法选择」是两个互不耦合的旋钮——ON 走 superpixel，OFF 走所选算法。那份文档保留为历史提案，未按新形状改写。
- **crop 抽取不再由"标志"驱动，也不写死倍数**：`crop_default` 从**实际 ROI 与 buffer 维度**推导整数抽取倍数（`decimation_factor`），再按整数除法缩放矩形（见 Finding 3）。这是"路径无关"在实现层的落点：尺寸就是唯一事实源，crop 不可能与 buffer 不一致；识别不出倍数时**显式报错**而非静默切成越界。
- **与 `OPTIMZ-PERFRM-000007` 收益互斥**：预览降到 1/4 后，并行化与编译优化带来的绝对收益同比缩小（1/4）。
- **`OPTIMZ-PERFRM-000001` 的 C4（画质契约）就此结案**：降分辨率不再需要"擅自降级"的许可，而是用户显式、默认关闭、随时可关的开关。
- **Coil 侧未动**：本条目只减小 native 的产出；`OPTIMZ-PERFRM-000003` 的「三处调用未给解码尺寸」仍独立存在，两者叠加才是完整的预览提速。
- **开关有可见延迟（按人工契约）**：因为不触发渲染，拨动后画面保持原样，直到下一次 develop（换文件、改 demosaic/曝光/白平衡、改 grade 都会触发）。drawer 的说明文案已写明"applies on the next develop"。
- **不可降采样时选择"禁用并说明"而不是"静默忽略"**：新增 `RawlerImageLoaded::supports_downsample`，与管线**共用同一守卫函数**（`demosaic::supports_downsample`），因此能力答复与管线行为不可能漂移。drawer 在 `false` 时禁用开关并给出原因文案。
- **回退是静默的**：X-Trans、Fuji 旋转、非 4 平面的非 RGGB 族 CFA、以及非 CFA（预着色）输入都会退回全分辨率 + 所选算法。管线内部没有日志（本 crate 未依赖 `log`，而新增依赖需要改 `Cargo.lock`，当前无本地 Rust 工具链 ⇒ 不做）。UI 侧由 `supportsDownsample` 兜住，但"管线为何回退"目前只体现在本条文档与代码注释里。
- **`grading` 分支同样生效**：grade fork 与 develop fork 走同一条 develop 管线，开关 ON 时 rawalchemy 也是在被降分辨率的 buffer 上运算（更少的像素，同样的算法）。

## Recommendation

1. 收益量化接 `OPTIMZ-PERFRM-000009` 的打点：同一张 RAW 在开关两态下的端到端耗时与峰值 RSS（预期 demosaic 之后的所有环节同比变快，而解码不变）。
2. `OPTIMZ-PERFRM-000003` 的另一半（给 Coil 指定解码尺寸）仍待做。
3. 若日后确实要"全分辨率 demosaic + demosaic 后 2×2 bin"，它是开关的**第三种取值**，届时应把布尔改成枚举（`Off/Quarter`），**不要再加第二个布尔**。
4. Fuji X-Trans 的 1/4 预览（若产品需要）需要上游提供 6×6 的 superpixel 原语，或退化为 demosaic 后的 bin —— 属上游改动，需按 `FOTLAB-NATIVE-000001` R4 走 recorded patch。

## Change History

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
