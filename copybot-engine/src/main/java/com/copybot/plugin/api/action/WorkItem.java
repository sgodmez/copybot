package com.copybot.plugin.api.action;

import com.copybot.engine.resources.DiskTraffic;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

public class WorkItem {

    // TODO : add history with timing ?
    private String sourceLocationDisplay;
    private String nameDisplay;
    private URL sourceLocationUrl;
    private Supplier<InputStream> inputStreamSupplier;
    private Path sourceLocationPath;
    private boolean isLocal;
    private boolean isDeleted = false;
    private String outPattern; // the item's own out pattern (spec own-out-pattern §1), null: the pipeline's
    /** The local copy the content is read from (or made from), null: the source itself. */
    private Path contentPath;
    private final WorkItemMetadata metadatas = new WorkItemMetadata();


    private WorkItem() {
    }

    /**
     * A copy for a dry run (spec pattern-helper §4): same source, its own metadata tables (the values, immutable,
     * are shared), so that changing the copy never changes this item.
     */
    public WorkItem copyForDryRun() {
        WorkItem copy = new WorkItem();
        copy.sourceLocationDisplay = sourceLocationDisplay;
        copy.nameDisplay = nameDisplay;
        copy.sourceLocationUrl = sourceLocationUrl;
        copy.inputStreamSupplier = inputStreamSupplier;
        copy.sourceLocationPath = sourceLocationPath;
        copy.isLocal = isLocal;
        copy.isDeleted = isDeleted;
        copy.outPattern = outPattern;
        copy.contentPath = contentPath;
        copy.metadatas.raw().putAll(metadatas.raw());
        copy.metadatas.display().putAll(metadatas.display());
        return copy;
    }

    /**
     * A copy of this item (as {@link #copyForDryRun()}) whose content is {@code content}, of {@code size} bytes: for a
     * process step that rewrites the file. The name and source are kept; this item is unchanged.
     * <p>
     * The copy may be kept until the end of the plan (with every other item of it) and its content may be opened
     * several times (the write, its comparison with an existing target, its verification): {@code content} must be
     * cheap to keep and to open again, and must not hold large buffers (a whole file in memory, per item, ends in an
     * {@link OutOfMemoryError} on a big card). Regenerate the content at each open, e.g. from this item's content,
     * and report a failure to do so as an {@link IOException} of the returned stream.
     */
    public WorkItem withContent(Supplier<InputStream> content, long size) {
        WorkItem copy = copyForDryRun();
        copy.inputStreamSupplier = content;
        copy.metadatas.setSize(size);
        return copy;
    }

    /**
     * A copy of this item (as {@link #copyForDryRun()}) whose content is read from {@code file}, a local copy of the
     * same content: for a step that buffers the file on a faster disk. The source is kept (its name, its location,
     * the out step deleting it); the steps after it hold the disk of {@code file}, no longer the source's. The file
     * is the step's own: it deletes it in {@link IAction#afterItem}, once the item is finished.
     */
    public WorkItem withContentFile(Path file) {
        WorkItem copy = copyForDryRun();
        copy.inputStreamSupplier = null;
        copy.contentPath = file;
        return copy;
    }

    /**
     * The local file the content is read from, whose disk the steps hold: the content file
     * ({@link #withContentFile}), else the local source; null for an item read from a URL.
     */
    public Path getContentLocation() {
        return contentPath != null ? contentPath : sourceLocationPath;
    }

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
        // counted on its disk: the gauges of a plan show the real throughput of each disk
        Path content = getContentLocation();
        return DiskTraffic.global().countReads(Files.newInputStream(content), content);
    }


    public WorkItemMetadata getMetadatas() {
        return metadatas;
    }

    public boolean isLocal() {
        return isLocal;
    }

    /** The local file of the item, null for an item read from a URL. */
    public Path getLocalLocation() {
        return sourceLocationPath;
    }

    /** The own out pattern of the item, empty: the out step's default applies (spec own-out-pattern §1). */
    public Optional<String> getOutPattern() {
        return Optional.ofNullable(outPattern);
    }

    /** @param pattern the own out pattern; null or blank: none */
    public void setOutPattern(String pattern) {
        this.outPattern = pattern == null || pattern.isBlank() ? null : pattern;
    }

    public boolean isDeleted() {
        return isDeleted;
    }

    public void setDeleted(boolean deleted) {
        isDeleted = deleted;
    }
}
