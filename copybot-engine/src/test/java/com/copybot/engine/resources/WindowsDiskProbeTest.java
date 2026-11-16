package com.copybot.engine.resources;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WindowsDiskProbeTest {

    @Test
    public void aShareIsOneNameWhateverItsCaseOrTrailingBackslash() {
        assertEquals("\\\\nas\\photos\\", WindowsDiskProbe.share("\\\\NAS\\Photos"));
        assertEquals("\\\\nas\\photos\\", WindowsDiskProbe.share("\\\\nas\\photos\\"));
    }

    @Test
    public void otherVolumesAreKeptAsTheyAre() {
        assertEquals("D:\\", WindowsDiskProbe.share("D:\\"));
        assertEquals("C:\\mnt\\Photos\\", WindowsDiskProbe.share("C:\\mnt\\Photos\\"));
    }
}
