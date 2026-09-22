# 对数空间枚举的别名契约 — 展示名与引擎名由 shim 内的单一映射表对齐，上游保持只读

- ID: ACTION-RAWLER-000007
- Status: Implemented
- Priority: P2
- Created: 2026-09-22
- Owner: —
- Related: `rules/REVIEW/detail/FOTLAB-RAWLER-000006.md`（同一个 `rawalchemy_fotlab` cxx 胶水，本条目是其枚举面的延伸）、`rules/REVIEW/detail/ACTION-PERFOR-000001.md`（本条目所依据的枚举解耦背景）

## Background & Goal

Studio 的 LOG 选择器此前直接展示上游 `LOG_SPACES` 的键（`"F-Log2C"`、`"S-Log3"`、`"Log3G10"`……）。
这些键是上游的**内部编码**，不是任何厂商的官方名称：既没有厂商名，也没有大小写与空格规范。
终端用户需要看懂"这是哪家的什么曲线"，而引擎需要一个能查表的稳定键——这两个需求此前被压成同一个字符串。

目标：**在不修改 `external/RawAlchemyCpp` 的前提下**，把"给用户看的名字"与"给引擎查表的键"分开，
并让两者的对应关系只存在于一个地方。

## Finding

### 契约落地位置

上游 `LOG_SPACES`（`external/RawAlchemyCpp/include/color_data.h:134-149`）是
`unordered_map<string, LogSpaceInfo>`，14 条，键即内部编码。它是**计算侧**的事实源，本条目不改动它。

展示名的事实源固定在我们第一方的 cxx shim：

```
app/src/binding/cxx/rawalchemy_fotlab/cpp/rawalchemy_shim.cc
    kLogSpaceAliases[]        ← 唯一的别名表，{canonical, display} 两列 14 行
    resolve_log_space()       ← display 优先、canonical 兜底的解析器
    grade()                   ← 入口调用 resolve_log_space()，再查 LOG_SPACES
    log_spaces()              ← 只吐 display 列
```

两列各自的归属：

- `canonical` —— 上游 `LOG_SPACES` 的键，**唯一允许进入 `LOG_SPACES` 的字符串**。
- `display` —— Kotlin 渲染的名字，厂商名写全、曲线按厂商官方写法拼写。

`log_spaces()` 与 `grade()` 读同一张表，方向相反，因此"菜单列出的名字"与"引擎能解析的名字"
在结构上不可能分叉。

### 映射表（14 条，2026-09-22 人工核定）

| canonical（进 `LOG_SPACES`） | display（进 UI / 回传 shim） |
| --- | --- |
| `F-Log` | `FUJIFILM F-Log` |
| `F-Log2` | `FUJIFILM F-Log2` |
| `F-Log2C` | `FUJIFILM F-Log2 C` |
| `V-Log` | `Panasonic V-Log` |
| `N-Log` | `Nikon N-Log` |
| `L-Log` | `Leica L-Log` |
| `Canon Log 2` | `Canon Log 2` |
| `Canon Log 3` | `Canon Log 3` |
| `S-Log3` | `Sony S-Log3` |
| `S-Log3.Cine` | `Sony S-Log3.Cine` |
| `Arri LogC3` | `ARRI LogC3` |
| `Arri LogC4` | `ARRI LogC4` |
| `Log3G10` | `RED Log3G10` |
| `D-Log` | `DJI D-Log` |

人工核定时的四条决定：

1. 富士统一用全大写 **`FUJIFILM`**。
2. `F-Log2C` 的展示名按富士官方写法补空格 —— **`FUJIFILM F-Log2 C`**（canonical 保持无空格的 `F-Log2C`）。
3. `S-Log3.Cine` 的后缀指的是**色域**（S-Gamut3.Cine）而非另一条曲线（上游两条共用 `LogCurve::S_Log3`），
   但仍维持上游后缀写法 —— **`Sony S-Log3.Cine`**。
4. ARRI 用全大写 —— **`ARRI LogC3` / `ARRI LogC4`**。

厂商本身已带厂商名的（Canon）与官方写法一致的，display 列照抄 canonical，不做改动。

