import { afterEach, describe, expect, it, vi } from 'vitest';
import { canonicalApiPath, secureFetch, securePost } from './api';

describe('api paths', () => {
    it('prefixes /api exactly once', () => {
        expect(canonicalApiPath('/risk')).toBe('/api/risk');
        expect(canonicalApiPath('risk')).toBe('/api/risk');
        expect(canonicalApiPath('/api/risk')).toBe('/api/risk');
        expect(canonicalApiPath('/api')).toBe('/api');
        expect(canonicalApiPath('/surface3d?model=SSVI')).toBe('/api/surface3d?model=SSVI');
    });
});

describe('requests', () => {
    afterEach(() => vi.unstubAllGlobals());

    it('reads with same-origin credentials so the session cookie is sent', async () => {
        const fetchMock = vi.fn().mockResolvedValue({ ok: true });
        vi.stubGlobal('fetch', fetchMock);

        await secureFetch('/spot');

        expect(fetchMock).toHaveBeenCalledWith('/api/spot', { credentials: 'same-origin' });
    });

    it('posts JSON with same-origin credentials for state changes', async () => {
        const fetchMock = vi.fn().mockResolvedValue({ ok: true });
        vi.stubGlobal('fetch', fetchMock);

        await securePost('/control/strategy', { enabled: false });

        const [url, init] = fetchMock.mock.calls[0];
        expect(url).toBe('/api/control/strategy');
        expect(init.method).toBe('POST');
        expect(init.credentials).toBe('same-origin');
        expect(init.headers['Content-Type']).toBe('application/json');
        expect(JSON.parse(init.body)).toEqual({ enabled: false });
    });

    it('sends an empty object when a POST has no body', async () => {
        const fetchMock = vi.fn().mockResolvedValue({ ok: true });
        vi.stubGlobal('fetch', fetchMock);

        await securePost('/control/resume');

        expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({});
    });
});
