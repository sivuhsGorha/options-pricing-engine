const pct = (v) => v * 100;

/**
 * Surface history points as chart series: time, ATM vol in percent and skew in vol points. A point without a
 * value for one series is left out of that series only, so a model that could not report skew still charts
 * its ATM vol.
 */
export function surfaceHistorySeries(points) {
    const rows = (Array.isArray(points) ? points : [])
        .filter(p => p && Number.isFinite(p.at))
        .sort((a, b) => a.at - b.at);
    const atm = rows.filter(p => Number.isFinite(p.atmVol));
    const skew = rows.filter(p => Number.isFinite(p.skew));
    return {
        count: rows.length,
        atm: { x: atm.map(p => new Date(p.at)), y: atm.map(p => pct(p.atmVol)) },
        skew: { x: skew.map(p => new Date(p.at)), y: skew.map(p => pct(p.skew)) },
    };
}

/** One line for the chart header: the latest ATM vol and skew, and how ATM vol moved over the window. */
export function surfaceHistorySummary(points) {
    const s = surfaceHistorySeries(points);
    if (!s.atm.y.length) return 'no calibration recorded yet';
    const first = s.atm.y[0];
    const last = s.atm.y[s.atm.y.length - 1];
    const move = last - first;
    const skewLast = s.skew.y.length ? s.skew.y[s.skew.y.length - 1] : NaN;
    const calibrations = `${s.count} calibration${s.count === 1 ? '' : 's'}`;
    return `ATM ${last.toFixed(2)}% (${move >= 0 ? '+' : ''}${move.toFixed(2)} over ${calibrations})`
        + (Number.isFinite(skewLast) ? ` · skew ${skewLast.toFixed(2)} pts` : '');
}

/** CSS class for a signed P&L figure; empty when the figure is unknown. */
export function pnlTone(value) {
    if (!Number.isFinite(value)) return '';
    return value < 0 ? 'ticker-down' : 'ticker-up';
}
