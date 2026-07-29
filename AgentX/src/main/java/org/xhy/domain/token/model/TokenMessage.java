package org.xhy.domain.token.model;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Token领域的消息模型 只包含Token计算所需的必要信息 */
public class TokenMessage {

    /** 消息ID */
    private String id;

    /** 消息内容 */
    private String content;

    /** 消息角色 */
    private String role;

    /** 消息Token数量 */
    private Integer tokenCount;

    /** 消息本体Token数量 */
    private Integer bodyTokenCount;

    /** 创建时间 */
    private OffsetDateTime createdAt;

    /** 默认构造函数 */
    public TokenMessage() {
        this.createdAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
    }

    /** 带参数的构造函数 */
    public TokenMessage(String id, String content, String role, Integer tokenCount) {
        this.id = id;
        this.content = content;
        this.role = role;
        this.tokenCount = tokenCount;
        this.createdAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
    }

    /** 完整参数的构造函数 */
    public TokenMessage(String id, String content, String role, Integer tokenCount, OffsetDateTime createdAt) {
        this.id = id;
        this.content = content;
        this.role = role;
        this.tokenCount = tokenCount;
        this.createdAt = createdAt != null ? createdAt : OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
    }

    /** 添加带有content和role参数的构造函数 */
    public TokenMessage(String content, String role) {
        this.content = content;
        this.role = role;
        this.createdAt = OffsetDateTime.now(ZoneId.of("Asia/Shanghai"));
    }

    // Getter和Setter

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public long getCreatedAtMillis() {
        return createdAt.toInstant().toEpochMilli();
    }

    public void setCreatedAtMillis(long createdAtMillis) {
        this.createdAt = OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(createdAtMillis), ZoneOffset.UTC);
    }

    public Integer getBodyTokenCount() {
        return bodyTokenCount;
    }

    public void setBodyTokenCount(Integer bodyTokenCount) {
        this.bodyTokenCount = bodyTokenCount;
    }
}