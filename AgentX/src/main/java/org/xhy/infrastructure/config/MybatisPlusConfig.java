package org.xhy.infrastructure.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/** MyBatis-Plus配置类 用于配置MyBatis-Plus的自动填充、分页等功能 */
@Configuration
public class MybatisPlusConfig implements MetaObjectHandler {

    private static final Logger logger = LoggerFactory.getLogger(MybatisPlusConfig.class);

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    /** 添加分页插件 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL)); // 如果配置多个插件, 切记分页最后添加
        // 如果有多数据源可以不配具体类型, 否则都建议配上具体的 DbType
        return interceptor;
    }

    /** 插入操作自动填充 */
    @Override
    public void insertFill(MetaObject metaObject) {

        // 填充创建时间和更新时间
        OffsetDateTime now = OffsetDateTime.now(ZONE_ID);
        this.strictInsertFill(metaObject, "createdAt", OffsetDateTime.class, now);
        this.strictInsertFill(metaObject, "updatedAt", OffsetDateTime.class, now);
    }

    /** 更新操作自动填充 */
    @Override
    public void updateFill(MetaObject metaObject) {

        // 填充更新时间
        OffsetDateTime now = OffsetDateTime.now(ZONE_ID);
        this.strictUpdateFill(metaObject, "updatedAt", OffsetDateTime.class, now);
    }
}