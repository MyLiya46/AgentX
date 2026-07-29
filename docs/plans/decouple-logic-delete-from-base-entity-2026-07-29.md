# Plan: BaseEntity 逻辑删除去强制化 + memory_items 软删除修复

- **创建日期**: 2026-07-29
- **关联 Spec**: [mybatis-plus-logic-delete-column-mismatch-fix-2026-07-29](../specs/mybatis-plus-logic-delete-column-mismatch-fix-2026-07-29.md)
- **前置依赖**: 必须先完成数据库迁移（为 `memory_items` 添加 `deleted_at` 列），否则代码变更后 INSERT 操作仍会因列不存在而失败
- **状态**: 待实施

---

## 一、实施概览

本 Plan 实施 Spec 中的逻辑删除去强制化方案，分 5 步执行。每一步完成后代码均可编译通过。

| 步骤 | 内容 | 涉及文件数 |
|------|------|-----------|
| 1 | 数据库迁移：为 `memory_items` 添加 `deleted_at` 列 | 1 (Flyway) |
| 2 | 移除 `BaseEntity` 的 `@TableLogic` + `application.yml` 全局配置 | 2 |
| 3 | 为需要逻辑删除的实体显式添加 `@TableLogic` | ~35 |
| 4 | 修复 `MemoryDomainService` 使用 `status` 软删除 | 1 |
| 5 | 修复 `PortalMemoryController` 删除端点 | 1 |

---

## 二、关键设计决策

| 决策 | 理由 |
|------|------|
| `@TableLogic` 在子类覆写而非保留在基类 | 让每个实体显式声明其删除策略，避免未来新增实体被动绑定 |
| `memory_items` 加 `deleted_at` 列但不用 `@TableLogic` | 保持表结构一致性（与其他表对齐），业务删除用 `status` 字段 |
| `status` 0/1 代替 `deleted_at` 的逻辑删除 | `memory_items` 有独立的生命周期语义（active/archived），不同于通用"已删除" |
| `pageMemories()` 改用显式 `eq(status,1)` | 不使用 MyBatis-Plus 自动注入，查询意图更明确 |

---

## 三、详细步骤

### 步骤 1：数据库迁移 — 为 `memory_items` 添加 `deleted_at` 列

**新文件**: `AgentX/src/main/resources/db/migration/V20260729001__add_deleted_at_to_memory_items.sql`

```sql
-- 为 memory_items 表添加 deleted_at 列（通用审计列，对齐其他表结构）
ALTER TABLE public.memory_items
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITHOUT TIME ZONE;

COMMENT ON COLUMN public.memory_items.deleted_at IS '删除时间戳（审计用，业务软删除由 status 字段管理）';
```

> **说明**：
> - 使用 `ADD COLUMN IF NOT EXISTS` 防止重复执行
> - 该列不参与 MyBatis-Plus 逻辑删除（`MemoryItemEntity` 不使用 `@TableLogic`），仅作为 `BaseEntity` 的常规审计字段
> - 现有数据的 `deleted_at` 为 NULL（即未被"物理/逻辑删除"）
> - 如果实际数据库已有该列，Flyway 会跳过

---

### 步骤 2：移除全局逻辑删除配置

#### 2a. BaseEntity — 移除 `@TableLogic`

**文件**: `AgentX/src/main/java/org/xhy/infrastructure/entity/BaseEntity.java`

```java
// === 修改前 ===
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import java.time.LocalDateTime;

public class BaseEntity {

    @TableField(fill = FieldFill.INSERT)
    protected LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    protected LocalDateTime updatedAt;

    @TableLogic                                                    // ← 删除此行
    protected LocalDateTime deletedAt;                             // ← 字段保留

    // ... operatedBy, getters/setters 不变 ...
}

// === 修改后 ===
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import java.time.LocalDateTime;

public class BaseEntity {

    @TableField(fill = FieldFill.INSERT)
    protected LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    protected LocalDateTime updatedAt;

    protected LocalDateTime deletedAt;                             // ← 仅为普通字段

    // ... operatedBy, getters/setters 不变 ...
}
```

> **关键影响**：此变更导致**所有**继承 `BaseEntity` 的实体立即失去逻辑删除行为：
> - SELECT 不再自动注入 `WHERE deleted_at IS NULL`
> - DELETE 不再自动改写为 `UPDATE SET deleted_at = now()`
> - `deleted_at` 作为一个普通可空字段被包含在 INSERT/UPDATE 中

