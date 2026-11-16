package com.copybot.ui;

import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.sample.PipelineSampler;
import com.copybot.engine.sample.Sample;
import com.copybot.engine.sample.SampleSession;
import com.copybot.ui.model.PatternHelperModel;
import com.copybot.ui.model.PipelineDocument;
import com.copybot.ui.model.PipelineDocument.Section;
import com.copybot.ui.util.Background;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import javafx.scene.control.TextInputControl;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The sample of the pipeline edited in an {@link EditorController} and the files the user chose to test (spec
 * pattern-helper §5), shown by the helper of the out form: taken in the background, one session each, until the
 * editor closes. JavaFX thread only.
 */
final class EditorSamples {

    private final PipelineDocument document;
    /** The sample of the edited pipeline, opened on the first out form shown. */
    private SampleSession sampleSession;
    private Sample sample;
    /** The JSON the sample was taken from, null before the first one (and after a failed one). */
    private String sampledIn;
    private String sampledProcessing;
    /** A sample is being taken in the background. */
    private boolean sampling;
    /** Set when the window closes: late background callbacks must not touch it. */
    private boolean closed;
    /** The helper of the out form shown, null when another form is shown. */
    private PatternHelper patternHelper;
    /** What the helper keeps across rebuilt forms while the editor is open (panel expanded, tested files). */
    private final PatternHelper.State helperState = new PatternHelper.State();
    /** Analyses the files the user chose to test, one at a time, apart from the sample's session. */
    private SampleSession pinnedSession;
    /** Tested files waiting for their analysis, and whether one runs. */
    private final Set<Path> pinnedQueue = new LinkedHashSet<>();
    private boolean pinnedRunning;

    EditorSamples(PipelineDocument document) {
        this.document = document;
    }

    /**
     * The helper of the pattern field of the out form being built: it shows the sample, taken (again) when it no
     * longer matches the steps.
     *
     * @param onMissingKey the current "onMissingKey" of the out step, null without such a field
     */
    PatternHelper helperFor(TextInputControl input, Supplier<String> onMissingKey) {
        patternHelper = new PatternHelper(input, onMissingKey, helperState, helperActions());
        if (sample != null) {
            patternHelper.showSample(sample);
        }
        ensureSample();
        return patternHelper;
    }

    /** Another form is shown: the helper belonged to the form being replaced. */
    void formReplaced() {
        patternHelper = null;
    }

    /** The window closed: the sample and the tested files are no longer wanted. */
    void close() {
        closed = true;
        cancelSample();
        if (pinnedSession != null) {
            pinnedSession.cancel();
        }
    }

    /**
     * Starts a sample when none matches the steps being edited: listed again when the input steps changed,
     * analysed again when only the analysis or action steps did. While one runs, the helper just shows it loading.
     */
    private void ensureSample() {
        if (sampling) {
            if (patternHelper != null) {
                patternHelper.showLoading();
            }
            return;
        }
        String in = document.samplingInJson();
        String processing = document.samplingProcessingJson();
        PatternHelperModel.Rerun rerun = PatternHelperModel.rerun(sampledIn, sampledProcessing, in, processing);
        if (rerun == PatternHelperModel.Rerun.NONE) {
            return;
        }
        PipelineConfig config;
        try {
            config = document.samplingConfig();
        } catch (IllegalArgumentException e) {
            showSampleResult(Sample.failed(String.valueOf(e.getMessage())), in, processing);
            return;
        }
        if (sampleSession == null) {
            sampleSession = PipelineSampler.open();
        }
        SampleSession session = sampleSession;
        sampling = true;
        if (patternHelper != null) {
            patternHelper.showLoading();
        }
        Background.run(() -> rerun == PatternHelperModel.Rerun.LIST ? session.list(config) : session.analyse(config),
                result -> sampleDone(result, in, processing),
                e -> sampleDone(Sample.failed(String.valueOf(e.getMessage())), in, processing));
    }

    private void sampleDone(Sample result, String in, String processing) {
        sampling = false;
        if (closed) {
            return;
        }
        showSampleResult(result, in, processing);
        if (result.failure().isEmpty()) {
            // analysed with the steps of this sample: the tested files follow
            // over a copy: analysePinned replaces the entries of the list
            List.copyOf(helperState.pinned).forEach(pinned -> analysePinned(pinned.file()));
        }
        if (patternHelper != null && result.failure().isEmpty()) {
            // the steps may have been edited while it ran (the out form shown again meanwhile): catch up
            ensureSample();
        }
    }

