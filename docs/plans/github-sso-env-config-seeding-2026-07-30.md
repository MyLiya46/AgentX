# Plan: GitHub SSO 环境变量配置注入

- **创建日期**: 2026-07-30
- **关联 Spec**: [github-sso-env-config-seeding-2026-07-30](../specs/github-sso-env-config-seeding-2026-07-30.md)
- **状态**: 待实施

---

## 一、实施概览

| 步骤 | 内容 | 涉及文件数 | 说明 |
|------|------|-----------|------|
| 1 | `.env.example` + `.env` 添加 GitHub SSO 变量 | 2 | 模板 + 实际值 |
| 2 | `docker-compose.yml` 传递环境变量到后端 | 1 | 基础设施 |
| 3 | `application.yml` 添加配置映射 | 1 | Spring 配置绑定 |
| 4 | 新建 `SsoConfigProperties` | 1 | 类型安全配置类 |
| 5 | 新建 `SsoConfigInitializer` | 1 | 启动时 DB 种子注入 |
| 6 | 验证 `SsoConfigProvider` 无需改动 | 0 | 纯 DB 读取不变 |
| 7 | **修复前端 SSO 回调地址** | 1 | login 页传 redirectUrl 指向前端回调页 |

---

## 二、详细步骤

### 步骤 1：`deploy/.env.example` 添加模板

**文件**: `deploy/.env.example`

在文件末尾追加：

```env
# ============================================
# GitHub SSO OAuth 配置
# ============================================
# 用于 GitHub 第三方登录，请在 GitHub Developer Settings 创建 OAuth App
# 回调地址需设置为: <BACKEND_URL>/api/sso/github/callback
GITHUB_CLIENT_ID=your_github_client_id
GITHUB_CLIENT_SECRET=your_github_client_secret
GITHUB_REDIRECT_URI=https://yourdomain.com/api/sso/github/callback
```

---

### 步骤 2：`deploy/.env` 添加实际值

**文件**: `deploy/.env`

在文件末尾追加：

```env
# ============================================
# GitHub SSO OAuth 配置
# ============================================
GITHUB_CLIENT_ID=Ov23ligZmXOp24E2wNID
GITHUB_CLIENT_SECRET=bea5916ea0c81c444b91643c8b98939f690e8078
GITHUB_REDIRECT_URI=http://localhost:8088/api/sso/github/callback
```

> **注意**: `deploy/.env` 包含真实密钥，已在 `.gitignore` 中，不会提交到仓库。

---

### 步骤 3：`docker-compose.yml` 传递环境变量

**文件**: `deploy/docker-compose.yml`

在 `agentx-backend` 服务的 `environment` 块中追加：

```yaml
      # GitHub SSO OAuth 配置
      GITHUB_CLIENT_ID: ${GITHUB_CLIENT_ID:-}
      GITHUB_CLIENT_SECRET: ${GITHUB_CLIENT_SECRET:-}
      GITHUB_REDIRECT_URI: ${GITHUB_REDIRECT_URI:-}
```

插入位置：在已有的 `GITHUB_TOKEN` 配置附近（第 160 行后）。

---

### 步骤 4：`application.yml` 添加配置映射

**文件**: `AgentX/src/main/resources/application.yml`

在文件末尾追加：

```yaml
# GitHub SSO OAuth 配置（启动时注入数据库，数据库配置优先）
sso:
  github:
    client-id: ${GITHUB_CLIENT_ID:}
    client-secret: ${GITHUB_CLIENT_SECRET:}
    redirect-uri: ${GITHUB_REDIRECT_URI:}
```

> **说明**: 默认值为空字符串 `""`，当环境变量未设置时，启动初始化器会跳过种子注入。

---

### 步骤 5：新建 `SsoConfigProperties.java`

**文件**: `AgentX/src/main/java/org/xhy/infrastructure/config/SsoConfigProperties.java`

```java
package org.xhy.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** GitHub SSO OAuth 配置属性（从环境变量/.env 读取）
 * 
 * 用于启动时将配置种子注入数据库，运行时以数据库为准。 */
@Component
@ConfigurationProperties(prefix = "sso.github")
public class SsoConfigProperties {

    /** GitHub OAuth App Client ID */
    private String clientId;

    /** GitHub OAuth App Client Secret */
    private String clientSecret;

    /** OAuth 回调地址 */
    private String redirectUri;

    // ===== getters / setters =====

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    /** 检查三个必要配置是否都不为空
     * 
     * @return true 表示环境变量已配置完整 */
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
            && clientSecret != null && !clientSecret.isBlank()
            && redirectUri != null && !redirectUri.isBlank();
    }
}
```

---

### 步骤 6：新建 `SsoConfigInitializer.java`

**文件**: `AgentX/src/main/java/org/xhy/infrastructure/initializer/SsoConfigInitializer.java`

