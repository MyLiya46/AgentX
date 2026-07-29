# Plan: 全栈时间戳标准化 — LocalDateTime → OffsetDateTime + TIMESTAMPTZ

- **创建日期**: 2026-07-29
- **关联 Spec**: [timestamp-type-mismatch-fix-2026-07-29](../specs/timestamp-type-mismatch-fix-2026-07-29.md)
- **前置依赖**: 无
- **状态**: 待实施

---

## 一、实施概览

| 阶段 | 内容 | 涉及文件数 | 类型 |
|------|------|-----------|------|
| 1 | 修改 `BaseEntity` + `SoftDeleteEntity` 字段类型 | 2 | 🔴 核心变更 |
| 2 | 修复覆盖字段的子实体类 | ~5 | 编译修复 |
| 3 | 修改 30 个 DTO 的时间字段类型 | ~30 | 编译修复 |
| 4 | 修复 Assembler 层转换逻辑 | ~8 | 编译修复 |
| 5 | 修复 Service/Domain 层调用方 | ~15 | 编译修复 |
| 6 | 修复非实体 `LocalDateTime` 的使用点（与实体交互处） | ~10 | 编译修复 |
| 7 | 更新 `MetaObjectHandler` 自动填充 | 1 | 配置 |
| 8 | 数据库迁移：所有表时间戳列标准化为 `TIMESTAMPTZ` | 1 | DDL |
| 9 | 配置更新：JDBC URL + Jackson | 1 | 配置 |
| 10 | 编写/修复单元测试 | ~3 | 测试 |

---

## 二、关键设计决策

| 决策 | 理由 |
|------|------|
| DTO 也改为 `OffsetDateTime` | `BeanUtils.copyProperties()` 要求源和目标类型一致；Jackson 原生支持 `OffsetDateTime` 序列化 |
| 使用 `Asia/Shanghai` 而非 UTC | 项目已配置 Jackson `GMT+8`，全栈统一时区避免混乱；PG 内部以 UTC 存储是透明的 |
| `@TableLogic(delval = "now()")` 保持不变 | 列类型改为 `TIMESTAMPTZ` 后，`now()` 返回 TIMESTAMPTZ → 精确匹配 |
| 非 DB 映射的 `LocalDateTime` 保持不变 | 类型变更只影响 DB 映射链路；纯内存的时间戳（如事件、Token 计算）不受 JDBC 影响 |
| 所有表统一迁移，不只是 `memory_items` | 一次对齐，不再留下"不一致"的隐患 |

---

## 三、详细步骤

### 阶段 1：修改 `BaseEntity` + `SoftDeleteEntity`

#### 1a. BaseEntity

**文件**: `AgentX/src/main/java/org/xhy/infrastructure/entity/BaseEntity.java`

```java
// === 修改前 ===
import java.time.LocalDateTime;

public class BaseEntity {
    @TableField(fill = FieldFill.INSERT)
    protected LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    protected LocalDateTime updatedAt;

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

// === 修改后 ===
import java.time.OffsetDateTime;

public class BaseEntity {
    @TableField(fill = FieldFill.INSERT)
    protected OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    protected OffsetDateTime updatedAt;

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

#### 1b. SoftDeleteEntity

**文件**: `AgentX/src/main/java/org/xhy/infrastructure/entity/SoftDeleteEntity.java`

```java
// === 修改前 ===
import java.time.LocalDateTime;

public class SoftDeleteEntity extends BaseEntity {
    @TableLogic(value = "null", delval = "now()")
    @TableField("deleted_at")
    protected LocalDateTime deletedAt;

    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
}

// === 修改后 ===
import java.time.OffsetDateTime;

public class SoftDeleteEntity extends BaseEntity {
    @TableLogic(value = "null", delval = "now()")  // ← now() 返回 TIMESTAMPTZ，与列类型一致
    @TableField("deleted_at")
    protected OffsetDateTime deletedAt;

    public OffsetDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(OffsetDateTime deletedAt) { this.deletedAt = deletedAt; }
}
```

---

### 阶段 2：修复覆盖字段的子实体类

部分子实体直接覆写了 `createdAt`/`updatedAt`/`deletedAt` 的 getter/setter 或字段，需要同步类型变更。

**搜索命令**：
```bash
grep -rn "LocalDateTime.*createdAt\|LocalDateTime.*updatedAt\|LocalDateTime.*deletedAt" \
  AgentX/src/main/java/org/xhy/domain --include="*Entity.java"
