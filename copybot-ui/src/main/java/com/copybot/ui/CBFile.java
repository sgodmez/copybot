package com.copybot.ui;

public class CBFile {
    private String name;
    private String folder;
    private Long size;


    public CBFile(String name, String folder, Long size) {
        this.name = name;
        this.folder = folder;
        this.size = size;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }
}
