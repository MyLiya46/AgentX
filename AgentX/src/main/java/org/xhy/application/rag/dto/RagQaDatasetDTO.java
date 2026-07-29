package org.xhy.application.rag.dto;

import java.time.OffsetDateTime;

/** RAG知识库数据集数据传输对象
 * @author shilong.zang
 * @date 2024-12-09 */
public class RagQaDatasetDTO {

    /** 数据集ID */
    private String id;

    /** 用户RAG安装记录ID（用于调用已安装RAG相关接口） */
    private String userRagId;

    /** 数据集名称 */
    private String name;

    /** 数据集图标 */
    private String icon;

    /** 数据集说明 */
    private String description;

    /** 用户ID */
    private String userId;

    /** 文件数量 */
    private Long fileCount;

    /** 创建时间 */
    private OffsetDateTime createdAt;

    /** 更新时间 */
    private OffsetDateTime updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserRagId() {
        return userRagId;
    }

    public void setUserRagId(String userRagId) {
        this.userRagId = userRagId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Long getFileCount() {
        return fileCount;
    }

    public void setFileCount(Long fileCount) {
        this.fileCount = fileCount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}