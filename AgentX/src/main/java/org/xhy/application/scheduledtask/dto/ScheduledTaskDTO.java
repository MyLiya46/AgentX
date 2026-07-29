package org.xhy.application.scheduledtask.dto;

import org.xhy.domain.scheduledtask.constant.RepeatType;
import org.xhy.domain.scheduledtask.constant.ScheduleTaskStatus;
import org.xhy.domain.scheduledtask.model.RepeatConfig;

import java.time.OffsetDateTime;

/** 定时任务DTO */
public class ScheduledTaskDTO {

    /** 任务ID */
    private String id;

    /** 用户ID */
    private String userId;

    /** Agent ID */
    private String agentId;

    /** 会话ID */
    private String sessionId;

    /** 任务内容 */
    private String content;

    /** 重复类型 */
    private RepeatType repeatType;

    /** 重复配置 */
    private RepeatConfig repeatConfig;

    /** 任务状态 */
    private ScheduleTaskStatus status;

    /** 上次执行时间 */
    private OffsetDateTime lastExecuteTime;

    /** 下次执行时间 */
    private OffsetDateTime nextExecuteTime;

    /** 创建时间 */
    private OffsetDateTime createdAt;

    /** 更新时间 */
    private OffsetDateTime updatedAt;

    // Getters and Setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public RepeatType getRepeatType() {
        return repeatType;
    }

    public void setRepeatType(RepeatType repeatType) {
        this.repeatType = repeatType;
    }

    public RepeatConfig getRepeatConfig() {
        return repeatConfig;
    }

    public void setRepeatConfig(RepeatConfig repeatConfig) {
        this.repeatConfig = repeatConfig;
    }

    public ScheduleTaskStatus getStatus() {
        return status;
    }

    public void setStatus(ScheduleTaskStatus status) {
        this.status = status;
    }

    public OffsetDateTime getLastExecuteTime() {
        return lastExecuteTime;
    }

    public void setLastExecuteTime(OffsetDateTime lastExecuteTime) {
        this.lastExecuteTime = lastExecuteTime;
    }

    public OffsetDateTime getNextExecuteTime() {
        return nextExecuteTime;
    }

    public void setNextExecuteTime(OffsetDateTime nextExecuteTime) {
        this.nextExecuteTime = nextExecuteTime;
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