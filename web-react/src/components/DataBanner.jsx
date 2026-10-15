const BASE = { textAlign: 'center', fontWeight: 'bold', padding: '5px' };

export default function DataBanner({ spotInfo, engineGap }) {
    const { status, source } = spotInfo;
    let dataBanner = null;
    if (status === 'LIVE' || status === 'DELAYED') {
        dataBanner = (
            <div className="banner" style={{ ...BASE, background: '#0A3D2A', color: '#00E676' }}>
                LIVE MARKET DATA — {source}{status === 'DELAYED' ? ' (DELAYED/EOD)' : ''}
            </div>
        );
    } else if (status === 'STALE') {
        dataBanner = (
            <div className="banner" style={{ ...BASE, background: '#3D2A00', color: '#FF9900' }}>
                STALE MARKET DATA — last quote from {source || 'API'}
            </div>
        );
    } else if (status === 'UNAVAILABLE') {
        dataBanner = (
            <div className="banner" style={{ ...BASE, background: '#3D0A0A', color: '#FF3D00' }}>
                MARKET DATA FEED UNAVAILABLE — check FINNHUB_KEY / POLYGON_API_KEY / ALPHA_VANTAGE_KEY
            </div>
        );
    } else {
        dataBanner = (
            <div className="banner" style={{ ...BASE, background: '#FF3D00', color: 'white' }}>
                SIMULATED DATA — set a market-data API key
            </div>
        );
    }

    if (engineGap && engineGap.hasGap) {
        return (
            <div>
                <div className="banner" style={{ ...BASE, background: '#3b1828', color: '#fca5a5', borderBottom: '1px solid #ef4444' }}>
                    ⚠️ DOWNTIME GAP: {engineGap.message || `Engine was off ${engineGap.durationHours} hours`}
                </div>
                {dataBanner}
            </div>
        );
    }
    return dataBanner;
}