    // ---- tested files ----

    private PatternHelper.Actions helperActions() {
        return new PatternHelper.Actions() {
            @Override
            public void retry() {
                resample();
            }

            @Override
            public void cancel() {
                cancelSample();
            }

            @Override
            public void test(Path file) {
                Path normalized = file.toAbsolutePath().normalize();
                helperState.pinned.removeIf(pinned -> pinned.file().equals(normalized));
                helperState.pinned.addFirst(new PatternHelper.Pinned(normalized, null));
                analysePinned(normalized);
            }

            @Override
            public void unpin(Path file) {
                helperState.pinned.removeIf(pinned -> pinned.file().equals(file));
                pinnedQueue.remove(file);
                refreshHelper();
            }

            @Override
            public Path sourceFolder() {
                return EditorSamples.this.sourceFolder();
            }
        };
    }

    /**
     * The path of the first input step's actionConfig, else null; whether it is a folder is checked by the helper,
     * off the JavaFX thread.
     */
    private Path sourceFolder() {
        List<JsonObject> in = document.steps(Section.IN);
        if (in.isEmpty() || !(in.getFirst().get("actionConfig") instanceof JsonObject config)
                || !(config.get("path") instanceof JsonPrimitive path) || !path.isString()) {
            return null;
        }
        try {
            return Path.of(path.getAsString());
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** Shows the file as being analysed and queues its analysis (with the steps current when it starts). */
    private void analysePinned(Path file) {
        setPinned(file, null);
        pinnedQueue.add(file);
        refreshHelper();
        nextPinned();
    }

    /** Runs the next queued analysis unless one runs: the session runs one operation at a time. */
    private void nextPinned() {
        if (pinnedRunning || pinnedQueue.isEmpty() || closed) {
            return;
        }
        Path file = pinnedQueue.iterator().next();
        pinnedQueue.remove(file);
        PipelineConfig config;
        try {
            config = document.samplingConfig();
        } catch (IllegalArgumentException e) {
            setPinned(file, Sample.failed(String.valueOf(e.getMessage())));
            refreshHelper();
            nextPinned();
            return;
        }
        if (pinnedSession == null) {
            pinnedSession = PipelineSampler.open();
        }
        SampleSession session = pinnedSession;
        pinnedRunning = true;
        Background.run(() -> session.analyseFile(config, file),
                result -> pinnedDone(file, result),
                e -> pinnedDone(file, Sample.failed(String.valueOf(e.getMessage()))));
    }

    private void pinnedDone(Path file, Sample result) {
        pinnedRunning = false;
        if (closed) {
            return;
        }
        if (!pinnedQueue.contains(file)) { // queued again meanwhile: the newer analysis decides
            setPinned(file, result);
        }
        refreshHelper();
        nextPinned();
    }

    /** Replaces the analysis of a tested file still pinned (an unpinned one is left alone). */
    private void setPinned(Path file, Sample result) {
        List<PatternHelper.Pinned> pinned = helperState.pinned;
        for (int i = 0; i < pinned.size(); i++) {
            if (pinned.get(i).file().equals(file)) {
                pinned.set(i, new PatternHelper.Pinned(file, result));
            }
        }
    }

    /** The effects shown follow the steps (an edited "onMissingKey", a tested file analysed). */
    void refreshHelper() {
        if (patternHelper != null) {
            patternHelper.refresh();
        }
    }

    private void showSampleResult(Sample result, String in, String processing) {
        sample = result;
        // a failed (or cancelled) sample is not remembered as taken: the next display of the out form tries again
        sampledIn = result.failure().isPresent() ? null : in;
        sampledProcessing = result.failure().isPresent() ? null : processing;
        if (patternHelper != null) {
            patternHelper.showSample(result);
        }
    }

    /** "Retry": listed again. */
    private void resample() {
        sampledIn = null;
        ensureSample();
    }

    private void cancelSample() {
        if (sampleSession != null) {
            sampleSession.cancel();
        }
    }
}
