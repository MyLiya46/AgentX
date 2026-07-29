# Spec: 时间戳类型标准化 — TIMESTAMPTZ + OffsetDateTime

- **创建日期**: 2026-07-29
- **状态**: 待实施
- **关联 Plans**:
  - [fix-timestamp-type-mismatch-2026-07-29](../plans/fix-timestamp-type-mismatch-2026-07-29.md)

---

## 一、问题陈述

管理员手动添加记忆后，`GET /api/portal/memory/items` 返回 HTTP 500：

```
PSQLException: Cannot convert the column of type TIMESTAMPTZ to requested type java.time.LocalDateTime.
```

**这不是一个需要"防御"的意外**。问题本质是技术栈三层之间的类型契约断裂：

| 层 | 当前实际 | 正确/标准选择 |
|----|---------|-------------|
| PostgreSQL 列 | `TIMESTAMPTZ` | `TIMESTAMPTZ` ✅（已是正确的） |
| Java 实体 | `LocalDateTime` | **❌ 错误** — 应改为 `OffsetDateTime` |
| JDBC 映射 | `TIMESTAMPTZ → LocalDateTime` | **不成立** — 驱动拒绝此转换是正确的 |

**结论**：不是数据库列类型错了，是 Java 代码的类型声明错了。修改方向是**修正 Java 端类型**，而不是把数据库降级成 `TIMESTAMP WITHOUT TIME ZONE` 再用防御代码兜底。

---

## 二、为什么 TIMESTAMPTZ + OffsetDateTime 是最优选择

### 2.1 PostgreSQL 官方立场

PostgreSQL 手册明确建议：
> "For most applications, `TIMESTAMP WITH TIME ZONE` is the recommended type. It represents a fixed point in time, unambiguous across time zones."

`TIMESTAMP WITHOUT TIME ZONE` 的适用场景非常窄：仅当你存储的是**与物理时刻无关的"钟表读数"**（如"每天早上 9 点开会"这种抽象规则），而不是具体的事件发生时间。

`created_at` / `updated_at` 显然是**具体事件时间**，用 `TIMESTAMPTZ` 是语义正确的。

### 2.2 JDBC 4.2 标准映射

| PostgreSQL 类型 | Java 类型 | 说明 |
|----------------|-----------|------|
| `TIMESTAMP WITH TIME ZONE` | `OffsetDateTime` | 保留时区偏移量，精确映射 |
| `TIMESTAMP WITHOUT TIME ZONE` | `LocalDateTime` | 无时区，表示"钟表读数" |

JDBC 驱动拒绝 `TIMESTAMPTZ → LocalDateTime` 是**有意为之**——这会丢失时区信息，是一个有损转换。驱动要求开发者显式选择。

### 2.3 三选一：哪个 Java 类型最合适

| 候选 | 语义 | JDBC 支持 | MyBatis-Plus 内置 Handler | 推荐 |
|------|------|-----------|--------------------------|------|
| `LocalDateTime` | 钟表读数，无时区 | ❌ 不支持 TIMESTAMPTZ | `LocalDateTimeTypeHandler` | ❌ 已经炸了 |
| `Instant` | UTC 时间线上的点 | ⚠️ 需 MyBatis-Plus 3.5.1+ | `InstantTypeHandler`（可能需手动注册） | 🟡 |
| `OffsetDateTime` | 带偏移的时间点 | ✅ JDBC 原生支持 | `OffsetDateTimeTypeHandler`（内置自动注册） | ✅ **推荐** |

选择 `OffsetDateTime` 的理由：
- JDBC 4.2 规范的原生映射，不需任何额外配置
- MyBatis-Plus 内置 `OffsetDateTimeTypeHandler`，自动注册
- 比 `Instant` 多一个"偏移量"信息，可追溯数据写入时的时区上下文

---

## 三、方案设计

### 3.1 核心变更

