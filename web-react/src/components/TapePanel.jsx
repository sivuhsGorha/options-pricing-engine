export default function TapePanel({ metrics }) {
    return (
        <div className="panel tape-panel">
            <div className="panel-header">
                <span>LIVE TAPE &amp; SOR ROUTER</span>
                <span className="tag" style={{ color: '#00E676' }}>L3 FILL {Number.isFinite(metrics.l3FillProb) ? metrics.l3FillProb + '%' : 'N/A'}</span>
            </div>
            <div style={{ background: '#080A0E', borderBottom: '1px solid #1C232D', padding: '6px 8px', fontSize: '14px', color: '#00E5FF' }}>
                SOR: {metrics.sorAllocations != null ? metrics.sorAllocations : 'N/A (no venue connectivity)'}
            </div>
            <div className="panel-content no-padding">
                <table className="data-table tape-table">
                    <thead>
                        <tr>
                            <th>CONTRACT</th>
                            <th className="align-right">QTY</th>
                            <th className="align-right">PRICE</th>
                        </tr>
                    </thead>
                    <tbody id="tape-body">
                        <tr><td colSpan={3}>No trade tape: there is no exchange feed.</td></tr>
                    </tbody>
                </table>
            </div>
        </div>
    );
}
