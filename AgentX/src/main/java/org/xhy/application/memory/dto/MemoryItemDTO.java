package org.xhy.application.memory.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** 记忆条目 DTO */
public class MemoryItemDTO {
    private String id;
    private String type;
    private String text;
    private Float importance;
    private List<String> tags;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public String getId() {
        return id;
    }
    public void setId(String id) {
        this.id = id;
    }
    public String getType() {
        return type;
    }
    public void setType(String type) {
        this.type = type;
    }
    public String getText() {
        return text;
    }
    public void setText(String text) {
        this.text = text;
    }
    public Float getImportance() {
        return importance;
    }
    public void setImportance(Float importance) {
        this.importance = importance;
    }
    public List<String> getTags() {
        return tags;
    }
    public void setTags(List<String> tags) {
        this.tags = tags;
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
