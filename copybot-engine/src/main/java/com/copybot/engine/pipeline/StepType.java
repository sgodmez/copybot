package com.copybot.engine.pipeline;

import com.copybot.plugin.action.*;

public enum StepType {
    IN(IInAction.class),
    ANALYZE(IAnalyzeAction.class),
    PROCESS(IProcessAction.class),
    OUT(IOutAction.class);

    private final Class<? extends IAction> actionClass;

    StepType(Class<? extends IAction> actionClass) {
        this.actionClass = actionClass;
    }

    public Class<? extends IAction> getActionClass() {
        return actionClass;
    }

    public static StepType getType(Class<? extends IAction> actionClass) {
        for (StepType st : StepType.values()) {
            if (st.getActionClass().equals(actionClass)) {
                return st;
            }
        }
        throw new UnsupportedOperationException(actionClass.getCanonicalName() + " is unknown");
    }
}
