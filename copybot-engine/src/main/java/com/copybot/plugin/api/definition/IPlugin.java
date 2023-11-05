package com.copybot.plugin.api.definition;

import com.copybot.plugin.api.action.*;
import com.copybot.resources.CombinedResourceBundle;
import com.google.gson.JsonElement;

import java.util.List;

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

    /*
    // make dedicated interface to use also with actions ?
    default boolean canMigrateConfig(int previousMajorVersion) {
        return false;
    }

    default JsonElement migrateConfig(JsonElement previousConfig) {
        return previousConfig;
    }
     */

    Iterable<String> getI18nBundleNames();

    void setResourceBundle(CombinedResourceBundle resourceBundle);

    CombinedResourceBundle getResourceBundle();

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
