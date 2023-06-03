package com.copybot.plugin.metadataextractor;

import com.copybot.plugin.action.ActionDefinition;
import com.copybot.plugin.action.IAnalyzeAction;
import com.copybot.plugin.definition.AbstractPlugin;
import com.copybot.plugin.metadataextractor.actions.ExtractMetadata;

import java.util.List;

public class MetadataExtractorPlugin extends AbstractPlugin {

    @Override
    public String getPluginCode() {
        var a=new javafx.geometry.Insets(12);
        return "metadata-extractor";
    }

    @Override
    public List<ActionDefinition<? extends IAnalyzeAction>> getAnalyzeActions() {
        return List.of(
                new ActionDefinition("extract", ExtractMetadata.class, true)
        );
    }
}