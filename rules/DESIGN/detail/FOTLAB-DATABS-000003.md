# FOTLAB-DATABS-000003 — 递归软删除与孤儿清扫原子性调研（Kotlin 临时闭包集合方案）

- **文档状态**：调研文档（RESEARCH ONLY）
- **关联文档**：`FOTLAB-DATABS-000002`（虚拟树数据库设计，含 R5/R10/R12/R13 及 2026-10-05 Change History）、`FOTLAB-UIXDES-000004`（sync/refresh 图标与 R10 删除闸门）
- **涉及代码**：`LibraryRepository.kt`（删 `markDeleted` / `deleteNodes` / `restoreFromBin` / `sweepOrphanRelations`）、`LibraryCore.kt`（`refresh`）、`FsNodeRelationDao.kt`（`stampRelationsWithDeadParent` / `orphanNodeIds`）
- **日期**：2026-10-06

> ⚠️ **本文档仅记录调研与分析结论，不包含任何代码改动，也未提交。** 待用户就第 6 节列出的待定事项拍板后，再进入实施阶段。当前所有描述均基于 `FOTLAB-DATABS-000002` 既有约束与现有代码现状，不预示任何已落地的修改。

---

## 0. 调研目的

用户提出：在递归删除（`deleteNodes`）与孤儿节点清扫（`refresh` 中的 orphan 处理）中，扫描进行时某些父节点"实际上会被删除、但还没被删除"，这给"谁该删、谁还算活着"的判断带来干扰。为保证递归软删除的原子性、并确保同一批删除获得相同的 `time_deleted`，用户提出一套方案：

> 由 Kotlin 临时持有一个 list，暂存"待删除的节点和节点关系 id"，用**三元组**表示——（类型 = 节点或关系、节点 id、父节点 id；直接待删节点的父 id 为空）。（节点关系没有节点那样的独立 id，其 id 可以是元组形式。）凡递归扫描到要删的对象，就 append 到临时 list；后续搜索待删对象时只查临时 list，无需多轮清扫。

本文档评估该方案的**可行性、收益、以及与既有设计约束的冲突点**，并给出建议的落地形态与待用户决定的事项。

---

## 1. 当前实现现状（先确认"已正确"的部分）

"父还没落库、判断已要做"这个担忧，当前代码逻辑上是**自洽的**，只是把"判断"和"落库"耦合在同一次递归里。

看 `LibraryRepository.deleteNodes`（行 187–200）与 `markDeleted`（行 225–261）：

```kotlin
suspend fun deleteNodes(nodeIds: Collection<Long>) {
    val targets = deletableIds(nodeIds)
    if (targets.isEmpty()) return
    val now = System.currentTimeMillis()
    database.withTransaction {
        val pending = ArrayDeque<Long>()
        pending.addAll(targets)
        while (pending.isNotEmpty()) {
            markDeleted(pending.removeLast(), now, pending)
        }
    }
}

private suspend fun markDeleted(nodeId: Long, now: Long, pending: ArrayDeque<Long>) {
    val relationDao = database.nodeRelationDao()
    val node = database.nodeObjectDao().getById(nodeId) ?: return
    if (node.timeDeleted != null) return

    for (relation in relationDao.relationsWithChild(nodeId)) {
        relationDao.update(relation.copy(timeDeleted = now))   // 先把"作为子"的边戳掉
    }
    for (relation in relationDao.relationsWithParent(nodeId)) {
        relationDao.update(relation.copy(timeDeleted = now))   // 再把"作为父"的边戳掉
        val childId = relation.fsNodeIdChild
        // 多对多：只有该子节点所有活父边都没了，它才入队
        if (relationDao.activeParentCount(childId) == 0) {
            pending.addLast(childId)
        }
    }
    database.nodeObjectDao().markDeleted(nodeId, now)
}
```

关键点：

