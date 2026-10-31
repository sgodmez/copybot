package com.copybot.plugin.exec.process;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ItemFilesTest {

    @Test
    public void aFileNameHasNoCharacterWindowsRefuses() {
        assertEquals("a_b_c_.jpg", ItemFiles.fileName("a/b\\c?.jpg"));
        assertEquals("photo.jpg", ItemFiles.fileName("photo.jpg"));
        assertEquals("item", ItemFiles.fileName(".."));
        assertEquals("item", ItemFiles.fileName("  "));
    }

    @Test
    public void theDotsAndSpacesWindowsDropsAtTheEndAreRemoved() {
        assertEquals("photo", ItemFiles.fileName("photo. . "));
        assertEquals("photo.jpg", ItemFiles.fileName("photo.jpg."));
    }

    @Test
    public void aDeviceNameOfWindowsIsPrefixed() {
        assertEquals("_CON", ItemFiles.fileName("CON"));
        assertEquals("_nul.mp4", ItemFiles.fileName("nul.mp4"));
        assertEquals("_com1.tar.gz", ItemFiles.fileName("com1.tar.gz"));
        assertEquals("_LPT9.jpg", ItemFiles.fileName("LPT9.jpg"));
        assertEquals("console.jpg", ItemFiles.fileName("console.jpg"));
        assertEquals("COM10.jpg", ItemFiles.fileName("COM10.jpg"));
    }
}
