package org.xhy.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** GitHub SSO OAuth 配置属性（从环境变量/.env 读取）
 *
 * 用于启动时将配置种子注入数据库，运行时以数据库为准。
 *
 * @author AgentX */
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
