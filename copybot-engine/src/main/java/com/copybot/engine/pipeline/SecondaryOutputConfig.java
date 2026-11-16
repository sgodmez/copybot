package com.copybot.engine.pipeline;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Objects;

/**
 * One secondary output of the pipeline (spec secondary-outputs §2): a branch that gets its own copy of every analysed
 * item, runs its own process steps and ends on its own out step, before the main branch.
 *
 * @param name        required, not empty, unique in the pipeline (case ignored): tells the branch in the messages
 * @param onError     what an error of the branch does to the item; absent: {@link OnError#FAIL}
 * @param actionSteps the process steps of the branch; absent: none; never null
 * @param outStep     the out step of the branch: required (refused at the reading when absent)
 */
public record SecondaryOutputConfig(String name, OnError onError, List<PipelineStepConfig> actionSteps,
                                    PipelineStepConfig outStep) {

    /** What an error of a secondary branch does, read in lower case ({@code "fail"}, {@code "warn"}). */
    public enum OnError {
        /** The item is in error and its main branch does not run. */
        @SerializedName("fail") FAIL,
        /** The error becomes a warning of the run, the item goes on. */
        @SerializedName("warn") WARN
    }

    public SecondaryOutputConfig {
        actionSteps = actionSteps == null ? List.of() : actionSteps.stream().filter(Objects::nonNull).toList();
        onError = onError == null ? OnError.FAIL : onError;
    }
}
