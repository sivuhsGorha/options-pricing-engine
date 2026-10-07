import { describe, expect, it } from 'vitest';
import { formatCurrency, formatNumber } from './format';

describe('format', () => {
    it('formats finite numbers with two decimals and thousands separators', () => {
        expect(formatNumber(1234.5)).toBe('1,234.50');
        expect(formatCurrency(1234.5)).toBe('$1,234.50');
        expect(formatNumber(0)).toBe('0.00');
    });

    it('shows -- for anything that is not a finite number instead of NaN or null', () => {
        for (const bad of [null, undefined, NaN, Infinity, 'abc']) {
            expect(formatNumber(bad)).toBe('--');
            expect(formatCurrency(bad)).toBe('--');
        }
    });
});
