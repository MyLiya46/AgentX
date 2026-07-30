# Spec: GitHub SSO 环境变量配置注入

- **创建日期**: 2026-07-30
- **状态**: 待实施
- **关联 Plans**:
  - [github-sso-env-config-seeding-2026-07-30](../plans/github-sso-env-config-seeding-2026-07-30.md)

---

## 一、背景

### 当前问题

1. **配置缺失**：GitHub SSO 登录配置（`clientId`、`clientSecret`、`redirectUri`）**仅存储在数据库** `auth_settings` 表的 `config_data` JSONB 字段中。初始化 SQL 只插入骨架记录，不包含 OAuth 密钥
2. **SSO 回调地址错误**：前端 `login/page.tsx` 调用 `getSsoLoginUrlApi()` 时未传 `redirectUrl` 参数，导致 GitHub 授权后回调到后端 API（`/api/sso/github/callback`）返回 JSON，而不是回调到前端页面（`/sso/github/callback`）完成登录跳转

```sql
INSERT INTO auth_settings (id, feature_type, feature_key, feature_name, enabled, display_order, description) VALUES
    ('auth-github-login', 'LOGIN', 'GITHUB_LOGIN', 'GitHub登录', TRUE, 2, 'GitHub OAuth登录');
-- 注意：无 config_data 字段
```

### 用户痛点

1. **首次部署体验差**：数据库初始化后，`GITHUB_LOGIN` 记录存在但 `config_data` 为空，用户点击 GitHub 登录返回 `400: "GitHub SSO配置不完整，请在管理后台配置GitHub OAuth应用信息"`
2. **额外手动步骤**：必须通过管理后台 API `PUT /admin/auth-settings/auth-github-login` 手动填入 OAuth 密钥
3. **不符合行业惯例**：业界通行做法是将 OAuth 密钥写在 `.env` 中，应用启动时自动加载

### 现有基础设施

项目已有 `.env` 机制（`deploy/.env` + `deploy/.env.example`）和启动注入模式（`DefaultDataInitializer` 实现 `ApplicationRunner`，启动时创建默认管理员/测试用户），可直接复用。

---

## 二、目标

1. 支持通过 `.env` 环境变量配置 GitHub OAuth 应用密钥
2. 应用启动时自动将环境变量注入数据库 `auth_settings.config_data`（仅当数据库配置为空时）
3. 数据库仍作为运行时唯一数据源，管理后台 API 可覆盖
4. `SsoConfigProvider` 保持纯数据库读取，不做任何改动
5. **修复 SSO 回调地址**：前端登录页传正确的 `redirectUrl`（前端回调页面而非后端 API），使 OAuth 回调 → 前端页面 → 换 token → 跳转首页的流程完整

---

## 三、技术设计

### 3.1 数据流

```
                    ┌─ 首次部署 ─┐
                    │            │
   .env ────→ docker-compose ──→ Spring Boot 启动
                                    │
                         SsoConfigInitializer (ApplicationRunner)
                                    │
                         检查 DB config_data 是否为空
                                    │
                         ┌─ 为空 ───┴── 不为空 ─┐
                         │                      │
                    写入 config_data          跳过
                         │                      │
                         └──────────┬───────────┘
                                    ▼
                           运行时：SsoConfigProvider
                                    │
                            只读数据库 config_data
                             （管理后台 API 可覆盖）
```

### 3.2 配置优先级

```
环境变量 (.env) ──(启动时注入)──→ 数据库 config_data ──(运行时读取)──→ SsoConfigProvider
                                          ↑
                              管理后台 API 可覆盖
```

- **启动时**：`.env` → 数据库（仅当 DB 为空时写入，不覆盖已有配置）
- **运行时**：数据库是唯一数据源，`SsoConfigProvider` 不动

### 3.3 环境变量定义

| 变量名 | 用途 | 示例值 |
|--------|------|--------|
| `GITHUB_CLIENT_ID` | GitHub OAuth App Client ID | `Ov23ligZmXOp24E2wNID` |
| `GITHUB_CLIENT_SECRET` | GitHub OAuth App Client Secret | `bea5916ea0c81...` |
| `GITHUB_REDIRECT_URI` | OAuth 回调地址 | `http://localhost:8088/api/sso/github/callback` |

### 3.4 改动范围

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `deploy/.env.example` | 修改 | 添加 GitHub SSO 模板，`redirectUri` 指向前端回调页 |
| `deploy/.env` | 修改 | 添加实际值 |
| `AgentX/src/main/resources/application.yml` | 修改 | 添加 `sso.github.*` 配置映射 |
| `AgentX/.../config/SsoConfigProperties.java` | **新建** | `@ConfigurationProperties` 绑定类 |
| `AgentX/.../initializer/SsoConfigInitializer.java` | **新建** | 启动时种子注入器 |
| `deploy/docker-compose.yml` | 修改 | 传递环境变量到后端容器 |
| `SsoConfigProvider.java` | **不修改** | 保持纯 DB 读取 |
| `agentx-frontend-plus/app/(auth)/login/page.tsx` | **修改** | GitHub 和 Community 登录传正确 `redirectUrl`，指向 `/sso/{provider}/callback` |

---

## 四、约束

- 仅在 DB 中 `config_data` 为空时才写入，**绝不覆盖**已有配置（保护管理后台的手动配置）
- 环境变量全部为空时不写入（保护正常的生产流程）
- 初始化失败不阻塞应用启动
- 遵循项目现有的 `DefaultDataInitializer` 模式
