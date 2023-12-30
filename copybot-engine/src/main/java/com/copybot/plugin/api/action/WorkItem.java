package com.copybot.plugin.api.action;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

public class WorkItem {

    // TODO : add history with timing ?
    private String sourceLocationDisplay;
    private String nameDisplay;
    private URL sourceLocationUrl;
    private Supplier<InputStream> inputStreamSupplier;
    private Path sourceLocationPath;
    private Path tempLocation;
    private boolean isLocal;
    private boolean isDeleteAfterCompletion = false;
    private boolean isDeleted = false;
    private boolean isSourceReaded; // when true, trigger next read (TODO : wrap input stream to catch close ?)
    private final WorkItemMetadata metadatas = new WorkItemMetadata();


    public WorkItem(URL sourceLocationUrl, Supplier<InputStream> inputStreamSupplier) {
        this.sourceLocationUrl = sourceLocationUrl;
        this.inputStreamSupplier = inputStreamSupplier;
        this.sourceLocationDisplay = sourceLocationUrl.toString();
        isLocal = false;
    }

    public WorkItem(Path path) throws IOException {
        this.sourceLocationPath = path;
        this.sourceLocationDisplay = path.getParent().toString();
        this.nameDisplay = path.getFileName().toString();
        isLocal = true; // TODO : => ajouter l'info si local/file ou pas en metadata ?
    }

    public String getSourceLocationDisplay() {
        return sourceLocationDisplay;
    }

    public String getNameDisplay() {
        return nameDisplay;
    }

    public InputStream openInputStream() throws IOException {
        if (inputStreamSupplier != null) {
            return inputStreamSupplier.get();
        }
        return Files.newInputStream(sourceLocationPath);
    }


    public boolean isSourceReaded() {
        return isSourceReaded;
    }

    public WorkItemMetadata getMetadatas() {
        return metadatas;
    }

    public boolean isLocal() {
        return isLocal;
    }

    public boolean isTempFile() {
        return tempLocation != null;
    }

    public Path getLocalLocation() {
        return tempLocation == null ? sourceLocationPath : tempLocation;
    }

    public boolean isDeleteAfterCompletion() {
        return isDeleteAfterCompletion;
    }

    public boolean isDeleted() {
        return isDeleted;
    }

    public void setDeleted(boolean deleted) {
        isDeleted = deleted;
    }
}
