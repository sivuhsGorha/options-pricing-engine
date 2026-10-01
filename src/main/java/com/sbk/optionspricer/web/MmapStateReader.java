package com.sbk.optionspricer.web;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.lang.invoke.VarHandle;
import com.sbk.optionspricer.core.MmapSecurityUtils;

/**
 * Reads the core risk state from the memory-mapped file for Zero-GC Inter-Process Communication (IPC).
 * This runs in the Web/REST API JVM (or thread) and isolates JSON garbage from the core engine.
 */
public class MmapStateReader {
    private static final long FILE_SIZE = 32;
    private static final VarHandle VH_DOUBLE = ValueLayout.JAVA_DOUBLE.varHandle();

    private final MemorySegment mappedSegment;
    private final Arena arena;

    private String getFilePath() {
        return System.getProperty("MMAP_STATE_FILE") != null ? 
            System.getProperty("MMAP_STATE_FILE") : 
            System.getenv("MMAP_STATE_FILE") != null ? 
            System.getenv("MMAP_STATE_FILE") : "data/quant_engine_state.dat";
    }

    public MmapStateReader() {
        this.arena = Arena.ofShared();
        try {
            File file = new File(getFilePath());
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            // Create initial state file if absent
            if (!file.exists() || file.length() < FILE_SIZE) {
                if (!file.exists()) {
                    file.createNewFile();
                }
                MmapSecurityUtils.secureMmapFile(file.toPath());
                try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                    raf.setLength(FILE_SIZE);
                    raf.writeDouble(-62500.0);  // netDelta
                    raf.writeDouble(-3500.0);   // netGamma
                    raf.writeDouble(-400000.0); // netVega
                    raf.writeDouble(14611250.0);// spanMargin
                }
            }

            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                try (FileChannel channel = raf.getChannel()) {
                    this.mappedSegment = channel.map(FileChannel.MapMode.READ_ONLY, 0, FILE_SIZE, arena);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to map state file for reading", e);
        }
    }

    public double getNetDelta() {
        return (double) VH_DOUBLE.getVolatile(mappedSegment, 0L);
    }

    public double getNetGamma() {
        return (double) VH_DOUBLE.getVolatile(mappedSegment, 8L);
    }

    public double getNetVega() {
        return (double) VH_DOUBLE.getVolatile(mappedSegment, 16L);
    }

    public double getSpanMargin() {
        return (double) VH_DOUBLE.getVolatile(mappedSegment, 24L);
    }

    public void close() {
        arena.close();
    }
}
