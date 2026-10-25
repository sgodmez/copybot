package com.copybot.plugin.metadataextractor;

import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.IProcessAction;
import com.copybot.plugin.api.definition.AbstractPlugin;
import com.copybot.plugin.metadataextractor.actions.ExtractMetadata;
import com.copybot.plugin.metadataextractor.actions.WriteDate;

import java.util.List;

public class MetadataExtractorPlugin extends AbstractPlugin {

    @Override
    public String getPluginCode() {
        return "metadata-extractor";
    }

    @Override
    public List<ActionDefinition<? extends IAnalyzeAction>> getAnalyzeActions() {
        return List.of(
                new ActionDefinition<>("extract", ExtractMetadata.class)
        );
    }

    @Override
    public List<ActionDefinition<? extends IProcessAction>> getProcessActions() {
        return List.of(
                new ActionDefinition<>("write-date", WriteDate.class)
        );
    }
}