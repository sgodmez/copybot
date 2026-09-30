package com.copybot.engine.pipeline;

public enum PipelineStatus {
    NEW,
    LISTED,
    /** listed and analysed, resume resolved: waiting for execute() */
    PREPARED,
    RUNNING,
    /** paused by the caller: no new step starts until resumed, then back to RUNNING */
    PAUSED,
    ERROR,
    SUCCESS,
    /** stopped by the caller (cancel) or by an interruption of the pipeline thread; the resume cursor is not written */
    CANCELLED
}
