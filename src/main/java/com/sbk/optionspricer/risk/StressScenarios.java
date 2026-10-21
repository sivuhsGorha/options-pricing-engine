package com.sbk.optionspricer.risk;

import com.sbk.optionspricer.BlackScholesPricer;
import com.sbk.optionspricer.execution.Valuation.PositionValuation;
import com.sbk.optionspricer.instruments.OccSymbol;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The book revalued from scratch under shocks to spot, volatility, skew and term structure. {@link MarginApproximation} stresses the net Greeks with a
 * second-order expansion, which cannot see skew or term structure (a book has one vega, not a vega per strike and per expiry). This reprices each
 * option with Black-Scholes at its own implied volatility, shocked per position, so a long out-of-the-money call and a long out-of-the-money put react
 * differently to a skew shock, and a one-month option and a one-year option to a front-month shock.
 *
 * <p>Scenarios are fixed and named: parallel spot and volatility moves, a skew steepening (out-of-the-money puts +5 volatility points, calls -5, scaled
 * by moneyness out to 10%), a front-month shock (+10 points decaying with maturity over about a month), and two combinations (a crash and a rally).
 * The profit or loss is the repricing against the same model at the base volatility, so it measures the scenario and nothing else, not the gap between
 * model and mark. A position with no usable volatility, an expired option or an unparsable symbol is listed as unvalued, never assumed flat. It is a
 * scale for how much the book could lose, not a margin requirement. A single underlying has no correlation to shock.
 */
public final class StressScenarios {

    private static final double SKEW_POINTS = 0.05;
    private static final double SKEW_FULL_MONEYNESS = 0.10;
    private static final double FRONT_SHOCK = 0.10;
    private static final double FRONT_DECAY_YEARS = 30.0 / 365.0;
    private static final double MIN_VOL = 0.01;

    private StressScenarios() {}

    public record Scenario(String name, double pnl) {}

    public record Result(List<Scenario> scenarios, List<String> unvalued) {
        public Scenario worst() {
            return scenarios.stream().min(Comparator.comparingDouble(Scenario::pnl)).orElseThrow();
        }
    }

    /** What a scenario does to a spot and to one option's volatility. */
    private record Shock(String name, double spotFactor, double volShift, boolean skew, boolean front) {}

    private static final List<Shock> SHOCKS = List.of(
            new Shock("spot -15%", 0.85, 0, false, false), new Shock("spot -10%", 0.90, 0, false, false), new Shock("spot -5%", 0.95, 0, false, false),
            new Shock("spot +5%", 1.05, 0, false, false), new Shock("spot +10%", 1.10, 0, false, false), new Shock("spot +15%", 1.15, 0, false, false),
            new Shock("vol -10 pts", 1.0, -0.10, false, false), new Shock("vol +10 pts", 1.0, 0.10, false, false), new Shock("vol +20 pts", 1.0, 0.20, false, false),
            new Shock("skew steepens", 1.0, 0, true, false), new Shock("front-month vol +10 pts", 1.0, 0, false, true),
            new Shock("crash: spot -10%, vol +15 pts", 0.90, 0.15, false, false), new Shock("rally: spot +5%, vol -5 pts", 1.05, -0.05, false, false));

    /** @param asOf the valuation day: time to expiry is counted from it in calendar days over 365 */
    public static Result run(List<PositionValuation> positions, double spot, LocalDate asOf, double riskFreeRate, double dividendYield) {
        List<String> unvalued = new ArrayList<>();
        List<Scenario> out = new ArrayList<>();
        List<Position> usable = new ArrayList<>();
        for (PositionValuation p : positions) {
            if (p.quantity() == 0) {
                continue;
            }
            Optional<Position> parsed = Position.of(p, spot, asOf);
            if (parsed.isEmpty()) {
                unvalued.add(p.symbol());
            } else {
                usable.add(parsed.get());
            }
        }
        for (Shock shock : SHOCKS) {
            double pnl = 0.0;
            for (Position p : usable) {
                pnl += p.pnl(shock, spot, riskFreeRate, dividendYield);
            }
            out.add(new Scenario(shock.name(), pnl));
        }
        return new Result(List.copyOf(out), List.copyOf(unvalued));
    }

    /** One position reduced to what the repricing needs; a stock has no strike. */
    private record Position(boolean option, boolean call, double strike, double years, double baseVol, double units) {

        static Optional<Position> of(PositionValuation p, double spot, LocalDate asOf) {
            double units = (double) p.quantity() * p.multiplier();
            if ("STOCK".equals(p.kind())) {
                return Optional.of(new Position(false, false, 0, 0, 0, units));
            }
            Optional<OccSymbol.Parsed> parsed = OccSymbol.parse(p.symbol());
            if (parsed.isEmpty() || p.expired() || !Double.isFinite(p.impliedVol()) || p.impliedVol() <= 0.0) {
                return Optional.empty();
            }
            double years = (parsed.get().expiry().toEpochDay() - asOf.toEpochDay()) / 365.0;
            if (!(years > 0.0)) {
                return Optional.empty();
            }
            return Optional.of(new Position(true, parsed.get().type() == com.sbk.optionspricer.OptionType.CALL, parsed.get().strike(), years,
                    p.impliedVol(), units));
        }

        double pnl(Shock shock, double spot, double r, double q) {
            double shockedSpot = spot * shock.spotFactor();
            if (!option) {
                return units * (shockedSpot - spot);
            }
            double vol = baseVol + shock.volShift();
            if (shock.skew()) {
                double moneyness = (spot - strike) / spot / SKEW_FULL_MONEYNESS;
                vol += SKEW_POINTS * Math.max(-1.0, Math.min(1.0, moneyness));
            }
            if (shock.front()) {
                vol += FRONT_SHOCK * Math.exp(-years / FRONT_DECAY_YEARS);
            }
            vol = Math.max(MIN_VOL, vol);
            com.sbk.optionspricer.OptionType type = call ? com.sbk.optionspricer.OptionType.CALL : com.sbk.optionspricer.OptionType.PUT;
            double base = BlackScholesPricer.price(type, spot, strike, years, r, baseVol, q);
            double shocked = BlackScholesPricer.price(type, shockedSpot, strike, years, r, vol, q);
            return units * (shocked - base);
        }
    }
}
