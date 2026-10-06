package com.sbk.optionspricer.execution;

public record ExecutionResult(String symbol, int filledQuantity, double executionPrice, boolean executed, String message) {}
