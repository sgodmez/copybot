package com.copybot.engine.sample;

import com.copybot.engine.StepResolver;
import com.copybot.engine.pipeline.PipelineConfig;
import com.copybot.engine.pipeline.PipelineStep;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.IProcessAction;

import java.util.List;

/** How the sampler gets the steps of a pipeline: the plugins in production, fakes in the tests. */
public interface SampleSteps {

    List<PipelineStep<IInAction>> in(PipelineConfig config);

    List<PipelineStep<IAnalyzeAction>> analyse(PipelineConfig config);

    List<PipelineStep<IProcessAction>> process(PipelineConfig config);

    SampleSteps PLUGINS = new SampleSteps() {
        @Override
        public List<PipelineStep<IInAction>> in(PipelineConfig config) {
            return StepResolver.in(config);
        }

        @Override
        public List<PipelineStep<IAnalyzeAction>> analyse(PipelineConfig config) {
            return StepResolver.analyse(config);
        }

        @Override
        public List<PipelineStep<IProcessAction>> process(PipelineConfig config) {
            return StepResolver.process(config);
        }
    };
}
