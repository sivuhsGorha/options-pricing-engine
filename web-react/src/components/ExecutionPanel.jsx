import { formatNumber, formatCurrency } from '../lib/format';
import { describePosition } from '../lib/contract';

const timeOf = (ms) => (ms ? new Date(ms).toLocaleTimeString() : '--');

/**
 * Paper-trading state and operator controls: halt status with HALT / RESUME, the strategy switch with its
 * trigger and size, open positions, and the most recent orders with reasons for rejections.
 */
export default function ExecutionPanel({ positions, orders, control, onHalt, onResume, onToggleStrategy }) {
    const recent = [...orders].reverse().slice(0, 25);
    const accepted = orders.filter(o => o.accepted).length;
    const halted = control.halted;
    const triggerText = Number.isFinite(control.triggerPct) ? `${(control.triggerPct * 100).toFixed(3)}%` : '--';
    return (
        <div className="panel tape-panel">
            <div className="panel-header">
                <span>PAPER TRADING</span>
                <span className="tag" style={{ color: '#00E676' }}>
                    {accepted} FILLED / {orders.length - accepted} REJECTED
                </span>
            </div>
            <div style={{
                display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '8px', flexWrap: 'wrap',
                padding: '6px 8px', fontSize: '13px', borderBottom: '1px solid #1C232D',
                background: halted ? '#3D0A0A' : '#0A3D2A', color: halted ? '#FF3D00' : '#00E676'
            }}>
                <span>{halted ? `TRADING HALTED — ${control.haltReason || 'no reason given'}` : 'TRADING ACTIVE'}</span>
                {halted
                    ? <button type="button" className="fkey" style={{ borderColor: '#00E676', color: '#00E676' }} onClick={onResume}>RESUME</button>
                    : <button type="button" className="fkey" style={{ borderColor: '#FF3D00', color: '#FF3D00' }} onClick={onHalt}>HALT</button>}
            </div>
            <div style={{
                display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '8px', flexWrap: 'wrap',
                padding: '6px 8px', fontSize: '12px', borderBottom: '1px solid #1C232D', color: '#9EC1FF'
            }}>
                <span>
                    STRATEGY <span style={{ color: control.strategyEnabled ? '#00E676' : '#FF9900', fontWeight: 'bold' }}>{control.strategyEnabled ? 'ON' : 'OFF'}</span>
                    {' · '}{control.symbol || '--'} · trigger {triggerText} · size {control.baseQuantity || '--'}
                </span>
                <button type="button" className="fkey" onClick={onToggleStrategy}>
                    {control.strategyEnabled ? 'STRATEGY OFF' : 'STRATEGY ON'}
                </button>
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
                                <td title={p.symbol}>{describePosition(p)}</td>
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
