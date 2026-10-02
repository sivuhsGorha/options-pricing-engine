package com.sbk.optionspricer.gateways;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Reads realistic historical market data from a CSV, converts it into raw 
 * binary network packets (simulating an exchange feed), and streams it into the Decoder.
 */
public class HistoricalReplayEngine {
    
    private final EobiDecoder decoder;
    private final String csvPath;

    public HistoricalReplayEngine(EobiDecoder decoder, String csvPath) {
        this.decoder = decoder;
        this.csvPath = csvPath;
    }

    /**
     * Starts the replay engine.
     */
    public void start() throws IOException {
        System.out.println("Starting Historical Replay Engine from: " + csvPath);
        
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath))) {
            String line = br.readLine(); // skip header
            
            // Reusable buffer to prevent GC allocations while simulating the network
            byte[] networkPacket = new byte[37];
            ByteBuffer buffer = ByteBuffer.wrap(networkPacket).order(ByteOrder.LITTLE_ENDIAN);
            
            long messageCount = 0;
            
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length < 8) continue;
                
                long timestamp = Long.parseLong(parts[0]);
                int instrumentId = Integer.parseInt(parts[1]);
                // parts[2] is 'CALL' or 'PUT', ignoring for now as ID uniquely identifies it
                // parts[3] is strike
                
                double bidPrice = Double.parseDouble(parts[4]);
                int bidSize = Integer.parseInt(parts[5]);
                
                double askPrice = Double.parseDouble(parts[6]);
                int askSize = Integer.parseInt(parts[7]);
                
                // --- Convert data back into raw exchange binary ---
                buffer.clear();
                buffer.put((byte) 1); // MsgType = 1 (OrderBook Snapshot)
                buffer.putLong(timestamp);
                buffer.putInt(instrumentId);
                buffer.putInt(bidSize);
                buffer.putLong((long)(bidPrice * 10000)); // Scale to integer for network transmission
                buffer.putInt(askSize);
                buffer.putLong((long)(askPrice * 10000));
                
                // Fire the raw packet into the decoder
                decoder.onMessage(networkPacket);
                messageCount++;
            }
            
            System.out.printf(java.util.Locale.ROOT, "Replay finished. Streamed %,d binary messages into the gateway.%n", messageCount);
        }
    }
}
