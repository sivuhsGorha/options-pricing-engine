import { useEffect, useState, useRef } from 'react';
import './App.css';
import { secureFetch, securePost } from './lib/api';
import { formatNumber } from './lib/format';
import { renderSurfaceCharts, renderHistoryChart } from './lib/surfaceCharts';
import { surfaceHistorySummary } from './lib/history';
import { surfaceLabel, surfaceTone } from './lib/surfaceLabel';
import LoginForm from './components/LoginForm';
import DataBanner from './components/DataBanner';
import RiskPanel from './components/RiskPanel';
import ExecutionPanel from './components/ExecutionPanel';
import ChartPanel from './components/ChartPanel';

const SURFACE_MODELS = [
  { id: 'SSVI', label: 'SSVI' },
  { id: 'SVI', label: 'SVI' },
  { id: 'SABR', label: 'SABR 2002' },
];

const isFresh = (status) => status === 'LIVE' || status === 'DELAYED';
const providerColor = (status) => (status === 'LIVE' ? '#00E676' : status === 'DELAYED' || status === 'STALE' ? '#FF9900' : '#FF3D00');

function App() {
  const [metrics, setMetrics] = useState({
    netDelta: null, netGamma: null, netVega: null, scenarioMargin: null,
    trackedNetDelta: null, trackedNetGamma: null, trackedNetVega: null, trackedNotional: null,
    recommendedHedge: null, optimizedMargin: null, marginReductionPct: null,
    l3FillProb: null, sorAllocations: null
  });
  const [positions, setPositions] = useState({ halted: false, haltReason: null, positions: [] });
  const [orders, setOrders] = useState([]);
  const [valuation, setValuation] = useState({ ready: false });
  const [control, setControl] = useState({ halted: false, haltReason: null, strategyEnabled: true, symbol: null, triggerPct: NaN, baseQuantity: 0 });
  const [logs, setLogs] = useState([{ time: '09:00:00', msg: 'AURA-OPT Unified Engine online. Mmap IPC active.' }]);
  const [surfaceData, setSurfaceData] = useState(null);
  const [otherSurfaces, setOtherSurfaces] = useState([]);
  const [surfaceHistory, setSurfaceHistory] = useState(null);
  const [pnl, setPnl] = useState({ ready: false });
  const [expandedChart, setExpandedChart] = useState(null);
  const [cmdText, setCmdText] = useState('VOLS <GO>');
  const [surfaceModel, setSurfaceModel] = useState('SSVI'); // 'SSVI' | 'SABR' | 'FREE_SABR'

  const chartRef3D = useRef(null);
  const chartRefSmile = useRef(null);
  const chartRefTerm = useRef(null);
  const chartRefHistory = useRef(null);

  const [spotInfo, setSpotInfo] = useState({ symbol: 'SPY', spotPrice: null, source: '', status: 'UNAVAILABLE', timestamp: 0 });
  const [healthInfo, setHealthInfo] = useState({ symbol: 'SPY', providers: {} });
  const [authenticated, setAuthenticated] = useState(false);
  const [operatorPassword, setOperatorPassword] = useState('');
  const [loginError, setLoginError] = useState('');

  const providers = healthInfo.providers || {};
  const spotTone = isFresh(spotInfo.status) ? 'ticker-up' : 'ticker-down';
  const marketRibbon = [
    {
      name: 'SPY',
      value: Number.isFinite(spotInfo.spotPrice) ? formatNumber(spotInfo.spotPrice) : '--',
      change: isFresh(spotInfo.status) ? `+${spotInfo.status}` : 'UNAVAILABLE',
      tone: spotTone
    },
    { name: 'SOURCE', value: spotInfo.source || 'N/A', change: spotInfo.status || 'UNAVAILABLE', tone: spotTone },
    // No exchange gateway: orders fill in the in-process simulator or in an Alpaca paper account.
    { name: 'GATEWAY', value: control.transport ? control.transport.transport.toUpperCase() : 'PAPER', change: Object.keys(providers).length > 0 ? 'FEEDS OK' : 'WAITING', tone: 'ticker-up' }
  ];

  const displayedRisk = {
    netDelta: Number.isFinite(metrics.trackedNetDelta) ? metrics.trackedNetDelta : metrics.netDelta,
    netGamma: Number.isFinite(metrics.trackedNetGamma) ? metrics.trackedNetGamma : metrics.netGamma,
    netVega: Number.isFinite(metrics.trackedNetVega) ? metrics.trackedNetVega : metrics.netVega,
    trackedNotional: Number.isFinite(metrics.trackedNotional) ? metrics.trackedNotional : null,
  };

  const handleLogin = async (event) => {
    event.preventDefault();
    setLoginError('');
    const pwd = operatorPassword;
    setOperatorPassword('');
    try {
      const response = await fetch('/login', {
        method: 'POST',
        headers: { 'Content-Type': 'text/plain; charset=utf-8' },
        credentials: 'same-origin',
        body: pwd
      });
      if (response.ok) {
        setAuthenticated(true);
      } else {
        setLoginError(response.status === 429 ? 'Too many attempts. Wait before retrying.' : 'Authentication failed.');
      }
    } catch {
      setLoginError('Connection error during authentication.');
    }
  };

  const handleLogout = async () => {
    try {
      await fetch('/logout', { method: 'POST', credentials: 'same-origin' });
    } catch {
      // Ignore network errors on logout
    }
    setAuthenticated(false);
    setOperatorPassword('');
    setLoginError('');
  };

  const addLog = (msg) => {
    setLogs(prev => {
      const time = new Date().toISOString().split('T')[1].substring(0, 8);
      return [{ time, msg }, ...prev].slice(0, 8);
    });
  };

  const handleCmdSubmit = (e) => {
    e.preventDefault();
    const cleanCmd = cmdText.trim().toUpperCase();
    addLog(`COMMAND: ${cleanCmd}`);

    if (cleanCmd.includes('HELP') || cleanCmd.includes('F1')) {
      addLog("HELP: F1:HELP F2:TICK F3:VOLS F4:RISK F5:MARGIN · HALT / RESUME · STRATEGY ON / STRATEGY OFF · SSVI / SVI / SABR");
    } else if (cleanCmd.includes('RESUME')) {
      resumeTrading();
    } else if (cleanCmd.includes('HALT')) {
      haltTrading();
    } else if (cleanCmd.includes('STRATEGY OFF')) {
      operate('/control/strategy', { enabled: false }, 'strategy switched off');
    } else if (cleanCmd.includes('STRATEGY ON')) {
      operate('/control/strategy', { enabled: true }, 'strategy switched on');
    } else if (cleanCmd.includes('VOLS') || cleanCmd.includes('F3')) {
      setExpandedChart(null);
      addLog("VOLS: Focused Volatility Surface & Smile curves.");
    } else if (cleanCmd.includes('SSVI')) {
      setSurfaceModel('SSVI');
      addLog("MODEL SWITCH: SSVI, one global fit; the badge says whether its no-arbitrage conditions hold.");
    } else if (cleanCmd.includes('SVI')) {
      setSurfaceModel('SVI');
      addLog("MODEL SWITCH: raw SVI, five parameters per expiry.");
    } else if (cleanCmd.includes('SABR')) {
      setSurfaceModel('SABR');
      addLog("MODEL SWITCH: SABR (Hagan 2002) Surface.");
    } else if (cleanCmd.includes('RISK') || cleanCmd.includes('F4') || cleanCmd.includes('F5') || cleanCmd.includes('MARGIN')) {
      addLog("RISK: Live portfolio Greeks & scenario margin optimizer active.");
    } else if (cleanCmd.includes('TICK') || cleanCmd.includes('F2')) {
      addLog("TICK: Paper-trading orders and positions (no exchange feed is connected).");
    } else {
      addLog(`UNKNOWN FUNCTION: ${cleanCmd}`);
    }
  };

  const triggerHotkey = (keyName) => {
    setCmdText(`${keyName} <GO>`);
    addLog(`HOTKEY TRIGGERED: ${keyName} <GO>`);
    if (keyName === 'VOLS') setExpandedChart(null);
  };

  const toggleChart = (name) => setExpandedChart(expandedChart === name ? null : name);

  // Operator actions go through POST endpoints that require the session and the page's origin.
  const operate = async (path, body, label) => {
    try {
      const response = await securePost(path, body);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      setControl(await response.json());
      addLog(`OPERATOR: ${label}.`);
    } catch (error) {
      addLog(`OPERATOR: ${label} failed (${error.message}).`);
    }
  };
  const haltTrading = () => operate('/control/halt', { reason: 'halted from the dashboard' }, 'trading halted');
  const resumeTrading = () => operate('/control/resume', {}, 'trading resumed');
  const toggleStrategy = () => operate('/control/strategy', { enabled: !control.strategyEnabled }, control.strategyEnabled ? 'strategy switched off' : 'strategy switched on');

  useEffect(() => {
    if (!authenticated) return;
    const poll = (path, onData, onError) => async () => {
      try {
        const response = await secureFetch(path);
        if (!response.ok) throw new Error(`${path} returned ${response.status}`);
        onData(await response.json());
      } catch {
        onError();
      }
    };

    const updateRiskMetrics = poll('/risk', setMetrics, () => addLog("DESYNC: Retrying Mmap IPC reader..."));
    const fetchSpotData = poll('/spot', setSpotInfo, () => {});
    const fetchSurfaceData = poll(`/surface3d?model=${surfaceModel}`,
      (data) => { setSurfaceData(data); addLog(`Vol surface [${surfaceModel}]: ${surfaceLabel(data)}`); },
      () => addLog("Failed to map Volatility Surface."));
    // The other two models are drawn on the smile so the differences between fits to the same quotes are visible.
    const fetchOtherSurfaces = async () => {
      const ids = SURFACE_MODELS.map(m => m.id).filter(id => id !== surfaceModel);
      const results = await Promise.all(ids.map(async (id) => {
        try {
          const response = await secureFetch(`/surface3d?model=${id}`);
          return response.ok ? await response.json() : null;
        } catch {
          return null;
        }
      }));
      setOtherSurfaces(results.filter(s => s && s.ready !== false));
    };
    const fetchPositions = poll('/positions', setPositions, () => {});
    const fetchOrders = poll('/execution', setOrders, () => {});
    const fetchControl = poll('/control', setControl, () => {});
    const fetchValuation = poll('/valuation', setValuation, () => {});
    const fetchPnl = poll('/pnl', setPnl, () => {});
    const fetchSurfaceHistory = poll(`/surface/history?model=${surfaceModel}&hours=24`, setSurfaceHistory, () => {});
    const fetchHealthData = poll('/health', setHealthInfo, () => setHealthInfo({ symbol: 'SPY', providers: {} }));

    const jobs = [[updateRiskMetrics, 1000], [fetchSpotData, 2000], [fetchSurfaceData, 5000], [fetchOtherSurfaces, 5000], [fetchHealthData, 5000], [fetchPositions, 2000], [fetchOrders, 2000], [fetchControl, 2000], [fetchValuation, 2000], [fetchPnl, 5000], [fetchSurfaceHistory, 60000]];
    jobs.forEach(([job]) => job());
    const timers = jobs.map(([job, ms]) => setInterval(job, ms));
    return () => timers.forEach(clearInterval);
  }, [authenticated, surfaceModel]);

  useEffect(() => {
    if (!surfaceData || surfaceData.ready === false || !chartRef3D.current || !chartRefSmile.current || !chartRefTerm.current || !window.Plotly) return;
    renderSurfaceCharts(window.Plotly, surfaceData, {
      surface3d: chartRef3D.current,
      smile: chartRefSmile.current,
      term: chartRefTerm.current
    }, otherSurfaces);
  }, [surfaceData, otherSurfaces]);

  useEffect(() => {
    if (!surfaceHistory || !chartRefHistory.current || !window.Plotly) return;
    renderHistoryChart(window.Plotly, chartRefHistory.current, surfaceHistory.points, surfaceModel);
  }, [surfaceHistory, surfaceModel]);

  useEffect(() => {
    const timer = setTimeout(() => window.dispatchEvent(new Event('resize')), 300);
    return () => clearTimeout(timer);
  }, [expandedChart]);

  if (!authenticated) {
    return <LoginForm password={operatorPassword} onPasswordChange={setOperatorPassword} error={loginError} onSubmit={handleLogin} />;
  }

  return (
    <div className="app-container">
      <DataBanner spotInfo={spotInfo} />
      {/* Terminal command header */}
      <header className="sys-header">
        <form onSubmit={handleCmdSubmit} className="cmd-bar">
          <span className="cmd-prompt">AURA-OPT &gt;</span>
          <input type="text" className="cmd-input" value={cmdText} onChange={(e) => setCmdText(e.target.value)} />
          <span className="cmd-go">&lt;GO&gt;</span>
        </form>

        <div className="function-keys">
          {['HELP', 'TICK', 'VOLS', 'RISK', 'MARGIN'].map((key, i) => (
            <button key={key} type="button" className="fkey" onClick={() => triggerHotkey(key)}>F{i + 1} {key}</button>
          ))}
          <button type="button" className="fkey" onClick={handleLogout} style={{ borderColor: '#FF3D00', color: '#FF3D00' }}>SIGN OUT</button>
        </div>

        <div className="sys-status">
          <span className="status-badge" style={{ color: '#FF9900', borderColor: '#FF9900' }}>
            {spotInfo.symbol}: ${spotInfo.spotPrice ? spotInfo.spotPrice.toFixed(2) : '--'} [{spotInfo.source ? spotInfo.source : 'N/A'}]
          </span>
          {Number.isFinite(spotInfo.bid) && Number.isFinite(spotInfo.ask) && (
            <span className="status-badge" title="bid / ask from the provider's book">
              {spotInfo.bid.toFixed(2)} / {spotInfo.ask.toFixed(2)}
            </span>
          )}
          {Number.isFinite(spotInfo.volume) && (
            <span className="status-badge" title="volume reported by the provider">VOL {spotInfo.volume.toLocaleString()}</span>
          )}
          <span className="status-badge" style={{ color: isFresh(spotInfo.status) ? '#00E676' : spotInfo.status === 'STALE' ? '#FF9900' : '#FF3D00' }}>
            {spotInfo.status}
          </span>
          <span className="status-badge">
            {spotInfo.timestamp ? new Date(spotInfo.timestamp).toLocaleTimeString() : '--:--:--'}
          </span>
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', marginTop: '10px', justifyContent: 'flex-end' }}>
          {Object.entries(providers).map(([provider, status]) => (
            <span key={provider} className="status-badge" style={{ color: providerColor(status), borderColor: providerColor(status) }}>
              {provider}: {status}
            </span>
          ))}
          {Object.keys(providers).length === 0 && (
            <span className="status-badge" style={{ color: '#FF3D00', borderColor: '#FF3D00' }}>FEEDS: UNAVAILABLE</span>
          )}
        </div>
      </header>

      <div className="layout-grid">
        <RiskPanel metrics={metrics} displayedRisk={displayedRisk} logs={logs} valuation={valuation} pnl={pnl} />

        <div className="middle-column">
          <ChartPanel
            id="volatilityChart3D" chartRef={chartRef3D} className="main-chart"
            expanded={expandedChart === '3D'} onToggle={() => toggleChart('3D')}
            headerContent={(
              <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                <span>SURFACE ({surfaceModel})</span>
                {SURFACE_MODELS.map(({ id, label }) => (
                  <button key={id} type="button" className={`fkey ${surfaceModel === id ? 'active' : ''}`} onClick={() => setSurfaceModel(id)}>{label}</button>
                ))}
                <span className="status-badge" style={{ color: surfaceTone(surfaceData), borderColor: surfaceTone(surfaceData) }}
                  title={surfaceData && surfaceData.warnings && surfaceData.warnings.length ? surfaceData.warnings.join('\n') : 'where this surface comes from'}>
                  {surfaceLabel(surfaceData)}
                </span>
              </div>
            )}
          />
          <div className="bottom-charts">
            <ChartPanel id="volatilityChartSmile" chartRef={chartRefSmile} className="sub-chart" title="SMILE · AND EACH MODEL'S ERROR VS QUOTES"
              expanded={expandedChart === 'SMILE'} onToggle={() => toggleChart('SMILE')} />
            <ChartPanel id="volatilityChartTerm" chartRef={chartRefTerm} className="sub-chart" title="TERM STRUCTURE (2D)"
              expanded={expandedChart === 'TERM'} onToggle={() => toggleChart('TERM')} />
            <ChartPanel id="volatilityChartHistory" chartRef={chartRefHistory} className="sub-chart"
              title={`HISTORY 24H · ${surfaceHistorySummary(surfaceHistory ? surfaceHistory.points : [])}`}
              expanded={expandedChart === 'HISTORY'} onToggle={() => toggleChart('HISTORY')} />
          </div>
        </div>

        <ExecutionPanel positions={positions} orders={orders} control={control} valuation={valuation} onHalt={haltTrading} onResume={resumeTrading} onToggleStrategy={toggleStrategy} />
      </div>

      <footer className="sys-footer">
        {marketRibbon.map((item) => (
          <div key={item.name} className="ticker-item">
            <span className="ticker-name">{item.name}</span>
            <span className="ticker-val">{item.value}</span>
            <span className={item.tone}>{item.change}</span>
          </div>
        ))}
      </footer>
    </div>
  );
}

export default App;