#### 2b. application.yml — 移除全局逻辑删除配置

**文件**: `AgentX/src/main/resources/application.yml`

```yaml
# === 修改前 (L64-77) ===
mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
  type-aliases-package: org.xhy.domain
  type-handlers-package: org.xhy.infrastructure.converter
  configuration:
    map-underscore-to-camel-case: true
    cache-enabled: false
  global-config:
    db-config:
      id-type: auto
      logic-delete-field: deleted_at                              # ← 删除
      logic-delete-value: now()                                   # ← 删除
      logic-not-delete-value: "null"                              # ← 删除
    banner: false

# === 修改后 ===
mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
  type-aliases-package: org.xhy.domain
  type-handlers-package: org.xhy.infrastructure.converter
  configuration:
    map-underscore-to-camel-case: true
    cache-enabled: false
  global-config:
    db-config:
      id-type: auto
    banner: false
```

> **说明**：`@TableLogic` 注解自带 `value`/`delval` 属性，单条实体上的注解即可完全替代全局配置，无需 `application.yml` 层面的统一声明。

---

### 步骤 3：审计并显式声明各实体的 `@TableLogic`

**原则**：对**数据库表确实有 `deleted_at` 列**且**业务上需要逻辑删除**的实体，在子类中覆写 `deletedAt` 字段并加上 `@TableLogic`。

#### 3a. 审计清单

以下列表基于 `docs/sql/01_init.sql` 中定义了 `deleted_at` 列的表（需人工二次逐表确认实际数据库结构）：

| 实体类 | 对应表 | 是否有 `deleted_at` (init.sql) | 是否需要 `@TableLogic` |
|--------|--------|:---:|:---:|
| `AgentEntity` | `agents` | ✅ 有 | ✅ 需要（自身有 `delete()` 方法设 `deletedAt`） |
| `AgentVersionEntity` | `agent_versions` | ✅ 有 | ✅ 需要 |
| `AgentWorkspaceEntity` | `agent_workspace` | ✅ 有 | ✅ 需要 |
| `AgentWidgetEntity` | `agent_widgets` | ✅ 有 (migration) | ✅ 需要（自身有 `delete()` 方法） |
| `MessageEntity` | `messages` | ✅ 有 | ✅ 需要 |
| `SessionEntity` | `sessions` | ✅ 有 | ✅ 需要 |
| `ContextEntity` | `context` | ✅ 有 | ✅ 需要 |
| `UserEntity` | `users` | ✅ 有 | ✅ 需要 |
| `AccountEntity` | `accounts` | ✅ 有 | ✅ 需要 |
| `ModelEntity` | `models` | ✅ 有 | ✅ 需要 |
| `ProviderEntity` | `providers` | ✅ 有 | ✅ 需要 |
| `ToolEntity` | `tools` | ✅ 有 | ✅ 需要 |
| `ToolVersionEntity` | `tool_versions` | ✅ 有 | ✅ 需要 |
| `UserToolEntity` | `user_tools` | ✅ 有 | ✅ 需要 |
| `ContainerEntity` | `containers` | ✅ 有 | ✅ 需要 |
| `ContainerTemplateEntity` | `container_templates` | ✅ 有 | ✅ 需要 |
| `RuleEntity` | `rules` | ✅ 有 | ✅ 需要 |
| `TaskEntity` | `agent_tasks` | ✅ 有 | ✅ 需要 |
| `ApiKeyEntity` | `api_keys` | ✅ 有 | ✅ 需要 |
| `AuthSettingEntity` | `auth_settings` | ✅ 有 | ✅ 需要 |
| `MemoryItemEntity` | `memory_items` | 🔧 刚加（Step 1） | ❌ **不需要**（用 `status` 软删除） |
| 其他 rag 实体 | 各 rag 表 | ✅ 有 | ✅ 需要 |
| 其他实体 | 对应表 | 待确认 | 待确认 |

#### 3b. 实体修改模式（以 `AgentEntity` 为例）

**文件**: `AgentX/src/main/java/org/xhy/domain/agent/model/AgentEntity.java`

```java
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableField;
import java.time.LocalDateTime;

@TableName("agents")
public class AgentEntity extends BaseEntity {

    // ... 现有字段不变 ...

    /** 逻辑删除时间（覆写 BaseEntity.deletedAt，显式声明 @TableLogic） */
    @TableLogic(value = "null", delval = "now()")
    @TableField("deleted_at")
    protected LocalDateTime deletedAt;

    @Override
    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    @Override
    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    /** 软删除 */
    public void delete() {
        this.deletedAt = LocalDateTime.now();    // ← 现有逻辑不变
    }

    // ... 其他方法不变 ...
}
```

