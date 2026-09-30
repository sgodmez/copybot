package com.copybot.engine.pipeline;

public enum ItemStatus {
    PENDING,
    WAITING_RESOURCES,
    RUNNING,
    DONE,
    ERROR,
    /** not selected by the resume point, or skipped by the out step: see WorkItemExecution#getSkipReason() */
    SKIPPED
}
