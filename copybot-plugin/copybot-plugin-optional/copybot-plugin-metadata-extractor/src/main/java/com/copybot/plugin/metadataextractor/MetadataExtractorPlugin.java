package com.copybot.plugin.metadataextractor;

import com.copybot.plugin.definition.api.IPlugin;

import java.io.*;

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
}