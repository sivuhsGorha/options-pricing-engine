import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import RiskPanel from './RiskPanel';

describe('RiskPanel', () => {
    it('formats the Greeks, margin and notional', () => {
        const metrics = { scenarioMargin: 12345.678, optimizedMargin: 10000, marginReductionPct: 19, recommendedHedge: -25 };
        const displayedRisk = { netDelta: 300, netGamma: 1.5, netVega: -12.25, trackedNotional: 30000 };
        render(<RiskPanel metrics={metrics} displayedRisk={displayedRisk} logs={[]} />);

        expect(screen.getByText('300.00')).toBeInTheDocument();
        expect(screen.getByText('-12.25')).toBeInTheDocument();
        expect(screen.getByText('$12,345.68')).toBeInTheDocument();
        expect(screen.getByText('$30,000.00')).toBeInTheDocument();
    });

    it('writes the recommended hedge with one sign, never "+-"', () => {
        const { rerender } = render(<RiskPanel metrics={{ recommendedHedge: -25 }} displayedRisk={{}} logs={[]} />);
        expect(screen.getByText('-25 SH')).toBeInTheDocument();
        expect(screen.queryByText(/\+-/)).not.toBeInTheDocument();

        rerender(<RiskPanel metrics={{ recommendedHedge: 40 }} displayedRisk={{}} logs={[]} />);
        expect(screen.getByText('+40 SH')).toBeInTheDocument();

        rerender(<RiskPanel metrics={{ recommendedHedge: 0 }} displayedRisk={{}} logs={[]} />);
        expect(screen.getByText('0 SH')).toBeInTheDocument();
    });

    it('shows -- rather than NaN or 0 when a value is unknown', () => {
        render(<RiskPanel metrics={{}} displayedRisk={{ netDelta: null, netGamma: NaN, netVega: undefined, trackedNotional: null }} logs={[]} />);

        expect(screen.getAllByText('--').length).toBeGreaterThanOrEqual(5);
        expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
    });

    it('shows theta and rho from the valuation once the book has been marked', () => {
        render(<RiskPanel metrics={{}} displayedRisk={{}} logs={[]} valuation={{ ready: true, asOf: 1, spot: 777.2, netTheta: -1234.5, netRho: 56.78 }} />);

        expect(screen.getByText('-1,234.50')).toBeInTheDocument();
        expect(screen.getByText('56.78')).toBeInTheDocument();
        expect(screen.getByText(/valued .* at spot 777\.20/)).toBeInTheDocument();
    });

    it('renders the event log entries with their time', () => {
        render(<RiskPanel metrics={{}} displayedRisk={{}} logs={[{ time: '09:00:00', msg: 'OPERATOR: trading halted.' }]} />);

        expect(screen.getByText('[09:00:00]')).toBeInTheDocument();
        expect(screen.getByText(/OPERATOR: trading halted\./)).toBeInTheDocument();
    });

    it('shows realised, unrealised, total, today and drawdown from the P&L record', () => {
        const pnl = {
            ready: true, asOf: 1, firstSampleAt: Date.UTC(2026, 9, 8), realizedPnl: 75, unrealizedPnl: -20.5, totalPnl: 54.5,
            day: { date: '2026-10-08', timezone: 'America/New_York', pnl: -12.25, maxDrawdown: 30, baselineIsPreviousClose: true, sampleCount: 12 }
        };
        render(<RiskPanel metrics={{}} displayedRisk={{}} logs={[]} pnl={pnl} />);

        expect(screen.getByText('$75.00')).toBeInTheDocument();
        expect(screen.getByText('-$20.50')).toBeInTheDocument();
        expect(screen.getByText('$54.50')).toBeInTheDocument();
        expect(screen.getByText('-$12.25')).toBeInTheDocument();
        expect(screen.getByText('-$30.00')).toBeInTheDocument();
        expect(screen.getByText('-$20.50').className).toContain('ticker-down');
        expect(screen.getByText('$75.00').className).toContain('ticker-up');
        expect(screen.getByText(/SINCE 2026-10-08/)).toBeInTheDocument();
        expect(screen.getByText(/12 marks today/)).toBeInTheDocument();
    });

    it('explains where the P&L came from, and is honest that shares alone leave only delta', () => {
        const pnl = {
            ready: true, asOf: 1, firstSampleAt: 1, realizedPnl: 0, unrealizedPnl: 0, totalPnl: 0,
            day: { date: '2026-10-14', timezone: 'America/New_York', pnl: 0, maxDrawdown: 0, baselineIsPreviousClose: true, sampleCount: 1 },
            attribution: { cumulativeDeltaPnl: 12.34, cumulativeGammaThetaPnl: -3.21, cumulativeVegaPnl: 4.56, cumulativeResidualPnl: -0.78 }
        };
        const shares = { ready: true, asOf: 1, spot: 777, positions: [{ kind: 'STOCK', quantity: -20 }] };
        const { rerender } = render(<RiskPanel metrics={{}} displayedRisk={{}} logs={[]} pnl={pnl} valuation={shares} />);

        expect(screen.getByText(/WHY P&L MOVED/)).toBeInTheDocument();
        expect(screen.getByText('$12.34')).toBeInTheDocument();
        expect(screen.getByText('-$3.21')).toBeInTheDocument();
        expect(screen.getByText('$4.56')).toBeInTheDocument();
        expect(screen.getByText('-$0.78')).toBeInTheDocument();
        expect(screen.getByText(/no option positions/i)).toBeInTheDocument();

        rerender(<RiskPanel metrics={{}} displayedRisk={{}} logs={[]} pnl={pnl}
            valuation={{ ...shares, positions: [{ kind: 'OPTION', quantity: -1 }] }} />);
        expect(screen.queryByText(/no option positions/i)).not.toBeInTheDocument();
        expect(screen.getByText(/since engine start/i)).toBeInTheDocument();
    });

    it('shows no attribution block when the engine does not send one', () => {
        render(<RiskPanel metrics={{}} displayedRisk={{}} logs={[]} pnl={{ ready: true, asOf: 1, firstSampleAt: 1, realizedPnl: 1, unrealizedPnl: 1, totalPnl: 2,
            day: { date: '2026-10-14', timezone: 'x', pnl: 0, maxDrawdown: 0, sampleCount: 1 } }} />);

        expect(screen.queryByText(/WHY P&L MOVED/)).not.toBeInTheDocument();
    });

    it('says why there is no P&L yet instead of showing zeros', () => {
        render(<RiskPanel metrics={{}} displayedRisk={{}} logs={[]} pnl={{ ready: false, status: 'no valuation recorded yet' }} />);

        expect(screen.getByText('no valuation recorded yet')).toBeInTheDocument();
        expect(screen.queryByText('$0.00')).not.toBeInTheDocument();
    });
});