```

**已知需要修改的实体**（基于继承 `SoftDeleteEntity` 且覆盖字段）：

| 实体 | 覆盖的字段 | 改动 |
|------|-----------|------|
| `AgentEntity` | `deletedAt`（有自定义 `delete()` 方法设置 `LocalDateTime.now()`） | `LocalDateTime` → `OffsetDateTime` |
| `AgentWidgetEntity` | 同上 | 同步修改 |
| 其他可能有自定义 `delete()` 的实体 | `deletedAt` | 搜索 `LocalDateTime.now()` 调用的实体 |

#### 示例：修复 `AgentEntity.delete()`

```java
// === 修改前 ===
public void delete() {
    this.deletedAt = LocalDateTime.now();
}

// === 修改后 ===
import java.time.ZoneId;
private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

public void delete() {
    this.deletedAt = OffsetDateTime.now(ZONE);
}
```

> **搜索范围**：`grep -rn "LocalDateTime.now()" AgentX/src/main/java/org/xhy/domain` 找出所有实体中手动设置时间的代码，统一改为 `OffsetDateTime.now(ZONE)`。

---

### 阶段 3：修改 DTO 的时间字段

**影响范围**：30 个 DTO 文件

每个 DTO 的修改模式是机械的：

```java
// === 修改前 ===
import java.time.LocalDateTime;

private LocalDateTime createdAt;
private LocalDateTime updatedAt;

public LocalDateTime getCreatedAt() { return createdAt; }
public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

// === 修改后 ===
import java.time.OffsetDateTime;

private OffsetDateTime createdAt;
private OffsetDateTime updatedAt;

public OffsetDateTime getCreatedAt() { return createdAt; }
public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
```

**30 个 DTO 文件清单**（按模块分组）：

| 模块 | DTO 文件 |
|------|---------|
| Agent | `AgentDTO`, `AgentWithUserDTO`, `AgentVersionDTO`, `AgentWidgetDTO` |
| Account | `AccountDTO` |
| API Key | `ApiKeyDTO` |
| Auth | `AuthSettingDTO` |
| Container | `ContainerDTO`, `ContainerTemplateDTO` |
| Conversation | `SessionDTO`, `MessageDTO` |
| LLM | `ProviderDTO`, `ModelDTO` |
| Memory | `MemoryItemDTO` |
| Order | `OrderDTO` |
| Product | `ProductDTO` |
| RAG | `UserRagDTO`, `RagVersionDTO`, `RagVersionDocumentDTO`, `RagQaDatasetDTO`, `RagMarketDTO`, `FileDetailDTO` |
| Rule | `RuleDTO` |
| ScheduledTask | `ScheduledTaskDTO` |
| Task | `TaskDTO` |
| Tool | `ToolDTO`, `ToolVersionDTO`, `ToolWithUserDTO` |
| Trace | `AgentExecutionSummaryDTO`, `AgentExecutionDetailDTO`, `SessionTraceStatisticsDTO` |
| Usage | `UsageRecordDTO` |
| User | `UserDTO` |

**注意**：某些 DTO 可能有额外的 `LocalDateTime` 字段（如 `lastLoginAt`, `expiredAt`, `paidAt` 等），需一并修改。

---

### 阶段 4：修复 Assembler 层

**已知涉及文件**（通过 grep 结果识别）：

| Assembler | 改动点 |
|-----------|--------|
| `MemoryAssembler` | `getCreatedAt()` 返回值类型 |
| `SessionAssembler` | 同上 |
| `ProviderAssembler` | 同上 |
| `ModelAssembler` | 同上 |
| `TaskAssembler` | 同上 |
| `ScheduledTaskAssembler` | 同上 |
| `AgentExecutionTraceAssembler` | 同上 |
| `RagQaDatasetAssembler` | 同上 |
| `DocumentUnitAssembler` | 同上 |
| `AgentAssembler` | 同上 |

**这些 Assembler 大部分使用 `BeanUtils.copyProperties()`**，如果 entity 和 DTO 类型已一致（都是 `OffsetDateTime`），则无需额外处理。需要手动检查的是那些对 `getCreatedAt()` 结果做了额外操作的代码。

---

### 阶段 5：修复 Service/Domain 层调用方

**已知涉及文件**（~15 个使用 `getCreatedAt()` / `getUpdatedAt()` 的 Service）：

| 文件 | 改动点 |
|------|--------|
| `ConversationAppService` | `getCreatedAt()` 返回值赋给 `LocalDateTime` 变量 → 改 `OffsetDateTime` |
| `ContainerDomainService` | 同上（7 处 LocalDateTime 引用） |
| `ToolVersionDomainService` | 同上 |
| `RagVersionDomainService` | 同上（5 处） |
| `RagDataAccessDomainService` | 同上 |
| `ProviderAggregate` | 同上 |
| `ContextProcessor` | 同上 |
| `AgentWidgetDomainService` | 同上 |
| `AgentDomainService` | 同上 |
| `AgentExecutionTraceAppService` | 同上 |
| `AgentWidgetAppService` | 同上 |
| `PaymentAppService` | 同上 |
| `TraceEventListener` | 同上 |
| `UsageRecordDomainService` | 同上（3 处） |

#### 典型修复模式

```java
// === 修改前 ===
LocalDateTime createdAt = entity.getCreatedAt();

