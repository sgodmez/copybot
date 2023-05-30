package com.copybot.plugin.metadataextractor;

import com.copybot.plugin.action.IAnalyzeAction;
import com.copybot.plugin.definition.IPlugin;
import com.copybot.plugin.metadataextractor.actions.ExtractMetadata;

import java.io.*;
import java.util.List;

public class MetadataExtractorPlugin implements IPlugin {

    @Override
    public String getName() {
        return "metadata";
    }

    @Override
    public void doManyThings(File file) {
        try (InputStream is = new FileInputStream(file)) {
            ExtractMetadata.extractMetadata(is);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Class<? extends IAnalyzeAction>> getAnalyzeActions() {
        return List.of(ExtractMetadata.class);
    }
}