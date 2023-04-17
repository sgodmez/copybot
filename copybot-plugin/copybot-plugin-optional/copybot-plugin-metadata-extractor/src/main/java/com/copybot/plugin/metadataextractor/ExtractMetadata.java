package com.copybot.plugin.metadataextractor;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class ExtractMetadata {
    public static Map<String, String> extractMetadata(InputStream fileIS) {
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
}
