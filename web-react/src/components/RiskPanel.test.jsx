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

    it('shows -- rather than NaN or 0 when a value is unknown', () => {
        render(<RiskPanel metrics={{}} displayedRisk={{ netDelta: null, netGamma: NaN, netVega: undefined, trackedNotional: null }} logs={[]} />);

        expect(screen.getAllByText('--').length).toBeGreaterThanOrEqual(5);
        expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
    });

    it('renders the event log entries with their time', () => {
        render(<RiskPanel metrics={{}} displayedRisk={{}} logs={[{ time: '09:00:00', msg: 'OPERATOR: trading halted.' }]} />);

        expect(screen.getByText('[09:00:00]')).toBeInTheDocument();
        expect(screen.getByText(/OPERATOR: trading halted\./)).toBeInTheDocument();
    });
});
