package com.sbk.optionspricer.core;

import com.sbk.optionspricer.web.MmapStateReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MmapStateTest {

    private static final String TEST_FILE = "target/test_mmap_state.dat";

    @BeforeEach
    void setup() {
        System.setProperty("MMAP_STATE_FILE", TEST_FILE);
        // Delete if exists
        new File(TEST_FILE).delete();
    }

    @AfterEach
    void teardown() {
        System.clearProperty("MMAP_STATE_FILE");
        new File(TEST_FILE).delete();
    }

    @Test
    void testMmapPublishAndRead() throws Exception {
        MmapStatePublisher publisher = new MmapStatePublisher();
        MmapStateReader reader = new MmapStateReader();

        publisher.publishRiskState(1.5, 2.5, 3.5, 4.5);
        
        // Read it back
        MmapStateReader.RiskState state = reader.readState();
        assertEquals(1.5, state.netDelta);
        assertEquals(2.5, state.netGamma);
        assertEquals(3.5, state.netVega);
        assertEquals(4.5, state.spanMargin);

        // Check if secure
        File f = new File(TEST_FILE);
        assertTrue(f.exists());
        
        publisher.close();
        reader.close();
    }

    @Test
    void testRepeatRunsDoNotLockFile() throws Exception {
        for (int i = 0; i < 3; i++) {
            MmapStatePublisher publisher = new MmapStatePublisher();
            MmapStateReader reader = new MmapStateReader();
            
            publisher.publishRiskState(1.0, 1.0, 1.0, 1.0);
            MmapStateReader.RiskState state = reader.readState();
            assertEquals(1.0, state.netDelta);
            
            publisher.close();
            reader.close();
            
            // Delete should succeed on Windows if properly unmapped and closed
            File f = new File(TEST_FILE);
            assertTrue(f.delete(), "Should be able to delete the file between runs if unmapped");
        }
    }
}