- **整个递归跑在 `database.withTransaction { }` 里、同一 SQLite 连接**。先 `update(relation.copy(timeDeleted = now))` 把边戳上戳，**再** `activeParentCount(childId)` 去数还活着的父边——刚戳的那条边对同连接**可见**（`time_deleted IS NOT NULL` 被排除），所以"数到的活父"已经反映了本事务内已戳的边。
- 这正是 `markDeleted` 注释里"**故意按边计数、不能按父节点存活计数**"的原因：正在删的父在同一事务里刚被戳，按父节点存活测会把父误判成还活着，子节点就会比父多活一轮（注释 248–254 行已写明）。
- 因此当前行为：节点 + 其子树边，已是**单 `now` + 单事务**，满足 `FOTLAB-DATABS-000002` 的 R10（同批次同 `time_deleted`）、R13（单事务、原子）、R12（子树边随节点一起删）。**"同批次同 `time_deleted`"对"节点删除"这件事已经成立。**

> 结论：用户的痛点（父未落库导致判断困难）在当前代码里**已被 `withTransaction` 同连接可见性隐式解决**，但这是一个**隐含不变量**——正确性依赖于"递归全程在同一事务、同一连接上可读到己写"。

---

## 2. 用户方案的价值（实打实的改进点）

把"要删什么"先收进一个 in-memory 集合、达到不动点后再统一落库，价值在以下方面，均为真实收益：

1. **去掉对"读己写"的隐式依赖**。当前正确性建立在 `withTransaction` 同连接可见性上；一旦有人改连接池/查询写法（例如把计数改成跨连接查询或缓存）就会静默出错。in-memory 闭包把这个判断**显式化**，不依赖数据库可见性语义。
2. **可纯 Kotlin 单测**。"算闭包"是不碰 DB 的纯函数，多对多、孤儿、循环等情况都能直接喂集合做断言，不用起 Room / 模拟数据库。
3. **节点删除 + 孤儿节点清扫合一**。`refresh()` 里 `missing + orphans`（行 250）的合并更自然，无需分两轮。
4. **三元组表示本身是正确的**：关系没有独立 id，但 `(fs_node_id_child, fs_node_id_parent)` 是 UNIQUE 复合主键，正好当"关系 id"。为清晰起见，落地时建议拆成两个集合 `nodeIds: Set<Long>` 与 `relationKeys: Set<Pair<Long,Long>>`，比单一三元组 list 更利于去重与判等。

---

## 3. ★ 必须先拍板的冲突：孤儿"关系"不能并进同批次

这是调研里**最重要的发现**。用户方案说"list 临时存放需要被删除的**节点和节点关系**"，并要"同一批删除获得相同 `time_deleted`"——这会把**孤儿关系**也并进节点批次。但 `FOTLAB-DATABS-000002` 的 R5 与 2026-10-05 Change History 白纸黑字写着：

> *Orphan relations are sweepable, stamped under their own batch timestamp — they belong to no deleted node's subtree walk, so they must not join the node sweep's batch (a delete is one atomic unit that can be undone as one unit).*

原因在于还原语义。看 `LibraryRepository.restoreFromBin`（行 274–286）：

```kotlin
suspend fun restoreFromBin(ids: List<Long>) {
    val batches = ids.mapNotNull { objectDao.getById(it)?.timeDeleted }.toSet()
    database.withTransaction {
        for (time in batches) {
            objectDao.restoreNodesByBatch(time)
            relationDao.restoreRelationsByBatch(time)
        }
    }
}
```

还原是**按批次 `time_deleted` 一体还原**的。`stampRelationsWithDeadParent`（孤儿关系清扫）戳的是"父节点已软删 / 父为 null"的悬空边（`FsNodeRelationDao`：`UPDATE fs_node_relation SET time_deleted = :timeDeleted WHERE time_deleted IS NULL AND NOT EXISTS (SELECT 1 FROM fs_node_object p WHERE p.fs_node_id = fs_node_relation.fs_node_id_parent AND p.time_deleted IS NULL)`）。

