package com.copybot.plugin.demo;

import com.copybot.plugin.MyService;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.Tag;
import com.google.auto.service.AutoService;

import java.io.File;
import java.io.IOException;
import java.util.Map;

@AutoService(MyService.class)
public class DummyService2 implements MyService {

    @Override
    public String getName() {
        return "dummy2-no-module";
    }

    @Override
    public void doManyThings(File file) {
        try {
            extractMetadata(file);
        } catch (ImageProcessingException e) {
            //throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Map<String, String> extractMetadata(File file) throws ImageProcessingException, IOException {
        Metadata metadata = ImageMetadataReader.readMetadata(file);

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
    }
}