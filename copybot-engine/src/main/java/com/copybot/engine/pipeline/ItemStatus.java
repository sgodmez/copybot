package com.copybot.engine.pipeline;

public enum ItemStatus {
    PENDING,
    WAITING_RESOURCES,
    RUNNING,
    DONE,
    ERROR,
    /** not selected by the resume point: see WorkItemExecution#getSkipReason() */
    SKIPPED
}
