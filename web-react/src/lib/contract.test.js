import { describe, expect, it } from 'vitest';
import { describePosition, formatExpiry } from './contract';

describe('contract labels', () => {
    it('describes an option position by underlying, expiry, strike and type', () => {
        expect(describePosition({ kind: 'OPTION', symbol: 'SPY261120C00780000', underlying: 'SPY', expiry: '2026-11-20', strike: 780, type: 'CALL' }))
            .toBe('SPY 20 Nov 26 780 C');
        expect(describePosition({ kind: 'OPTION', symbol: 'SPY261120P00782500', underlying: 'SPY', expiry: '2026-11-20', strike: 782.5, type: 'PUT' }))
            .toBe('SPY 20 Nov 26 782.50 P');
    });

    it('leaves a stock as its ticker and tolerates missing fields', () => {
        expect(describePosition({ kind: 'STOCK', symbol: 'SPY' })).toBe('SPY');
        expect(describePosition({ symbol: 'SPY' })).toBe('SPY');
        expect(formatExpiry('not a date')).toBe('not a date');
        expect(formatExpiry(undefined)).toBe('');
    });
});
