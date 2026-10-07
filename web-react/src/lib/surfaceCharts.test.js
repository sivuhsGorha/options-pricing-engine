import { describe, expect, it, vi } from 'vitest';
import { displayablePoints, errorGrid, interpolate, renderSurfaceCharts, residuals, smileExpiries } from './surfaceCharts';

const strikes = [600, 700, 800, 900];

describe('surface chart data selection', () => {
    it('drops quotes outside the drawn strike band and thins each expiry to a readable number', () => {
        const points = [];
        for (let k = 250; k <= 1000; k += 5) points.push({ t: 0.1, strike: k, marketVol: 0.2 });   // 151 quotes, many far outside
        for (let k = 650; k <= 850; k += 50) points.push({ t: 0.5, strike: k, marketVol: 0.18 });   // 5 quotes inside

        const shown = displayablePoints(points, strikes, 40);

        expect(shown.every(p => p.strike >= 600 && p.strike <= 900)).toBe(true);
        expect(shown.filter(p => p.t === 0.1).length).toBeLessThanOrEqual(40);
        expect(shown.filter(p => p.t === 0.1).length).toBeGreaterThan(20);
        expect(shown.filter(p => p.t === 0.5).length).toBe(5);
        expect(displayablePoints([], strikes)).toEqual([]);
        expect(displayablePoints(undefined, strikes)).toEqual([]);
    });

    it('picks smile slices from the expiries that carry quotes, not from interpolated rows', () => {
        const rows = [0.08, 0.13, 0.18, 0.23, 0.28, 0.33, 0.38, 0.43, 0.48];
        expect(smileExpiries(rows, [0.08, 0.23, 0.48])).toEqual([0.08, 0.23, 0.48]);
        expect(smileExpiries(rows, [0.08, 0.15, 0.23, 0.48])).toEqual([0.08, 0.23, 0.48]);
        expect(smileExpiries(rows, [])).toEqual([0.08, 0.28, 0.48]);
        expect(smileExpiries([0.25], [0.25])).toEqual([0.25]);
    });

    it('pins the 3D axes to the surface so distant quotes cannot shrink it', () => {
        const plotly = { react: vi.fn() };
        const surface = {
            x: strikes, y: [0.1, 0.5], z: [[0.3, 0.2, 0.15, 0.2], [0.25, 0.18, 0.14, 0.17]],
            fittedExpiries: [0.1, 0.5],
            points: [{ t: 0.1, strike: 250, marketVol: 0.62 }, { t: 0.1, strike: 700, marketVol: 0.21 }]
        };

        renderSurfaceCharts(plotly, surface, { surface3d: 'a', smile: 'b', term: 'c' });

        const [, traces, layout] = plotly.react.mock.calls[0];
        expect(layout.scene.xaxis.range).toEqual([600, 900]);
        expect(layout.scene.yaxis.range).toEqual([0.1, 0.5]);
        expect(layout.scene.zaxis.range[1]).toBeCloseTo(33, 5);
        expect(traces[1].x).toEqual([700]);
        expect(plotly.react).toHaveBeenCalledTimes(3);
    });

    it('plots each model\'s error against the quotes at the shortest expiry, where fits to the same data differ', () => {
        const plotly = { react: vi.fn() };
        const quotes = [{ t: 0.1, strike: 700, marketVol: 0.21 }, { t: 0.1, strike: 750, marketVol: 0.18 }];
        const surface = { model: 'SSVI', x: strikes, y: [0.1, 0.5], z: [[0.3, 0.2, 0.15, 0.2], [0.25, 0.18, 0.14, 0.17]], fittedExpiries: [0.1, 0.5], points: quotes };
        const svi = { model: 'SVI', x: strikes, y: [0.1, 0.5], z: [[0.31, 0.21, 0.15, 0.19], [0.25, 0.18, 0.14, 0.17]] };

        renderSurfaceCharts(plotly, surface, { surface3d: 'a', smile: 'b', term: 'c' }, [svi, null, surface]);

        const [, smileTraces, layout] = plotly.react.mock.calls[1];
        const errors = smileTraces.filter(t => t.yaxis === 'y2');
        expect(errors.map(t => t.name)).toEqual(['SSVI error', 'SVI error'], 'the selected model once, each other model once');
        expect(errors[0].y[0]).toBeCloseTo(-1.0, 6);   // SSVI 20% at 700 vs quote 21%
        expect(errors[1].y[0]).toBeCloseTo(0.0, 6);    // SVI 21% at 700 matches the quote
        expect(errors[0].y[1]).toBeCloseTo(-0.5, 6);   // SSVI interpolated 17.5% at 750 vs 18%
        expect(layout.yaxis2.domain[1]).toBeLessThan(layout.yaxis.domain[0], 'the error row sits under the smile');
        expect(layout.yaxis2.range[1]).toBeGreaterThan(1.0);
    });

    it('colours each grid node by that model\'s error against the quotes, NaN where nothing is quoted', () => {
        const surface = {
            x: [600, 700, 800, 900], y: [0.1, 0.3, 0.5], fittedExpiries: [0.1, 0.5],
            z: [[0.30, 0.20, 0.15, 0.20], [0.275, 0.19, 0.145, 0.185], [0.25, 0.18, 0.14, 0.17]],
            points: [
                { t: 0.1, strike: 700, marketVol: 0.21 }, { t: 0.1, strike: 800, marketVol: 0.15 },  // short expiry quoted 700..800 only
                { t: 0.5, strike: 600, marketVol: 0.25 }, { t: 0.5, strike: 900, marketVol: 0.17 },  // long expiry quoted 600..900
            ]
        };

        const grid = errorGrid(surface, surface.points);

        expect(grid[0][0]).toBeNaN();                 // 600 at 0.1y: no quote
        expect(grid[0][1]).toBeCloseTo(1.0, 6);       // 20% vs 21%
        expect(grid[0][2]).toBeCloseTo(0.0, 6);
        expect(grid[2][0]).toBeCloseTo(0.0, 6);       // long expiry matches its quotes at the ends
        expect(grid[2][1]).toBeCloseTo(Math.abs(18 - (25 - (25 - 17) / 3)), 6); // vs the quote line interpolated to 700
        expect(grid[1][1]).toBeCloseTo((grid[0][1] + grid[2][1]) / 2, 6, 'the middle row interpolates in time');
        expect(grid[1][0]).toBeNaN();

        const plotly = { react: vi.fn() };
        renderSurfaceCharts(plotly, surface, { surface3d: 'a', smile: 'b', term: 'c' });
        const trace = plotly.react.mock.calls[0][1][0];
        expect(trace.surfacecolor.length).toBe(3);
        expect(trace.cmax).toBe(3);
        expect(trace.showscale).toBe(true);
    });

    it('interpolates linearly within the grid and clamps outside it', () => {
        expect(interpolate([1, 2, 4], [10, 20, 40], 3)).toBe(30);
        expect(interpolate([1, 2, 4], [10, 20, 40], 0)).toBe(10);
        expect(interpolate([1, 2, 4], [10, 20, 40], 9)).toBe(40);
        expect(residuals(null, [{ strike: 1, marketVol: 0.2 }], 0.1)).toEqual([]);
    });
});
