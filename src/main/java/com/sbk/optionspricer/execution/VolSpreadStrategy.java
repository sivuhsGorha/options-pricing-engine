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

    /**
     * @param volEdge          vol points (as a fraction, 0.01 = one point) the market must deviate from the reference to open
     * @param hedgeBand        net delta (in underlying units) beyond which shares are traded to flatten it
     * @param maxDaysToExpiry  close an open straddle when its expiry is this close
     * @param minDaysToExpiry  do not open on an expiry closer than this
     * @param contracts        straddle size per leg
     * @param referenceModel   {@code SSVI} or {@code SVI}
     */
    public record Params(double volEdge, double hedgeBand, int maxDaysToExpiry, int minDaysToExpiry, int contracts, String referenceModel) {
        public Params {
            if (!(volEdge > 0.0) || !(hedgeBand >= 0.0) || maxDaysToExpiry < 0 || minDaysToExpiry < 0 || contracts < 1) {
                throw new IllegalArgumentException("volEdge > 0, hedgeBand >= 0, day limits >= 0, contracts >= 1");
            }
            if (minDaysToExpiry <= maxDaysToExpiry) {
                throw new IllegalArgumentException("minDaysToExpiry must exceed maxDaysToExpiry, or a straddle would be opened and closed at once");
            }
            referenceModel = referenceModel == null || referenceModel.isBlank() ? "SSVI" : referenceModel.trim().toUpperCase(Locale.ROOT);
            if (!referenceModel.equals("SSVI") && !referenceModel.equals("SVI")) {
                throw new IllegalArgumentException("referenceModel must be SSVI or SVI");
            }
        }
    }

    /** What one step saw and did. {@code edge} is market minus reference, as a vol fraction. */
    public record Decision(Instant at, String action, String reason, LocalDate expiry, double strike,
                           double marketIv, double referenceIv, double edge, int straddleSign, List<String> orders) {
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
        if (orderManager == null || chains == null || surfaces == null || spotSupplier == null || tracker == null || symbol == null || params == null || clock == null) {
            throw new IllegalArgumentException("all dependencies except valuation must be provided");
        }
        this.orderManager = orderManager;
        this.chains = chains;
        this.surfaces = surfaces;
        this.valuation = valuation;
        this.spotSupplier = spotSupplier;
        this.tracker = tracker;
        this.symbol = symbol.trim().toUpperCase(Locale.ROOT);
        this.riskFreeRate = riskFreeRate;
        this.dividendYield = dividendYield;
        this.params = params;
        this.clock = clock;
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

    /** One evaluation: hedge if needed, then open, hold or close the straddle. Never throws for market conditions. */
    public synchronized Decision step() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<String> orders = new ArrayList<>();

        MarketSnapshot spot = spotSupplier.get();
        if (spot == null || spot.status() == MarketDataStatus.UNAVAILABLE || !(spot.last() > 0.0)) {
            return record(new Decision(now, "WAIT", "no spot price", null, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, orders));
        }
        hedgeIfNeeded(spot, orders);

        if (hasOpenStraddle()) {
            return manageOpenStraddle(now, today, spot, orders);
        }
        return considerOpening(now, today, spot, orders);
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
        int sign = Integer.signum(tracker.getNetQuantity(openCall) != 0 ? tracker.getNetQuantity(openCall) : tracker.getNetQuantity(openPut));
        if ((tracker.getNetQuantity(openCall) == 0) != (tracker.getNetQuantity(openPut) == 0)) {
            // One leg only: a refused second leg, or a flatten that was refused earlier. A lone short option is a
            // naked position the strategy never intends to hold, whatever the edge says, so it is closed first.
            closeStraddle(orders);
            return record(new Decision(now, "CLOSE", "only one leg is held; flattening it", openExpiry, openStrike,
                    Double.NaN, Double.NaN, Double.NaN, sign, orders));
        }
        double t = TimeConventions.yearFraction(today, openExpiry);
        long daysLeft = Math.round(t * TimeConventions.DAYS_PER_YEAR);
        if (daysLeft <= params.maxDaysToExpiry()) {
            closeStraddle(orders);
            return record(new Decision(now, "CLOSE", "expiry within " + params.maxDaysToExpiry() + " days (" + daysLeft + " left)",
                    openExpiry, openStrike, Double.NaN, Double.NaN, Double.NaN, sign, orders));
        }
        Edge edge = edgeFor(openExpiry, openStrike, spot.last(), t);
        if (edge == null) {
            return record(new Decision(now, "HOLD", "cannot evaluate the open straddle (no quotes or surface); holding",
                    openExpiry, openStrike, Double.NaN, Double.NaN, Double.NaN, sign, orders));
        }
        // A short straddle (sign -1) was opened on a positive edge, a long one on a negative edge: the trade has done
        // its work once the edge has crossed back to zero (sign * edge >= 0) or come inside half the band.
        if (sign * edge.value() >= 0.0 || Math.abs(edge.value()) < params.volEdge() / 2.0) {
            closeStraddle(orders);
            return record(new Decision(now, "CLOSE", String.format(Locale.ROOT, "edge reverted inside half the band (%.2f pts)", 100 * params.volEdge() / 2.0),
                    openExpiry, openStrike, edge.marketIv(), edge.referenceIv(), edge.value(), sign, orders));
        }
        return record(new Decision(now, "HOLD", sign < 0 ? "short straddle; market still rich to the reference" : "long straddle; market still cheap to the reference",
                openExpiry, openStrike, edge.marketIv(), edge.referenceIv(), edge.value(), sign, orders));
    }

    private void closeStraddle(List<String> orders) {
        for (String leg : new String[]{openCall, openPut}) {
            int qty = tracker.getNetQuantity(leg);
            if (qty == 0) {
                continue;
            }
            Optional<MarketSnapshot> quote = chains.optionQuote(leg);
            if (quote.isEmpty()) {
                orders.add(leg + ": no quote to close against");
                continue;
            }
            submit(new Order(0, qty < 0, Math.abs(qty), quote.get().last()), quote.get(), orders);
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
        LocalDate front = null;
        for (LocalDate expiry : chains.loadedExpiries()) {
            if (TimeConventions.yearFraction(today, expiry) * TimeConventions.DAYS_PER_YEAR >= params.minDaysToExpiry()) {
                front = expiry;
                break;
            }
        }
        if (front == null) {
            return record(new Decision(now, "WAIT", "no loaded expiry at least " + params.minDaysToExpiry() + " days out", null, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, orders));
        }
        double t = TimeConventions.yearFraction(today, front);
        double forward = spot.last() * Math.exp((riskFreeRate - dividendYield) * t);
        Optional<OptionChain> chain = chains.chain(front);
        if (chain.isPresent() && Math.abs(chain.get().spot() - spot.last()) > MAX_SPOT_DISAGREEMENT * spot.last()) {
            // A generated fallback chain is built around a nominal price; its strikes mean nothing against the live spot.
            return record(new Decision(now, "WAIT", String.format(Locale.ROOT,
                    "chain underlying price %.2f disagrees with the live spot %.2f; not a usable chain", chain.get().spot(), spot.last()),
                    front, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, orders));
        }
        OptionalDouble atm = chain.map(c -> nearestStrike(c, forward)).orElse(OptionalDouble.empty());
        if (atm.isEmpty()) {
            return record(new Decision(now, "WAIT", "no strikes in the " + front + " chain", front, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, orders));
        }
        double strike = atm.getAsDouble();
        Edge edge = edgeFor(front, strike, spot.last(), t);
        if (edge == null) {
            return record(new Decision(now, "WAIT", lastEdgeProblem, front, strike, Double.NaN, Double.NaN, Double.NaN, 0, orders));
        }
        if (Math.abs(edge.value()) <= params.volEdge()) {
            return record(new Decision(now, "HOLD", String.format(Locale.ROOT, "edge within the %.2f-point band", 100 * params.volEdge()),
                    front, strike, edge.marketIv(), edge.referenceIv(), edge.value(), 0, orders));
        }
        boolean sell = edge.value() > 0.0;
        String call = OccSymbol.format(symbol, front, OptionType.CALL, strike);
        String put = OccSymbol.format(symbol, front, OptionType.PUT, strike);
        int filledLegs = 0;
        for (String leg : new String[]{call, put}) {
            MarketSnapshot quote = chains.optionQuote(leg).orElse(null);
            if (quote == null) {
                orders.add(leg + ": no quote");
                break;
            }
            if (!submit(new Order(0, !sell, params.contracts(), quote.last()), quote, orders)) {
                break; // never leg into a straddle when the first leg already failed the gates
            }
            filledLegs++;
        }
        if (filledLegs == 0) {
            return record(new Decision(now, sell ? "SELL_STRADDLE_REJECTED" : "BUY_STRADDLE_REJECTED", String.join("; ", orders),
                    front, strike, edge.marketIv(), edge.referenceIv(), edge.value(), 0, orders));
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
            return record(new Decision(now, sell ? "SELL_STRADDLE_REJECTED" : "BUY_STRADDLE_REJECTED",
                    "second leg refused; the first leg was flattened" + left, front, strike, edge.marketIv(), edge.referenceIv(), edge.value(),
                    hasOpenStraddle() ? (sell ? -1 : 1) : 0, orders));
        }
        String action = sell ? "SELL_STRADDLE" : "BUY_STRADDLE";
        String reason = "market " + (sell ? "rich" : "cheap") + " to the " + params.referenceModel() + " reference by more than the band";
        return record(new Decision(now, action, reason, front, strike, edge.marketIv(), edge.referenceIv(), edge.value(), sell ? -1 : 1, orders));
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

    private record Edge(double marketIv, double referenceIv, double value) {}

    private String lastEdgeProblem = "";

    /** Market straddle IV (mean of the call and put mids inverted) minus the reference surface; null with the reason in {@link #lastEdgeProblem}. */
    private Edge edgeFor(LocalDate expiry, double strike, double spot, double t) {
        String call = OccSymbol.format(symbol, expiry, OptionType.CALL, strike);
        String put = OccSymbol.format(symbol, expiry, OptionType.PUT, strike);
        double sum = 0.0;
        for (String leg : new String[]{call, put}) {
            MarketSnapshot quote = chains.optionQuote(leg).orElse(null);
            if (quote == null || !quote.hasBook()) {
                lastEdgeProblem = leg + ": no two-sided quote";
                return null;
            }
            if (quote.status() == MarketDataStatus.STALE || quote.status() == MarketDataStatus.UNAVAILABLE) {
                lastEdgeProblem = leg + ": quote " + quote.status();
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
        SurfaceFitter.Fit fit = surfaces.latest()
                .map(s -> "SVI".equals(params.referenceModel()) ? s.svi() : s.ssvi()).orElse(null);
        OptionalDouble reference = SurfaceFitter.volAt(fit, t, strike, 3.0);
        if (reference.isEmpty()) {
            lastEdgeProblem = "no " + params.referenceModel() + " reference surface for " + expiry;
            return null;
        }
        return new Edge(marketIv, reference.getAsDouble(), marketIv - reference.getAsDouble());
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

    private Decision record(Decision decision) {
        lastDecision = decision;
        System.out.println("[VOL SPREAD] " + decision.summary() + (decision.orders().isEmpty() ? "" : " | " + String.join("; ", decision.orders())));
        return decision;
    }
}
