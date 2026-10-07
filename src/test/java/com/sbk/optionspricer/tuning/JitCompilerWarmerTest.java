package com.sbk.optionspricer.tuning;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JitCompilerWarmerTest {

    @Test
    void warmUpExercisesThePricersAndFinishesQuickly() {
        long millis = JitCompilerWarmer.warmUp();

        // The checksum depends on prices, Greeks, implied vols, the CDF, the PDE and the tick path, so a warm-up that
        // only touched tick getters (as the old one did) would leave it at about the tick midpoints alone.
        double checksum = JitCompilerWarmer.lastChecksum();
        assertTrue(Double.isFinite(checksum), "checksum " + checksum);
        assertTrue(checksum > 1_000_000.0, "pricing work must be reflected in the checksum: " + checksum);
        assertTrue(millis >= 0 && millis < 20_000, "warm-up took " + millis + "ms");
    }
}
