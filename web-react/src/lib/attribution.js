const finiteOrNull = (v) => (Number.isFinite(v) ? v : null);

/**
 * The "why did P&L move" rows from the attribution the engine sends inside /api/pnl, or null when it sends none.
 * The figures are cumulative since the engine started (they are kept in memory, not in the ledger). With only
 * shares on the book just the delta row can be non-zero, and the caption says so rather than let a column of
 * zeros look like an explanation.
 */
export function attributionRows(pnl, valuation) {
    if (!pnl || !pnl.ready || !pnl.attribution) return null;
    const a = pnl.attribution;
    const optionsHeld = Boolean(valuation && valuation.ready && Array.isArray(valuation.positions)
        && valuation.positions.some(p => p.kind === 'OPTION' && p.quantity !== 0));
    return {
        optionsHeld,
        rows: [
            { label: 'DELTA', value: finiteOrNull(a.cumulativeDeltaPnl), title: 'net delta times the move in spot, interval by interval' },
            { label: 'GAMMA / THETA', value: finiteOrNull(a.cumulativeGammaThetaPnl), title: 'convexity gained or lost on the move in spot, and time decay' },
            { label: 'VEGA', value: finiteOrNull(a.cumulativeVegaPnl), title: 'vega times the change in each option\'s implied volatility' },
            { label: 'OTHER', value: finiteOrNull(a.cumulativeResidualPnl),
                title: 'what the Greeks above do not explain: the spread paid on a fill, a position changing between two marks, higher-order terms' },
        ],
        caption: optionsHeld
            ? 'since the engine last started; OTHER holds the spread paid on fills and anything the Greeks do not explain'
            : 'since the engine last started; no option positions held: only delta (the shares) can explain P&L',
    };
}
