const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** '2026-11-20' -> '20 Nov 26'; anything unparseable is returned as given. */
export function formatExpiry(iso) {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso || '');
    if (!m) return iso || '';
    return `${Number(m[3])} ${MONTHS[Number(m[2]) - 1]} ${m[1].slice(2)}`;
}

/** Human label for a position row: 'SPY 20 Nov 26 780 C' for an option, the ticker for a stock. */
export function describePosition(p) {
    if (p.kind === 'OPTION' && p.expiry && Number.isFinite(p.strike)) {
        const strike = Number.isInteger(p.strike) ? String(p.strike) : p.strike.toFixed(2);
        return `${p.underlying} ${formatExpiry(p.expiry)} ${strike} ${p.type === 'PUT' ? 'P' : 'C'}`;
    }
    return p.symbol;
}
