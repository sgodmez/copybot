package com.copybot.ui.model;

import com.copybot.engine.resources.DiskTraffic.Bytes;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class DiskRatesTest {

    private static final long SECOND = 1_000_000_000L;
    private static final String CARD = "disk:PhysicalDrive1";

    @Test
    public void oneSampleGivesNoRate() {
        DiskRates rates = new DiskRates();
        rates.sample(0, Map.of(CARD, new Bytes(1_000_000, 0)));

        assertEquals(Map.of(), rates.rates());
    }

    @Test
    public void theRateIsAveragedOverTheLastSeconds() {
        DiskRates rates = new DiskRates();
        rates.sample(0, Map.of(CARD, new Bytes(0, 0)));
        rates.sample(SECOND, Map.of(CARD, new Bytes(10_000_000, 0)));
        rates.sample(2 * SECOND, Map.of(CARD, new Bytes(30_000_000, 20_000_000)));

        assertEquals(Map.of(CARD, new DiskRates.Rate(15_000_000, 10_000_000)), rates.rates());
    }

    @Test
    public void theOldSamplesAreForgotten() {
        DiskRates rates = new DiskRates();
        rates.sample(0, Map.of(CARD, new Bytes(0, 0)));
        rates.sample(SECOND, Map.of(CARD, new Bytes(100_000_000, 0)));
        rates.sample(5 * SECOND, Map.of(CARD, new Bytes(100_000_000, 0)));
        rates.sample(6 * SECOND, Map.of(CARD, new Bytes(100_000_000, 0)));

        assertEquals(Map.of(CARD, new DiskRates.Rate(0, 0)), rates.rates(), "idle for the last seconds");
    }

    @Test
    public void samplesTooCloseGiveNoRateYet() {
        DiskRates rates = new DiskRates();
        rates.sample(0, Map.of(CARD, new Bytes(0, 0)));
        rates.sample(SECOND / 10, Map.of(CARD, new Bytes(1_000_000, 0)));

        assertEquals(Map.of(), rates.rates());
    }

    @Test
    public void aDiskFirstSeenStartedFromNothing() {
        DiskRates rates = new DiskRates();
        rates.sample(0, Map.of());
        rates.sample(2 * SECOND, Map.of(CARD, new Bytes(0, 4_000_000)));

        assertEquals(Map.of(CARD, new DiskRates.Rate(0, 2_000_000)), rates.rates());
    }
}
