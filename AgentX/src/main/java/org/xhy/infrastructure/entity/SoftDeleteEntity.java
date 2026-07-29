package org.xhy.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;

import java.time.LocalDateTime;

/** 支持逻辑删除的实体基类（继承自 BaseEntity，增加 deletedAt 逻辑删除字段）
 *
 * 对于数据库表有 deleted_at 列且需要通过 MyBatis-Plus @TableLogic 自动过滤已删除记录的实体， 应继承本类而非直接继承 BaseEntity。
 *
 * 不需要逻辑删除的实体（如 memory_items 使用 status 字段管理软删除）直接继承 BaseEntity。 */
public class SoftDeleteEntity extends BaseEntity {

    /** 逻辑删除时间（null=未删除，非null=已删除） */
    @TableLogic(value = "null", delval = "now()")
    @TableField("deleted_at")
    protected LocalDateTime deletedAt;

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