> **注意**：由于 `BaseEntity.deletedAt` 不再是 `@TableLogic`，子类覆写的字段需要**重新声明所有注解**（`@TableLogic` + `@TableField`），并且需要**覆写 getter/setter**（否则 MyBatis-Plus 无法识别子类字段映射）。

#### 3c. MemoryItemEntity — **不添加** `@TableLogic`

**文件**: `AgentX/src/main/java/org/xhy/domain/memory/model/MemoryItemEntity.java`

```java
@TableName("memory_items")
public class MemoryItemEntity extends BaseEntity {

    // ... 现有字段不变 ...

    // ⚠️ 不覆写 deletedAt，不添加 @TableLogic
    // 软删除由 status 字段管理（1=active, 0=archived/deleted）
    // deletedAt 仅作为 BaseEntity 的通用审计列（默认 NULL）
}
```

---

### 步骤 4：修复 `MemoryDomainService` — 使用 `status` 字段管理软删除

**文件**: `AgentX/src/main/java/org/xhy/domain/memory/service/MemoryDomainService.java`

#### 4a. `pageMemories()` — 添加 `status = 1` 过滤

```java
// === 修改前 (L208-217) ===
public Page<MemoryItemEntity> pageMemories(String userId, String type, int page, int pageSize) {
    Page<MemoryItemEntity> mpPage = new Page<>(Math.max(1, page), Math.max(1, pageSize));
    var qw = Wrappers.<MemoryItemEntity>lambdaQuery()
            .eq(MemoryItemEntity::getUserId, userId);
    if (type != null && !type.isBlank()) {
        qw.eq(MemoryItemEntity::getType, type.trim().toUpperCase());
    }
    qw.orderByDesc(MemoryItemEntity::getUpdatedAt);
    memoryItemRepository.selectPage(mpPage, qw);
    return mpPage;
}

// === 修改后 ===
public Page<MemoryItemEntity> pageMemories(String userId, String type, int page, int pageSize) {
    Page<MemoryItemEntity> mpPage = new Page<>(Math.max(1, page), Math.max(1, pageSize));
    var qw = Wrappers.<MemoryItemEntity>lambdaQuery()
            .eq(MemoryItemEntity::getUserId, userId)
            .eq(MemoryItemEntity::getStatus, 1);                          // ← 新增：仅查 active
    if (type != null && !type.isBlank()) {
        qw.eq(MemoryItemEntity::getType, type.trim().toUpperCase());
    }
    qw.orderByDesc(MemoryItemEntity::getUpdatedAt);
    memoryItemRepository.selectPage(mpPage, qw);
    return mpPage;
}
```

#### 4b. `listMemories()` — 添加 `status = 1` 过滤

```java
// === 修改前 (L220-231) ===
public List<MemoryItemEntity> listMemories(String userId, String type, Integer limit) {
    var qw = Wrappers.<MemoryItemEntity>lambdaQuery()
            .eq(MemoryItemEntity::getUserId, userId);
    if (type != null && !type.isBlank()) {
        qw.eq(MemoryItemEntity::getType, type.trim().toUpperCase());
    }
    qw.orderByDesc(MemoryItemEntity::getUpdatedAt);
    List<MemoryItemEntity> list = memoryItemRepository.selectList(qw);
    // ...

// === 修改后 ===
public List<MemoryItemEntity> listMemories(String userId, String type, Integer limit) {
    var qw = Wrappers.<MemoryItemEntity>lambdaQuery()
            .eq(MemoryItemEntity::getUserId, userId)
            .eq(MemoryItemEntity::getStatus, 1);                          // ← 新增：仅查 active
    if (type != null && !type.isBlank()) {
        qw.eq(MemoryItemEntity::getType, type.trim().toUpperCase());
    }
    qw.orderByDesc(MemoryItemEntity::getUpdatedAt);
    List<MemoryItemEntity> list = memoryItemRepository.selectList(qw);
    // ...
```

#### 4c. `delete()` — 改为 `status = 0` 软删除

