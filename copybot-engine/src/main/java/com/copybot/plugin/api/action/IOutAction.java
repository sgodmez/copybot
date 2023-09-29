package com.copybot.plugin.api.action;

import com.copybot.plugin.api.exception.ActionErrorException;

public interface IOutAction extends IAction {

    void writeItem(WorkItem workItem) throws ActionErrorException;


}