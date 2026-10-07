import { formatNumber, formatCurrency } from '../lib/format';

const timeOf = (ms) => (ms ? new Date(ms).toLocaleTimeString() : '--');

/** Paper-trading state: halt status, open positions, and the most recent orders with reasons for rejections. */
export default function ExecutionPanel({ positions, orders }) {
    const recent = [...orders].reverse().slice(0, 25);
    const accepted = orders.filter(o => o.accepted).length;
    return (
        <div className="panel tape-panel">
            <div className="panel-header">
                <span>PAPER TRADING</span>
                <span className="tag" style={{ color: '#00E676' }}>
                    {accepted} FILLED / {orders.length - accepted} REJECTED
                </span>
            </div>
            <div style={{
                padding: '6px 8px', fontSize: '13px', borderBottom: '1px solid #1C232D',
                background: positions.halted ? '#3D0A0A' : '#0A3D2A', color: positions.halted ? '#FF3D00' : '#00E676'
            }}>
                {positions.halted ? `TRADING HALTED — ${positions.haltReason || 'no reason given'}` : 'TRADING ACTIVE'}
            </div>
            <div className="panel-content no-padding">
                <table className="data-table">
                    <thead>
                        <tr>
                            <th>POSITION</th>
                            <th className="align-right">QTY</th>
                            <th className="align-right">x</th>
                            <th className="align-right">NOTIONAL</th>
                        </tr>
                    </thead>
                    <tbody id="positions-body">
                        {positions.positions.length === 0 && (
                            <tr><td colSpan={4}>No positions yet.</td></tr>
                        )}
                        {positions.positions.map(p => (
                            <tr key={p.symbol}>
                                <td>{p.symbol}</td>
                                <td className="align-right mono">{p.quantity}</td>
                                <td className="align-right mono">{p.multiplier}</td>
                                <td className="align-right mono">{formatCurrency(p.notional)}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
                <table className="data-table tape-table">
                    <thead>
                        <tr>
                            <th>TIME / ORDER</th>
                            <th className="align-right">QTY</th>
                            <th className="align-right">FILL</th>
                            <th>RESULT</th>
                        </tr>
                    </thead>
                    <tbody id="orders-body">
                        {recent.length === 0 && (
                            <tr><td colSpan={4}>No orders yet. The strategy trades when the price moves by the trigger percentage between two quotes.</td></tr>
                        )}
                        {recent.map(o => (
                            <tr key={o.orderId} className={o.accepted ? 'row-up' : 'row-down'}>
                                <td>{timeOf(o.timestamp)} #{o.orderId}</td>
                                <td className="align-right mono">{o.quantity}</td>
                                <td className="align-right mono">{o.accepted ? formatNumber(o.fillPrice) : '--'}</td>
                                <td title={o.rejectionReason || ''}>{o.accepted ? `FILLED (${o.sourceStatus})` : `REJECTED: ${o.rejectionReason || 'unknown'}`}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
