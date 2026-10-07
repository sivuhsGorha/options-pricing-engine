const BASE = { textAlign: 'center', fontWeight: 'bold', padding: '5px' };

export default function DataBanner({ spotInfo }) {
    const { status, source } = spotInfo;
    if (status === 'LIVE' || status === 'DELAYED') {
        return (
            <div className="banner" style={{ ...BASE, background: '#0A3D2A', color: '#00E676' }}>
                LIVE MARKET DATA — {source}{status === 'DELAYED' ? ' (DELAYED/EOD)' : ''}
            </div>
        );
    }
    if (status === 'STALE') {
        return (
            <div className="banner" style={{ ...BASE, background: '#3D2A00', color: '#FF9900' }}>
                STALE MARKET DATA — last quote from {source || 'API'}
            </div>
        );
    }
    if (status === 'UNAVAILABLE') {
        return (
            <div className="banner" style={{ ...BASE, background: '#3D0A0A', color: '#FF3D00' }}>
                MARKET DATA FEED UNAVAILABLE — check FINNHUB_KEY / POLYGON_API_KEY / ALPHA_VANTAGE_KEY
            </div>
        );
    }
    return (
        <div className="banner" style={{ ...BASE, background: '#FF3D00', color: 'white' }}>
            SIMULATED DATA — set a market-data API key
        </div>
    );
}
