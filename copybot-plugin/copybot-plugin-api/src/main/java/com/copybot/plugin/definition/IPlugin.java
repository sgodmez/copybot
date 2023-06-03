package com.copybot.plugin.definition;

import com.copybot.plugin.action.*;
import com.google.gson.JsonElement;

import java.util.List;
import java.util.ResourceBundle;

public interface IPlugin {

    /**
     * Plugin code is used for reference in the pipeline JSON.
     * Display name and description are i18n derived from this code as :
     * - display name : plugin.pluginCode.name
     * - description : plugin.pluginCode.description
     */
    String getPluginCode();

    default void loadConfig(JsonElement config) {
        // nothing by default
    }

    Iterable<String> getI18nBundleNames();

    void setResourceBundle(ResourceBundle resourceBundle);

    ResourceBundle getResourceBundle();

    default List<ActionDefinition<? extends IInAction>> getInActions() {
        return List.of();
    }

    default List<ActionDefinition<? extends IAnalyzeAction>> getAnalyzeActions() {
        return List.of();
    }

    default List<ActionDefinition<? extends IProcessAction>> getProcessActions() {
        return List.of();
    }

    default List<ActionDefinition<? extends IOutAction>> getOutActions() {
        return List.of();
    }

}