// === 修改后 ===
OffsetDateTime createdAt = entity.getCreatedAt();
```

#### 需要特殊处理的模式

```java
// 模式 1：DateTimeFormatter 格式化
// 修改前
entity.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
// 修改后（OffsetDateTime 也支持 format）
entity.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

// 模式 2：日期比较
// 修改前
entity.getCreatedAt().isAfter(someTime)  // LocalDateTime
// 修改后（OffsetDateTime 也支持 isAfter/isBefore）
entity.getCreatedAt().isAfter(someTime)  // OffsetDateTime，但注意时区一致性

// 模式 3：转为 Instant 用于比较
// 建议统一为 OffsetDateTime 比较，不混合 Instant
entity.getCreatedAt().toInstant()  // 若需要，可以调用但建议直接用 OffsetDateTime
```

---

### 阶段 6：修复非实体 `LocalDateTime` 与实体交互处

这些是那些不继承 `BaseEntity` 但使用了 `LocalDateTime` 的类，需要检查它们是否与实体有时间字段交互：

**关键文件**：

| 文件 | `LocalDateTime` 引用数 | 是否需要改 |
|------|----------------------|-----------|
| `AgentExecutionTraceDomainService` | 18 | ⚠️ 需检查是否与 Entity 时间字段交互 |
| `OrderEntity` | 18 | ✅ 继承 BaseEntity，改 |
| `RepeatConfig` | 8 | ❌ 独立时间逻辑（调度周期），不改 |
| `ScheduledTaskEntity` | 8 | ✅ 继承 BaseEntity，改 |
| `TokenMessage` | 10 | ❌ 纯内存对象，不改 |
| `TraceContext` | 4 | ❌ 纯内存对象，不改 |
| `FileInfo` | 5 | ❌ OSS 文件元数据，不改 |

**判断规则**：只要该类不涉及 PostgreSQL 的 `TIMESTAMPTZ` 列读写，就不需要改。

---

### 阶段 7：更新 MetaObjectHandler

**文件**: `AgentX/src/main/java/org/xhy/infrastructure/config/MybatisPlusConfig.java`

```java
// === 修改前 ===
import java.time.LocalDateTime;

@Override
public void insertFill(MetaObject metaObject) {
    LocalDateTime now = LocalDateTime.now();
    this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
    this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
}

@Override
public void updateFill(MetaObject metaObject) {
    LocalDateTime now = LocalDateTime.now();
    this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, now);
}

// === 修改后 ===
import java.time.OffsetDateTime;
import java.time.ZoneId;

private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

@Override
public void insertFill(MetaObject metaObject) {
    OffsetDateTime now = OffsetDateTime.now(ZONE_ID);
    this.strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, now);
    this.strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);
}

