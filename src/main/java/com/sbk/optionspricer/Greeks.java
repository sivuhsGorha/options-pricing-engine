package com.sbk.optionspricer;

/**
 * The option Greeks — sensitivities of the option price to each input.
 *
 * @param delta sensitivity to a $1 move in the underlying
 * @param gamma sensitivity of delta itself to a $1 move in the underlying
 * @param vega  sensitivity to a 1.00 (100 percentage point) move in volatility —
 *              divide by 100 for the conventional "per 1% vol" quote
 * @param theta sensitivity to the passage of one year of time (will be negative
 *              for a long option) — divide by 365 for the conventional "per day" quote
 * @param rho   sensitivity to a 1.00 (100 percentage point) move in the risk-free rate —
 *              divide by 100 for the conventional "per 1% rate" quote
 */
public record Greeks(double delta, double gamma, double vega, double theta, double rho) {
}
