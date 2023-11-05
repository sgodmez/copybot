package com.copybot.plugin.embedded.actions;

import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IInAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.exception.ActionErrorException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

public class ReadFiles extends AbstractActionWithConfig<ReadFileConfig> implements IInAction {

    @Override
    protected Class<ReadFileConfig> getConfigClass() {
        return ReadFileConfig.class;
    }


    @Override
    public void listFiles(Consumer<WorkItem> workItemConsumer) throws ActionErrorException {
        String path = getConfig().path();
        try {
            Files.find(Path.of(path),
                            Integer.MAX_VALUE,
                            (filePath, fileAttr) -> fileAttr.isRegularFile())
                    .forEach(p -> {
                        /*
                        try {
                            Thread.currentThread().sleep(30);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                         */
                        WorkItem wi;
                        try {
                            wi = new WorkItem(p.getFileName().toString(), p.getParent().toString(), Files.size(p));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                        workItemConsumer.accept(wi);
                    });
        } catch (IOException | UncheckedIOException e) {
            throw new ActionErrorException(e);
        }
    }
}
