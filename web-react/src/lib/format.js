const twoDecimals = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export function formatNumber(num) {
    return Number.isFinite(num) ? twoDecimals.format(num) : '--';
}

export function formatCurrency(num) {
    return Number.isFinite(num) ? '$' + twoDecimals.format(num) : '--';
}
