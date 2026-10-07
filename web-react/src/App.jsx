import { useEffect, useState, useRef } from 'react';
import './App.css';
import { secureFetch } from './lib/api';
import { formatNumber } from './lib/format';
import { renderSurfaceCharts } from './lib/surfaceCharts';
import LoginForm from './components/LoginForm';
import DataBanner from './components/DataBanner';
import RiskPanel from './components/RiskPanel';
import ExecutionPanel from './components/ExecutionPanel';
import ChartPanel from './components/ChartPanel';

const SURFACE_MODELS = [
  { id: 'SSVI', label: 'SSVI' },
  { id: 'FREE_SABR', label: 'FREE-SABR' },
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
  const [logs, setLogs] = useState([{ time: '09:00:00', msg: 'AURA-OPT Unified Engine online. Mmap IPC active.' }]);
  const [surfaceData, setSurfaceData] = useState(null);
  const [expandedChart, setExpandedChart] = useState(null);
  const [cmdText, setCmdText] = useState('VOLS <GO>');
  const [surfaceModel, setSurfaceModel] = useState('SSVI'); // 'SSVI' | 'SABR' | 'FREE_SABR'

  const chartRef3D = useRef(null);
  const chartRefSmile = useRef(null);
  const chartRefTerm = useRef(null);

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
    // There is no exchange gateway: orders only fill in the paper-trading adapter.
    { name: 'GATEWAY', value: 'PAPER', change: Object.keys(providers).length > 0 ? 'FEEDS OK' : 'WAITING', tone: 'ticker-up' }
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
      addLog("HELP: Shortcuts -> F1:HELP F2:TICK F3:VOLS F4:RISK F5:MARGIN");
    } else if (cleanCmd.includes('VOLS') || cleanCmd.includes('F3')) {
      setExpandedChart(null);
      addLog("VOLS: Focused Volatility Surface & Smile curves.");
    } else if (cleanCmd.includes('SSVI')) {
      setSurfaceModel('SSVI');
      addLog("MODEL SWITCH: SSVI (Gatheral) Arbitrage-Free Surface.");
    } else if (cleanCmd.includes('SABR')) {
      setSurfaceModel('SABR');
      addLog("MODEL SWITCH: SABR (Hagan 2002) Surface.");
    } else if (cleanCmd.includes('FREE')) {
      setSurfaceModel('FREE_SABR');
      addLog("MODEL SWITCH: Free-Boundary SABR Density Solver.");
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
      (data) => { setSurfaceData(data); addLog(`Vol Surface updated [${surfaceModel}].`); },
      () => addLog("Failed to map Volatility Surface."));
    const fetchPositions = poll('/positions', setPositions, () => {});
    const fetchOrders = poll('/execution', setOrders, () => {});
    const fetchHealthData = poll('/health', setHealthInfo, () => setHealthInfo({ symbol: 'SPY', providers: {} }));

    const jobs = [[updateRiskMetrics, 1000], [fetchSpotData, 2000], [fetchSurfaceData, 5000], [fetchHealthData, 5000], [fetchPositions, 2000], [fetchOrders, 2000]];
    jobs.forEach(([job]) => job());
    const timers = jobs.map(([job, ms]) => setInterval(job, ms));
    return () => timers.forEach(clearInterval);
  }, [authenticated, surfaceModel]);

  useEffect(() => {
    if (!surfaceData || !chartRef3D.current || !chartRefSmile.current || !chartRefTerm.current || !window.Plotly) return;
    renderSurfaceCharts(window.Plotly, surfaceData, {
      surface3d: chartRef3D.current,
      smile: chartRefSmile.current,
      term: chartRefTerm.current
    });
  }, [surfaceData]);

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
        <RiskPanel metrics={metrics} displayedRisk={displayedRisk} logs={logs} />

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
              </div>
            )}
          />
          <div className="bottom-charts">
            <ChartPanel id="volatilityChartSmile" chartRef={chartRefSmile} className="sub-chart" title="VOLATILITY SMILE (2D)"
              expanded={expandedChart === 'SMILE'} onToggle={() => toggleChart('SMILE')} />
            <ChartPanel id="volatilityChartTerm" chartRef={chartRefTerm} className="sub-chart" title="TERM STRUCTURE (2D)"
              expanded={expandedChart === 'TERM'} onToggle={() => toggleChart('TERM')} />
          </div>
        </div>

        <ExecutionPanel positions={positions} orders={orders} />
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
