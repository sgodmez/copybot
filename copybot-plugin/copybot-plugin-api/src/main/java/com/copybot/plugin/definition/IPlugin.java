package com.copybot.plugin.definition;

import com.copybot.plugin.action.IAnalyzeAction;
import com.copybot.plugin.action.IInAction;
import com.copybot.plugin.action.IOutAction;
import com.copybot.plugin.action.IProcessAction;

import java.io.File;
import java.util.List;

public interface IPlugin {

    String getName();

    void doManyThings(File file);

   default List<Class<? extends IInAction>> getInActions() {
     return List.of();
   };

   default List<Class<? extends IAnalyzeAction>> getAnalyzeActions() {
     return List.of();
   };

   default List<Class<? extends IProcessAction>> getProcessActions() {
     return List.of();
   };

   default List<Class<? extends IOutAction>> getOutActions() {
     return List.of();
   };


}
