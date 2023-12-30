package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkItemMetadata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.Consumer;

public class FileReadAction extends AbstractActionWithConfig<FileReadConfig> implements IInAction {

    @Override
    protected Class<FileReadConfig> getConfigClass() {
        return FileReadConfig.class;
    }


    @Override
    public void listFiles(Consumer<WorkItem> workItemConsumer) {
        String path = getConfig().path();
        try {
            Files.find(Path.of(path),
                            Integer.MAX_VALUE,
                            (filePath, fileAttr) -> fileAttr.isRegularFile())
                    .forEach(p -> {

                        try {
                            Thread.currentThread().sleep(5);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }

                        WorkItem wi;
                        try {
                            wi = new WorkItem(p);
                            extractMetadata(p, wi.getMetadatas());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        workItemConsumer.accept(wi);
                    });
        } catch (IOException | UncheckedIOException e) {
            throw CopybotException.ofResource(e, "plugin.embedded.file.read.error.io", e.getLocalizedMessage());
        }
    }


    private void extractMetadata(Path path, WorkItemMetadata metadatas) throws IOException {
        // file metadatas
        metadatas.display().put("name", path.getFileName().toString());

        BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);
        metadatas.setSize(attr.size());
        metadatas.setTime("creation", attr.creationTime().toInstant());
        metadatas.setTime("lastModified", attr.lastModifiedTime().toInstant());
        metadatas.setTime("lastAccess", attr.lastAccessTime().toInstant());
    }
}
