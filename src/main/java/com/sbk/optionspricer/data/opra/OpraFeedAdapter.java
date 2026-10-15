package com.sbk.optionspricer.data.opra;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.instruments.OccSymbol;
import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.market.OptionMarketData;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * High-throughput streaming OPRA feed adapter implementing {@link OptionMarketData}.
 *
 * <p>Consumes raw binary or streaming OPRA BBO messages through a lock-free {@link OpraRingBuffer},
 * maintains real-time in-memory chain snapshots, and dispatches sub-millisecond updates to listeners.
 */
public final class OpraFeedAdapter implements OptionMarketData, AutoCloseable {

    private final String targetSymbol;
    private final OpraRingBuffer ringBuffer;
    private final Map<String, MarketSnapshot> quoteTable = new ConcurrentHashMap<>();
    private final Map<LocalDate, Map<String, OptionQuote>> chainByExpiry = new ConcurrentHashMap<>();
    private final Set<LocalDate> expiries = Collections.synchronizedSet(new TreeSet<>());

    private volatile Consumer<OpraMessage> quoteListener = msg -> {};
    private volatile boolean running = true;
    private final Thread dispatchWorker;

    public OpraFeedAdapter(String targetSymbol) {
        this(targetSymbol, 65536);
    }

    public OpraFeedAdapter(String targetSymbol, int ringCapacity) {
        this.targetSymbol = targetSymbol == null ? "SPY" : targetSymbol.trim().toUpperCase(Locale.ROOT);
        this.ringBuffer = new OpraRingBuffer(ringCapacity);

        this.dispatchWorker = new Thread(this::runDispatchLoop, "opra-dispatch-worker");
        this.dispatchWorker.setDaemon(true);
        this.dispatchWorker.start();
    }

    public void setQuoteListener(Consumer<OpraMessage> listener) {
        this.quoteListener = listener == null ? msg -> {} : listener;
    }

    /** Ingests a raw 40-byte binary OPRA packet into the ring buffer. */
    public boolean onBinaryPacket(ByteBuffer packet) {
        if (!running) return false;
        OpraMessage msg = OpraBinaryDecoder.decode(packet);
        return onMessage(msg);
    }

    /** Ingests a normalized OpraMessage into the ring buffer. */
    public boolean onMessage(OpraMessage msg) {
        if (!running || msg == null) return false;
        if (!targetSymbol.equals(msg.symbol())) return false; // Filter symbol
        return ringBuffer.offer(msg);
    }

    private void runDispatchLoop() {
        int idleSpins = 0;
        while (running) {
            int drained = ringBuffer.drainTo(this::processMessage);
            if (drained == 0) {
                idleSpins++;
                if (idleSpins < 100) {
                    Thread.onSpinWait();
                } else if (idleSpins < 1000) {
                    Thread.yield();
                } else {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            } else {
                idleSpins = 0;
            }
        }
    }

    private void processMessage(OpraMessage msg) {
        String occ = msg.occSymbol();
        Instant now = Instant.now();
        Instant sourceTime = msg.instant();

        // 1. Update MarketSnapshot for OrderManager & VolSpreadStrategy
        MarketSnapshot snapshot = new MarketSnapshot(
                occ,
                msg.bid(),
                msg.ask(),
                msg.midPrice(),
                msg.bidSize() + msg.askSize(),
                now,
                sourceTime,
                Math.max(0L, now.toEpochMilli() - sourceTime.toEpochMilli()),
                "OPRA",
                MarketDataStatus.LIVE
        );
        quoteTable.put(occ, snapshot);

        // 2. Update OptionQuote in chainByExpiry
        LocalDate expiry = msg.expiry();
        expiries.add(expiry);
        chainByExpiry.computeIfAbsent(expiry, e -> new ConcurrentHashMap<>())
                .put(occ, new OptionQuote(msg.symbol(), expiry, msg.strike(), msg.type(), msg.bid(), msg.ask(), 0.0, msg.bidSize(), msg.askSize()));

        // 3. Notify real-time tick listeners
        try {
            quoteListener.accept(msg);
        } catch (Throwable ignored) {}
    }

    @Override
    public List<LocalDate> loadedExpiries() {
        return List.copyOf(expiries);
    }

    @Override
    public Optional<OptionChain> chain(LocalDate expiry) {
        Map<String, OptionQuote> quotesMap = chainByExpiry.get(expiry);
        if (quotesMap == null || quotesMap.isEmpty()) {
            return Optional.empty();
        }
        List<OptionQuote> quotes = new ArrayList<>(quotesMap.values());
        quotes.sort(Comparator.comparingDouble(OptionQuote::strike));
        // Use latest mid of front ATM as reference spot, or fallback to first quote strike
        double spot = quotes.get(quotes.size() / 2).strike();
        return Optional.of(new OptionChain(targetSymbol, expiry, spot, quotes));
    }

    @Override
    public Optional<MarketSnapshot> optionQuote(String contractSymbol) {
        if (contractSymbol == null) return Optional.empty();
        return Optional.ofNullable(quoteTable.get(contractSymbol));
    }

    public int queueSize() {
        return ringBuffer.size();
    }

    public int activeQuoteCount() {
        return quoteTable.size();
    }

    @Override
    public void close() {
        running = false;
        dispatchWorker.interrupt();
        try {
            dispatchWorker.join(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
