package com.copybot.plugin.api.action;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What a run did, handed to the end-of-run actions ({@link IRunEndAction}). The lists and the map are unmodifiable
 * copies.
 *
 * @param pipelineName       the pipeline file's name without extension; empty when unknown
 * @param pipelinePath       the pipeline file; null when unknown
 * @param outcome            how the run ended
 * @param started            the start of the run's first phase (preparation included)
 * @param copyStarted        the start of the copy, null when it did not begin
 * @param ended              the end of the run, before the first end-of-run action
 * @param counts             the items per outcome
 * @param items              the items processed in this run (not the ones a resume point skipped)
 * @param writtenFiles       every file written, in the order of the writes
 * @param createdDirectories the directories the run created
 * @param warnings           the warnings of the run so far
 * @param failure            the message of the failure that ended the run in ERROR, null otherwise
 * @param runId              the run's unique id
 * @param outputs            what the previous end-of-run actions published
 */
public record RunReport(String pipelineName, Path pipelinePath, RunOutcome outcome, Instant started,
                        Instant copyStarted, Instant ended,
                        RunCounts counts, List<RunItem> items, List<WrittenFile> writtenFiles,
                        List<Path> createdDirectories, List<String> warnings, String failure, String runId,
                        Map<String, String> outputs) {

    public RunReport {
        items = List.copyOf(items);
        writtenFiles = List.copyOf(writtenFiles);
        createdDirectories = List.copyOf(createdDirectories);
        warnings = List.copyOf(warnings);
        outputs = Map.copyOf(outputs);
    }

    /** The time of the copy: from {@code copyStarted} (from {@code started} when the copy did not begin) to the end. */
    public Duration duration() {
        return Duration.between(copyStarted == null ? started : copyStarted, ended);
    }
}
