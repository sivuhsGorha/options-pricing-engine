import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import DataBanner from './DataBanner';

describe('DataBanner', () => {
    it('announces live data with its source', () => {
        render(<DataBanner spotInfo={{ status: 'LIVE', source: 'POLYGON' }} />);
        expect(screen.getByText(/LIVE MARKET DATA — POLYGON/)).toBeInTheDocument();
    });

    it('marks delayed data as delayed, not live', () => {
        render(<DataBanner spotInfo={{ status: 'DELAYED', source: 'FINNHUB' }} />);
        expect(screen.getByText(/DELAYED\/EOD/)).toBeInTheDocument();
    });

    it('warns when the last quote is stale', () => {
        render(<DataBanner spotInfo={{ status: 'STALE', source: 'FINNHUB' }} />);
        expect(screen.getByText(/STALE MARKET DATA — last quote from FINNHUB/)).toBeInTheDocument();
    });

    it('says the feed is unavailable and which keys to check', () => {
        render(<DataBanner spotInfo={{ status: 'UNAVAILABLE', source: '' }} />);
        expect(screen.getByText(/MARKET DATA FEED UNAVAILABLE/)).toBeInTheDocument();
    });

    it('never presents simulated data as market data', () => {
        render(<DataBanner spotInfo={{ status: 'SIMULATED', source: 'SIMULATED' }} />);
        expect(screen.getByText(/SIMULATED DATA/)).toBeInTheDocument();
        expect(screen.queryByText(/LIVE MARKET DATA/)).not.toBeInTheDocument();
    });
});
