# Shared FsNode DAO 重构后全 routine 静态审计

- ID: ACTION-KOTLIN-000009
- Status: Observation
- Priority: P2
- Created: 2026-11-28
- Owner: —
- Related: `rules/REVIEW/detail/ACTION-LIBRND-000002.md` (two-table batch audit), `rules/REVIEW/detail/ACTION-KOTLIN-000002.md` (original refresh no-op bug), `rules/DESIGN/detail/FOTLAB-DATABS-000002.md` (schema)
- Files audited: `data/common/FsNodeObjectDao.kt`, `data/common/FsNodeRelationDao.kt`, `data/common/FsNodeRepository.kt`, `data/common/FsNodeDatabase.kt`, `feature/library/LibraryCore.kt`, `feature/gallery/GalleryCore.kt`, `feature/library/LibraryDatabase.kt`, `feature/gallery/GalleryDatabase.kt`

## Background & Goal

DAO 层从 feature-specific 重构为 `data/common/` 下的共享组件：两个 DAO interface（`FsNodeObjectDao` / `FsNodeRelationDao`），一个参数化的 `FsNodeRepository`（注入 `RootConfig`），一个抽象的 `FsNodeDatabase` 基类。Library 和 Gallery 各持有独立 Room DB 实例（物理文件隔离），但共享同一 DAO + Repository 代码路径。

本次审计目标：重构后的 DAO/Repository 是否正确覆盖所有数据操作路径——sync 清理（refresh）、soft delete（markDeleted）、restore from bin、delete forever、revive/重导入、批次选择展开（`deletedNodeIdsAt`），以及两个 feature 的对称接入是否正确。

## Findings

### 1. Refresh（sync 清理）—— 正确 ✓

两个 Core 的 refresh 实现完全对称：

```kotlin
// LibraryCore.refresh() / GalleryCore.refresh()
val missing = repo.fileEntryNodes().filter { !uriExists(it.uriStorage) }.mapNotNull { it.fsNodeId }
val orphans = repo.orphanNodeIds().mapNotNull { it }
repo.deleteNodes((missing + orphans).toSet())          // ← batch timestamp T_refresh
repo.sweepOrphanRelations(System.currentTimeMillis())  // ← 独立 timestamp T_sweep
```

**设计选择**——`deleteNodes` 和 `sweepOrphanRelations` 用了两个不同的 `timeDeleted`。这意味着 refresh 可能产生两个回收站批次：

- `deleteNodes` 扫的是文件实际不存在的节点 + orphan 节点（批量 soft delete，同一 transaction）
- `sweepOrphanRelations` 扫的是 `NOT EXISTS (live parent node)` 的 live 边——正常 `markDeleted` 路径已经把所有应 stamp 的边都 stamp 了，所以这个 sweep **通常扫不到任何行**（0 行受影响，不会产生空批次）。只有在数据不一致（手动操作、历史版本残留）时才生效。

**结论**：不是 bug，是防御性设计。如果要统一 timestamp，可以让 `deleteNodes` 返回它用的 `now`，再传给 `sweepOrphanRelations`。当前可接受。

### 2. Soft Delete（markDeleted）—— 正确 ✓

递归逻辑验证（场景：`Root → A → B`，删 A）：

| 步骤 | 操作 | 边状态 |
|------|------|--------|
| 1 | `relationsWithChild(A)` → 取 A 作为 child 的 live 边 | 取到 `A→Root` |
| 2 | 对每条边执行 `relation.copy(timeDeleted = now)` | `A→Root` stamp T1 |
| 3 | `relationsWithParent(A)` → 取 A 作为 parent 的 live 边 | 取到 `B→A` |
| 4 | 对每条边 stamp T1；检查 `activeParentCount(B) == 0` → B 入 pending | `B→A` stamp T1 |
| 5 | `markDeleted(nodeId, T1)` | A.time_deleted = T1 |
| 6 | `markDeleted(B, T1)` → `relationsWithChild(B)` 返回空（`B→A` 已被 stamp，`relationsWithChild` 只查 `IS NULL`） | 直接 stamp B.time_deleted = T1 |

**幂等设计**：

- 节点幂等：`node.timeDeleted != null` 守卫，已删节点跳过
- 边幂等：`relationsWithChild` / `relationsWithParent` 都只查 `time_deleted IS NULL`，同一条边不会被重复 stamp
- 同一 batch 内的边 stamp 相同 timestamp，UPDATE 相同值是 SQL no-op

**多 parent 安全**：`activeParentCount(childId)` 检查的是**剩余 live 边数**，已经把当前正在 stamp 的边排除在外（因为它还没 commit... 不对，在同一个 transaction 里 `activeParentCount` 查的是 DB 当前值，UPDATE 还没 commit）——但实际上 `relationsWithParent` 先把边 UPDATE 成 stamped，然后才调用 `activeParentCount`，所以 DB 里这条边已经是 stamped 状态（Room 的 `@Update` 直接 UPDATE 行），所以 count 正确返回"剩余 live 边数"。

### 3. Delete Forever —— 正确 ✓

```kotlin
val batches = ids.mapNotNull { objectDao().getById(it)?.timeDeleted }.toSet()
queue.add(id to batch)
while (queue.isNotEmpty()) {
    batchChildIdsOf(node, batch) → 取同 batch 子边
    + getById(child).timeDeleted == batch → 双重守卫
}
```

**安全性分析**：

- `batchChildIdsOf(parentId, batchTime)` 只取 `time_deleted = batchTime` 的边——不会跨 batch 拉节点
- `getById(child).timeDeleted == batch` 守卫确保 child 本身也属于这个 batch
- 两者结合，live 子节点（被其他 parent 引用，batch 内 markDeleted 时没被 stamp）的 `timeDeleted` 是 NULL，被安全跳过

