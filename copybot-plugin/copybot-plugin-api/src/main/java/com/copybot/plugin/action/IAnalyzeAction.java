package com.copybot.plugin.action;

import com.copybot.plugin.exception.ActionErrorException;

public interface IAnalyzeAction extends IAction {

    void doAnalyze(WorkItem item) throws ActionErrorException;

}