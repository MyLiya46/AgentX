# Spec: MyBatis-Plus 逻辑删除与表结构失配修复

- **创建日期**: 2026-07-29
- **状态**: 待实施
- **关联 Plans**:
  - [decouple-logic-delete-from-base-entity-2026-07-29](../plans/decouple-logic-delete-from-base-entity-2026-07-29.md)

---

## 一、问题陈述

### 1.1 触发 Bug

`GET /api/portal/memory/items?page=1&pageSize=15` 返回 **500 错误**：

```
SQL: SELECT COUNT(*) AS total FROM memory_items
     WHERE deleted_at IS NULL AND (user_id = ?)
Error: column "deleted_at" does not exist
```

### 1.2 调用链路

```
PortalMemoryController.list()
  → MemoryAppService.listUserMemories()
    → MemoryDomainService.pageMemories()
      → MemoryItemRepository.selectPage()
        → MyBatis-Plus 自动注入 WHERE deleted_at IS NULL
          → 数据库: memory_items 表无 deleted_at 列 → 500
```

### 1.3 根因三层分析

| 层级 | 位置 | 问题描述 |
|------|------|---------|
| **L1 — 基类强制** | `BaseEntity.java:17` | `@TableLogic` 注解强制所有 37 个子类实体启用逻辑删除 |
| **L2 — 全局配置** | `application.yml:74-76` | `logic-delete-field: deleted_at` 全局开启 MyBatis-Plus 逻辑删除，自动在所有 SQL 中注入 `WHERE deleted_at IS NULL` |
| **L3 — 表结构不同步** | `memory_items` 实际表 | 建表时参考 `memory_schema.md` 设计文档，该文档未包含 `deleted_at` 列；虽然 `01_init.sql:1191` 定义了该列，但实际运行数据库未执行该脚本 |

**本质问题**：`BaseEntity` 将逻辑删除作为一种"默认必须"的行为强加给所有子类，但并非所有表都支持或需要 `deleted_at` 列的 MyBatis-Plus 逻辑删除语义。`memory_items` 表使用 `status` 字段（1=active, 0=archived）实现软删除，与 MyBatis-Plus 的 `deleted_at` 逻辑删除机制冲突。

### 1.4 影响面

项目中共有 **37 个实体**继承 `BaseEntity`：

| 模块 | 实体数 | 示例 |
|------|--------|------|
| agent | 4 | `AgentEntity`, `AgentVersionEntity`, `AgentWorkspaceEntity`, `AgentWidgetEntity` |
| rag | 9 | `UserRagEntity`, `RagVersionEntity`, `DocumentUnitEntity` 等 |
| memory | 1 | `MemoryItemEntity` ← **当前炸点** |
| conversation | 3 | `SessionEntity`, `MessageEntity`, `ContextEntity` |
| user | 4 | `UserEntity`, `AccountEntity`, `UsageRecordEntity`, `UserSettingsEntity` |
| tool | 3 | `ToolEntity`, `ToolVersionEntity`, `UserToolEntity` |
| llm | 2 | `ProviderEntity`, `ModelEntity` |
| task / trace / container / auth / apikey / product / order / rule / scheduledtask | 11 | 其他业务实体 |

任意一个表缺少 `deleted_at` 列就会触发同样的 500 错误。当前仅在 `memory_items` 上暴露。

### 1.5 关联的潜在 Bug

`MemoryDomainService.delete()` 当前实现也有问题——调用 `repository.delete(qw)` 时，MyBatis-Plus 会将其改写为 `UPDATE SET deleted_at = now()`，同样因列不存在而失败。

---

## 二、目标架构

### 2.1 核心原则

**逻辑删除从"强制继承"改为"按需声明"** — 每个实体根据自身业务语义和表结构，自主决定是否启用 MyBatis-Plus `@TableLogic`。

### 2.2 变更前后对比