```
                   修改前                          修改后
          ┌─────────────────────┐       ┌─────────────────────────┐
  Java    │ LocalDateTime       │  ──▶  │ OffsetDateTime          │
          │ (无时区，语义模糊)    │       │ (带时区偏移，语义精确)    │
          └─────────────────────┘       └─────────────────────────┘
                    │                              │
                    │ ❌ PSQLException             │ ✅ 原生映射
                    ▼                              ▼
          ┌─────────────────────┐       ┌─────────────────────────┐
  DB      │ TIMESTAMPTZ         │  ──▶  │ TIMESTAMPTZ             │
          │ (已经是正确的)        │       │ (保持不变)               │
          └─────────────────────┘       └─────────────────────────┘
```

### 3.2 改动范围

| 层 | 文件 | 改动 |
|----|------|------|
| 实体基类 | `BaseEntity.java` | `LocalDateTime` → `OffsetDateTime`（2 个字段） |
| 软删除基类 | `SoftDeleteEntity.java` | `LocalDateTime` → `OffsetDateTime`（1 个字段） |
| 自动填充 | `MybatisPlusConfig.java` | `LocalDateTime.now()` → `OffsetDateTime.now()` |
| 数据库 | 新 Flyway 迁移 | 统一所有表时间戳列为 `TIMESTAMPTZ` |
| 应用配置 | `application.yml` | JDBC URL 加 `TimeZone` 参数 |
| 编译修复 | ~39 个实体 + 相关 Service/Assembler | 修复类型不匹配的编译错误 |

### 3.3 时区约定

| 节点 | 配置 | 值 |
|------|------|-----|
| Java 代码生成时间 | `OffsetDateTime.now(ZoneId)` | `Asia/Shanghai` |
| JDBC 驱动读写时区 | `?TimeZone=Asia/Shanghai` | `Asia/Shanghai` |
| Jackson JSON 序列化 | `spring.jackson.time-zone` | `GMT+8` |
| PostgreSQL 内部存储 | （自动） | UTC（PG 内部实现） |

**约定**：Java 应用层统一使用 `Asia/Shanghai` 时区。PostgreSQL 内部以 UTC 存储（透明），读回时由 JDBC `TimeZone` 参数还原为 `Asia/Shanghai` 偏移量。

### 3.4 `@TableLogic` delval 处理

```java
// 当前：now() 返回 TIMESTAMPTZ → 写入 TIMESTAMP 列依赖隐式转换
@TableLogic(value = "null", delval = "now()")

// 修改后：now() 返回 TIMESTAMPTZ → 写入 TIMESTAMPTZ 列无需转换
@TableLogic(value = "null", delval = "now()")
```

**不改为 `LOCALTIMESTAMP`**。列类型变为 `TIMESTAMPTZ` 后，`now()` 正是正确的函数。

---

## 四、影响范围

| 模块 | 影响 | 处理方式 |
|------|------|---------|
| `BaseEntity` | `createdAt`/`updatedAt` 类型变更 | 直接修改，getter/setter 签名跟随 |
| `SoftDeleteEntity` | `deletedAt` 类型变更 | 直接修改，`@TableLogic` 不变 |
| 39 个实体子类 | 继承的字段类型变化 | 大部分无需改动（仅用继承的 getter/setter） |
| Service/Assembler | 使用 `getCreatedAt()` 的代码 | 局部类型修正（`LocalDateTime` → `OffsetDateTime`） |
| DTO 层 | 可能用 `LocalDateTime` 定义时间字段 | 改为 `OffsetDateTime`，或保持 `LocalDateTime` 并做转换 |
| 数据库 | 确保所有时间戳列是 `TIMESTAMPTZ` | 一个 Flyway 迁移脚本 |

---

## 五、验收标准

1. `GET /api/portal/memory/items` 返回 200，数据正确
2. 创建记忆 → 成功 → 刷新列表可见
3. 所有已有模块（Agent、会话、消息、用户、RAG 等）查询/写入正常
4. `created_at` / `updated_at` 自动填充的值包含正确的时区偏移量（`+08:00`）
5. `deleted_at` 软删除值包含正确的时区偏移量
6. 数据库所有表的 `created_at` / `updated_at` 列类型为 `TIMESTAMP WITH TIME ZONE`
7. 现有单元测试无回归
8. 新增单元测试覆盖 OffsetDateTime 自动填充
