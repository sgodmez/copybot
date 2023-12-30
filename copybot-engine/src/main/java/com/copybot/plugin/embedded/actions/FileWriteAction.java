package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.action.AbstractActionWithConfig;
import com.copybot.plugin.api.action.IOutAction;
import com.copybot.plugin.api.action.WorkItem;
import com.copybot.plugin.api.action.WorkStatus;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

public class FileWriteAction extends AbstractActionWithConfig<FileWriteConfig> implements IOutAction {
    private static final Pattern PARAM_PATTERN = Pattern.compile("\\{(.*?)\\}");

    private static final int BUFFER_SIZE = 8192;

    @Override
    protected Class<FileWriteConfig> getConfigClass() {
        return FileWriteConfig.class;
    }

    @Override
    public void writeItem(WorkItem workItem) {
        String outFileName = resolveFileName(workItem);
        Path outPath = Path.of(outFileName);

        try {
            doWrite(workItem, outPath);
        } catch (IOException e) {
            throw CopybotException.ofResource(e, "plugin.embedded.file.write.error.io", outPath);
        }
    }

    private String resolveFileName(WorkItem workItem) {
        String outPattern = getConfig().outPattern();

        return PARAM_PATTERN.matcher(outPattern)
                .replaceAll(m -> workItem.getMetadatas().display().getOrDefault(m.group(1), m.group(0)).toString());
    }

    private void doWrite(WorkItem workItem, Path outPath) throws IOException {
        updateStatus(new WorkStatus("Copy file " + workItem.getSourceLocationDisplay(), -1));

        if (canBeMoved(workItem, outPath)) {
            if (getConfig().overwrite()) {
                Files.move(workItem.getLocalLocation(), outPath, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(workItem.getLocalLocation(), outPath);
            }
            workItem.setDeleted(!workItem.isTempFile());
        } else {
            // TODO : test speed of different methods
            // https://baptiste-wicht.com/posts/2010/08/file-copy-in-java-benchmark.html
            // https://baptiste-wicht.com/wp-content/uploads/2010/08/FileCopyBenchmark2.java
            // https://github.com/wichtounet/java-benchmarks/blob/master/src/com/wicht/benchmarks/FileCopyBenchmark.java
            StandardOpenOption copyOption = getConfig().overwrite() ? StandardOpenOption.WRITE : StandardOpenOption.CREATE_NEW;

            try (InputStream is = workItem.openInputStream();
                 OutputStream os = Files.newOutputStream(outPath, copyOption);) {

                long transferred = 0;
                int percent = -1;
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = is.read(buffer, 0, BUFFER_SIZE)) >= 0) {
                    os.write(buffer, 0, read);
                    transferred += read;
                    Long size = workItem.getMetadatas().getSize();
                    if (size != null) {
                        percent = (int) (transferred / size * 100);
                        updatePercent(percent);
                    }
                }
            }
        }
    }

    private static boolean canBeMoved(WorkItem workItem, Path outPath) {
        // can be moved if either is a temp file or the original file with delete option enabled
        if ((workItem.isLocal() && workItem.isDeleteAfterCompletion()) || workItem.isTempFile()) {
            // can be moved if on the same disk
            return workItem.getLocalLocation().getFileSystem().provider() == outPath.getFileSystem().provider();
        }
        return false;
    }

}
