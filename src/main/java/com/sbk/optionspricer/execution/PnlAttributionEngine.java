package com.sbk.optionspricer.execution;

import com.sbk.optionspricer.TimeConventions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Real-time quantitative P&L attribution engine.
 *
 * <p>Decomposes portfolio mark-to-market P&L changes across consecutive valuation intervals into:
 * <ul>
 *     <li><b>Delta P&L</b>: $\Delta \Pi_{\Delta} = \text{NetDelta}_{t-1} \cdot \Delta S$</li>
 *     <li><b>Gamma P&L</b>: $\Delta \Pi_{\Gamma} = \frac{1}{2} \text{NetGamma}_{t-1} \cdot (\Delta S)^2$</li>
 *     <li><b>Theta P&L</b>: $\Delta \Pi_{\Theta} = \text{NetTheta}_{t-1} \cdot \Delta t$</li>
 *     <li><b>Gamma/Theta</b>: $\Delta \Pi_{\Gamma\Theta} = \Delta \Pi_{\Gamma} + \Delta \Pi_{\Theta}$</li>
 *     <li><b>Vega P&L</b>: $\Delta \Pi_{\mathcal{V}} = \sum_i Q_i \cdot \mathcal{V}_i \cdot \Delta \sigma_i$</li>
 *     <li><b>Residual</b>: $\Delta \Pi_{\text{residual}} = \Delta \Pi_{\text{actual}} - (\Delta \Pi_{\Delta} + \Delta \Pi_{\Gamma} + \Delta \Pi_{\Theta} + \Delta \Pi_{\mathcal{V}})$</li>
 * </ul>
 */
public final class PnlAttributionEngine {

    /** About a day of five-second valuations; older intervals are dropped, the running totals are kept. */
    public static final int MAX_HISTORY = 20_000;

    private Valuation previousValuation;
    private double cumTotalPnl = 0.0;
    private double cumDeltaPnl = 0.0;
    private double cumGammaPnl = 0.0;
    private double cumThetaPnl = 0.0;
    private double cumVegaPnl = 0.0;
    private double cumResidualPnl = 0.0;

    private final java.util.ArrayDeque<PnlAttribution> history = new java.util.ArrayDeque<>();
    private volatile PnlAttribution latest = PnlAttribution.ZERO;

    public synchronized PnlAttribution update(Valuation current) {
        if (current == null) {
            return latest;
        }
        if (previousValuation == null) {
            previousValuation = current;
            cumTotalPnl = current.unrealizedPnl() + current.realizedPnl();
            latest = new PnlAttribution(current.asOf(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
                    cumTotalPnl, 0.0, 0.0, 0.0, 0.0, 0.0, cumTotalPnl);
            remember(latest);
            return latest;
        }

        double dt = TimeConventions.yearFraction(previousValuation.asOf(), current.asOf());
        double dS = current.spot() - previousValuation.spot();
        double prevTotal = previousValuation.unrealizedPnl() + previousValuation.realizedPnl();
        double currTotal = current.unrealizedPnl() + current.realizedPnl();
        double dTotal = currTotal - prevTotal;

        // 1. Delta P&L: NetDelta * dS
        double deltaPnl = previousValuation.netDelta() * dS;

        // 2. Gamma P&L: 0.5 * NetGamma * (dS)^2
        double gammaPnl = 0.5 * previousValuation.netGamma() * dS * dS;

        // 3. Theta P&L: NetTheta * dt
        double thetaPnl = previousValuation.netTheta() * dt;
        double gammaThetaPnl = gammaPnl + thetaPnl;

        // 4. Vega P&L: sum_i Q_i * vega_i * dVol_i
        double vegaPnl = 0.0;
        for (Valuation.PositionValuation currPos : current.positions()) {
            if (!Double.isFinite(currPos.vega()) || currPos.vega() == 0.0) continue;
            for (Valuation.PositionValuation prevPos : previousValuation.positions()) {
                if (prevPos.symbol().equals(currPos.symbol()) && Double.isFinite(prevPos.impliedVol()) && Double.isFinite(currPos.impliedVol())) {
                    double dVol = currPos.impliedVol() - prevPos.impliedVol();
                    double units = (double) prevPos.quantity() * prevPos.multiplier();
                    vegaPnl += units * prevPos.vega() * dVol;
                    break;
                }
            }
        }

        // 5. Residual P&L: Unexplained remainder
        double explained = deltaPnl + gammaPnl + thetaPnl + vegaPnl;
        double residual = dTotal - explained;

        // Cumulative running totals
        cumTotalPnl += dTotal;
        cumDeltaPnl += deltaPnl;
        cumGammaPnl += gammaPnl;
        cumThetaPnl += thetaPnl;
        cumVegaPnl += vegaPnl;
        cumResidualPnl += residual;

        latest = new PnlAttribution(
                current.asOf(), dS, dt, dTotal,
                deltaPnl, gammaPnl, thetaPnl, gammaThetaPnl, vegaPnl, residual,
                cumTotalPnl, cumDeltaPnl, cumGammaPnl, cumThetaPnl,
                cumGammaPnl + cumThetaPnl, cumVegaPnl, cumResidualPnl
        );

        remember(latest);
        previousValuation = current;
        return latest;
    }

    private void remember(PnlAttribution attribution) {
        history.addLast(attribution);
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
    }

    public synchronized PnlAttribution latest() {
        return latest;
    }

    public synchronized List<PnlAttribution> history() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    public synchronized void reset() {
        previousValuation = null;
        cumTotalPnl = 0.0;
        cumDeltaPnl = 0.0;
        cumGammaPnl = 0.0;
        cumThetaPnl = 0.0;
        cumVegaPnl = 0.0;
        cumResidualPnl = 0.0;
        history.clear();
        latest = PnlAttribution.ZERO;
    }
}
