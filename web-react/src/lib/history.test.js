import { describe, expect, it } from 'vitest';
import { pnlTone, surfaceHistoryLastChange, surfaceHistorySeries, surfaceHistorySummary, surfaceHistoryTitle } from './history';

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

describe('surfaceHistoryLastChange', () => {
    const MIN = 60000;

    it('is empty until two calibrations exist', () => {
        expect(surfaceHistoryLastChange([])).toBe('');
        expect(surfaceHistoryLastChange([{ at: 1000, atmVol: 0.2, skew: 0.01 }])).toBe('');
        expect(surfaceHistoryLastChange(undefined)).toBe('');
    });

    it('reports how the last fit moved ATM vol and skew against the one before, and how far apart they were', () => {
        const text = surfaceHistoryLastChange([
            { at: 0, atmVol: 0.1360, skew: 0.1100 },
            { at: 5 * MIN, atmVol: 0.1324, skew: 0.1084 },
        ]);

        expect(text).toBe('last fit vs 5 min earlier: ATM -0.36, skew -0.16');
    });

    it('says unchanged when the two fits agree, as with the same closed-market chain fitted twice', () => {
        const text = surfaceHistoryLastChange([
            { at: 0, atmVol: 0.13585, skew: 0.11249 },
            { at: 5 * MIN, atmVol: 0.13585, skew: 0.11249 },
        ]);

        expect(text).toBe('last fit unchanged from 5 min earlier');
    });

    it('works from ATM vol alone, uses the two latest points whatever their order, and rounds the gap', () => {
        const text = surfaceHistoryLastChange([
            { at: 15 * MIN + 20000, atmVol: 0.2050, skew: null },
            { at: 0, atmVol: 0.2000, skew: null },
            { at: 10 * MIN, atmVol: 0.2100, skew: null },
        ]);

        expect(text).toBe('last fit vs 5 min earlier: ATM -0.50');
    });
});

describe('surfaceHistoryTitle', () => {
    it('is the summary alone with one calibration, and adds the last change from two', () => {
        expect(surfaceHistoryTitle([])).toBe('HISTORY 24H · no calibration recorded yet');
        expect(surfaceHistoryTitle([{ at: 0, atmVol: 0.2, skew: 0.01 }])).toBe('HISTORY 24H · ATM 20.00% (+0.00 over 1 calibration) · skew 1.00 pts');

        const two = surfaceHistoryTitle([{ at: 0, atmVol: 0.2, skew: 0.01 }, { at: 300000, atmVol: 0.2, skew: 0.01 }]);

        expect(two).toBe('HISTORY 24H · ATM 20.00% (+0.00 over 2 calibrations) · skew 1.00 pts · last fit unchanged from 5 min earlier');
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
