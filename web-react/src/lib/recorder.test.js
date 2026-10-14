import { describe, expect, it } from 'vitest';
import { recorderLabel } from './recorder';

describe('recorderLabel', () => {
    it('is absent when the engine does not report a recorder', () => {
        expect(recorderLabel(undefined)).toBeNull();
        expect(recorderLabel(null)).toBeNull();
    });

    it('says off, and says unavailable, without inventing numbers', () => {
        expect(recorderLabel({ status: 'OFF' })).toEqual({ value: 'OFF', tone: '' });
        expect(recorderLabel({ status: 'UNAVAILABLE' })).toEqual({ value: 'UNAVAILABLE', tone: 'ticker-down' });
    });

    it('reports files kept and snapshots skipped because the feed had not moved', () => {
        const label = recorderLabel({ status: 'ON', filesWritten: 12, skippedUnchanged: 40, lastWrittenAt: Date.UTC(2026, 9, 14, 11, 29) });

        expect(label.tone).toBe('ticker-up');
        expect(label.value).toMatch(/^ON · 12 files · 40 skipped \(feed unchanged\) · last /);
    });

    it('says it is waiting, not that it wrote, before the first file', () => {
        const label = recorderLabel({ status: 'ON', filesWritten: 0, skippedUnchanged: 0, lastWrittenAt: null });

        expect(label.value).toBe('ON · waiting for the first change in the feed');
    });

    it('writes a single file in the singular', () => {
        expect(recorderLabel({ status: 'ON', filesWritten: 1, skippedUnchanged: 0, lastWrittenAt: 1 }).value).toMatch(/^ON · 1 file · /);
    });
});
