package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.OptionChain;
import com.sbk.optionspricer.OptionQuote;
import com.sbk.optionspricer.OptionType;
import com.sbk.optionspricer.TimeConventions;
import com.sbk.optionspricer.VolatilitySurfaceCalibrator;
import com.sbk.optionspricer.instruments.OccSymbol;
import com.sbk.optionspricer.market.MarketDataStatus;
import com.sbk.optionspricer.market.MarketSnapshot;
import com.sbk.optionspricer.market.OptionMarketData;
import com.sbk.optionspricer.market.OptionQuoteSnapshots;
import com.sbk.optionspricer.volatility.SurfaceFitter;
import com.sbk.optionspricer.volatility.VolatilitySurfaceSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;

/**
 * Relative-value options strategy on the front-month at-the-money straddle.
 *
 * <p>Signal: the straddle's market implied volatility (inverted from the call and put mids) against the
 * fitted reference surface at the same strike and expiry. The reference is the global SSVI fit by default:
 * a per-slice SVI fit sits on the quotes it was fitted to, so it carries no information about them, while the
 * three-parameter global surface is a smoothed "fair" value the market deviates from. When the market trades
 * more than {@code volEdge} above the reference the straddle is sold; more than {@code volEdge} below, bought.
 * One straddle at a time; it is closed when the edge reverts through zero or the expiry comes within
 * {@code maxDaysToExpiry}. Net delta beyond {@code hedgeBand} is hedged with shares.
 *
 * <p>Every order goes through {@link OrderManager}'s gates like any other; a rejected leg is reported in the
 * decision and the next step flattens whatever is open. The quotes are 15-minute delayed, so "edge" includes
 * staleness: the band must exceed that noise, and the decision record says what was seen.
 */
public final class VolSpreadStrategy {

    public enum AllowedSide {
        BOTH,
        LONG_ONLY,
        SHORT_ONLY;

        public static AllowedSide parse(String text) {
            if (text == null || text.isBlank()) return BOTH;
            return switch (text.trim().toLowerCase(Locale.ROOT)) {
                case "long_only", "long", "buy_only" -> LONG_ONLY;
                case "short_only", "short", "sell_only" -> SHORT_ONLY;
                case "both" -> BOTH;
                // a typo must not quietly become the setting that allows short straddles
                default -> throw new IllegalArgumentException("strategy.side must be both, long_only or short_only (found " + text.trim() + ")");
            };
        }
    }

    /**
     * @param volEdge          vol points (as a fraction, 0.01 = one point) the market must deviate from the reference to open
     * @param hedgeBand        net delta (in underlying units) beyond which shares are traded to flatten it
     * @param maxDaysToExpiry  close an open straddle when its expiry is this close
     * @param minDaysToExpiry  do not open on an expiry closer than this
     * @param contracts        straddle size per leg
     * @param referenceModel   {@code SSVI}, {@code SVI}, {@code HAR}, {@code EWMA}, {@code REALIZED} or {@code VRP}
     * @param feePerContract   broker/exchange fee per contract in dollars (e.g. 0.65)
     * @param useMultiLeg      if true, transmits the straddle legs as a single atomic order
     * @param allowedSide      {@code BOTH}, {@code LONG_ONLY} or {@code SHORT_ONLY}
     * @param rmseMultiplier   multiple of reference surface RMSE required before trading (0.0 to disable)
     */
    public record Params(double volEdge, double hedgeBand, int maxDaysToExpiry, int minDaysToExpiry, int contracts,
                         String referenceModel, double feePerContract, boolean useMultiLeg, AllowedSide allowedSide,
                         double rmseMultiplier) {
        public Params(double volEdge, double hedgeBand, int maxDaysToExpiry, int minDaysToExpiry, int contracts, String referenceModel) {
            this(volEdge, hedgeBand, maxDaysToExpiry, minDaysToExpiry, contracts, referenceModel, 0.65, false, AllowedSide.BOTH, 0.0);
        }

        public Params(double volEdge, double hedgeBand, int maxDaysToExpiry, int minDaysToExpiry, int contracts, String referenceModel, double feePerContract) {
            this(volEdge, hedgeBand, maxDaysToExpiry, minDaysToExpiry, contracts, referenceModel, feePerContract, false, AllowedSide.BOTH, 0.0);
        }

        public Params(double volEdge, double hedgeBand, int maxDaysToExpiry, int minDaysToExpiry, int contracts, String referenceModel, double feePerContract, boolean useMultiLeg) {
            this(volEdge, hedgeBand, maxDaysToExpiry, minDaysToExpiry, contracts, referenceModel, feePerContract, useMultiLeg, AllowedSide.BOTH, 0.0);
        }

        public Params(double volEdge, double hedgeBand, int maxDaysToExpiry, int minDaysToExpiry, int contracts, String referenceModel, double feePerContract, boolean useMultiLeg, AllowedSide allowedSide) {
            this(volEdge, hedgeBand, maxDaysToExpiry, minDaysToExpiry, contracts, referenceModel, feePerContract, useMultiLeg, allowedSide, 0.0);
        }

        public Params {
            if (!(volEdge > 0.0) || !(hedgeBand >= 0.0) || maxDaysToExpiry < 0 || minDaysToExpiry < 0 || contracts < 1 || feePerContract < 0.0 || rmseMultiplier < 0.0) {
                throw new IllegalArgumentException("volEdge > 0, hedgeBand >= 0, day limits >= 0, contracts >= 1, feePerContract >= 0, rmseMultiplier >= 0");
            }
            if (minDaysToExpiry <= maxDaysToExpiry) {
                throw new IllegalArgumentException("minDaysToExpiry must exceed maxDaysToExpiry, or a straddle would be opened and closed at once");
            }
            referenceModel = referenceModel == null || referenceModel.isBlank() ? "SSVI" : referenceModel.trim().toUpperCase(Locale.ROOT);
            if (!referenceModel.equals("SSVI") && !referenceModel.equals("SVI") && !referenceModel.equals("HAR")
                    && !referenceModel.equals("EWMA") && !referenceModel.equals("REALIZED") && !referenceModel.equals("VRP")) {
                throw new IllegalArgumentException("referenceModel must be SSVI, SVI, HAR, EWMA, REALIZED or VRP");
            }
            allowedSide = allowedSide == null ? AllowedSide.BOTH : allowedSide;
        }

        /** True when the reference is a realized-vol forecast and not a fitted surface. */
        public boolean usesFairVolReference() {
            return !referenceModel.equals("SSVI") && !referenceModel.equals("SVI");
        }
    }

