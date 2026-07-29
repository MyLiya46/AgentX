package org.xhy.domain.trace.event;

import org.springframework.context.ApplicationEvent;
import org.xhy.domain.trace.model.ToolCallInfo;
import org.xhy.domain.trace.model.TraceContext;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 工具执行事件 */
public class ToolExecutedEvent extends ApplicationEvent {

    private final TraceContext traceContext;
    private final ToolCallInfo toolCallInfo;
    private final OffsetDateTime toolExecutionStartTime;

    public ToolExecutedEvent(Object source, TraceContext traceContext, ToolCallInfo toolCallInfo) {
        super(source);
        this.traceContext = traceContext;
        this.toolCallInfo = toolCallInfo;
        this.toolExecutionStartTime = OffsetDateTime.now(ZoneId.of("Asia/Shanghai")); // 默认使用当前时间
    }

    public ToolExecutedEvent(Object source, TraceContext traceContext, ToolCallInfo toolCallInfo,
            OffsetDateTime toolExecutionStartTime) {
        super(source);
        this.traceContext = traceContext;
        this.toolCallInfo = toolCallInfo;
        this.toolExecutionStartTime = toolExecutionStartTime;
    }

    public TraceContext getTraceContext() {
        return traceContext;
    }

    public ToolCallInfo getToolCallInfo() {
        return toolCallInfo;
    }

    public OffsetDateTime getToolExecutionStartTime() {
        return toolExecutionStartTime;
    }
}