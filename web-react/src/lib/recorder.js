/**
 * The footer label for the engine's option-chain recorder, from the recorder block of /api/health; null when the
 * engine does not report one. A feed that has not changed (overnight, weekends) is skipped on purpose, so a recorder
 * that has not written for hours is not by itself a fault, and the label says "skipped (feed unchanged)".
 */
export function recorderLabel(recorder) {
    if (!recorder) return null;
    if (recorder.status === 'OFF') return { value: 'OFF', tone: '' };
    if (recorder.status !== 'ON') return { value: 'UNAVAILABLE', tone: 'ticker-down' };
    const files = recorder.filesWritten || 0;
    if (files === 0) return { value: 'ON · waiting for the first change in the feed', tone: 'ticker-up' };
    const last = recorder.lastWrittenAt ? new Date(recorder.lastWrittenAt).toLocaleTimeString() : '--';
    return {
        value: `ON · ${files} ${files === 1 ? 'file' : 'files'} · ${recorder.skippedUnchanged || 0} skipped (feed unchanged) · last ${last}`,
        tone: 'ticker-up',
    };
}
