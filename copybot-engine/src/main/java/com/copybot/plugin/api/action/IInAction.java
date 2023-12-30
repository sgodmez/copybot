package com.copybot.plugin.api.action;

import java.util.function.Consumer;

public interface IInAction extends IAction {

    /**
     * Function that produce work items to the provided consumer.
     * Action ends when returns.
     */
    void listFiles(Consumer<WorkItem> workItemConsumer);


}