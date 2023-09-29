package com.copybot.plugin.api.action;

import com.copybot.plugin.api.exception.ActionErrorException;

public interface IProcessAction extends IAction {

    void doProcess(WorkItem item) throws ActionErrorException;

}