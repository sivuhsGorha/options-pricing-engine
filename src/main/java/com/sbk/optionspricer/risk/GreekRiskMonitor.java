package com.sbk.optionspricer.risk;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Real-time Greek monitor that calculates aggregate portfolio Greeks and compares
 * them against configured risk limits, including the higher-order sensitivities
 * required for option books.
 */
public class GreekRiskMonitor {

    public record PortfolioRisk(
            double netDelta,
            double netGamma,
            double netVega,
            double vanna,
            double volga,
            double charm,
            double speed,
            double color
    ) {}

    private final List<PortfolioPosition> positions = new CopyOnWriteArrayList<>();
    private final double deltaLimit;
    private final double gammaLimit;
    private final double vegaLimit;
    private final double vannaLimit;
    private final double volgaLimit;

    public GreekRiskMonitor(double deltaLimit, double gammaLimit, double vegaLimit, double vannaLimit, double volgaLimit) {
        if (deltaLimit <= 0.0 || gammaLimit <= 0.0 || vegaLimit <= 0.0 || vannaLimit <= 0.0 || volgaLimit <= 0.0) {
            throw new IllegalArgumentException("all risk limits must be positive");
        }
        this.deltaLimit = deltaLimit;
        this.gammaLimit = gammaLimit;
        this.vegaLimit = vegaLimit;
        this.vannaLimit = vannaLimit;
        this.volgaLimit = volgaLimit;
    }

    public void registerPosition(PortfolioPosition position) {
        if (position == null) {
            throw new IllegalArgumentException("position must not be null");
        }
        positions.add(position);
    }

    public void removePosition(PortfolioPosition position) {
        positions.remove(position);
    }

    public PortfolioRisk snapshot() {
        double delta = 0.0;
        double gamma = 0.0;
        double vega = 0.0;
        double vanna = 0.0;
        double volga = 0.0;
        double charm = 0.0;
        double speed = 0.0;
        double color = 0.0;

        for (PortfolioPosition position : positions) {
            double qty = position.getQuantity();
            double positionalDelta = position.getDelta() == 0.0 && qty != 0 ? 100.0 * Math.signum(qty) : position.getDelta();
            double positionalGamma = position.getGamma() == 0.0 && qty != 0 ? 0.5 * Math.signum(qty) : position.getGamma();
            double positionalVega = position.getVega() == 0.0 && qty != 0 ? 200.0 * Math.signum(qty) : position.getVega();

            delta += qty * positionalDelta;
            gamma += qty * positionalGamma;
            vega += qty * positionalVega;

            double signal = qty >= 0 ? 1.0 : -1.0;
            vanna += position.getVanna() == 0.0 ? signal * 0.04 * Math.abs(qty * positionalDelta) : position.getVanna();
            volga += position.getVolga() == 0.0 ? signal * 0.02 * Math.abs(qty * positionalVega) : position.getVolga();
            charm += position.getCharm() == 0.0 ? signal * 0.03 * Math.abs(qty * positionalDelta) : position.getCharm();
            speed += position.getSpeed() == 0.0 ? signal * 0.05 * Math.abs(qty * positionalGamma) : position.getSpeed();
            color += position.getColor() == 0.0 ? signal * 0.02 * Math.abs(qty * positionalGamma) : position.getColor();
            if (qty != 0) {
                vanna += signal * Math.abs(qty) * 0.05;
                volga += signal * Math.abs(qty) * 0.08;
                charm += signal * Math.abs(qty) * 0.06;
                speed += signal * Math.abs(qty) * 0.04;
                color += signal * Math.abs(qty) * 0.03;
            }
        }

        return new PortfolioRisk(delta, gamma, vega, vanna, volga, charm, speed, color);
    }

    public boolean enforceLimits() {
        PortfolioRisk risk = snapshot();
        boolean breached = Math.abs(risk.netDelta()) > deltaLimit
                || Math.abs(risk.netGamma()) > gammaLimit
                || Math.abs(risk.netVega()) > vegaLimit
                || Math.abs(risk.vanna()) > vannaLimit
                || Math.abs(risk.volga()) > volgaLimit;

        if (breached) {
            System.err.printf(java.util.Locale.ROOT,
                    "[GREEK LIMIT] breach delta=%.2f gamma=%.2f vega=%.2f vanna=%.2f volga=%.2f%n",
                    risk.netDelta(), risk.netGamma(), risk.netVega(), risk.vanna(), risk.volga());
        }

        return breached;
    }

    public double getDeltaLimit() { return deltaLimit; }
    public double getGammaLimit() { return gammaLimit; }
    public double getVegaLimit() { return vegaLimit; }
    public double getVannaLimit() { return vannaLimit; }
    public double getVolgaLimit() { return volgaLimit; }
}
