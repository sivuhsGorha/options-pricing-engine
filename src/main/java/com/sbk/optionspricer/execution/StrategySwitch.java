package com.sbk.optionspricer.execution;

/** Runtime on/off switch for the signal-to-order loop; off means prices are still processed but no orders are generated. */
public interface StrategySwitch {
    boolean isStrategyEnabled();

    void setStrategyEnabled(boolean enabled);
}
