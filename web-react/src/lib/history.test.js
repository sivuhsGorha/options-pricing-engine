import { describe, expect, it } from 'vitest';
import { pnlTone, surfaceHistorySeries, surfaceHistorySummary } from './history';

describe('surfaceHistorySeries', () => {
    it('sorts by time and converts to percent and vol points, dropping missing values per series', () => {
        const points = [
            { at: 2000, atmVol: 0.19, skew: 0.012 },
            { at: 1000, atmVol: 0.18, skew: null },
            { at: 3000, atmVol: null, skew: 0.015 },
        ];

        const s = surfaceHistorySeries(points);

        expect(s.count).toBe(3);
        expect(s.atm.y).toEqual([18, 19]);
        expect(s.atm.x.map(d => d.getTime())).toEqual([1000, 2000]);
        expect(s.skew.y.map(v => +v.toFixed(6))).toEqual([1.2, 1.5]);
        expect(s.skew.x.map(d => d.getTime())).toEqual([2000, 3000]);
    });

    it('is empty for no data or malformed data', () => {
        expect(surfaceHistorySeries(undefined).count).toBe(0);
        expect(surfaceHistorySeries([null, { atmVol: 0.2 }]).atm.y).toEqual([]);
    });
});

describe('surfaceHistorySummary', () => {
    it('says when nothing has been recorded', () => {
        expect(surfaceHistorySummary([])).toBe('no calibration recorded yet');
    });

    it('reports the latest ATM vol, its move over the window and the latest skew', () => {
        const text = surfaceHistorySummary([
            { at: 1000, atmVol: 0.180, skew: 0.010 },
            { at: 2000, atmVol: 0.175, skew: 0.012 },
        ]);

        expect(text).toBe('ATM 17.50% (-0.50 over 2 calibrations) · skew 1.20 pts');
    });

    it('omits the skew when no point carries one', () => {
        expect(surfaceHistorySummary([{ at: 1000, atmVol: 0.2, skew: null }])).toBe('ATM 20.00% (+0.00 over 1 calibration)');
    });
});

describe('pnlTone', () => {
    it('colours losses down, gains and zero up, and unknown values not at all', () => {
        expect(pnlTone(-1)).toBe('ticker-down');
        expect(pnlTone(0)).toBe('ticker-up');
        expect(pnlTone(12.5)).toBe('ticker-up');
        expect(pnlTone(null)).toBe('');
        expect(pnlTone(NaN)).toBe('');
    });
});
