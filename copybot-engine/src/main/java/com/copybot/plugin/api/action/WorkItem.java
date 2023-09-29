package com.copybot.plugin.api.action;

import com.copybot.utils.FileUtil;

import java.io.InputStream;
import java.net.URL;
import java.util.Map;
import java.util.function.Supplier;

public class WorkItem {
    private String name;
    private String sourceLocationDisplay;
    private URL sourceLocation;
    private URL finalDestLocation;
    private Supplier<InputStream> inputStreamSupplier;
    private boolean isSourceReaded; // when true, trigger next read (TODO : wrap input stream to catch close)
    private Long size;
    private InputStream destinationInputStream;
    private Map<String, Object> metadatas;


    public WorkItem(String name, String sourceLocationDisplay, Long size) {
        this.name = name;
        this.sourceLocationDisplay = sourceLocationDisplay;
        this.size = size;
        FileUtil.toAutoUnitSize(12, 2);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSourceLocationDisplay() {
        return sourceLocationDisplay;
    }

    public void setSourceLocationDisplay(String sourceLocationDisplay) {
        this.sourceLocationDisplay = sourceLocationDisplay;
    }

    public URL getSourceLocation() {
        return sourceLocation;
    }

    public void setSourceLocation(URL sourceLocation) {
        this.sourceLocation = sourceLocation;
    }

    public Supplier<InputStream> getInputStreamSupplier() {
        return inputStreamSupplier;
    }

    public void setInputStreamSupplier(Supplier<InputStream> inputStreamSupplier) {
        this.inputStreamSupplier = inputStreamSupplier;
    }

    public boolean isSourceReaded() {
        return isSourceReaded;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getSizeHR() {
        return FileUtil.toAutoUnitSize(getSize(), 2);
    }

}