    /**
     * What one step saw and did. {@code edge} is market minus reference, as a vol fraction. {@code optionSpreadBps} is the
     * wider of the call's and the put's bid-ask spread in the units of the pre-trade liquidity gate (spread over mid, in
     * basis points), NaN when the step did not look at the straddle's quotes.
     */
    public record Decision(Instant at, String action, String reason, LocalDate expiry, double strike,
                           double marketIv, double referenceIv, double edge, int straddleSign, List<String> orders,
                           double spotAgeSeconds, double chainAgeSeconds, double optionQuoteAgeSeconds, double optionSpreadBps) {
        public Decision(Instant at, String action, String reason, LocalDate expiry, double strike,
                        double marketIv, double referenceIv, double edge, int straddleSign, List<String> orders) {
            this(at, action, reason, expiry, strike, marketIv, referenceIv, edge, straddleSign, orders,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        public Decision(Instant at, String action, String reason, LocalDate expiry, double strike,
                        double marketIv, double referenceIv, double edge, int straddleSign, List<String> orders,
                        double spotAgeSeconds, double chainAgeSeconds, double optionQuoteAgeSeconds) {
            this(at, action, reason, expiry, strike, marketIv, referenceIv, edge, straddleSign, orders,
                    spotAgeSeconds, chainAgeSeconds, optionQuoteAgeSeconds, Double.NaN);
        }

        public String summary() {
            String level = Double.isFinite(marketIv)
                    ? String.format(Locale.ROOT, " %s %.0f: market %.1f%% vs ref %.1f%% (edge %+.2f pts)", expiry, strike, 100 * marketIv, 100 * referenceIv, 100 * edge)
                    : "";
            return action + level + (reason == null || reason.isBlank() ? "" : " - " + reason);
        }
    }

    private final OrderManager orderManager;
    private volatile OptionMarketData chains;
    private final VolatilitySurfaceSource surfaces;
    private final com.sbk.optionspricer.volatility.FairVolProvider fairVolProvider;
    private final ValuationSource valuation;
    private final Supplier<MarketSnapshot> spotSupplier;
    private final PositionTracker tracker;
    private final String symbol;
    private final double riskFreeRate;
    private final double dividendYield;
    private final Params params;
    private volatile Clock clock;

    /** The chain's own underlying price may differ from the live spot by at most this fraction before the chain is distrusted. */
    static final double MAX_SPOT_DISAGREEMENT = 0.10;
    /** Lets tests drive the strategy with generated chains; the live wiring leaves it false. */
    private volatile boolean allowSimulated;

    void allowSimulatedChains(boolean allow) {
        this.allowSimulated = allow;
    }

    private String openCall;
    private String openPut;
    private LocalDate openExpiry;
    private double openStrike = Double.NaN;
    private volatile Decision lastDecision;

    public VolSpreadStrategy(OrderManager orderManager, OptionMarketData chains, VolatilitySurfaceSource surfaces, ValuationSource valuation,
                             Supplier<MarketSnapshot> spotSupplier, PositionTracker tracker, String symbol,
                             double riskFreeRate, double dividendYield, Params params, Clock clock) {
        this(orderManager, chains, surfaces, null, valuation, spotSupplier, tracker, symbol, riskFreeRate, dividendYield, params, clock);
    }

    public VolSpreadStrategy(OrderManager orderManager, OptionMarketData chains, VolatilitySurfaceSource surfaces,
                             com.sbk.optionspricer.volatility.FairVolProvider fairVolProvider, ValuationSource valuation,
                             Supplier<MarketSnapshot> spotSupplier, PositionTracker tracker, String symbol,
                             double riskFreeRate, double dividendYield, Params params, Clock clock) {
        if (orderManager == null || chains == null || surfaces == null || spotSupplier == null || tracker == null || symbol == null || params == null || clock == null) {
            throw new IllegalArgumentException("all dependencies except valuation must be provided");
        }
        if (params.usesFairVolReference() && fairVolProvider == null) {
            // Without this the strategy would price against the SSVI surface while its decisions said "HAR".
            throw new IllegalArgumentException("reference model " + params.referenceModel() + " needs a fair-vol provider");
        }
        this.orderManager = orderManager;
        this.chains = chains;
        this.surfaces = surfaces;
        this.fairVolProvider = fairVolProvider;
        this.valuation = valuation;
        this.spotSupplier = spotSupplier;
        this.tracker = tracker;
        this.symbol = symbol.trim().toUpperCase(Locale.ROOT);
        this.riskFreeRate = riskFreeRate;
        this.dividendYield = dividendYield;
        this.params = params;
        this.clock = clock;
    }

    public VolSpreadStrategy(OrderManager orderManager, OptionMarketData chains, VolatilitySurfaceSource surfaces,
                             com.sbk.optionspricer.volatility.FairVolProvider fairVolProvider, ValuationSource valuation,
                             Supplier<MarketSnapshot> spotSupplier, PositionTracker tracker, String symbol,
                             double riskFreeRate, double dividendYield, Params params, Clock clock,
                             LegQuoteSource legQuoteSource) {
        this(orderManager, chains, surfaces, fairVolProvider, valuation, spotSupplier, tracker, symbol, riskFreeRate, dividendYield, params, clock);
        this.legQuoteSource = legQuoteSource;
    }

    @FunctionalInterface
    public interface LegQuoteSource {
        Optional<MarketSnapshot> quote(String occSymbol);
    }

    private volatile LegQuoteSource legQuoteSource;

    public void setLegQuoteSource(LegQuoteSource source) {
        this.legQuoteSource = source;
    }

    MarketSnapshot resolveQuote(String occSymbol) {
        if (occSymbol == null) {
            return null;
        }
        if (legQuoteSource != null) {
            try {
                Optional<MarketSnapshot> venue = legQuoteSource.quote(occSymbol);
                if (venue != null && venue.isPresent() && venue.get().hasBook()
                        && venue.get().status() != MarketDataStatus.UNAVAILABLE) {
                    return venue.get();
                }
            } catch (RuntimeException ignored) {
                // fall through to chains
            }
        }
        return chains.optionQuote(occSymbol).orElse(null);
    }

    public Optional<Decision> lastDecision() {
        return Optional.ofNullable(lastDecision);
    }

    public Params params() {
        return params;
    }

    /** Evaluates against other market data, keeping the position memory: for tests and replays. */
    synchronized Decision stepWith(OptionMarketData market) {
        return stepWith(market, this.clock);
    }

    /** Evaluates against other market data at another time, keeping the position memory: for tests and replays. */
    synchronized Decision stepWith(OptionMarketData market, Clock at) {
        if (market == null || at == null) {
            throw new IllegalArgumentException("market and clock must not be null");
        }
        this.chains = market;
        this.clock = at;
        return step();
    }

    /** Spread of the wider leg in the pre-trade gate's units (over mid, in bps); NaN when neither leg has a two-sided book. */
    static double legSpreadBps(MarketSnapshot a, MarketSnapshot b) {
        double worst = Double.NaN;
        for (MarketSnapshot q : new MarketSnapshot[]{a, b}) {
            if (q == null || !(q.bid() > 0.0) || !(q.ask() > q.bid()) || !Double.isFinite(q.ask())) continue;
            double bps = (q.ask() - q.bid()) / ((q.ask() + q.bid()) / 2.0) * 10_000.0;
            worst = Double.isNaN(worst) ? bps : Math.max(worst, bps);
        }
        return worst;
    }

    /** The straddle's spread as seen by the step in progress; stamped onto the decision it records. Steps are synchronized. */
    private double stepSpreadBps = Double.NaN;

    /** Seconds since the feed stamped the quote; NaN when there is no quote or the feed gave no time (an epoch stamp). */
    static double quoteAge(MarketSnapshot s) {
        if (s == null || s.sourceTimestamp() == null || s.sourceTimestamp().equals(Instant.EPOCH)) return Double.NaN;
        return s.derivedQuoteAgeMs() >= 0 ? s.derivedQuoteAgeMs() / 1000.0 : Double.NaN;
    }

    /** Seconds since the feed stamped the chain for {@code exp}; NaN when the feed gave no time. */
    double chainAge(LocalDate exp, Instant now) {
        if (exp == null) return Double.NaN;
        return chains.chainAsOf(exp)
                .map(asOf -> Math.max(0.0, (now.toEpochMilli() - asOf.toEpochMilli()) / 1000.0))
                .orElse(Double.NaN);
    }

    static double maxQuoteAge(MarketSnapshot a, MarketSnapshot b) {
        double ageA = quoteAge(a);
        double ageB = quoteAge(b);
        if (Double.isNaN(ageA)) return ageB;
        if (Double.isNaN(ageB)) return ageA;
        return Math.max(ageA, ageB);
    }

    /** One evaluation: hedge if needed, then open, hold or close the straddle. Never throws for market conditions. */
    public synchronized Decision step() {
        stepSpreadBps = Double.NaN;
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<String> orders = new ArrayList<>();

        MarketSnapshot spot = spotSupplier.get();
        if (spot == null || spot.status() == MarketDataStatus.UNAVAILABLE || !(spot.last() > 0.0)) {
            return record(new Decision(now, "WAIT", "no spot price", null, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, orders,
                    Double.NaN, Double.NaN, Double.NaN));
        }
        if (spot.status() == MarketDataStatus.STALE) {
            // The option chain is reloaded every minute and so reads as fresh even when the market is shut, but the
            // spot's age shows it: a stale spot means nothing here can be priced, hedged or traded.
            return record(new Decision(now, "WAIT", "spot quote STALE", null, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, orders,
                    quoteAge(spot), Double.NaN, Double.NaN));
        }
        hedgeIfNeeded(spot, orders);

        if (hasOpenStraddle()) {
            return manageOpenStraddle(now, today, spot, orders);
        }
        return considerOpening(now, today, spot, orders);
    }

    private LocalDate frontExpiry(LocalDate today) {
        for (LocalDate expiry : chains.loadedExpiries()) {
            if (TimeConventions.yearFraction(today, expiry) * TimeConventions.DAYS_PER_YEAR >= params.minDaysToExpiry()) {
                return expiry;
            }
        }
        return null;
    }

    private static final java.time.ZoneId NEW_YORK = java.time.ZoneId.of("America/New_York");

    /**
     * An operator-supervised test of the whole live path: buys one straddle (the front-month at-the-money call and put, long
     * only) with no edge required, through the same gates, venue quotes, transport, tracker and ledger as a signal trade.
     * From then on the ordinary {@link #step()} manages it (hedge, hold, close), so the position is booked where the
     * reconciliation against the venue can see it. Without {@code confirm} it only reports the legs and the debit.
     *
     * <p>Refused outside US regular hours (09:30 to 16:00 New York, Monday to Friday; exchange holidays and early closes are
     * left to the stale-quote gate and the venue), when the strategy is short-only, with a stale spot, or while a straddle is
     * already open. A refusal sends nothing.
     */
    public synchronized Decision supervisedBuyStraddle(boolean confirm) {
        stepSpreadBps = Double.NaN;
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<String> orders = new ArrayList<>();
        java.time.ZonedDateTime ny = java.time.ZonedDateTime.ofInstant(now, NEW_YORK);
        java.time.LocalTime open = java.time.LocalTime.of(9, 30), close = java.time.LocalTime.of(16, 0);
        boolean weekend = ny.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || ny.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
        if (weekend || ny.toLocalTime().isBefore(open) || !ny.toLocalTime().isBefore(close)) {
            return supervisedRefusal(now, "outside US regular trading hours (09:30-16:00 New York, Monday to Friday): it is "
                    + ny.toLocalDate() + " " + ny.toLocalTime().withNano(0) + " there");
        }
        if (params.allowedSide() == AllowedSide.SHORT_ONLY) {
            return supervisedRefusal(now, "strategy.side is short_only, and this test buys");
        }
        if (hasOpenStraddle()) {
            return supervisedRefusal(now, "a straddle is already open (" + openCall + " / " + openPut + ")");
        }
        MarketSnapshot spot = spotSupplier.get();
        double spotAge = quoteAge(spot);
        if (spot == null || spot.status() == MarketDataStatus.UNAVAILABLE || spot.status() == MarketDataStatus.STALE || !(spot.last() > 0.0)) {
            return supervisedRefusal(now, "no fresh spot price for " + symbol, spotAge);
        }
        LocalDate front = frontExpiry(today);
        Optional<OptionChain> chain = front == null ? Optional.empty() : chains.chain(front);
        double cAgeInitial = chainAge(front, now);
        if (chain.isEmpty()) {
            return supervisedRefusal(now, "no loaded expiry at least " + params.minDaysToExpiry() + " days out", spotAge, cAgeInitial, Double.NaN);
        }
        double t = TimeConventions.yearFraction(today, front);
        double forward = spot.last() * Math.exp((riskFreeRate - dividendYield) * t);
        OptionalDouble atm = nearestStrike(chain.get(), forward);
        if (atm.isEmpty()) {
            return supervisedRefusal(now, "no strikes in the " + front + " chain", spotAge, cAgeInitial, Double.NaN);
        }
        double strike = atm.getAsDouble();
        String call = OccSymbol.format(symbol, front, OptionType.CALL, strike);
        String put = OccSymbol.format(symbol, front, OptionType.PUT, strike);
        double cAge = chainAge(front, now);
        MarketSnapshot callQuote = resolveQuote(call);
        MarketSnapshot putQuote = resolveQuote(put);
        double optAge = maxQuoteAge(callQuote, putQuote);
        stepSpreadBps = legSpreadBps(callQuote, putQuote);
        if (callQuote == null || putQuote == null) {
            return supervisedRefusal(now, "no quote for " + (callQuote == null ? call : put), spotAge, cAge, optAge);
        }
        if (!confirm) {
            String plan = String.format(Locale.ROOT, "dry run: would buy %d %s and %d %s; debit about $%.2f at the ask (%.2f + %.2f, x100 per contract), nothing sent",
                    params.contracts(), call, params.contracts(), put,
                    params.contracts() * 100.0 * (callQuote.ask() + putQuote.ask()), callQuote.ask(), putQuote.ask());
            System.out.println("[SUPERVISED] " + plan);
            return new Decision(now, "SUPERVISED_DRY_RUN", plan, front, strike, Double.NaN, Double.NaN, Double.NaN, 0, orders,
                    spotAge, cAge, optAge, stepSpreadBps);
        }
        System.out.println("[SUPERVISED] operator confirmed: buying 1 straddle " + front + " " + strike);
        return placeStraddle(now, front, strike, null, false, orders, "SUPERVISED_",
                multiLeg -> "operator-supervised test straddle, no edge required");
    }

    private Decision supervisedRefusal(Instant now, String why) {
        return supervisedRefusal(now, why, Double.NaN, Double.NaN, Double.NaN);
    }

    private Decision supervisedRefusal(Instant now, String why, double spotAge) {
        return supervisedRefusal(now, why, spotAge, Double.NaN, Double.NaN);
    }

    private Decision supervisedRefusal(Instant now, String why, double spotAge, double chainAge, double optionQuoteAge) {
        System.out.println("[SUPERVISED] refused: " + why);
        return new Decision(now, "SUPERVISED_REFUSED", why, null, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, List.of(),
                spotAge, chainAge, optionQuoteAge);
    }

    // ------------------------------------------------------------------ open position

    private boolean hasOpenStraddle() {
        if (openCall == null) {
            return false;
        }
        if (tracker.getNetQuantity(openCall) == 0 && tracker.getNetQuantity(openPut) == 0) {
            openCall = null; // flattened elsewhere (operator, halt recovery)
            openPut = null;
            openExpiry = null;
            openStrike = Double.NaN;
            return false;
        }
        return true;
    }

    private Decision manageOpenStraddle(Instant now, LocalDate today, MarketSnapshot spot, List<String> orders) {
        double spotAge = quoteAge(spot);
        double cAge = chainAge(openExpiry, now);
        MarketSnapshot openCallQuote = resolveQuote(openCall);
        MarketSnapshot openPutQuote = resolveQuote(openPut);
        double optAge = maxQuoteAge(openCallQuote, openPutQuote);
        stepSpreadBps = legSpreadBps(openCallQuote, openPutQuote);

        int sign = Integer.signum(tracker.getNetQuantity(openCall) != 0 ? tracker.getNetQuantity(openCall) : tracker.getNetQuantity(openPut));
        if ((tracker.getNetQuantity(openCall) == 0) != (tracker.getNetQuantity(openPut) == 0)) {
            // One leg only: a refused second leg, or a flatten that was refused earlier. A lone short option is a
            // naked position the strategy never intends to hold, whatever the edge says, so it is closed first.
            closeStraddle(orders);
            return record(new Decision(now, "CLOSE", "only one leg is held; flattening it", openExpiry, openStrike,
                    Double.NaN, Double.NaN, Double.NaN, sign, orders, spotAge, cAge, optAge));
        }
        LocalDate exp = openExpiry;
        double strk = openStrike;
        double t = TimeConventions.yearFraction(today, exp);
        long daysLeft = Math.round(t * TimeConventions.DAYS_PER_YEAR);
        if (daysLeft <= params.maxDaysToExpiry()) {
            closeStraddle(orders);
            return record(new Decision(now, "CLOSE", "expiry within " + params.maxDaysToExpiry() + " days (" + daysLeft + " left)",
                    exp, strk, Double.NaN, Double.NaN, Double.NaN, sign, orders, spotAge, cAge, optAge));
        }

        // Pin risk monitor: DTE <= 1 and spot within 0.5% of strike
        if (daysLeft <= 1 && Math.abs(spot.last() - strk) / strk <= 0.005) {
            closeStraddle(orders);
            return record(new Decision(now, "CLOSE", String.format(Locale.ROOT,
                    "pin risk: DTE <= 1 (%d days) and spot %.2f within 0.5%% of strike %.2f", daysLeft, spot.last(), strk),
                    exp, strk, Double.NaN, Double.NaN, Double.NaN, sign, orders, spotAge, cAge, optAge));
        }

        // Early assignment monitor for short straddles: ITM short option with extrinsic value < $0.10
        if (sign < 0) {
            MarketSnapshot callQuote = openCallQuote;
            MarketSnapshot putQuote = openPutQuote;
            if (callQuote != null && spot.last() > strk) {
                double intrinsic = spot.last() - strk;
                double extrinsic = callQuote.last() - intrinsic;
                if (extrinsic < 0.10) {
                    closeStraddle(orders);
                    return record(new Decision(now, "CLOSE", String.format(Locale.ROOT,
                            "early assignment risk: short ITM call extrinsic value $%.2f < $0.10", extrinsic),
                            exp, strk, Double.NaN, Double.NaN, Double.NaN, sign, orders, spotAge, cAge, optAge));
                }
            }
            if (putQuote != null && spot.last() < strk) {
                double intrinsic = strk - spot.last();
                double extrinsic = putQuote.last() - intrinsic;
                if (extrinsic < 0.10) {
                    closeStraddle(orders);
                    return record(new Decision(now, "CLOSE", String.format(Locale.ROOT,
                            "early assignment risk: short ITM put extrinsic value $%.2f < $0.10", extrinsic),
                            exp, strk, Double.NaN, Double.NaN, Double.NaN, sign, orders, spotAge, cAge, optAge));
                }
            }
        }

        Edge edge = edgeFor(exp, strk, spot.last(), t, openCallQuote, openPutQuote);
        if (edge == null) {
            return record(new Decision(now, "HOLD", "cannot evaluate the open straddle (no quotes or surface); holding",
                    exp, strk, Double.NaN, Double.NaN, Double.NaN, sign, orders, spotAge, cAge, optAge));
        }
        // A short straddle (sign -1) was opened on a positive edge, a long one on a negative edge: the trade has done
        // its work once the edge has crossed back to zero (sign * edge >= 0) or come inside half the band.
        if (sign * edge.value() >= 0.0 || Math.abs(edge.value()) < params.volEdge() / 2.0) {
            closeStraddle(orders);
            return record(new Decision(now, "CLOSE", String.format(Locale.ROOT, "edge reverted inside half the band (%.2f pts)", 100 * params.volEdge() / 2.0),
                    exp, strk, edge.marketIv(), edge.referenceIv(), edge.value(), sign, orders, spotAge, cAge, optAge));
        }
        return record(new Decision(now, "HOLD", sign < 0 ? "short straddle; market still rich to the reference" : "long straddle; market still cheap to the reference",
                exp, strk, edge.marketIv(), edge.referenceIv(), edge.value(), sign, orders, spotAge, cAge, optAge));
    }

    /**
     * One order for both legs, marked as closing, when the straddle is whole (both legs held, in equal size, on the same side)
     * and multi-leg orders are on; the venue fills both or neither, so a close cannot leave one leg behind. When it is not filled
     * (or cannot be tried) the caller closes leg by leg, as it always did. Returns whether both legs are closed.
     */
    private boolean closeAtomically(List<String> orders) {
        int callQty = tracker.getNetQuantity(openCall);
        int putQty = tracker.getNetQuantity(openPut);
        if (callQty == 0 || putQty == 0 || Integer.signum(callQty) != Integer.signum(putQty) || Math.abs(callQty) != Math.abs(putQty)) {
            return false;
        }
        MarketSnapshot callQuote = resolveQuote(openCall);
        MarketSnapshot putQuote = resolveQuote(openPut);
        if (callQuote == null || putQuote == null) {
            return false;
        }
        boolean buyBack = callQty < 0;
        int quantity = Math.abs(callQty);
        // priced at the touch, as a single order is: the bids for a sell (the proceeds asked for), the asks for a buy-back. The mid
        // asks for more than the market pays for a sell and was never filled (live, 2026-10-20); a leg without a book falls back to its last
        double net = touchOrLast(callQuote, buyBack) + touchOrLast(putQuote, buyBack);
        OrderManager.OrderDecision decision = orderManager.submitMultiLeg(
                List.of(new Order(0, buyBack, quantity, callQuote.last()), new Order(0, buyBack, quantity, putQuote.last())),
                List.of(callQuote, putQuote), net);
        orders.add((buyBack ? "BUY " : "SELL ") + quantity + " " + openCall + "/" + openPut + " MLEG "
                + (decision.accepted() ? "filled" : "rejected: " + decision.message()));
        return decision.accepted();
    }

    private static double touchOrLast(MarketSnapshot quote, boolean buy) {
        double touch = buy ? quote.ask() : quote.bid();
        return Double.isFinite(touch) && touch > 0.0 ? touch : quote.last();
    }

    private void closeStraddle(List<String> orders) {
        boolean closedTogether = params.useMultiLeg() && closeAtomically(orders);
        for (String leg : closedTogether ? new String[0] : new String[]{openCall, openPut}) {
            int qty = tracker.getNetQuantity(leg);
            if (qty == 0) {
                continue;
            }
            MarketSnapshot quote = resolveQuote(leg);
            if (quote == null) {
                orders.add(leg + ": no quote to close against");
                continue;
            }
            submit(new Order(0, qty < 0, Math.abs(qty), quote.last()), quote, orders);
        }
        if (tracker.getNetQuantity(openCall) == 0 && tracker.getNetQuantity(openPut) == 0) {
            openCall = null;
            openPut = null;
            openExpiry = null;
            openStrike = Double.NaN;
        }
    }

    // ------------------------------------------------------------------ opening

    private Decision considerOpening(Instant now, LocalDate today, MarketSnapshot spot, List<String> orders) {
        double spotAge = quoteAge(spot);
        LocalDate front = frontExpiry(today);
        if (front == null) {
            return record(new Decision(now, "WAIT", "no loaded expiry at least " + params.minDaysToExpiry() + " days out", null, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, orders,
                    spotAge, Double.NaN, Double.NaN));
        }
        double t = TimeConventions.yearFraction(today, front);
        double forward = spot.last() * Math.exp((riskFreeRate - dividendYield) * t);
        Optional<OptionChain> chain = chains.chain(front);
        double cAgeInitial = chainAge(front, now);
        if (chain.isPresent() && Math.abs(chain.get().spot() - spot.last()) > MAX_SPOT_DISAGREEMENT * spot.last()) {
            // A generated fallback chain is built around a nominal price; its strikes mean nothing against the live spot.
            return record(new Decision(now, "WAIT", String.format(Locale.ROOT,
                    "chain underlying price %.2f disagrees with the live spot %.2f; not a usable chain", chain.get().spot(), spot.last()),
                    front, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, orders,
                    spotAge, cAgeInitial, Double.NaN));
        }
        OptionalDouble atm = chain.map(c -> nearestStrike(c, forward)).orElse(OptionalDouble.empty());
        if (atm.isEmpty()) {
            return record(new Decision(now, "WAIT", "no strikes in the " + front + " chain", front, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, orders,
                    spotAge, cAgeInitial, Double.NaN));
        }
        double strike = atm.getAsDouble();
        String call = OccSymbol.format(symbol, front, OptionType.CALL, strike);
        String put = OccSymbol.format(symbol, front, OptionType.PUT, strike);
        double cAge = chainAge(front, now);
        MarketSnapshot callQuote = resolveQuote(call);
        MarketSnapshot putQuote = resolveQuote(put);
        double optAge = maxQuoteAge(callQuote, putQuote);
        stepSpreadBps = legSpreadBps(callQuote, putQuote);

        Edge edge = edgeFor(front, strike, spot.last(), t, callQuote, putQuote);
        if (edge == null) {
            return record(new Decision(now, "WAIT", lastEdgeProblem, front, strike, Double.NaN, Double.NaN, Double.NaN, 0, orders,
                    spotAge, cAge, optAge));
        }
        double modelHurdle = (params.rmseMultiplier() > 0.0 && Double.isFinite(edge.referenceRmse()))
                ? Math.max(params.volEdge(), params.rmseMultiplier() * edge.referenceRmse())
                : params.volEdge();
        double hurdle = Math.max(modelHurdle, edge.breakEvenEdge());
        if (Math.abs(edge.value()) <= hurdle) {
            String reason;
            if (edge.breakEvenEdge() > modelHurdle) {
                reason = String.format(Locale.ROOT, "edge %.2f pts does not exceed break-even hurdle %.2f pts (round-trip half-spread+fees)/vega", 100 * Math.abs(edge.value()), 100 * hurdle);
            } else if (modelHurdle > params.volEdge()) {
                reason = String.format(Locale.ROOT, "edge %.2f pts within surface residual uncertainty hurdle %.2f pts (%.1fx RMSE %.2f pts)",
                        100 * Math.abs(edge.value()), 100 * hurdle, params.rmseMultiplier(), 100 * edge.referenceRmse());
            } else {
                reason = String.format(Locale.ROOT, "edge within the %.2f-point band", 100 * params.volEdge());
            }
            return record(new Decision(now, "HOLD", reason + shadowReadout(front, strike, t, edge.marketIv()),
                    front, strike, edge.marketIv(), edge.referenceIv(), edge.value(), 0, orders,
                    spotAge, cAge, optAge));
        }
        boolean sell = edge.value() > 0.0;
        if (sell && params.allowedSide() == AllowedSide.LONG_ONLY) {
            return record(new Decision(now, "HOLD",
                    "market rich to reference by " + String.format(Locale.ROOT, "%.2f pts", 100 * edge.value())
                            + " but short straddle not permitted (strategy.side is long_only: a short straddle is two uncovered short"
                            + " options, and whether this account may place them has not been checked)"
                            + shadowReadout(front, strike, t, edge.marketIv()),
                    front, strike, edge.marketIv(), edge.referenceIv(), edge.value(), 0, orders,
                    spotAge, cAge, optAge));
        }
        if (!sell && params.allowedSide() == AllowedSide.SHORT_ONLY) {
            return record(new Decision(now, "HOLD",
                    "market cheap to reference by " + String.format(Locale.ROOT, "%.2f pts", 100 * Math.abs(edge.value()))
                            + " but long straddle not permitted (short-only configured)"
                            + shadowReadout(front, strike, t, edge.marketIv()),
                    front, strike, edge.marketIv(), edge.referenceIv(), edge.value(), 0, orders,
                    spotAge, cAge, optAge));
        }
        String side = sell ? "rich" : "cheap";
        return placeStraddle(now, front, strike, edge, sell, orders, "",
                multiLeg -> "market " + side + " to the " + params.referenceModel() + (multiLeg
                        ? " reference by more than the break-even hurdle" : " reference by more than the band"));
    }

    /**
     * Sends both legs and, when they fill, makes the straddle the one this strategy manages. Shared by the signal path and the
     * supervised test, so a test straddle goes through exactly the gates, quotes, transport and bookkeeping a real one does.
     * {@code edge} may be null (the supervised test needs none); {@code prefix} marks the decision's action.
     */
    private Decision placeStraddle(Instant now, LocalDate front, double strike, Edge edge, boolean sell, List<String> orders,
                                   String prefix, java.util.function.Function<Boolean, String> openedReason) {
        double mIv = edge == null ? Double.NaN : edge.marketIv();
        double rIv = edge == null ? Double.NaN : edge.referenceIv();
        double eVal = edge == null ? Double.NaN : edge.value();
        String call = OccSymbol.format(symbol, front, OptionType.CALL, strike);
        String put = OccSymbol.format(symbol, front, OptionType.PUT, strike);
        MarketSnapshot callQuote = resolveQuote(call);
        MarketSnapshot putQuote = resolveQuote(put);
        double spotAge = quoteAge(spotSupplier.get());
        double cAge = chainAge(front, now);
        double optAge = maxQuoteAge(callQuote, putQuote);
        stepSpreadBps = legSpreadBps(callQuote, putQuote);
        if (callQuote == null || putQuote == null) {
            orders.add((callQuote == null ? call : put) + ": no quote");
            return record(new Decision(now, prefix + (sell ? "SELL_STRADDLE_REJECTED" : "BUY_STRADDLE_REJECTED"), String.join("; ", orders),
                    front, strike, mIv, rIv, eVal, 0, orders, spotAge, cAge, optAge));
        }

        if (params.useMultiLeg()) {
            Order legCall = new Order(0, !sell, params.contracts(), callQuote.last());
            Order legPut = new Order(0, !sell, params.contracts(), putQuote.last());
            OrderManager.OrderDecision dec = orderManager.submitMultiLeg(List.of(legCall, legPut), List.of(callQuote, putQuote), callQuote.last() + putQuote.last());
            orders.add((!sell ? "BUY " : "SELL ") + params.contracts() + " " + call + "/" + put + " MLEG " + (dec.accepted() ? "filled" : "rejected: " + dec.message()));
            if (!dec.accepted()) {
                return record(new Decision(now, prefix + (sell ? "SELL_STRADDLE_REJECTED" : "BUY_STRADDLE_REJECTED"), dec.message(),
                        front, strike, mIv, rIv, eVal, 0, orders, spotAge, cAge, optAge));
            }
            openCall = call;
            openPut = put;
            openExpiry = front;
            openStrike = strike;
            String action = prefix + (sell ? "SELL_STRADDLE" : "BUY_STRADDLE");
            String reason = openedReason.apply(true);
            return record(new Decision(now, action, reason, front, strike, mIv, rIv, eVal, sell ? -1 : 1, orders, spotAge, cAge, optAge));
        }

        // Sequential submission with fallback flattening for non-mleg transports
        int filledLegs = 0;
        for (String leg : new String[]{call, put}) {
            MarketSnapshot quote = leg.equals(call) ? callQuote : putQuote;
            if (!submit(new Order(0, !sell, params.contracts(), quote.last()), quote, orders)) {
                break; // never leg into a straddle when the first leg already failed the gates
            }
            filledLegs++;
        }
        if (filledLegs == 0) {
            return record(new Decision(now, prefix + (sell ? "SELL_STRADDLE_REJECTED" : "BUY_STRADDLE_REJECTED"), String.join("; ", orders),
                    front, strike, mIv, rIv, eVal, 0, orders, spotAge, cAge, optAge));
        }
        openCall = call;
        openPut = put;
        openExpiry = front;
        openStrike = strike;
        if (filledLegs == 1) {
            // Never carry a lone leg: close it now. If the close is refused too, the book still holds it and the
            // next step flattens it first (see manageOpenStraddle).
            closeStraddle(orders);
            String left = hasOpenStraddle() ? "; the close was refused, the next step retries" : "";
            return record(new Decision(now, prefix + (sell ? "SELL_STRADDLE_REJECTED" : "BUY_STRADDLE_REJECTED"),
                    "second leg refused; the first leg was flattened" + left, front, strike, mIv, rIv, eVal,
                    hasOpenStraddle() ? (sell ? -1 : 1) : 0, orders, spotAge, cAge, optAge));
        }
        String action = prefix + (sell ? "SELL_STRADDLE" : "BUY_STRADDLE");
        String reason = openedReason.apply(false);
        return record(new Decision(now, action, reason, front, strike, mIv, rIv, eVal, sell ? -1 : 1, orders, spotAge, cAge, optAge));
    }

    /**
     * With a surface as the reference and a realized-vol forecast available, the forecast is only reported:
     * " | RV forecast 14.2%, VRP +1.9 pts". Implied volatility sits above realized volatility on average, so a
     * forecast used as the reference would call the market rich almost every day; this keeps the evidence
     * coming without letting it trade.
     */
    private String shadowReadout(LocalDate expiry, double strike, double t, double marketIv) {
        if (fairVolProvider == null || params.usesFairVolReference()) {
            return "";
        }
        OptionalDouble fair = fairVolProvider.fairVol(symbol, expiry, strike, t);
        if (fair.isEmpty() || !Double.isFinite(marketIv)) {
            return "";
        }
        return String.format(Locale.ROOT, " | RV forecast %.1f%%, VRP %+.1f pts", 100 * fair.getAsDouble(), 100 * (marketIv - fair.getAsDouble()));
    }

    private static OptionalDouble nearestStrike(OptionChain chain, double forward) {
        OptionalDouble best = OptionalDouble.empty();
        for (OptionQuote q : chain.quotes()) {
            if (best.isEmpty() || Math.abs(q.strike() - forward) < Math.abs(best.getAsDouble() - forward)) {
                best = OptionalDouble.of(q.strike());
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ edge

    public record Edge(double marketIv, double referenceIv, double value, double breakEvenEdge, double referenceRmse) {
        public Edge(double marketIv, double referenceIv, double value, double breakEvenEdge) {
            this(marketIv, referenceIv, value, breakEvenEdge, Double.NaN);
        }
    }

    private String lastEdgeProblem = "";

    /**
     * Market straddle IV (mean of the call and put mids inverted) minus the reference surface; null with the reason in
     * {@link #lastEdgeProblem}. The leg quotes are the ones the caller already asked the venue for this step.
     */
    private Edge edgeFor(LocalDate expiry, double strike, double spot, double t, MarketSnapshot callQuote, MarketSnapshot putQuote) {
        String call = OccSymbol.format(symbol, expiry, OptionType.CALL, strike);
        String put = OccSymbol.format(symbol, expiry, OptionType.PUT, strike);
        double sum = 0.0;
        for (String leg : new String[]{call, put}) {
            MarketSnapshot quote = leg.equals(call) ? callQuote : putQuote;
            if (quote == null || !quote.hasBook()) {
                lastEdgeProblem = leg + ": no two-sided quote";
                return null;
            }
            if (quote.status() == MarketDataStatus.STALE || quote.status() == MarketDataStatus.UNAVAILABLE) {
                lastEdgeProblem = leg + ": quote " + quote.status() + (quote.status() == MarketDataStatus.STALE
                        ? String.format(Locale.ROOT, " (feed %d s old, limit %d s)", quote.derivedQuoteAgeMs() / 1000L, OptionQuoteSnapshots.STALE_AFTER_MS / 1000L)
                        : "");
                return null;
            }
            if (quote.status() == MarketDataStatus.SIMULATED && !allowSimulated) {
                lastEdgeProblem = leg + ": the chain is generated (SIMULATED), not market data; not trading on it";
                return null;
            }
            OptionalDouble iv = VolatilitySurfaceCalibrator.impliedVolatility(spot, strike, t, riskFreeRate, dividendYield,
                    leg.equals(call) ? OptionType.CALL : OptionType.PUT, quote.last());
            if (iv.isEmpty()) {
                lastEdgeProblem = leg + ": mid cannot be inverted to a volatility";
                return null;
            }
            sum += iv.getAsDouble();
        }
        double marketIv = sum / 2.0;

        // Cost-aware break-even edge: (half-spread + fees) / vega, paid on the way in and again on the way out,
        // because every straddle this strategy opens it also closes (the edge reverting is the exit).
        double callHalf = (callQuote.ask() - callQuote.bid()) / 2.0;
        double putHalf = (putQuote.ask() - putQuote.bid()) / 2.0;
        double oneWayFrictionPerShare = Math.max(0.0, callHalf) + Math.max(0.0, putHalf) + (2.0 * params.feePerContract() / 100.0);
        double totalFrictionPerShare = 2.0 * oneWayFrictionPerShare;
        double callVega = com.sbk.optionspricer.BlackScholesPricer.vega(spot, strike, t, riskFreeRate, marketIv, dividendYield);
        double straddleVega = 2.0 * callVega;
        double breakEvenEdge = straddleVega > 1e-6 ? (totalFrictionPerShare / straddleVega) : Double.POSITIVE_INFINITY;

        OptionalDouble reference;
        double rmse = Double.NaN;
        if (params.usesFairVolReference()) {
            reference = fairVolProvider.fairVol(symbol, expiry, strike, t);
            if (reference.isEmpty()) {
                lastEdgeProblem = "no " + params.referenceModel() + " reference forecast for " + expiry;
                return null;
            }
        } else {
            SurfaceFitter.Fit fit = surfaces.latest()
                    .map(s -> "SVI".equals(params.referenceModel()) ? s.svi() : s.ssvi()).orElse(null);
            if (fit != null) {
                rmse = fit.rmse();
            }
            reference = SurfaceFitter.volAt(fit, t, strike, 3.0);
            if (reference.isEmpty()) {
                lastEdgeProblem = "no " + params.referenceModel() + " reference surface for " + expiry;
                return null;
            }
        }
        return new Edge(marketIv, reference.getAsDouble(), marketIv - reference.getAsDouble(), breakEvenEdge, rmse);
    }

    // ------------------------------------------------------------------ hedge and orders

    private void hedgeIfNeeded(MarketSnapshot spot, List<String> orders) {
        if (valuation == null) {
            return;
        }
        Optional<Valuation> v = valuation.latestValuation();
        if (v.isEmpty() || !Double.isFinite(v.get().netDelta())) {
            return;
        }
        double netDelta = v.get().netDelta();
        if (Math.abs(netDelta) <= params.hedgeBand()) {
            return;
        }
        int shares = (int) Math.round(-netDelta);
        if (shares == 0) {
            return;
        }
        submit(new Order(0, shares > 0, Math.abs(shares), spot.last()), spot, orders);
    }

    private boolean submit(Order order, MarketSnapshot snapshot, List<String> orders) {
        OrderManager.OrderDecision decision = orderManager.submit(order, snapshot);
        orders.add((order.isBuy() ? "BUY " : "SELL ") + order.quantity() + " " + snapshot.symbol()
                + (decision.accepted() ? " filled" : " rejected: " + decision.message()));
        return decision.accepted();
    }

    private volatile java.util.function.Consumer<Decision> decisionListener;

    /** Told of every decision (the journal). A listener that fails is reported and ignored: it must never stop a step. */
    public void setDecisionListener(java.util.function.Consumer<Decision> listener) {
        this.decisionListener = listener;
    }

    private Decision record(Decision decision) {
        if (Double.isNaN(decision.optionSpreadBps()) && Double.isFinite(stepSpreadBps)) {
            decision = new Decision(decision.at(), decision.action(), decision.reason(), decision.expiry(), decision.strike(),
                    decision.marketIv(), decision.referenceIv(), decision.edge(), decision.straddleSign(), decision.orders(),
                    decision.spotAgeSeconds(), decision.chainAgeSeconds(), decision.optionQuoteAgeSeconds(), stepSpreadBps);
        }
        lastDecision = decision;
        System.out.println("[VOL SPREAD] " + decision.summary() + (decision.orders().isEmpty() ? "" : " | " + String.join("; ", decision.orders())));
        java.util.function.Consumer<Decision> listener = decisionListener;
        if (listener != null) {
            try {
                listener.accept(decision);
            } catch (RuntimeException e) {
                System.err.println("[VOL SPREAD] decision listener failed: " + e.getMessage());
            }
        }
        return decision;
    }
}
