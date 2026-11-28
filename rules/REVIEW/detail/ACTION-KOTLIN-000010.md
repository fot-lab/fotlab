# Studio 对 Library / Gallery 跨 feature 调用审计

- ID: ACTION-KOTLIN-000010
- Status: Observation
- Priority: P2
- Created: 2026-11-28
- Owner: —
- Related: `rules/REVIEW/detail/ACTION-KOTLIN-000009.md` (DAO 重构全 routine 审计), `feature/studio/StudioScreen.kt`, `feature/studio/StudioEngine.kt`, `feature/library/LibraryCore.kt`, `feature/gallery/GalleryCore.kt`

## Background & Goal

Studio 是 Snapseed 风格的渲染/编辑引擎，独立于 Library/Gallery 的虚拟文件树。它需要和两个 tree feature 交互：打开文件时登记到 Library，导出时登记到 Gallery。本次审计目标：这些跨 feature 调用是否正确、URI 是否一致、是否存在初始化竞态、是否有遗漏调用。

## Call Site 总览

Studio 对 LibraryCore / GalleryCore **只有两处调用**，都在 `StudioScreen.kt`：

| # | 位置 | 代码 | 用途 |
|---|------|------|------|
| ① | StudioScreen.kt:153 | `LibraryCore.importUris(LibraryCore.currentDirectoryId.value, listOf(picked))` | Studio Open 把文件登记到 Library |
| ② | StudioScreen.kt:348 | `GalleryCore.importUri(GalleryRoot.ID, target)` | Studio Export 把成品登记到 Gallery |

两个 Core 都在 `MainApplication.onCreate()` 同步 prepare（`runBlocking` 阻塞到 DB ready），不存在初始化竞态。

---

## Finding ① — Import 到 Library（正确 ✓）

```kotlin
// StudioScreen.kt:149-155
scope.launch {
    mediaPref.setLastImportUri(picked.toString())
    LibraryCore.importUris(LibraryCore.currentDirectoryId.value, listOf(picked))
    StudioEngine.setCurrentNode(picked.toString())
}
```

**调用链**：

```
LibraryCore.importUris(parentId, [uri])
  ├─ getByUriAnyStatus(uri)              // 查 DB 判重（跨软删除状态）
  │   ├─ existing.timeDeleted != null → revive(existing.fsNodeId!!, name, mime)  // 复活
  │   └─ existing == null → addNode(name, mime, uriText, now)  // 新增
  └─ link(childId, parentId)              // transaction: reviveRelation + IGNORE insert
StudioEngine.setCurrentNode(uriString)
  └─ Uri.parse → contentResolver.openInputStream()  // 直接用 SAF URI 开流
```

**关键设计**：`setCurrentNode` **不查 DB**——它直接用 `content://` URI 从 ContentResolver 开流。所以 `importUris` 和 `setCurrentNode` 是两个独立操作：

- import → 把文件**登记**到 Library 的虚拟目录树（Library 界面能看到）
- setCurrentNode → **直接渲染** SAF URI 指向的物理文件

两者用的 URI 字符串完全相同（`picked.toString()`），符合一致性要求。

**`currentDirectoryId.value` 同步读取安全**：它是 `MutableStateFlow<Long>`，prepare 后默认值是 `LibraryRoot.ID`（0L），Library 界面导航时通过 `LibraryCore.setCurrentDirectory(id)` 更新，Studio 读到的就是用户最后浏览的目录。

---

## Finding ② — Export 到 Gallery（正确 ✓）

```kotlin
// StudioScreen.kt:342-349
scope.launch(Dispatchers.IO) {
    runCatching {
        val bytes = encodeExport(context, displayedResult, format, committedCropRotateParams)
        if (bytes != null) {
            context.contentResolver.openOutputStream(target)?.use { it.write(bytes) }
            GalleryCore.importUri(GalleryRoot.ID, target)
        }
    }
}
```

**调用链**：

