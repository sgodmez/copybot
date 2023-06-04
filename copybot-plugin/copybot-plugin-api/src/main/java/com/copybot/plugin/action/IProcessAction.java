package com.copybot.plugin.action;

import com.copybot.plugin.exception.ActionErrorException;

public interface IProcessAction extends IAction {

    void doProcess(WorkItem item) throws ActionErrorException;

}