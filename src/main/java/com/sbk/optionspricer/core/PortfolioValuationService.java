package com.sbk.optionspricer.core;

import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.TimeConventions;
import com.sbk.optionspricer.VolatilitySurfaceCalibrator;
import com.sbk.optionspricer.execution.PositionTracker;
import com.sbk.optionspricer.execution.Valuation;
import com.sbk.optionspricer.execution.ValuationSource;
import com.sbk.optionspricer.instruments.OccSymbol;
import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.market.OptionMarketData;
import com.sbk.optionspricer.risk.PortfolioPosition;
import com.sbk.optionspricer.volatility.SurfaceFitter;
import com.sbk.optionspricer.volatility.VolatilitySurfaceSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Marks the book to market on a schedule and pushes each option's Greeks into its position, so the risk
 * numbers the engine publishes come from a pricer rather than from a linear-delta placeholder.
 *
 * <p>Per position: an option is marked at the quote mid when a two-sided quote exists (otherwise at the
 * Black-Scholes price), its volatility comes from the fitted SVI slice at its strike and expiry (falling back
 * to the implied volatility of the quote mid, then to the feed's figure), and its Greeks are Black-Scholes at
 * that volatility. A stock is marked at spot with delta one. An expired contract is valued at intrinsic and
 * flagged. Every number carries its source, and anything that cannot be computed is reported as such, not as
 * zero.
 */
public final class PortfolioValuationService implements ValuationSource, AutoCloseable {

    /** A held contract is matched to a fitted slice when their expiries are within this many days. */
    static final double SLICE_MATCH_DAYS = 3.0;

    private final PositionTracker tracker;
    private final VolatilitySurfaceSource surfaces;
    private final OptionMarketData chains;
    private final Supplier<MarketSnapshot> spotSupplier;
    private final double riskFreeRate;
    private final double dividendYield;
    private final Duration interval;
    private final Clock clock;

    private volatile Valuation latest;
    private volatile String status = "valuation has not run yet";
    private Thread worker;
    private volatile boolean closed;

    public PortfolioValuationService(PositionTracker tracker, VolatilitySurfaceSource surfaces, OptionMarketData chains,
                                     Supplier<MarketSnapshot> spotSupplier, double riskFreeRate, double dividendYield,
                                     Duration interval, Clock clock) {
        if (tracker == null || interval == null || clock == null) {
            throw new IllegalArgumentException("tracker, interval and clock must not be null");
        }
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.tracker = tracker;
        this.surfaces = surfaces;
        this.chains = chains;
        this.spotSupplier = spotSupplier == null ? () -> null : spotSupplier;
        this.riskFreeRate = riskFreeRate;
        this.dividendYield = dividendYield;
        this.interval = interval;
        this.clock = clock;
    }

    /** Revalues now, on the calling thread. Returns empty (and sets the status) when there is no usable spot. */
    public synchronized Optional<Valuation> revalue() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<String> warnings = new ArrayList<>();

        double spot = Double.NaN;
        String spotSource = null;
        MarketSnapshot spotQuote = null;
        try {
            spotQuote = spotSupplier.get();
        } catch (RuntimeException e) {
            warnings.add("spot feed failed: " + e.getMessage());
        }
        if (spotQuote != null && spotQuote.status() != MarketDataStatus.UNAVAILABLE && Double.isFinite(spotQuote.last()) && spotQuote.last() > 0.0) {
            spot = spotQuote.last();
            spotSource = spotQuote.source() + "/" + spotQuote.status().name();
        } else if (surfaces != null && surfaces.latest().isPresent() && surfaces.latest().get().spot() > 0.0) {
            spot = surfaces.latest().get().spot();
            spotSource = "CHAIN/" + surfaces.latest().get().source();
            warnings.add("no live spot; marks use the option chain's underlying price");
        }
        if (!(spot > 0.0)) {
            status = "no spot price: cannot mark the book";
            return Optional.empty();
        }

        SurfaceFitter.Fit surface = surfaces == null ? null : surfaces.latest().map(s -> s.svi() != null ? s.svi() : s.ssvi()).orElse(null);
        List<Valuation.PositionValuation> rows = new ArrayList<>();
        double unrealized = 0.0, netDelta = 0.0, netGamma = 0.0, netVega = 0.0, netTheta = 0.0, netRho = 0.0;
        for (Map.Entry<String, PortfolioPosition> entry : tracker.getPositions().entrySet()) {
            PortfolioPosition position = entry.getValue();
            if (position.getQuantity() == 0) {
                continue;
            }
            final double spotNow = spot;
            Valuation.PositionValuation row = OccSymbol.parse(entry.getKey())
                    .map(contract -> valueOption(entry.getKey(), position, contract, spotNow, today, surface, warnings))
                    .orElseGet(() -> valueStock(entry.getKey(), position, spotNow));
            rows.add(row);
            double units = (double) position.getQuantity() * position.getMultiplier();
            if (Double.isFinite(row.unrealizedPnl())) unrealized += row.unrealizedPnl();
            if (Double.isFinite(row.delta())) {
                netDelta += units * row.delta();
                netGamma += units * row.gamma();
                netVega += units * row.vega();
                netTheta += units * row.theta();
                netRho += units * row.rho();
            }
        }
        Valuation valuation = new Valuation(now, spot, spotSource, List.copyOf(rows), unrealized, tracker.getRealizedPnl(),
                netDelta, netGamma, netVega, netTheta, netRho, List.copyOf(warnings));
        latest = valuation;
        status = "";
        return Optional.of(valuation);
    }

    private Valuation.PositionValuation valueStock(String symbol, PortfolioPosition position, double spot) {
        position.updateGreeks(1.0, 0.0, 0.0);
        double avg = tracker.getAverageCost(symbol);
        double pnl = Double.isFinite(avg) ? position.getQuantity() * (double) position.getMultiplier() * (spot - avg) : Double.NaN;
        return new Valuation.PositionValuation(symbol, "STOCK", position.getQuantity(), position.getMultiplier(), spot, "SPOT",
                avg, pnl, Double.NaN, "NONE", 1.0, 0.0, 0.0, 0.0, 0.0, false, null);
    }

    private Valuation.PositionValuation valueOption(String symbol, PortfolioPosition position, OccSymbol.Parsed contract, double spot,
                                                    LocalDate today, SurfaceFitter.Fit surface, List<String> warnings) {
        double t = TimeConventions.yearFraction(today, contract.expiry());
        double avg = tracker.getAverageCost(symbol);
        double units = (double) position.getQuantity() * position.getMultiplier();
        boolean call = contract.type() == OptionType.CALL;

        if (!(t > 0.0)) {
            double intrinsic = call ? Math.max(spot - contract.strike(), 0.0) : Math.max(contract.strike() - spot, 0.0);
            double delta = call ? (spot > contract.strike() ? 1.0 : 0.0) : (spot < contract.strike() ? -1.0 : 0.0);
            position.updateGreeks(delta, 0.0, 0.0);
            return new Valuation.PositionValuation(symbol, "OPTION", position.getQuantity(), position.getMultiplier(), intrinsic, "INTRINSIC",
                    avg, Double.isFinite(avg) ? units * (intrinsic - avg) : Double.NaN, Double.NaN, "EXPIRED",
                    delta, 0.0, 0.0, 0.0, 0.0, true, "expired " + contract.expiry() + "; settle against the underlying");
        }

        Optional<MarketSnapshot> quote = chains == null ? Optional.empty() : chains.optionQuote(symbol);
        double vol = Double.NaN;
        String volSource = "NONE";
        OptionalDouble fromSurface = surfaceVol(surface, t, contract.strike());
        if (fromSurface.isPresent()) {
            vol = fromSurface.getAsDouble();
            volSource = surface.model();
        } else if (quote.isPresent() && quote.get().hasBook()) {
            OptionalDouble iv = VolatilitySurfaceCalibrator.impliedVolatility(spot, contract.strike(), t, riskFreeRate, dividendYield, contract.type(), quote.get().last());
            if (iv.isPresent()) {
                vol = iv.getAsDouble();
                volSource = "MARKET_IV";
            }
        }
        if (!Double.isFinite(vol) && chains != null) {
            double feedIv = chains.chain(contract.expiry()).flatMap(c -> c.quotes().stream()
                    .filter(q -> q.type() == contract.type() && Math.abs(q.strike() - contract.strike()) < 1e-9).findFirst())
                    .map(q -> q.impliedVolatility()).orElse(0.0);
            if (feedIv > 0.01) {
                vol = feedIv;
                volSource = "FEED_IV";
            }
        }

        double mark;
        String markSource;
        if (quote.isPresent() && quote.get().hasBook()) {
            mark = quote.get().last();
            markSource = "MID";
        } else if (Double.isFinite(vol)) {
            mark = BlackScholesPricer.price(contract.type(), spot, contract.strike(), t, riskFreeRate, vol, dividendYield);
            markSource = "MODEL";
        } else {
            mark = Double.NaN;
            markSource = "NONE";
            warnings.add(symbol + ": no quote and no volatility; not marked");
        }

        double delta = Double.NaN, gamma = Double.NaN, vega = Double.NaN, theta = Double.NaN, rho = Double.NaN;
        if (Double.isFinite(vol)) {
            double[] g = new double[5];
            BlackScholesPricer.greeks(contract.type(), spot, contract.strike(), t, riskFreeRate, vol, dividendYield, g);
            delta = g[0];
            gamma = g[1];
            vega = g[2];
            theta = g[3];
            rho = g[4];
            position.updateGreeks(delta, gamma, vega);
        } else {
            warnings.add(symbol + ": Greeks unavailable (no volatility source)");
        }
        double pnl = Double.isFinite(mark) && Double.isFinite(avg) ? units * (mark - avg) : Double.NaN;
        return new Valuation.PositionValuation(symbol, "OPTION", position.getQuantity(), position.getMultiplier(), mark, markSource,
                avg, pnl, vol, volSource, delta, gamma, vega, theta, rho, false, null);
    }

    /** Volatility from the fitted slice whose expiry matches the contract's, interpolated in strike. */
    static OptionalDouble surfaceVol(SurfaceFitter.Fit fit, double t, double strike) {
        return SurfaceFitter.volAt(fit, t, strike, SLICE_MATCH_DAYS);
    }

    public synchronized void start() {
        if (worker != null) {
            return;
        }
        worker = new Thread(() -> {
            while (!closed) {
                try {
                    revalue();
                } catch (Throwable t) {
                    status = "valuation failed: " + t.getMessage();
                }
                try {
                    Thread.sleep(interval.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "portfolio-valuation");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void close() {
        closed = true;
        Thread w;
        synchronized (this) {
            w = worker;
        }
        if (w != null) {
            w.interrupt();
        }
    }

    @Override
    public Optional<Valuation> latestValuation() {
        return Optional.ofNullable(latest);
    }

    @Override
    public String valuationStatus() {
        return status;
    }
}