```
先 encodeExport() + contentResolver.openOutputStream(target)  ← PNG/PDF bytes 物理落盘
再 GalleryCore.importUri(GalleryRoot.ID, target)
  ├─ takePersistableUriPermission(target, write = false)  // 持久化读权限
  ├─ getByUriAnyStatus(target.toString())                 // Gallery DB 判重
  ├─ existing.timeDeleted != null → revive(...)           // 复活
  ├─ existing == null → addNode(...)                      // 新增
  └─ link(childId, GalleryRoot.ID)                         // transaction: reviveRelation + IGNORE insert
```

**与 Import 的对称与差异**：

- **流程对称**：先写物理文件（SAF target），再登记到虚拟目录树
- **GalleryRoot.ID 硬编码**：导出作品统一进 Gallery 顶层（`GalleryRoot.ID = 0L`），不尊重 currentDirectory——合理，Studio 导出的是"作品"，不是普通文件
- **Import 用 Library currentDirectoryId**：尊重用户正在浏览的 Library 目录——Studio import 是"打开一个文件"，登记位置自然跟随用户上下文

---

## Finding ③ — StudioEngine 完全独立，零 DB 依赖 ✓

StudioEngine 不 import `LibraryCore` 或 `GalleryCore`。它只负责渲染管线：

```
FormatSniffer.sniff(header, timeout)
  → route(verdicts)
    ├─ Route.RawToRaster → RawlerFotlabBridge.loadRawlerImageFromFile(path) → renderRawlerImage → PNG
    ├─ Route.ToCoil → Coil 直接渲染 SAF URI
    └─ Route.Unsupported → StudioRenderResult.Unsupported
```

StudioEngine 的输入是 SAF URI 字符串，输出是渲染结果。它不知道 Library/Gallery 的存在，也不关心虚拟目录树。

**好处**：Studio 可以在没有 Library/Gallery 的情况下独立渲染任意 SAF URI（比如 CameraCore 直接传一个 URI 进来）。

---

## Finding ④ — refresh 不依赖 Studio ✓

Studio 不触发 refresh。Library/Gallery 的 refresh 在各自的生命周期（prepare / resume）触发，Studio 不参与 orphan 清理。

Studio 只关心"SAF URI 能不能打开"——这由 ContentResolver 保证，和 Library/Gallery DB 的 refresh 状态无关。

---

## Finding ⑤ — 初始化顺序 ✓

```kotlin
// MainApplication.onCreate()
LibraryCore.prepare(this)   // runBlocking → Repository ready
GalleryCore.prepare(this)   // runBlocking → Repository ready
StudioEngine.prepare(this)
CameraCore.prepare(this)
```

LibraryCore / GalleryCore 先 prepare，确保当用户在 Studio 里打开文件时，DB 已经 ready。不存在"Studio importUris 时 Library DB 还没初始化"的竞态。

---

## 总结

| 方面 | 状态 |
|------|------|
| Import → Library | ✅ 正确 |
| Export → Gallery | ✅ 正确 |
| URI 字符串一致性 | ✅ 两侧用同一个 SAF URI 字符串 |
| 初始化顺序 | ✅ Library/Gallery 先 prepare，无竞态 |
| StudioEngine 独立性 | ✅ 完全独立，不碰 DB |
| revive / 重导入 | ✅ `getByUriAnyStatus` 跨软删除，同一文件重复导入会 revive 而非 duplicate |
| refresh 解耦 | ✅ Studio 不触发也不依赖 refresh |

**零错误、零遗漏。** Studio 与 Library/Gallery 的耦合恰到好处——只在文件"进入"（import 到 Library）或"离开"（export 到 Gallery）Studio 时做登记，渲染时完全独立走 SAF URI。

## Impact / Conflict

- 与 ACTION-KOTLIN-000009（DAO 重构审计）互补：那次审计覆盖数据层 routine，本次覆盖跨 feature 调用边界。
- 无冲突。

## Recommendation

保持当前设计——跨 feature 调用严格限制在 import/export 两个入口，StudioEngine 不直接碰 DB。

## Change History

- 2026-11-28 — 首次 Studio 跨 feature 调用审计。确认两处调用正确、URI 一致、初始化顺序安全、StudioEngine 完全独立。零 bug。