```java
package org.xhy.infrastructure.initializer;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.xhy.domain.auth.constant.AuthFeatureKey;
import org.xhy.domain.auth.model.AuthSettingEntity;
import org.xhy.domain.auth.service.AuthSettingDomainService;
import org.xhy.infrastructure.config.SsoConfigProperties;

/** SSO 配置初始化器
 * 
 * 应用启动时检查数据库中的 GitHub SSO 配置是否完整。
 * 如果 config_data 为空且环境变量已配置，则自动注入。
 * 模式参照 {@link DefaultDataInitializer}。
 * 
 * @author AgentX */
@Component
@Order(90) // 在 DefaultDataInitializer(100) 之前执行
public class SsoConfigInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SsoConfigInitializer.class);

    private final AuthSettingDomainService authSettingDomainService;
    private final SsoConfigProperties ssoConfigProperties;

    public SsoConfigInitializer(AuthSettingDomainService authSettingDomainService,
            SsoConfigProperties ssoConfigProperties) {
        this.authSettingDomainService = authSettingDomainService;
        this.ssoConfigProperties = ssoConfigProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("检查 GitHub SSO 配置...");

        try {
            seedGitHubConfig();
        } catch (Exception e) {
            log.error("GitHub SSO 配置注入失败，不影响应用启动", e);
        }
    }

    /** 从环境变量种子注入 GitHub OAuth 配置到数据库 */
    private void seedGitHubConfig() {
        // 1. 检查环境变量是否配置
        if (!ssoConfigProperties.isConfigured()) {
            log.info("GitHub SSO 环境变量未配置，跳过种子注入");
            return;
        }

        // 2. 查询数据库记录
        AuthSettingEntity entity = authSettingDomainService.getByFeatureKey(AuthFeatureKey.GITHUB_LOGIN);
        if (entity == null) {
            log.warn("auth_settings 表中未找到 GITHUB_LOGIN 记录，跳过种子注入");
            return;
        }

        // 3. 如果数据库已有 config_data，不覆盖
        Map<String, Object> existingConfig = entity.getConfigData();
        if (existingConfig != null 
                && existingConfig.get("clientId") != null 
                && existingConfig.get("clientSecret") != null) {
            log.info("数据库已有 GitHub SSO 配置，跳过种子注入");
            return;
        }

        // 4. 写入环境变量配置到数据库
        Map<String, Object> configData = new HashMap<>();
        configData.put("clientId", ssoConfigProperties.getClientId());
        configData.put("clientSecret", ssoConfigProperties.getClientSecret());
        configData.put("redirectUri", ssoConfigProperties.getRedirectUri());

        entity.setConfigData(configData);
        authSettingDomainService.updateAuthSetting(entity);

        log.info("GitHub SSO 配置已从环境变量注入数据库 (clientId={})",
                ssoConfigProperties.getClientId().substring(0, 6) + "***");
    }
}
```

---

### 步骤 7：修复前端 SSO 回调地址

**文件**: `agentx-frontend-plus/app/(auth)/login/page.tsx`

**问题**: `handleGitHubLogin()` 和 `handleQiaoyaLogin()` 调用 `getSsoLoginUrlApi()` 时未传 `redirectUrl`，导致 GitHub 回调到后端 API（`/api/sso/github/callback`）返回 JSON，而非回调到前端页面完成登录流程。

**修改 1 — GitHub 登录**:

```diff
- const res = await getSsoLoginUrlApi('github')
+ const res = await getSsoLoginUrlApi('github', `${window.location.origin}/sso/github/callback`)
```

**修改 2 — 敲鸭登录**:

```diff
- const res = await getSsoLoginUrlApi('community')
+ const res = await getSsoLoginUrlApi('community', `${window.location.origin}/sso/community/callback`)
```

**数据流**:

```text
前端 login 页 → 后端获取 loginUrl（redirect_uri = 前端回调页）
  → GitHub 授权
  → 浏览器跳转到 http://localhost:3000/sso/github/callback?code=xxx
  → 前端页面拿到 code → handleSsoCallbackApi() → 后端换 token
  → 保存 token → 跳转首页
```

> **注意**: GitHub OAuth App 的 Authorization callback URL 需同步添加 `http://localhost:3000/sso/github/callback`。

---

### 步骤 8：验证 `SsoConfigProvider` 无需改动

**文件**: `AgentX/src/main/java/org/xhy/infrastructure/sso/SsoConfigProvider.java`

确认现有逻辑完全满足需求：

```java
// 现有逻辑（不动）：
// 1. getGitHubConfig() → 从 DB 读取 config_data
// 2. getEffectiveConfig() → 校验 clientId/clientSecret/redirectUri 是否完整
// 3. 不完整时抛出 BusinessException
```

启动注入后，DB 中已有完整配置，此文件无需任何修改。

---

## 三、验证方案

### 3.1 首次部署验证

1. 清空数据库或删除 `auth_settings` 中 `GITHUB_LOGIN` 的 `config_data`
2. 启动应用，查看日志：
   ```
   GitHub SSO 配置已从环境变量注入数据库 (clientId=Ov23li***)
   ```
3. 调用 `GET /api/sso/github/login`，应返回 `200` + GitHub 授权 URL（`redirect_uri` 应为前端地址）
4. 点击 GitHub 登录 → GitHub 授权 → 浏览器应跳转到 `http://localhost:3000/sso/github/callback?code=xxx`
5. 前端回调页显示 loading → 获取 token → 跳转首页 ✅

### 3.2 已有配置保护验证

1. 数据库已有完整 `config_data`
2. 重启应用，日志显示：
   ```
   数据库已有 GitHub SSO 配置，跳过种子注入
   ```
3. 数据库中原有配置未被覆盖

### 3.3 未配置环境变量验证

1. `.env` 不设置 `GITHUB_CLIENT_ID` 等变量
2. 启动应用，日志显示：
   ```
   GitHub SSO 环境变量未配置，跳过种子注入
   ```
3. `GET /api/sso/github/login` 仍返回 400（预期行为）

### 3.4 前端回调地址验证

1. 确认 `GET /api/sso/github/login` 返回的 `loginUrl` 中 `redirect_uri` 参数为 `http://localhost:3000/sso/github/callback`
2. 确认 GitHub OAuth App 的 Authorization callback URL 包含 `http://localhost:3000/sso/github/callback`
3. 完整走通 GitHub 授权 → 前端回调页 → 换 token → 跳转首页
