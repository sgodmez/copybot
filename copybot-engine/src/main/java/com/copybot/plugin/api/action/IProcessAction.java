package com.copybot.plugin.api.action;

import java.util.List;

public interface IProcessAction extends IAction {

    List<WorkItem> doProcess(WorkItem item);

}