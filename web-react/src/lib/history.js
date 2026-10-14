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

const signed = (v) => `${v >= 0 ? '+' : ''}${v.toFixed(2)}`;

/**
 * How the latest calibration differs from the one before it: ATM vol and skew moves in vol points, and how many
 * minutes apart the two fits were. Empty until two calibrations exist. "Unchanged" is a real answer: with the
 * market shut, each refit fits the same delayed chain and returns the same surface.
 */
export function surfaceHistoryLastChange(points) {
    const rows = (Array.isArray(points) ? points : [])
        .filter(p => p && Number.isFinite(p.at) && Number.isFinite(p.atmVol))
        .sort((a, b) => a.at - b.at);
    if (rows.length < 2) return '';
    const prev = rows[rows.length - 2];
    const last = rows[rows.length - 1];
    const minutes = Math.max(1, Math.round((last.at - prev.at) / 60000));
    const atm = pct(last.atmVol) - pct(prev.atmVol);
    const skew = Number.isFinite(last.skew) && Number.isFinite(prev.skew) ? pct(last.skew) - pct(prev.skew) : NaN;
    const moved = (d) => Number.isFinite(d) && Math.abs(d) >= 0.005;
    if (!moved(atm) && !moved(skew)) return `last fit unchanged from ${minutes} min earlier`;
    return `last fit vs ${minutes} min earlier: ATM ${signed(atm)}` + (Number.isFinite(skew) ? `, skew ${signed(skew)}` : '');
}

/** The history chart's header: the window summary, then how the latest fit differs from the previous one. */
export function surfaceHistoryTitle(points) {
    const change = surfaceHistoryLastChange(points);
    return `HISTORY 24H · ${surfaceHistorySummary(points)}` + (change ? ` · ${change}` : '');
}

/** CSS class for a signed P&L figure; empty when the figure is unknown. */
export function pnlTone(value) {
    if (!Number.isFinite(value)) return '';
    return value < 0 ? 'ticker-down' : 'ticker-up';
}
