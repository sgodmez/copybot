package com.copybot.plugin.metadataextractor.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.AbstractAction;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.action.WorkItem;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class ExtractMetadata extends AbstractAction implements IAnalyzeAction {
    private static Map<String, String> extractMetadata(InputStream fileIS) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(fileIS);

            for (Directory directory : metadata.getDirectories()) {
                for (Tag tag : directory.getTags()) {
                    System.out.format("[%s] - %s = %s\n",
                            directory.getName(), tag.getTagName(), tag.getDescription());
                }
                if (directory.hasErrors()) {
                    for (String error : directory.getErrors()) {
                        System.err.format("ERROR: %s", error);
                    }
                }
            }
            return null;
        } catch (ImageProcessingException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void doAnalyze(WorkItem item) {
        try (InputStream is = item.openInputStream()) {
            extractMetadata(is);
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "plugin.metadata-extractor.extract.error.io", item.getSourceLocationDisplay());
        }
    }
}
