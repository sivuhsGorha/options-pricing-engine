package com.sbk.optionspricer.core;

import com.sbk.optionspricer.execution.OperatorControls;
import com.sbk.optionspricer.execution.StrategyExecutionLoop;
import com.sbk.optionspricer.execution.StrategySwitch;
import com.sbk.optionspricer.execution.TradingHalt;

import java.util.Optional;

/** Operator controls over the live trading halt and the strategy switch, exposed to the dashboard. */
public final class OperatorConsole implements OperatorControls {

    private final TradingHalt tradingHalt;
    private final StrategySwitch strategySwitch;
    private final StrategyExecutionLoop strategyLoop;

    public OperatorConsole(TradingHalt tradingHalt, StrategySwitch strategySwitch, StrategyExecutionLoop strategyLoop) {
        if (tradingHalt == null || strategySwitch == null) {
            throw new IllegalArgumentException("tradingHalt and strategySwitch must not be null");
        }
        this.tradingHalt = tradingHalt;
        this.strategySwitch = strategySwitch;
        this.strategyLoop = strategyLoop;
    }

    @Override
    public ControlState state() {
        Optional<TradingHalt.Reason> reason = tradingHalt.reason();
        return new ControlState(
                tradingHalt.isHalted(),
                reason.map(TradingHalt.Reason::message).orElse(null),
                reason.map(TradingHalt.Reason::at).orElse(null),
                strategySwitch.isStrategyEnabled(),
                strategyLoop == null ? null : strategyLoop.symbol(),
                strategyLoop == null ? Double.NaN : strategyLoop.triggerPct(),
                strategyLoop == null ? 0 : strategyLoop.baseQuantity());
    }

    @Override
    public ControlState halt(String reason) {
        tradingHalt.halt(reason == null || reason.isBlank() ? "halted by operator" : "operator: " + reason.trim());
        return state();
    }

    @Override
    public ControlState resume() {
        tradingHalt.resume();
        return state();
    }

    @Override
    public ControlState setStrategyEnabled(boolean enabled) {
        strategySwitch.setStrategyEnabled(enabled);
        System.out.println("[OPERATOR] strategy " + (enabled ? "enabled" : "disabled"));
        return state();
    }
}
