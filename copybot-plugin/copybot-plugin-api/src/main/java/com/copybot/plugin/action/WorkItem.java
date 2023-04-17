package com.copybot.plugin.action;

import java.io.InputStream;
import java.net.URL;
import java.util.function.Supplier;

public class WorkItem {
    private String name;
    private String sourceLocationDisplay;
    private URL sourceLocation;
    private Supplier<InputStream> inputStreamSupplier;
    private boolean isSourceReaded; // when true, trigger next read (TODO : wrap input stream to catch close)
    private Long size;


    public WorkItem(String name, String sourceLocationDisplay, Long size) {
        this.name = name;
        this.sourceLocationDisplay = sourceLocationDisplay;
        this.size = size;
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

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

}
