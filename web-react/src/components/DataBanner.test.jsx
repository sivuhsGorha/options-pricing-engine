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

    it('displays downtime gap banner when engineGap hasGap is true', () => {
        render(
            <DataBanner
                spotInfo={{ status: 'LIVE', source: 'POLYGON' }}
                engineGap={{ hasGap: true, durationHours: 16.2, message: 'Engine was off 16.2 hours' }}
            />
        );
        expect(screen.getByText(/⚠️ DOWNTIME GAP: Engine was off 16.2 hours/)).toBeInTheDocument();
        expect(screen.getByText(/LIVE MARKET DATA/)).toBeInTheDocument();
    });

    it('does not display downtime gap when engineGap hasGap is false', () => {
        render(
            <DataBanner
                spotInfo={{ status: 'LIVE', source: 'POLYGON' }}
                engineGap={{ hasGap: false, durationHours: 0 }}
            />
        );
        expect(screen.queryByText(/⚠️ DOWNTIME GAP/)).not.toBeInTheDocument();
        expect(screen.getByText(/LIVE MARKET DATA/)).toBeInTheDocument();
    });
});
