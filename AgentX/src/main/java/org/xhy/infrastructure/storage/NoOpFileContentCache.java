package org.xhy.infrastructure.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/** 空操作缓存实现
 *
 * 当 Redis 不可用或未配置时使用，所有操作均为空操作。 通过 ConditionalOnMissingBean 确保仅在无其他 FileContentCache 实现时生效。 */
@Component
@ConditionalOnMissingBean(FileContentCache.class)
public class NoOpFileContentCache implements FileContentCache {

    @Override
    public String get(String fileUrl) {
        return null;
    }

    @Override
    public void put(String fileUrl, String content) {
        // 无操作
    }
}
