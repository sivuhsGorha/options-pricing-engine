/**
 * Provenance badge for the volatility surface: what it was fitted to and how well. A fitted-looking
 * surface is never shown unlabelled.
 */
export function surfaceLabel(s) {
    if (!s) return 'LOADING';
    if (s.ready === false) return `${s.status}${s.message ? ': ' + String(s.message).slice(0, 80) : ''}`;
    const rmse = Number.isFinite(s.rmse) ? ` · RMSE ${(s.rmse * 100).toFixed(2)} vol pts` : '';
    if (s.demo) return `DEMO · ${s.source}${rmse}`;
    return `FIT · ${s.source} · ${s.quotesUsed} quotes${rmse}`;
}

/** Green only for a fit to market data; amber for loading or demo; red for a failure. */
export function surfaceTone(s) {
    if (!s) return '#FF9900';
    if (s.ready === false) return '#FF3D00';
    return s.demo ? '#FF9900' : '#00E676';
}