@Override
public void updateFill(MetaObject metaObject) {
    OffsetDateTime now = OffsetDateTime.now(ZONE_ID);
    this.strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, now);
}
```

> **注意**：`OffsetDateTime.now(ZoneId)` 会生成带该时区当前偏移的 `OffsetDateTime`，例如 `2026-07-29T20:28:00+08:00`。

---

### 阶段 8：数据库迁移

**新文件**: `AgentX/src/main/resources/db/migration/V20260729003__standardize_timestamptz_columns.sql`

```sql
-- ===========================================================================
-- 统一所有表的时间戳列为 TIMESTAMP WITH TIME ZONE (TIMESTAMPTZ)
-- 这是 PostgreSQL 官方推荐的时间类型，与 Java OffsetDateTime 标准映射
-- ===========================================================================

-- 8a. 诊断查询（手动执行）
-- 查看当前哪些表的时间戳列不是 TIMESTAMPTZ
/*
SELECT table_name, column_name, data_type
FROM information_schema.columns
WHERE table_schema = 'public'
  AND column_name IN ('created_at', 'updated_at', 'deleted_at')
  AND data_type <> 'timestamp with time zone'
ORDER BY table_name, column_name;
*/

-- 8b. 使用匿名块批量转换所有 TIMESTAMP WITHOUT TIME ZONE → TIMESTAMPTZ
-- PostgreSQL 的 AT TIME ZONE 语法在列转换时保留 UTC 时刻不变
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT table_name, column_name
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND column_name IN ('created_at', 'updated_at', 'deleted_at',
                              'expired_at', 'paid_at', 'cancelled_at', 'refunded_at',
                              'last_accessed_at', 'last_login_at')
          AND data_type = 'timestamp without time zone'
        ORDER BY table_name, column_name
    LOOP
        -- 将列改为 TIMESTAMPTZ，使用当前会话时区解释现有值
        EXECUTE format(
            'ALTER TABLE public.%I ALTER COLUMN %I TYPE timestamptz USING %I AT TIME ZONE ''Asia/Shanghai''',
            r.table_name, r.column_name, r.column_name
        );
        RAISE NOTICE '[V20260729003] 已转换: %.% → TIMESTAMPTZ', r.table_name, r.column_name;
    END LOOP;
END $$;
```

**关键点**：

| 要素 | 说明 |
|------|------|
| `USING column AT TIME ZONE 'Asia/Shanghai'` | 将现有 `TIMESTAMP` 值解释为 Asia/Shanghai 时区的时刻 → 转为 UTC 存储，现有数据时间语义不丢失 |
| 动态 SQL 循环 | 无需手动枚举表名，自动处理所有表 |
| 幂等 | `WHERE data_type = 'timestamp without time zone'`，已转换的列自动跳过 |
| 包含业务时间列 | `expired_at`, `paid_at`, `last_login_at` 等一并转换 |

---

### 阶段 9：配置更新

#### 9a. JDBC URL 添加 TimeZone 参数

**文件**: `AgentX/src/main/resources/application.yml`

```yaml
# === 修改前 ===
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:agentx}

# === 修改后 ===
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:agentx}?TimeZone=Asia/Shanghai
```

#### 9b. Jackson 配置（确认无需修改）

```yaml
spring:
  jackson:
    date-format: yyyy-MM-dd HH:mm:ss
    time-zone: GMT+8
```

Jackson 原生支持 `OffsetDateTime` 序列化。当 `time-zone: GMT+8` 时，`OffsetDateTime` 值会按此时区格式化。配置保持不变。

---

### 阶段 10：单元测试

#### 10a. 更新现有测试

搜索 `LocalDateTime` 在测试代码中的使用，将涉及实体时间字段的测试代码同步修改。

```bash
grep -rn "LocalDateTime" AgentX/src/test --include="*.java"
```

#### 10b. 新增测试用例

**文件**: `AgentX/src/test/java/org/xhy/infrastructure/config/MybatisPlusConfigTest.java`

| 测试用例 | 验证点 |
|---------|--------|
| `insertFill 应填充 OffsetDateTime` | `metaObject.getValue("createdAt")` 类型是 `OffsetDateTime`，偏移量为 `+08:00` |
| `updateFill 应填充 OffsetDateTime` | `metaObject.getValue("updatedAt")` 类型正确 |

#### 10c. 集成冒烟测试

| 测试场景 | 验证点 |
|---------|--------|
| 记忆 CRUD | `GET /api/portal/memory/items` → 200 + 数据正确 |
| Agent 查询 | `GET /admin/agents` → 200 + `createdAt` 正常序列化 |
| 软删除 | 删除 Agent → `deleted_at` 正确写入 |
| 会话列表 | `GET /api/conversations` → 200 |

---

## 四、编译顺序与依赖关系

```
阶段 1: BaseEntity + SoftDeleteEntity     ← 所有实体依赖
    │