```java
// === 修改前 (L234-239) ===
/** 归档（软删除）记忆条目 */
public boolean delete(String userId, String itemId) {
    LambdaQueryWrapper<MemoryItemEntity> qw = Wrappers.<MemoryItemEntity>lambdaQuery()
            .eq(MemoryItemEntity::getUserId, userId)
            .eq(MemoryItemEntity::getId, itemId);
    memoryItemRepository.delete(qw);    // ← 依赖 MyBatis-Plus 逻辑删除（已失效）
    return true;
}

// === 修改后 ===
/** 归档（软删除）记忆条目 — 将 status 设为 0 */
public boolean delete(String userId, String itemId) {
    // 1. 先查确认记录存在且属于当前用户
    MemoryItemEntity entity = memoryItemRepository.selectOne(
            Wrappers.<MemoryItemEntity>lambdaQuery()
                    .eq(MemoryItemEntity::getUserId, userId)
                    .eq(MemoryItemEntity::getId, itemId)
                    .eq(MemoryItemEntity::getStatus, 1));            // ← 仅操作 active 记录

    if (entity == null) {
        return false;                                                // ← 记录不存在或已归档
    }

    // 2. 更新 status 为 0（归档）
    entity.setStatus(0);
    memoryItemRepository.updateById(entity);
    return true;
}
```

> **设计要点**：
> - 不再使用 `repository.delete()` — 该方法依赖 MyBatis-Plus 逻辑删除，而 `MemoryItemEntity` 不使用 `@TableLogic`
> - 先查后改 — 确保记录存在且未归档，返回 `false` 时 Controller 可返回 404
> - 不使用 `LambdaUpdateWrapper` 直接更新 — 防止 `updatedAt` 字段不触发 `@TableField(fill = FieldFill.INSERT_UPDATE)` 自动填充

#### 4d. `saveMemories()` 查重 — 是否需要添加 `status = 1`？

```java
// 当前 L83-84
MemoryItemEntity existed = memoryItemRepository.selectOne(Wrappers.<MemoryItemEntity>lambdaQuery()
        .eq(MemoryItemEntity::getUserId, userId).eq(MemoryItemEntity::getDedupeHash, hash));
```

> **分析**：查重只匹配 `user_id + dedupe_hash`，未过滤 `status`。如果已归档的记忆被再次"抽取"出来，会更新已归档记录（其 `status` 变为 1？不——当前合并逻辑只更新 importance/tags/text，不设 status）。但从业务角度，已归档记忆不应被覆盖。建议添加 `.eq(MemoryItemEntity::getStatus, 1)`。

```java
// === 建议修改 ===
MemoryItemEntity existed = memoryItemRepository.selectOne(Wrappers.<MemoryItemEntity>lambdaQuery()
        .eq(MemoryItemEntity::getUserId, userId)
        .eq(MemoryItemEntity::getDedupeHash, hash)
        .eq(MemoryItemEntity::getStatus, 1));           // ← 新增：仅匹配 active 记录
```

#### 4e. `searchRelevant()` 中的过滤

当前 L171-172 已通过 `status=1` 过滤条目（隐式依赖 `@TableLogic`），现在需要改为显式过滤：

```java
// === 修改前 (L171-172) ===
List<MemoryItemEntity> items = memoryItemRepository
        .selectList(Wrappers.<MemoryItemEntity>lambdaQuery()
                .in(MemoryItemEntity::getId, itemIds));

// === 修改后 ===
List<MemoryItemEntity> items = memoryItemRepository
        .selectList(Wrappers.<MemoryItemEntity>lambdaQuery()
                .in(MemoryItemEntity::getId, itemIds)
                .eq(MemoryItemEntity::getStatus, 1));      // ← 新增：仅查 active 条目
```

---

### 步骤 5：修复 `PortalMemoryController` 删除端点

**文件**: `AgentX/src/main/java/org/xhy/interfaces/api/portal/memory/PortalMemoryController.java`

```java
// === 当前 (L43-48) — 逻辑不变，但返回值依赖 MemoryDomainService.delete() 的 boolean ===
/** 归档（软删除）记忆 */
@DeleteMapping("/items/{itemId}")
public Result<Void> delete(@PathVariable String itemId) {
    String userId = UserContext.getCurrentUserId();
    boolean ok = memoryAppService.deleteMemory(userId, itemId);
    return ok ? Result.success() : Result.notFound("记忆不存在或无权限");
}
```

> 无需修改。`MemoryDomainService.delete()` 现在返回 `false` 时（记录不存在或已归档），Controller 正确返回 404。

---

## 四、编译顺序与依赖关系

