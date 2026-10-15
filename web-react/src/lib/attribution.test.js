import { describe, expect, it } from 'vitest';
import { attributionRows } from './attribution';

const pnl = (attribution) => ({ ready: true, attribution });
const block = {
    cumulativeDeltaPnl: 12.34, cumulativeGammaThetaPnl: -3.21, cumulativeVegaPnl: 4.56, cumulativeResidualPnl: -0.78,
};

describe('attributionRows', () => {
    it('is absent until the P&L record is ready and carries an attribution', () => {
        expect(attributionRows(undefined, undefined)).toBeNull();
        expect(attributionRows({ ready: false }, undefined)).toBeNull();
        expect(attributionRows({ ready: true }, undefined)).toBeNull();
    });

    it('lists delta, gamma/theta, vega and the unexplained remainder', () => {
        const a = attributionRows(pnl(block), { ready: true, positions: [{ kind: 'OPTION', quantity: -1 }] });

        expect(a.rows.map(r => r.label)).toEqual(['DELTA', 'GAMMA / THETA', 'VEGA', 'OTHER']);
        expect(a.rows.map(r => r.value)).toEqual([12.34, -3.21, 4.56, -0.78]);
        expect(a.optionsHeld).toBe(true);
        expect(a.caption).toMatch(/since the engine last started/i);
        expect(a.rows[3].title).toMatch(/spread/i);
    });

    it('says plainly that only delta can explain anything while the book is shares only', () => {
        const a = attributionRows(pnl(block), { ready: true, positions: [{ kind: 'STOCK', quantity: -20 }] });

        expect(a.optionsHeld).toBe(false);
        expect(a.caption).toMatch(/no option positions/i);
        // the figures restart with the engine, so they differ from "today" in the P&L block above; say so in both cases
        expect(a.caption).toMatch(/since the engine last started/i);
    });

    it('does not claim an option position that has been closed, or one it cannot see', () => {
        expect(attributionRows(pnl(block), { ready: true, positions: [{ kind: 'OPTION', quantity: 0 }] }).optionsHeld).toBe(false);
        expect(attributionRows(pnl(block), undefined).optionsHeld).toBe(false);
    });

    it('leaves an unknown figure unknown instead of showing it as zero', () => {
        const a = attributionRows(pnl({ ...block, cumulativeVegaPnl: null }), undefined);

        expect(a.rows[2].value).toBeNull();
    });
});
