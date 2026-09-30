package com.copybot.engine.pipeline;

public enum PipelineStatus {
    NEW,
    LISTED,
    /** listed and analysed, resume resolved: waiting for execute() */
    PREPARED,
    RUNNING,
    ERROR,
    SUCCESS
}
