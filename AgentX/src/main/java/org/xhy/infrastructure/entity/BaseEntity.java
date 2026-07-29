package org.xhy.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;

import java.time.OffsetDateTime;

public class BaseEntity {

    @TableField(fill = FieldFill.INSERT)
    protected OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    protected OffsetDateTime updatedAt;

    @TableField(exist = false)
    private Operator operatedBy = Operator.USER;

    public void setAdmin() {
        this.operatedBy = Operator.ADMIN;
    }

    public boolean needCheckUserId() {
        return this.operatedBy == Operator.USER;
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

    public Operator getOperatedBy() {
        return operatedBy;
    }

    public void setOperatedBy(Operator operatedBy) {
        this.operatedBy = operatedBy;
    }
}
