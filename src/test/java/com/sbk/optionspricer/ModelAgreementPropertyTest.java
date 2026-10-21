package com.sbk.optionspricer;

import com.sbk.optionspricer.models.tree.AmericanTreePricer;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Properties that must hold for any sane inputs, checked on a few thousand seeded random ones: the models agree where theory says they must, and
 * an implied volatility is the inverse of the price it came from. A failure prints the seed's parameters, so it can be replayed.
 */
class ModelAgreementPropertyTest {

    private static final int CASES = 2_000;
    private static final long SEED = 20261021L;

    private static OptionParameters random(Random rng) {
        double spot = 20 + rng.nextDouble() * 480;
        double strike = spot * (0.7 + rng.nextDouble() * 0.6);
        double t = 0.02 + rng.nextDouble() * 2.0;
        double r = rng.nextDouble() * 0.08;
        double vol = 0.08 + rng.nextDouble() * 0.7;
        double q = rng.nextDouble() * 0.04;
        return new OptionParameters(spot, strike, t, r, vol, q);
    }

    @Test
    void putCallParityHoldsAcrossRandomInputs() {
        Random rng = new Random(SEED);
        for (int i = 0; i < CASES; i++) {
            OptionParameters p = random(rng);
            double call = BlackScholesPricer.price(OptionType.CALL, p);
            double put = BlackScholesPricer.price(OptionType.PUT, p);
            double forwardValue = p.spot() * Math.exp(-p.dividendYield() * p.timeToExpiry()) - p.strike() * Math.exp(-p.riskFreeRate() * p.timeToExpiry());

            assertEquals(forwardValue, call - put, 1e-8 * Math.max(1.0, p.spot()), p.toString());
        }
    }

    @Test
    void anAmericanOptionIsNeverWorthLessThanTheEuropeanOrItsIntrinsicValue() {
        Random rng = new Random(SEED + 1);
        for (int i = 0; i < 300; i++) {
            OptionParameters p = random(rng);
            for (OptionType type : OptionType.values()) {
                double american = AmericanTreePricer.price(type, p, 200);
                double european = BlackScholesPricer.price(type, p);
                double intrinsic = type == OptionType.CALL ? Math.max(0, p.spot() - p.strike()) : Math.max(0, p.strike() - p.spot());

                assertTrue(american >= european - 0.02 * Math.max(1.0, european), type + " american " + american + " < european " + european + " for " + p);
                assertTrue(american >= intrinsic - 1e-9, type + " american " + american + " < intrinsic " + intrinsic + " for " + p);
            }
        }
    }

    @Test
    void anAmericanCallWithoutDividendsIsWorthTheEuropeanCall() {
        Random rng = new Random(SEED + 2);
        for (int i = 0; i < 200; i++) {
            OptionParameters base = random(rng);
            OptionParameters p = new OptionParameters(base.spot(), base.strike(), base.timeToExpiry(), base.riskFreeRate(), base.volatility(), 0.0);

            double american = AmericanTreePricer.price(OptionType.CALL, p, 300);
            double european = BlackScholesPricer.price(OptionType.CALL, p);

            assertEquals(european, american, 0.01 * Math.max(1.0, european) + 0.01, "early exercise of a call never pays without dividends: " + p);
        }
    }

    @Test
    void theTreeConvergesToBlackScholesForAnEuropeanStylePayoff() {
        // a deep out-of-the-money American call without dividends is the European one; the tree must land on Black-Scholes within a tight band
        Random rng = new Random(SEED + 3);
        for (int i = 0; i < 100; i++) {
            OptionParameters base = random(rng);
            OptionParameters p = new OptionParameters(base.spot(), base.spot() * 1.1, base.timeToExpiry(), base.riskFreeRate(), base.volatility(), 0.0);

            double tree = AmericanTreePricer.price(OptionType.CALL, p, 400);

            assertEquals(BlackScholesPricer.price(OptionType.CALL, p), tree, 0.005 * p.spot(), p.toString());
        }
    }

    @Test
    void anImpliedVolatilityIsTheInverseOfThePriceItCameFrom() {
        Random rng = new Random(SEED + 4);
        int solved = 0;
        for (int i = 0; i < CASES; i++) {
            OptionParameters p = random(rng);
            OptionType type = rng.nextBoolean() ? OptionType.CALL : OptionType.PUT;
            double price = BlackScholesPricer.price(type, p);
            double vega = BlackScholesPricer.vega(p.spot(), p.strike(), p.timeToExpiry(), p.riskFreeRate(), p.volatility(), p.dividendYield());
            if (vega < 1e-3 || price < 1e-6) {
                continue; // a price this insensitive to volatility cannot be inverted, which is a limit of the data, not of the solver
            }

            OptionalDouble iv = ImpliedVolatilitySolver.solve(type, p, price);

            assertTrue(iv.isPresent(), "no solution for a price that has one: " + type + " " + p);
            assertEquals(p.volatility(), iv.getAsDouble(), 1e-4, type + " " + p);
            solved++;
        }
        assertTrue(solved > CASES / 2, "the property must have been exercised on most cases: " + solved);
    }

    @Test
    void thePriceIsNeverNegativeOrNaNAndGreeksAreFinite() {
        Random rng = new Random(SEED + 5);
        for (int i = 0; i < CASES; i++) {
            OptionParameters p = random(rng);
            for (OptionType type : OptionType.values()) {
                double price = BlackScholesPricer.price(type, p);
                Greeks g = BlackScholesPricer.greeks(type, p);

                assertTrue(price >= 0.0 && Double.isFinite(price), type + " price " + price + " for " + p);
                assertTrue(Double.isFinite(g.delta()) && Double.isFinite(g.gamma()) && Double.isFinite(g.vega()) && Double.isFinite(g.theta()) && Double.isFinite(g.rho()), p.toString());
                assertTrue(g.gamma() >= 0.0 && g.vega() >= 0.0, "gamma and vega of a long option are never negative: " + p);
            }
        }
    }

    @Test
    void theVectorisedPricerAgreesWithTheScalarOne() {
        Random rng = new Random(SEED + 6);
        for (int round = 0; round < 50; round++) {
            double spot = 50 + rng.nextDouble() * 400;
            double t = 0.05 + rng.nextDouble();
            double r = rng.nextDouble() * 0.06;
            double vol = 0.1 + rng.nextDouble() * 0.5;
            double[] strikes = new double[37];
            for (int k = 0; k < strikes.length; k++) strikes[k] = spot * (0.7 + 0.6 * rng.nextDouble());
            double[] out = new double[strikes.length];

            VectorBlackScholesPricer.priceBatchPreallocated(spot, strikes, t, r, vol, true, out);

            for (int k = 0; k < strikes.length; k++) {
                assertEquals(BlackScholesPricer.price(OptionType.CALL, spot, strikes[k], t, r, vol, 0.0), out[k], 1e-6 * Math.max(1.0, spot), "strike " + strikes[k]);
            }
        }
    }
}