### 解析顺序

`resolve_log_space()` **先按 display 找，找不到再按 canonical 找**。前者保证菜单自己的词汇永远优先；
后者的存在是为向前兼容——别名化之前持久化的选择、直接经 FFI 传上游拼写的调用方、以及尚未迁移的测试。

## Impact / Conflict

- **单一映射点**：Kotlin / Rust 两侧都不拼写任何曲线名，也不认识上游的键。
  `StudioEngine.GradeSelection.logSpace` 直接持有 display 名，UniFFI 形状不变（仍是 `Vec<String>` / `Option<String>`）。
- **手动同步义务（本条目唯一的长期约束）**：别名表的 canonical 列必须与上游 `LOG_SPACES` 的键一致。
  上游新增曲线不会自动出现在菜单里；我们的表若多写一条上游不存在的 canonical，
  枚举会正常列出，直到 `grade()` 才失败——此时 shim 抛出的是区分度更高的错误
 （`maps to '<canonical>', which upstream does not provide`），而不是笼统的 unknown。
- **排序即分组**：Rust 侧 `supported_log_spaces()` 的 `sort()` 未改。加厂商前缀后，
  字母序天然按厂商聚类（ARRI → Canon → DJI → FUJIFILM → Leica → Nikon → Panasonic → RED → Sony），
  菜单可读性优于按内部编码排序。
- **测试契约随之改变**：`gradeLogSpacesAreEnumeratedNatively` 由"抽查 6 个厂商代表"改为
  **逐名断言排序后的完整 14 项**——厂商前缀正是要钉住的东西，抽查不再足够。
  `RawRoutingTest` 中经 UI 与经 `setGradeLogSpace` 的两条 E2E 路径改用 display 名，
  `gradeError` 必须保持为 null，即 display→canonical 的解析由这两条路径实际覆盖。
- **未独立覆盖的点（已知缺口）**：canonical 兜底分支没有专门的测试。
  它是为旧值兼容而加的防御路径，UI 永远不会走到；`grade()` 需要真实 RAW 缓冲才能抵达，
  现有冒烟测试不构造该场景。若后续要钉死它，需要在 `RawRoutingTest` 里加一步
  "用 canonical 拼写再评一次、帧应逐字节相同"的用例（注意该场景不能用
  `waitForDevelopedFrame(requireDifferentFrom = ...)` 等待，因为帧不会变化）。

## Recommendation

1. 保持别名表为**唯一**的映射点。后续若要新增曲线：先确认上游 `LOG_SPACES` 已有该键，
   再往 `kLogSpaceAliases` 追加一行，并同步 `gradeLogSpacesAreEnumeratedNatively` 的完整期望列表。
2. 若要正式化同步义务，可考虑在上游 vendored 版本升级时加一条检查（比对 canonical 列与
   `LOG_SPACES` 键集合），但当前规模（14 条、低变更频率）下人工同步足够。
3. 语言：`rules/REVIEW.md` §General Rules 第 1 条仍写着英文撰写；本条目按 2026-09-21/22 的人工口头许可
   用中文撰写，与 `ACTION-PERFOR-*` 批次一致。该规则文本本身未改，是否放宽仍待人工拍板。

## Change History

- 2026-09-22 — 创建并直接落地。别名表与 `resolve_log_space()` 加入 `rawalchemy_shim.cc`，
  `log_spaces()` 改为返回 display 列，`grade()` 改为 display→canonical 后再查上游 `LOG_SPACES`；
  上游 `external/RawAlchemyCpp` 未改动。同步更新 cxx 侧 `rawalchemy_api.h` / `src/lib.rs`、
  Rust 侧 `rawler_fotlab`（`lib.rs`、`develop.rs`）、Kotlin 侧 `RawlerFotlabBridge` 与 `StudioEngine` 的文档注释；
  两条冒烟测试的断言与注释改用 display 名。
- 2026-09-22 — 顺带清理两处已过期的注释（别名化之前写成"single-sourced from upstream's `LOG_SPACES` map"
  的 Rust 文档，以及写成"Upstream's `LOG_SPACES` table ships 14 curves"的冒烟测试注释）。