如果让孤儿关系**共享**用户删除的 `now`，那"按批次还原"就会把这些悬空边也 `time_deleted = NULL` 清掉——等于把一条指向已删/null 父的边**复活成新的悬空边**。所以孤儿关系**必须是独立批次**。

当前 `refresh()` 正是这么做的：

```kotlin
val toDelete = (missing + orphans).toSet()
if (toDelete.isNotEmpty()) {
    repo().deleteNodes(toDelete)                      // now1：节点批次
}
repo().sweepOrphanRelations(System.currentTimeMillis())  // now2 ≠ now1：孤儿关系独立批次
```

**用户的方案若把孤儿关系也并进 `now1`，就违反了这条已定的设计。** 这是落地前必须抉择的第一冲突。

---

## 4. 推荐落地形态（待拍板后实施）

两阶段，但把"关系"拆成两类，避开第 3 节的冲突：

- **Phase 1 — 算闭包（纯 Kotlin，不落库）**：从 `targets` 出发，产出 `nodeIds: Set<Long>` + `relationKeys: Set<(child,parent)>`。多对多规则 = "某子节点的所有活父边都在集合里，它才入集"；用 `Set` 天然去重、防循环。此阶段只读必要的图结构，不写库，得到不动点即停。
- **Phase 2 — 统一落库**：单事务、单 `now`，把 `nodeIds` 与 `relationKeys` 一次性 stamp。覆盖 R12 的"子树边随节点一起删"。
- **孤儿关系**：仍走 `stampRelationsWithDeadParent`，**保持独立批次**（用 `now2`），不并入节点批次（见第 3 节）。

这样"节点删除 + 孤儿节点清扫"得到用户想要的原子性与同 `time_deleted`，且不破坏还原语义。

> 注：此形态只是**建议骨架**，未落到代码，也未讨论细节（如闭包计算的图查询来源、是否仍依赖 `withTransaction` 读图等）。

---

## 5. 待用户决定的事项

进入实施前，需用户拍板：

1. **孤儿关系要不要并入节点批次？**（即第 3 节的冲突）
   - 建议：**不并入**，维持 `FOTLAB-DATABS-000002` 已定设计（独立批次、还原语义安全）。
   - 若用户明确要推翻该条、把悬空边并入用户批次，需同步修订 R5 与还原逻辑——**不推荐**。
2. **`now` 的来源**：当前用 `System.currentTimeMillis()`，`FOTLAB-DATABS-000002` 文档 Q9 已标注同毫秒可能碰撞。in-memory 闭包方案不解决这个；是否顺手换成单调计数器（如 `atomicLong` / 更高精度来源）需要决策。
3. **闭包规模上限**：深嵌套收藏夹的闭包可能很大，但本库规模下只是 `Long` 集合，无虞；若担心可加阈值或分页计算，需不需要由用户定。

---

## 6. 结论

- 用户提出的核心痛点（父未落库导致判断困难）在当前代码中已被 `withTransaction` 同连接可见性**隐式**解决，但正确性建立在隐含不变量上。
- 把"待删集"显式收进 in-memory 闭包，是**稳健的改进方向**：去隐含依赖、可纯 Kotlin 测试、便于节点删除与孤儿节点清扫合一。
- 但方案必须把"孤儿关系"与"节点子树边"区分对待，否则会破坏 `FOTLAB-DATABS-000002` 已定的还原语义（独立批次约束）。
- 在用户就第 5 节三项拍板之前，**不实施任何代码改动**。

---

> ⚠️ **再次声明（文档结尾）：本文档为调研产出，仅梳理可行性与冲突点。当前所有描述均为分析结论，未修改任何代码、未提交、未触发 CI。** 待用户就第 5 节待定事项做出决定后，方可进入实施阶段。