**场景验证**：B 同时在 A（batch=T1）和 C（live）下，deleteForever(batch=T1)：

- A 入 toDelete
- `batchChildIdsOf(A, T1)` 返回 B（B→A 的 time_deleted=T1）
- 但 `getById(B).timeDeleted` 是 NULL（B 因 C 还活着，markDeleted 时没被 stamp）
- 守卫 `== batch` 不满足 → B 不入 toDelete ✓
- 最终 toDelete={A}，硬删 A 和 B→A 边，B→C 不受影响 ✓

### 4. Restore From Bin —— 正确 ✓

```kotlin
val batches = ids.mapNotNull { objectDao().getById(it)?.timeDeleted }.toSet()
database.withTransaction {
    for (time in batches) {
        objectDao().restoreNodesByBatch(time)    // SET time_deleted = NULL
        relationDao().restoreRelationsByBatch(time)  // SET time_deleted = NULL
    }
}
```

两表成对恢复，同一 transaction 内。ids 可以跨多个 batch——自动合并所有涉及的 batch。

**边界安全**：如果用户意外传入 live 节点 id（`timeDeleted == null`），`mapNotNull` 过滤掉它（`.timeDeleted` 返回 null）。Repository 层对"混入 live id"是安全的。

### 5. 批次选择展开（deletedNodeIdsAt）—— 正确 ✓

```kotlin
// FsNodeObjectDao :96
@Query("SELECT fs_node_id FROM fs_node_object WHERE time_deleted = :time AND fs_node_id IS NOT NULL")
suspend fun deletedNodeIdsAt(time: Long): List<Long>
```

只查节点表——正确，因为它只服务于"批次文件夹 → 展开成 selectedIds"这个中间翻译步骤。后续 restore/deleteForever 从 ids 反推 batch time，再同时操作两张表。

Gallery 同样接入了 `recycleBatchNodeIds(time)`（`GalleryCore.kt:115`），如果 Gallery 也实现批次选择 UI，可以直接复用。

### 6. import / revive / 重导入 —— 正确 ✓

```kotlin
// 重导入流程
val existing = repo.getByUriAnyStatus(uri)   // 跨软删除状态查找
if (existing.timeDeleted != null) {
    repo.revive(id, name, mime)               // 清 time_deleted
}
repo.link(childId, parentId)
  // link 内部：
  relationDao().reviveRelation(childId, parentId)  // 清旧边的 time_deleted
  relationDao().insert(FsNodeRelation(childId, parentId))  // IGNORE
```

**一致性边界**：一个被删节点原来在 A/C 下，revive 后只重新 link 到 B。之后 restore batch T1（A/C 的删除批次）会同时恢复 `child→A` 和 `child→C` 两条旧边。此时 child 同时在 A、C、B 三个 parent 下。

这是**正确行为**：restore 要完整还原删除前的状态。如果产品不希望 revive 后 restore 产生多 parent，需要在 revive 时清掉该节点所有 batch 的旧边——但当前行为更符合"restore = undo"的语义。

### 7. 两表 batch 维护总览

| 路径 | `fs_node_object` | `fs_node_relation` | Transaction |
|------|-----------------|-------------------|-------------|
| Soft delete | `markDeleted(nodeId, now)` | 所有 child/parent 边 stamp `now` | Single |
| Restore | `restoreNodesByBatch(time)` → NULL | `restoreRelationsByBatch(time)` → NULL | Single |
| Delete forever | `deleteNodesForever(toDelete)` 硬删 | `deleteRelationsForever(toDelete)` 硬删 | Single |
| Refresh orphan sweep | — （不改节点） | `stampRelationsWithDeadParent(T_sweep)` | refresh 本身 |
| Refresh missing+orphan | `deleteNodes` 递归 batch stamp | — （被 markDeleted 内部处理） | Single |
| Revive 节点 | `revive(id)` → NULL | — （reviveRelation 在 link 时） | link transaction |
| Revive 边 | — | `reviveRelation(child, parent)` → NULL | link transaction |

**核心不变量**：除了 `sweepOrphanRelations`（防御性扫孤儿边），所有 batch stamp 都是两表成对的。

## Impact / Conflict

- **与 ACTION-LIBRND-000002**：那次审计聚焦于两表 batch 维护的 lockstep + 虚拟批次文件夹选择；本次审计扩展到 DAO 重构后的完整 routine 覆盖（refresh / revive / import / 跨 feature 对称接入）。互补，无冲突。
- **与 ACTION-KOTLIN-000002**：原始 refresh bug（`count >= 0`）已被修复（当前代码是 `count > 0`）。refresh 流程本身正确，只是 `deleteNodes` 和 `sweepOrphanRelations` 用了不同 timestamp——设计选择而非 bug。
- **Gallery 对称接入**：`GalleryCore` 和 `LibraryCore` 代码几乎完全镜像，唯一差异是 `RootConfig`（不同 id/mime/name）和 DB 文件名。这是共享 Repository 参数化设计的预期效果。

## Recommendation

1. **当前算法全部正确**——保留共享 DAO + 参数化 Repository 架构。
2. **可选优化**：让 `deleteNodes` 返回它用的 `now` timestamp，传入 `sweepOrphanRelations(now)` 统一 batch。不是 bug 修复，是可选的 UX 一致性改进（避免回收站里出现"纯边 batch"）。
3. **Gallery 同样可实现批次选择 UI**——它已经有 `recycleBatchNodeIds(time)` 方法，代码可直接复用。

## Change History

- 2026-11-28 — 重构后首次全 routine 静态审计。覆盖 7 条数据路径，全部正确。发现 refresh 两个 timestamp 是设计选择而非 bug。
