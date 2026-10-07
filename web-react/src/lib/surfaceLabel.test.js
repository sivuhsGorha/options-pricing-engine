import { describe, expect, it } from 'vitest';
import { surfaceLabel, surfaceTone } from './surfaceLabel';

describe('surface provenance badge', () => {
    it('says LOADING before anything has been fetched', () => {
        expect(surfaceLabel(null)).toBe('LOADING');
        expect(surfaceTone(null)).toBe('#FF9900');
    });

    it('shows the calibration status and message while no surface is fitted', () => {
        const s = { ready: false, status: 'FAILED', message: 'no option chain for SPY: Provider YAHOO_FINANCE failed: HTTP 401' };
        expect(surfaceLabel(s)).toBe('FAILED: no option chain for SPY: Provider YAHOO_FINANCE failed: HTTP 401');
        expect(surfaceTone(s)).toBe('#FF3D00');
    });

    it('labels a fit to a synthetic chain as a DEMO, in amber, never green', () => {
        const s = { ready: true, demo: true, source: 'SYNTHETIC', rmse: 0.0, quotesUsed: 28 };
        expect(surfaceLabel(s)).toBe('DEMO · SYNTHETIC · RMSE 0.00 vol pts');
        expect(surfaceTone(s)).toBe('#FF9900');
    });

    it('labels a fit to market data with the provider, quote count and RMSE in vol points', () => {
        const s = { ready: true, demo: false, source: 'YAHOO_FINANCE', rmse: 0.0042, quotesUsed: 61 };
        expect(surfaceLabel(s)).toBe('FIT · YAHOO_FINANCE · 61 quotes · RMSE 0.42 vol pts');
        expect(surfaceTone(s)).toBe('#00E676');
    });

    it('omits the RMSE when the response has none (the fixed-parameter demo)', () => {
        expect(surfaceLabel({ ready: true, demo: true, source: 'DEMO' })).toBe('DEMO · DEMO');
    });
});