阶段 2: 子实体类                           ← 依赖阶段 1
    │
阶段 7: MetaObjectHandler                  ← 依赖阶段 1
    │
阶段 3: DTO                                ← 可与阶段 2 并行
    │
阶段 4: Assembler                          ← 依赖阶段 1 + 3
阶段 5: Service/Domain                     ← 依赖阶段 1 + 2
阶段 6: 其他 LocalDateTime                 ← 依赖阶段 5
    │
阶段 8: DB 迁移                            ← 独立（代码改完前执行）
阶段 9: 配置                               ← 独立
    │
阶段 10: 测试                              ← 全部完成
```

**推荐执行顺序**：8 (DB) → 1 → 2 → 7 → 3 → 4 → 5 → 6 → 9 → 10

---

## 五、改动文件清单

| 类型 | 数量 | 文件示例 |
|------|------|---------|
| 实体基类 | 2 | `BaseEntity`, `SoftDeleteEntity` |
| 子实体类 | ~5 | `AgentEntity`, `AgentWidgetEntity` 等 |
| DTO 类 | ~30 | `AgentDTO`, `MemoryItemDTO`, `UserDTO` 等 |
| Assembler 类 | ~10 | `MemoryAssembler`, `AgentAssembler` 等 |
| Service/Domain 类 | ~15 | `ConversationAppService`, `ContainerDomainService` 等 |
| 配置类 | 2 | `MybatisPlusConfig`, `application.yml` |
| Flyway 迁移 | 1 | `V20260729003__standardize_timestamptz_columns.sql` |
| 测试类 | ~3 | 更新 + 新增 |
| **合计** | **~68 文件** | |

> 68 个文件看起来多，但变更是**高度机械的**：本质上是把 `LocalDateTime` 替换成 `OffsetDateTime`，并导入正确的包。每个 DTO 改动仅影响 import + 字段声明 + getter/setter 签名。

---

## 六、风险与回滚

| 风险 | 等级 | 缓解措施 |
|------|------|---------|
| `BeanUtils.copyProperties` OffsetDateTime → ??? 隐式转换 | 🟢 低 | DTO 也改为 `OffsetDateTime`，类型一致则无转换 |
| `OffsetDateTime.now(ZONE)` 与旧数据的时区语义不一致 | 🟡 中 | 迁移脚本用 `AT TIME ZONE 'Asia/Shanghai'` 确保旧数据正确解释 |
| Jackson 序列化 `OffsetDateTime` 格式与前端期望不一致 | 🟡 中 | 用 `date-format: yyyy-MM-dd HH:mm:ss` + `time-zone: GMT+8` 约束输出格式 |
| 遗漏某个文件的 `LocalDateTime` → `OffsetDateTime` 替换 | 🟢 低 | IDE 编译检查会捕获类型不匹配 |
| 前端对时间格式敏感 | 🟡 中 | 序列化输出不变（`yyyy-MM-dd HH:mm:ss`），前端无需改动 |

### 回滚方式

```bash
# 代码回滚
git revert <commit-hash>

# DB 回滚（如需要）
# 重新执行反向 ALTER：TIMESTAMPTZ → TIMESTAMP WITHOUT TIME ZONE
# 注意：此操作会丢失时区信息，仅在代码也回滚时执行
```

---

## 七、后续工作（不在本 Plan 范围）

1. **前端时间展示优化**：当前 `yyyy-MM-dd HH:mm:ss` 无时区指示，未来可考虑 ISO 8601 格式
2. **`rag_tables.sql` 修正**：将 MySQL 语法改为 PostgreSQL，纳入 Flyway 管理
3. **`LocalDateTime` 残留清理**：非 DB 映射的 `LocalDateTime`（事件、Token 计算等）可保持原样，但建议逐步统一
4. **CI 中加 Schema 检查**：启动时自动校验实体定义与数据库列类型一致性
