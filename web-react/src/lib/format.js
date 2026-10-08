const twoDecimals = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export function formatNumber(num) {
    return Number.isFinite(num) ? twoDecimals.format(num) : '--';
}

/** Negative amounts read -$75.00, not $-75.00. */
export function formatCurrency(num) {
    if (!Number.isFinite(num)) return '--';
    return (num < 0 ? '-$' : '$') + twoDecimals.format(Math.abs(num));
}
