package com.copybot.plugin.api.action;

import com.copybot.plugin.api.exception.ActionErrorException;

public interface IAnalyzeAction extends IAction {

    void doAnalyze(WorkItem item) throws ActionErrorException;

}