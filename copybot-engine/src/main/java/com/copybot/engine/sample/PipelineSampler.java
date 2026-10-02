package com.copybot.engine.sample;

import java.time.Duration;

/** Entry point of the pipeline editor's sample (spec pattern-helper §3): a session on the installed plugins. */
public final class PipelineSampler {

    /** The listing stops after this many items. */
    public static final int MAX_LISTED = 200;
    /** The listing stops after this long. */
    public static final Duration LISTING_TIMEOUT = Duration.ofSeconds(2);
    /** Number of items kept, analysed and shown. */
    public static final int MAX_KEPT = 10;

    private PipelineSampler() {
    }

    public static SampleSession open() {
        return new SampleSession(SampleSteps.PLUGINS, MAX_LISTED, LISTING_TIMEOUT, MAX_KEPT);
    }
}