```
=== 变更前（当前）===
BaseEntity (@TableLogic on deletedAt)          ← 强制所有子类逻辑删除
  ├── AgentEntity           → WHERE deleted_at IS NULL ✅
  ├── MessageEntity         → WHERE deleted_at IS NULL ✅
  ├── MemoryItemEntity      → WHERE deleted_at IS NULL ❌ 列不存在!
  └── ... 34 个其他实体        → WHERE deleted_at IS NULL (取决于表结构)

application.yml
  logic-delete-field: deleted_at               ← 全局配置生效于所有实体

=== 变更后（目标）===
BaseEntity (deletedAt 仅为普通字段，无 @TableLogic)
  ├── AgentEntity           → @TableLogic 显式声明 ✅
  ├── MessageEntity         → @TableLogic 显式声明 ✅
  ├── MemoryItemEntity      → 无 @TableLogic，用 status 字段软删除 ✅
  └── ... 34 个其他实体        → 逐个审计，按需声明 @TableLogic 或使用替代策略

application.yml
  (移除 logic-delete-field 全局配置)
```

### 2.3 MemoryItemEntity 的删除策略

`memory_items` 表有独立的 `status` 字段管理生命周期：

| 操作 | 当前（已炸） | 修复后 |
|------|------------|--------|
| 查询列表 | `WHERE deleted_at IS NULL` ← 自动注入 | `WHERE status = 1` ← 显式过滤 |
| 删除（归档） | `repository.delete(qw)` → 尝试 `SET deleted_at = now()` | `UPDATE SET status = 0` |

### 2.4 设计文档同步

已同步更新 [docs/tech_disign/memory_schema.md](../tech_disign/memory_schema.md)：
- 字段定义中补充 `deleted_at` 列及其语义说明
- 明确标注 `MemoryItemEntity` 不使用 `@TableLogic`，软删除由 `status` 字段管理
- 更新示例 DDL 和实体字段列表

---

## 三、方案选择（已决策）

| 方案 | 思路 | 评估 | 
|------|------|------|
| **A（采纳）** | 移除 `BaseEntity` 的 `@TableLogic`，改为各实体按需声明 | ✅ 根治、可增量迁移、尊重各表差异 |
| B（不采纳） | 给 `memory_items` 加 `deleted_at` 列，确保所有表统一 | ❌ 治标不治本、双重软删除机制、未来新增表仍会出问题 |
| C（不采纳） | 自定义注解让实体退出逻辑删除 | ❌ 与 MyBatis-Plus 内部行为冲突、维护成本高 |

---

## 四、影响范围

| 文件 | 操作 | 风险等级 |
|------|------|---------|
| `BaseEntity.java` | 移除 `@TableLogic` 注解（字段保留） | **高** — 影响所有 37 个子类 |
| `application.yml` | 移除 `logic-delete-field` 全局配置 | **高** — 必须与 Step 1 同步 |
| `MemoryItemEntity.java` | 不使用 `@TableLogic`（不变更） | 低 |
| `MemoryDomainService.java` | `pageMemories()` 加 `status=1` 过滤；`delete()` 改为 `UPDATE SET status=0` | 中 — 业务逻辑变更 |
| `PortalMemoryController.java` | `delete()` 返回值处理适配 | 低 |
| ~30 个实体类 | 逐个添加 `@TableLogic` 覆写 | 中 — 需全面审计 |
| `docs/tech_disign/memory_schema.md` | 补充 `deleted_at` 列定义和删除策略说明 | 低 |

---

## 五、验收标准

1. `GET /api/portal/memory/items` 正常返回 200，不再报 `column "deleted_at" does not exist`
2. `DELETE /api/portal/memory/items/{itemId}` 正常归档（`status` 设为 0），不再报错
3. 已归档记忆不出现在列表查询结果中（`status = 0` 被过滤）
4. 已启用 `@TableLogic` 的实体（如 `AgentEntity`, `MessageEntity`）查询行为不变
5. 全量单元测试通过（`mvn test`）
6. 编译通过（`mvn clean compile`）
