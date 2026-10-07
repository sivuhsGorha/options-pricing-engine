import { formatNumber, formatCurrency } from '../lib/format';

export default function RiskPanel({ metrics, displayedRisk, logs }) {
    return (
        <div className="panel risk-panel">
            <div className="panel-header">
                <span>PORTFOLIO RISK MATRIX</span>
                <span className="tag">MONITOR [F4]</span>
            </div>
            <div className="panel-content">
                <table className="data-table">
                    <thead>
                        <tr>
                            <th>GREEK / METRIC</th>
                            <th className="align-right">POSITION VALUE</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr id="row-delta">
                            <td className="val-amber">NET DELTA</td>
                            <td className="align-right mono val-white" id="val-delta">{formatNumber(displayedRisk.netDelta)}</td>
                        </tr>
                        <tr id="row-gamma">
                            <td className="val-amber">NET GAMMA</td>
                            <td className="align-right mono val-white" id="val-gamma">{formatNumber(displayedRisk.netGamma)}</td>
                        </tr>
                        <tr id="row-vega">
                            <td className="val-amber">NET VEGA</td>
                            <td className="align-right mono val-white" id="val-vega">{formatNumber(displayedRisk.netVega)}</td>
                        </tr>
                    </tbody>
                </table>

                <div className="margin-block" id="margin-block">
                    <div className="margin-label">SCENARIO MARGIN</div>
                    <div className="margin-value mono" id="val-margin">{formatCurrency(metrics.scenarioMargin)}</div>
                    <div style={{ color: '#9EC1FF', fontSize: '11px', marginTop: '6px' }}>
                        EXECUTION BOOK NOTIONAL: <span style={{ color: '#00E5FF', fontWeight: 'bold' }}>{formatCurrency(displayedRisk.trackedNotional)}</span>
                    </div>
                </div>

                <div style={{ marginTop: '10px', background: '#080C10', border: '1px solid #FF9900', padding: '10px' }}>
                    <div style={{ color: '#FF9900', fontSize: '15px', fontWeight: 'bold' }}>SCENARIO MARGIN OPTIMIZER [F5]</div>
                    <div style={{ color: '#FFFFFF', fontSize: '16px', marginTop: '4px' }}>
                        OPTIMIZED MARGIN: <span style={{ color: '#00E676', fontWeight: 'bold' }}>{formatCurrency(metrics.optimizedMargin)}</span>
                    </div>
                    <div style={{ color: '#00E5FF', fontSize: '15px', marginTop: '2px' }}>
                        REDUCTION: <span style={{ fontWeight: 'bold' }}>{Number.isFinite(metrics.marginReductionPct) ? '-' + metrics.marginReductionPct + '%' : '--%'}</span> | HEDGE: <span style={{ color: '#FF9900' }}>{Number.isFinite(metrics.recommendedHedge) ? '+' + metrics.recommendedHedge + ' SH' : '-- SH'}</span>
                    </div>
                </div>

                <div className="panel-header mt-auto" style={{ marginTop: 'auto', borderTop: '1px solid #1C232D' }}>TERMINAL EVENT LOG</div>
                <div className="log-container" id="sys-logs">
                    {logs.map((log, idx) => (
                        <div key={idx} className="log-entry"><span className="log-time">[{log.time}]</span> {log.msg}</div>
                    ))}
                </div>
            </div>
        </div>
    );
}
