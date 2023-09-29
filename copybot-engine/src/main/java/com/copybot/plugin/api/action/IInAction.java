package com.copybot.plugin.api.action;

import com.copybot.plugin.api.exception.ActionErrorException;

import java.util.function.Consumer;

public interface IInAction extends IAction {

    /**
     * Function that produce work items to the provided consumer.
     * Action ends when returns.
     */
    void listFiles(Consumer<WorkItem> workItemConsumer) throws ActionErrorException;


}