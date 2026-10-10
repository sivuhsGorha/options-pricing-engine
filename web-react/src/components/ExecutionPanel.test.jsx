import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import ExecutionPanel from './ExecutionPanel';

const activeControl = { halted: false, haltReason: null, strategyEnabled: true, symbol: 'SPY', triggerPct: 0.001, baseQuantity: 10 };
const noPositions = { positions: [] };
const noValuation = { ready: false, status: 'no spot price: cannot mark the book' };

describe('ExecutionPanel', () => {
    it('shows TRADING ACTIVE with a HALT button that calls the halt action', () => {
        const onHalt = vi.fn();
        render(<ExecutionPanel positions={noPositions} orders={[]} control={activeControl} valuation={noValuation} onHalt={onHalt} onResume={() => {}} onToggleStrategy={() => {}} />);

        expect(screen.getByText('TRADING ACTIVE')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: 'HALT' }));
        expect(onHalt).toHaveBeenCalledTimes(1);
        expect(screen.queryByRole('button', { name: 'RESUME' })).not.toBeInTheDocument();
    });

    it('shows the halt reason with a RESUME button when halted', () => {
        const onResume = vi.fn();
        const halted = { ...activeControl, halted: true, haltReason: 'operator: end of day' };
        render(<ExecutionPanel positions={noPositions} orders={[]} control={halted} valuation={noValuation} onHalt={() => {}} onResume={onResume} onToggleStrategy={() => {}} />);

        expect(screen.getByText(/TRADING HALTED — operator: end of day/)).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: 'RESUME' }));
        expect(onResume).toHaveBeenCalledTimes(1);
    });

    it('shows the strategy state, trigger and size and offers the opposite switch', () => {
        const onToggle = vi.fn();
        render(<ExecutionPanel positions={noPositions} orders={[]} control={activeControl} valuation={noValuation} onHalt={() => {}} onResume={() => {}} onToggleStrategy={onToggle} />);

        expect(screen.getByText('ON')).toBeInTheDocument();
        expect(screen.getByText(/MOMENTUM · SPY · trigger 0\.100% · size 10/)).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: 'STRATEGY OFF' }));
        expect(onToggle).toHaveBeenCalledTimes(1);
    });

    it('shows the options strategy mode with its latest decision', () => {
        const volSpread = { ...activeControl, mode: 'vol_spread', note: 'HOLD 2026-11-20 775: market 18.2% vs ref 17.4% (edge +0.80 pts) - edge within the 1.00-point band' };
        render(<ExecutionPanel positions={noPositions} orders={[]} control={volSpread} valuation={noValuation} onHalt={() => {}} onResume={() => {}} onToggleStrategy={() => {}} />);

        expect(screen.getByText(/VOL SPREAD · SPY · HOLD 2026-11-20 775: market 18\.2% vs ref 17\.4%/)).toBeInTheDocument();
    });

    it('lists positions with notional and orders with their fill or rejection reason, newest first', () => {
        const positions = { positions: [
            { symbol: 'SPY', kind: 'STOCK', quantity: 10, multiplier: 1, notional: 480250 },
            { symbol: 'SPY261120C00780000', kind: 'OPTION', underlying: 'SPY', expiry: '2026-11-20', strike: 780, type: 'CALL', quantity: -2, multiplier: 100, notional: 1980 },
        ] };
        const orders = [
            { orderId: 1, accepted: true, quantity: 10, fillPrice: 480.25, sourceStatus: 'LIVE', timestamp: 1 },
            { orderId: 2, accepted: false, quantity: 10, rejectionReason: 'market data not tradable: STALE', timestamp: 2 },
        ];
        const valuation = { ready: true, asOf: 1, spot: 480.25, spotSource: 'FINNHUB/DELAYED', unrealizedPnl: 1580, realizedPnl: -75,
            positions: [{ symbol: 'SPY261120C00780000', mark: 9.9, markSource: 'MID', volSource: 'SVI', unrealizedPnl: 1580 }] };
        render(<ExecutionPanel positions={positions} orders={orders} control={activeControl} valuation={valuation} onHalt={() => {}} onResume={() => {}} onToggleStrategy={() => {}} />);

        expect(screen.getByText('$480,250.00')).toBeInTheDocument();
        expect(screen.getByText(/MARKED .* spot 480\.25 \(FINNHUB\/DELAYED\)/)).toBeInTheDocument();
        expect(screen.getAllByText('$1,580.00').length).toBeGreaterThanOrEqual(2, 'the option row and the total');
        expect(screen.getByText('9.90')).toBeInTheDocument();
        expect(screen.getByText('-$75.00')).toBeInTheDocument();
        expect(screen.getByText('SPY 20 Nov 26 780 C')).toBeInTheDocument();
        expect(screen.getByText('$1,980.00')).toBeInTheDocument();
        expect(screen.getByText('1 FILLED / 1 REJECTED')).toBeInTheDocument();
        expect(screen.getByText('FILLED (LIVE)')).toBeInTheDocument();
        expect(screen.getByText('REJECTED: market data not tradable: STALE')).toBeInTheDocument();
        const rows = screen.getAllByRole('row').map(r => r.textContent);
        expect(rows.findIndex(t => t.includes('#2'))).toBeLessThan(rows.findIndex(t => t.includes('#1')));
    });

    it('explains the empty states instead of showing blank tables', () => {
        render(<ExecutionPanel positions={noPositions} orders={[]} control={activeControl} valuation={noValuation} onHalt={() => {}} onResume={() => {}} onToggleStrategy={() => {}} />);

        expect(screen.getByText('No positions yet.')).toBeInTheDocument();
        expect(screen.getByText(/No orders yet/)).toBeInTheDocument();
        expect(screen.getByText(/VALUATION: no spot price/)).toBeInTheDocument();
    });

    it('shows the paper transport by default and the Alpaca transport with its reconciliation', () => {
        const { unmount } = render(<ExecutionPanel positions={noPositions} orders={[]} control={activeControl} valuation={noValuation} onHalt={() => {}} onResume={() => {}} onToggleStrategy={() => {}} />);
        expect(screen.getByText('TRANSPORT PAPER')).toBeInTheDocument();
        expect(screen.queryByText(/ALPACA PAPER/)).not.toBeInTheDocument();
        unmount();

        const alpaca = { ...activeControl, transport: { transport: 'alpaca', venue: 'Alpaca paper account', description: 'day limit orders at the touch',
            checkedAt: 1, reconciliation: 'OK', differences: [] } };
        render(<ExecutionPanel positions={noPositions} orders={[]} control={alpaca} valuation={noValuation} onHalt={() => {}} onResume={() => {}} onToggleStrategy={() => {}} />);
        expect(screen.getByText('TRANSPORT ALPACA · OK')).toBeInTheDocument();
        expect(screen.getByText(/ALPACA PAPER · book reconciled/)).toBeInTheDocument();
    });

    it('shows a book mismatch with the differing positions', () => {
        const mismatch = { ...activeControl, halted: true, haltReason: 'book does not match the Alpaca account: SPY: local 10, alpaca 0',
            transport: { transport: 'alpaca', venue: 'Alpaca paper account', description: 'x', checkedAt: 1, reconciliation: 'MISMATCH', differences: ['SPY: local 10, alpaca 0'] } };
        render(<ExecutionPanel positions={noPositions} orders={[]} control={mismatch} valuation={noValuation} onHalt={() => {}} onResume={() => {}} onToggleStrategy={() => {}} />);

        expect(screen.getByText('TRANSPORT ALPACA · MISMATCH')).toBeInTheDocument();
        expect(screen.getByText('BOOK MISMATCH vs ALPACA: SPY: local 10, alpaca 0')).toBeInTheDocument();
    });

    it('says the trading state is unknown until the engine has reported it, and still offers HALT', () => {
        const onHalt = vi.fn();
        const unknown = { halted: null, haltReason: null, strategyEnabled: null, symbol: null, triggerPct: NaN, baseQuantity: 0 };
        render(<ExecutionPanel positions={noPositions} orders={[]} control={unknown} valuation={noValuation} onHalt={onHalt} onResume={() => {}} onToggleStrategy={() => {}} />);

        expect(screen.getByText(/TRADING STATE UNKNOWN/)).toBeInTheDocument();
        expect(screen.queryByText('TRADING ACTIVE')).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: 'RESUME' })).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: 'HALT' }));
        expect(onHalt).toHaveBeenCalledTimes(1);
        expect(screen.getByRole('button', { name: 'STRATEGY ON' })).toBeDisabled();
        expect(screen.queryByText('ON')).not.toBeInTheDocument();
    });

    it('describes the empty order tape for the options strategy in its own terms', () => {
        render(<ExecutionPanel positions={noPositions} orders={[]} control={{ ...activeControl, mode: 'vol_spread' }} valuation={noValuation} onHalt={() => {}} onResume={() => {}} onToggleStrategy={() => {}} />);

        expect(screen.getByText(/No orders yet\. The strategy trades when the straddle/)).toBeInTheDocument();
    });
});