```
步骤 1 (Flyway 迁移)                      ← 必须先执行（否则 MemoryItemEntity INSERT 仍失败）
    │
步骤 2 (BaseEntity + application.yml)     ← 核心变更，影响所有实体
    │
步骤 3 (逐个实体添加 @TableLogic)         ← 必须在步骤 2 之后（否则所有实体失去逻辑删除）
    │
步骤 4 + 5 (MemoryDomainService + Controller) ← 可用步骤 3 并行（不相互依赖）
```

**推荐顺序**：1 → 2 → 3 → 4 → 5，每步完成后 `mvn compile` 验证。

---

## 五、测试计划

### 5.1 回归测试 — 已有逻辑删除实体

下列实体的查询/删除行为**必须**与变更前一致：

| 测试场景 | 验证方法 |
|---------|---------|
| 查询 Agent 列表 | `GET /admin/agents` — 不返回已删除（`deleted_at IS NOT NULL`）的 Agent |
| 删除 Agent | `DELETE /admin/agents/{id}` — Agent 的 `deleted_at` 被设为当前时间，非物理删除 |
| 查询 Message 列表 | 不返回已删除消息 |
| 查询 Session 列表 | 不返回已删除会话 |

### 5.2 专项测试 — MemoryItem

| # | 测试场景 | 预期结果 |
|---|---------|---------|
| 1 | `GET /api/portal/memory/items?page=1&pageSize=15` | 返回 200，data.records 仅含 `status=1` 的记录 |
| 2 | 手动插入 `status=0` 的记录 → 执行 #1 | 该记录不出现在结果中 |
| 3 | `DELETE /api/portal/memory/items/{itemId}` | 返回 200，该记录 `status` 变为 0 |
| 4 | 再次 `DELETE /api/portal/memory/items/{itemId}` | 返回 404（记录已归档） |
| 5 | 创建新记忆 → `POST /api/portal/memory/items` | 正常创建，`status` 默认为 1 |
| 6 | 记忆检索（Agent 对话中自动触发） | 不返回已归档记忆 |

### 5.3 单元测试

**`MemoryDomainServiceTest`**:
- `pageMemories` → 过滤 `status=0` 的记录
- `delete` → 记录存在 → 返回 true，`status` 变为 0
- `delete` → 记录已归档 → 返回 false
- `delete` → 记录不存在 → 返回 false
- `searchRelevant` → 不返回 `status=0` 的记忆

---

## 六、风险与回滚

| 风险 | 等级 | 缓解措施 |
|------|------|---------|
| 遗漏某个实体的 `@TableLogic` 声明 | **高** | 步骤 3 中对 37 个实体逐表确认，编译后运行全量测试；漏掉的实体表现为"删除变物理删除、查询返回已删除数据" |
| `@TableLogic` 覆写字段的 getter/setter 与父类冲突 | 中 | MyBatis-Plus 反射读取子类字段；子类覆写 getter/setter 后优先级高于父类。若覆写不正确，会导致 `deletedAt` 值丢失 |
| `delete()` 方法中 `status=0` 不触发 `updatedAt` 自动填充 | 低 | 使用 `updateById(entity)` 而非 `LambdaUpdateWrapper`，MyBatis-Plus 会识别 `@TableField(fill = FieldFill.INSERT_UPDATE)` |
| `saveMemories()` 查重未过滤 `status=0` | 低 | 步骤 4d 中已修复，添加 `.eq(MemoryItemEntity::getStatus, 1)` |
| 内存/本地运行的数据库与 init.sql 不一致 | 中 | 每个实体需对照**实际数据库**结构确认，而非仅依赖 init.sql |

### 回滚方式

```bash
git revert <commit-hash>
```

改动涉及：
- 数据库迁移（`V20260729001__add_deleted_at_to_memory_items.sql`）— Flyway 回滚需新迁移脚本 `ALTER TABLE memory_items DROP COLUMN deleted_at`
- 代码层面（`BaseEntity.java`, `application.yml`, ~35 实体类, `MemoryDomainService.java`）— pure `git revert`

---

## 七、后续工作（不在本 Plan 范围）

1. **Flyway 迁移补全**：为所有缺少 `deleted_at` 列的表编写增量迁移脚本
2. **启动时 Schema 校验**：编写单元测试，启动时校验所有 `BaseEntity` 子类对应的表实际结构是否与实体定义一致
3. **`BaseEntity` 重构**：如未来发现更多实体不使用 `deleted_at`，可将 `deletedAt` 从 `BaseEntity` 中提取到接口 `SoftDeletable`，彻底解耦
