import { describe, expect, it, vi } from 'vitest';
import { displayablePoints, renderSurfaceCharts, smileExpiries } from './surfaceCharts';

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

    it('overlays the other models at the shortest expiry on the smile so fits to the same quotes can be compared', () => {
        const plotly = { react: vi.fn() };
        const surface = { model: 'SSVI', x: strikes, y: [0.1, 0.5], z: [[0.3, 0.2, 0.15, 0.2], [0.25, 0.18, 0.14, 0.17]], fittedExpiries: [0.1, 0.5], points: [] };
        const svi = { model: 'SVI', x: strikes, y: [0.1, 0.5], z: [[0.31, 0.2, 0.15, 0.19], [0.25, 0.18, 0.14, 0.17]] };
        const sabr = { model: 'SABR', x: strikes, y: [0.1, 0.5], z: [[0.29, 0.21, 0.15, 0.21], [0.25, 0.18, 0.14, 0.17]] };

        renderSurfaceCharts(plotly, surface, { surface3d: 'a', smile: 'b', term: 'c' }, [svi, sabr, null]);

        const smileTraces = plotly.react.mock.calls[1][1];
        const names = smileTraces.map(t => t.name);
        expect(names).toContain('SSVI 0.1y');
        expect(names).toContain('SVI 0.1y');
        expect(names).toContain('SABR 0.1y');
        expect(names.filter(n => n.startsWith('SVI')).length).toBe(1, 'other models appear at the shortest expiry only');
        expect(smileTraces.find(t => t.name === 'SABR 0.1y').line.dash).toBe('dot');
    });
});
