package com.copybot.engine;

/**
 * The phase is being stopped (cancel, interrupt): thrown by the listing's emitter through the plugin's listFiles,
 * and by {@link ItemRunner} when the fork of an item is rejected by the executor the phase shut down.
 */
final class PhaseStopped extends RuntimeException {
    PhaseStopped() {
        super("phase stopped", null, false, false);
    }
}
