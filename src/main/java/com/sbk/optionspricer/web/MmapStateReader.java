package com.sbk.optionspricer.web;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;

/**
 * Reads the core risk state from the memory-mapped file for Zero-GC Inter-Process Communication (IPC).
 * This runs in the Web/REST API JVM (or thread) and isolates JSON garbage from the core engine.
 */
public class MmapStateReader {
    private static final String FILE_PATH = "target/quant_engine_state.dat";
    private static final long FILE_SIZE = 32;

    private final MemorySegment mappedSegment;
    private final Arena arena;

    public MmapStateReader() {
        this.arena = Arena.ofShared();
        try {
            File file = new File(FILE_PATH);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            // Create initial state file if absent
            if (!file.exists() || file.length() < FILE_SIZE) {
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
        return mappedSegment.get(ValueLayout.JAVA_DOUBLE, 0);
    }

    public double getNetGamma() {
        return mappedSegment.get(ValueLayout.JAVA_DOUBLE, 8);
    }

    public double getNetVega() {
        return mappedSegment.get(ValueLayout.JAVA_DOUBLE, 16);
    }

    public double getSpanMargin() {
        return mappedSegment.get(ValueLayout.JAVA_DOUBLE, 24);
    }

    public void close() {
        arena.close();
    }
}
