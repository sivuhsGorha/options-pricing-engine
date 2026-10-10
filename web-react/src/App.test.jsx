import { afterEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import App from './App';

// Found on review (2026-10-10): the app shell had no test, and it kept showing the last numbers under a green banner
// after its session expired, showed TRADING ACTIVE / STRATEGY ON before the engine had answered, said FEEDS OK when
// every provider was rejected, named SPY whatever the configured symbol, and opened with an invented log line.

const respond = (status, body) => Promise.resolve({ ok: status >= 200 && status < 300, status, json: () => Promise.resolve(body) });

/** Answers /login and the routes given (a body, or a function returning a response); every other path is a 404. */
function mockApi(routes) {
    vi.stubGlobal('fetch', vi.fn((url) => {
        const path = String(url);
        if (path === '/login') return respond(200, {});
        const key = Object.keys(routes).find((k) => path.startsWith(k));
        if (!key) return respond(404, {});
        const route = routes[key];
        return typeof route === 'function' ? route() : respond(200, route);
    }));
}

function signIn() {
    render(<App />);
    fireEvent.change(screen.getByLabelText('Operator password'), { target: { value: 'operator-password' } });
    fireEvent.click(screen.getByRole('button', { name: 'Open dashboard' }));
}

afterEach(() => {
    vi.unstubAllGlobals();
});

describe('App', () => {
    it('returns to the sign-in form with a notice when the session has expired', async () => {
        mockApi({ '/api/control': () => respond(401, {}) });

        signIn();

        expect(await screen.findByRole('alert')).toHaveTextContent(/session expired/i);
        expect(screen.getByLabelText('Operator password')).toBeInTheDocument();
        expect(screen.queryByText('TRADING ACTIVE')).not.toBeInTheDocument();
    });

    it('does not claim trading is active before the engine has said so', async () => {
        mockApi({ '/api/control': () => respond(503, { error: 'operator controls unavailable' }) });

        signIn();

        expect(await screen.findByText(/TRADING STATE UNKNOWN/)).toBeInTheDocument();
        expect(screen.queryByText('TRADING ACTIVE')).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'STRATEGY ON' })).toBeDisabled();
    });

    it('says FEEDS OK only when a provider is actually serving a fresh quote', async () => {
        mockApi({ '/api/health': { symbol: 'SPY', providers: { FINNHUB: 'REJECTED', POLYGON: 'UNAVAILABLE' } } });
        signIn();
        expect(await screen.findByText('NO FEED')).toBeInTheDocument();
        expect(screen.queryByText('FEEDS OK')).not.toBeInTheDocument();
    });

    it('says FEEDS OK when one provider is serving', async () => {
        mockApi({ '/api/health': { symbol: 'SPY', providers: { FINNHUB: 'DELAYED', POLYGON: 'REJECTED' } } });
        signIn();
        expect(await screen.findByText('FEEDS OK')).toBeInTheDocument();
    });

    it('names the instrument the engine reports, not a hard-coded one', async () => {
        mockApi({ '/api/spot': { symbol: 'QQQ', spotPrice: 450.12, source: 'FINNHUB', status: 'DELAYED', timestamp: Date.now() } });

        signIn();

        expect(await screen.findByText('QQQ', { selector: '.ticker-name' })).toBeInTheDocument();
        expect(screen.queryByText('SPY', { selector: '.ticker-name' })).not.toBeInTheDocument();
    });

    it('opens with an empty event log, not an invented first line', async () => {
        mockApi({});

        signIn();

        expect(await screen.findByText('TERMINAL EVENT LOG')).toBeInTheDocument();
        expect(screen.queryByText(/Mmap IPC active/)).not.toBeInTheDocument();
        expect(screen.queryByText('[09:00:00]')).not.toBeInTheDocument();
    });
});
