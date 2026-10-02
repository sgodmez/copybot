package com.copybot.engine.sample;

import java.util.List;
import java.util.Optional;

/**
 * The result of a listing or an analysis of the sample (spec pattern-helper §3).
 *
 * @param items     the kept items, analysed and dry run (an item can produce several, or none)
 * @param listed    how many items the listing returned before it was cut
 * @param truncated the listing was cut by the cap or the timeout
 * @param notes     what happened to some items (filtered, unsupported, failed)
 * @param failure   a global failure: then there is no item
 */
public record Sample(List<SampleItem> items, int listed, boolean truncated, List<String> notes, Optional<String> failure) {

    public Sample {
        items = List.copyOf(items);
        notes = List.copyOf(notes);
    }

    public static Sample failed(String message) {
        return new Sample(List.of(), 0, false, List.of(), Optional.of(message));
    }
}
