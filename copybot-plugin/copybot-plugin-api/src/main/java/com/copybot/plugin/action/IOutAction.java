package com.copybot.plugin.action;

import com.copybot.plugin.exception.ActionErrorException;

public interface IOutAction extends IAction {

    void writeItem(WorkItem workItem) throws ActionErrorException;


}