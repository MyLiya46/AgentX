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

        log.info("GitHub SSO 配置已从环境变量注入数据库 (clientId={}***)",
                ssoConfigProperties.getClientId().substring(0, 6));
    }
}
