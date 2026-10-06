import { useEffect, useState, useRef } from 'react';
import './App.css';

const strikes = [490, 495, 500, 505, 510];

function canonicalApiPath(rawPath) {
    const path = rawPath.startsWith('/') ? rawPath : `/${rawPath}`;
    return path.startsWith('/api/') || path === '/api' ? path : `/api${path}`;
}

async function secureFetch(pathWithQuery) {
    return fetch(canonicalApiPath(pathWithQuery), { credentials: 'same-origin' });
}

function formatNumber(num) {
    return Number.isFinite(num)
        ? new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(num)
        : '--';
}

function formatCurrency(num) {
    return Number.isFinite(num)
        ? '$' + new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(num)
        : '--';
}

function App() {
  const [metrics, setMetrics] = useState({ 
    netDelta: null, netGamma: null, netVega: null, scenarioMargin: null,
    trackedNetDelta: null, trackedNetGamma: null, trackedNetVega: null, trackedNotional: null,
    recommendedHedge: null, optimizedMargin: null, marginReductionPct: null,
    l3FillProb: null, sorAllocations: null
  });
  const [tape, setTape] = useState([]);
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

  const marketRibbon = [
      {
          name: 'SPY',
          value: spotInfo.spotPrice !== null && Number.isFinite(spotInfo.spotPrice) ? formatNumber(spotInfo.spotPrice) : '--',
          change: spotInfo.status === 'LIVE' ? '+LIVE' : spotInfo.status === 'DELAYED' ? '+DELAYED' : 'UNAVAILABLE',
          tone: spotInfo.status === 'LIVE' ? 'ticker-up' : spotInfo.status === 'DELAYED' ? 'ticker-up' : 'ticker-down'
      },
      {
          name: 'SOURCE',
          value: spotInfo.source || 'N/A',
          change: spotInfo.status || 'UNAVAILABLE',
          tone: spotInfo.status === 'LIVE' ? 'ticker-up' : spotInfo.status === 'DELAYED' ? 'ticker-up' : 'ticker-down'
      },
      {
          name: 'GATEWAY',
          value: 'LIVE',
          change: healthInfo.providers && Object.keys(healthInfo.providers).length > 0 ? 'STATUS OK' : 'WAITING',
          tone: 'ticker-up'
      }
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
      } catch (err) {
          setLoginError('Connection error during authentication.');
      }
  };

  const handleLogout = async () => {
      try {
          await fetch('/logout', {
              method: 'POST',
              credentials: 'same-origin'
          });
      } catch (err) {
          // Ignore network errors on logout
      }
      setAuthenticated(false);
      setOperatorPassword('');
      setLoginError('');
  };

  const addLog = (msg) => {
      setLogs(prev => {
          const time = new Date().toISOString().split('T')[1].substring(0, 8);
          const newLogs = [{ time, msg }, ...prev];
          if (newLogs.length > 8) newLogs.pop();
          return newLogs;
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
          addLog("TICK: High-frequency market tape & SOR routing replay.");
      } else {
          addLog(`UNKNOWN FUNCTION: ${cleanCmd}`);
      }
  };

  const triggerHotkey = (keyName) => {
      setCmdText(`${keyName} <GO>`);
      addLog(`HOTKEY TRIGGERED: ${keyName} <GO>`);
      if (keyName === 'VOLS') setExpandedChart(null);
  };

  useEffect(() => {
      if (!authenticated) return;
      const updateRiskMetrics = async () => {
          try {
              const response = await secureFetch(`/risk`);
              const data = await response.json();
              setMetrics(data);
          } catch (error) {
              addLog("DESYNC: Retrying Mmap IPC reader...");
          }
      };

      const fetchSpotData = async () => {
          try {
              const response = await secureFetch(`/spot`);
              const data = await response.json();
              setSpotInfo(data);
          } catch (error) {
              // fallback
          }
      };

      const fetchSurfaceData = async () => {
          try {
              const response = await secureFetch(`/surface3d?model=${surfaceModel}`);
              const data = await response.json();
              setSurfaceData(data);
              addLog(`Vol Surface updated [${surfaceModel}].`);
          } catch (error) {
              addLog("Failed to map Volatility Surface.");
          }
      };

      const fetchHealthData = async () => {
          try {
              const response = await secureFetch('/health');
              const data = await response.json();
              setHealthInfo(data);
          } catch (error) {
              setHealthInfo({ symbol: 'SPY', providers: {} });
          }
      };

      updateRiskMetrics();
      fetchSpotData();
      fetchSurfaceData();
      fetchHealthData();

      const metricsInterval = setInterval(updateRiskMetrics, 1000);
      const spotInterval = setInterval(fetchSpotData, 2000);
      const surfaceInterval = setInterval(fetchSurfaceData, 5000);
      const healthInterval = setInterval(fetchHealthData, 5000);

      return () => {
          clearInterval(metricsInterval);
          clearInterval(spotInterval);
          clearInterval(surfaceInterval);
          clearInterval(healthInterval);
      };
    }, [authenticated, surfaceModel]);

  useEffect(() => {
      if (!surfaceData || !chartRef3D.current || !chartRefSmile.current || !chartRefTerm.current || !window.Plotly) return;

      const layoutBase = {
          paper_bgcolor: '#0C0F14',
          plot_bgcolor: '#05070A',
          font: { family: 'JetBrains Mono, Roboto Mono, monospace', color: '#00E5FF' },
          colorway: ['#FF9900', '#00E5FF', '#00E676', '#FF3D00', '#E0E6ED']
      };

      // Bloomberg Amber/Cyan Colorscale
      const bbgColorscale = [
          [0.0, '#05070A'],
          [0.2, '#1A1D24'],
          [0.5, '#FF9900'],
          [0.8, '#FF5500'],
          [1.0, '#00E5FF']
      ];

      // 3D Surface
      const zVols = surfaceData.z.map(row => row.map(v => v * 100));
      const trace3D = {
          x: surfaceData.x,
          y: surfaceData.y,
          z: zVols,
          type: 'surface',
          colorscale: bbgColorscale,
          reversescale: false,
          showscale: false,
          contours: {
              z: { show: true, usecolormap: true, highlightcolor: "#FF9900", project: { z: true } }
          }
      };
      const layout3D = {
          ...layoutBase,
          scene: {
              xaxis: { title: 'STRIKE', gridcolor: '#1C232D', backgroundcolor: '#05070A', zerolinecolor: '#FF9900' },
              yaxis: { title: 'EXPIRY', gridcolor: '#1C232D', backgroundcolor: '#05070A', zerolinecolor: '#FF9900' },
              zaxis: { title: 'IV (%)', gridcolor: '#1C232D', backgroundcolor: '#05070A', zerolinecolor: '#FF9900' },
              camera: { eye: { x: 1.4, y: -1.4, z: 0.8 } }
          },
          margin: { t: 0, r: 0, l: 0, b: 0 }
      };
      window.Plotly.react(chartRef3D.current, [trace3D], layout3D, {responsive: true, displayModeBar: false});

      // Smile
      const expiriesIndices = [0, Math.floor(surfaceData.y.length / 2), surfaceData.y.length - 1];
      const tracesSmile = expiriesIndices.map(idx => ({
          x: surfaceData.x,
          y: surfaceData.z[idx].map(v => v * 100),
          type: 'scatter',
          mode: 'lines+markers',
          name: `Exp ${surfaceData.y[idx]}y`,
          line: { width: 2 },
          marker: { size: 5 }
      }));
      const layoutSmile = {
          ...layoutBase,
          margin: { t: 10, r: 20, l: 40, b: 40 },
          xaxis: { title: 'STRIKE', gridcolor: '#1C232D', zerolinecolor: '#FF9900', titlefont: { size: 14 } },
          yaxis: { title: 'IV (%)', gridcolor: '#1C232D', zerolinecolor: '#FF9900', titlefont: { size: 14 } },
          showlegend: true,
          legend: { orientation: 'h', y: -0.3, x: 0.5, xanchor: 'center', font: { size: 14, color: '#FF9900' } }
      };
      window.Plotly.react(chartRefSmile.current, tracesSmile, layoutSmile, {responsive: true, displayModeBar: false});

      // Term Structure
      const midIdx = Math.floor(surfaceData.x.length / 2);
      const strikeIndices = [0, midIdx, surfaceData.x.length - 1];
      const tracesTerm = strikeIndices.map(idx => ({
          x: surfaceData.y,
          y: surfaceData.z.map(row => row[idx] * 100),
          type: 'scatter',
          mode: 'lines+markers',
          name: `K=${surfaceData.x[idx]}`,
          line: { width: 2 },
          marker: { size: 5 }
      }));
      const layoutTerm = {
          ...layoutBase,
          margin: { t: 10, r: 20, l: 40, b: 40 },
          xaxis: { title: 'EXPIRY', gridcolor: '#1C232D', zerolinecolor: '#FF9900', titlefont: { size: 14 } },
          yaxis: { title: 'IV (%)', gridcolor: '#1C232D', zerolinecolor: '#FF9900', titlefont: { size: 14 } },
          showlegend: true,
          legend: { orientation: 'h', y: -0.3, x: 0.5, xanchor: 'center', font: { size: 14, color: '#FF9900' } }
      };
      window.Plotly.react(chartRefTerm.current, tracesTerm, layoutTerm, {responsive: true, displayModeBar: false});

  }, [surfaceData]);

  useEffect(() => {
      setTimeout(() => {
          window.dispatchEvent(new Event('resize'));
      }, 300);
  }, [expandedChart]);

  useEffect(() => {
      // Tape and trade logic removed (AUD-17)
  }, []);

  if (!authenticated) {
      return (
          <main className="login-screen">
              <form className="login-form" onSubmit={handleLogin}>
                  <div className="login-kicker">AURA-OPT / OPERATOR ACCESS</div>
                  <h1>Sign in</h1>
                  <label htmlFor="operator-password">Operator password</label>
                  <input id="operator-password" type="password" autoComplete="current-password" value={operatorPassword}
                      onChange={(event) => setOperatorPassword(event.target.value)} required />
                  {loginError && <p className="login-error" role="alert">{loginError}</p>}
                  <button type="submit">Open dashboard</button>
              </form>
          </main>
      );
  }

  return (
    <div className="app-container">
        {spotInfo.status === 'LIVE' || spotInfo.status === 'DELAYED' ? (
            <div className="banner" style={{ background: '#0A3D2A', color: '#00E676', textAlign: 'center', fontWeight: 'bold', padding: '5px' }}>
                LIVE MARKET DATA — {spotInfo.source}{spotInfo.status === 'DELAYED' ? ' (DELAYED/EOD)' : ''}
            </div>
        ) : spotInfo.status === 'STALE' ? (
            <div className="banner" style={{ background: '#3D2A00', color: '#FF9900', textAlign: 'center', fontWeight: 'bold', padding: '5px' }}>
                STALE MARKET DATA — last quote from {spotInfo.source || 'API'}
            </div>
        ) : spotInfo.status === 'UNAVAILABLE' ? (
            <div className="banner" style={{ background: '#3D0A0A', color: '#FF3D00', textAlign: 'center', fontWeight: 'bold', padding: '5px' }}>
                MARKET DATA FEED UNAVAILABLE — check FINNHUB_KEY / POLYGON_API_KEY / ALPHA_VANTAGE_KEY
            </div>
        ) : (
            <div className="banner" style={{ background: '#FF3D00', color: 'white', textAlign: 'center', fontWeight: 'bold', padding: '5px' }}>
                SIMULATED DATA — set a market-data API key
            </div>
        )}
        {/* Bloomberg Terminal Top Command Header */}
        <header className="sys-header">
            <form onSubmit={handleCmdSubmit} className="cmd-bar">
                <span className="cmd-prompt">AURA-OPT &gt;</span>
                <input 
                  type="text" 
                  className="cmd-input" 
                  value={cmdText} 
                  onChange={(e) => setCmdText(e.target.value)} 
                />
                <span className="cmd-go">&lt;GO&gt;</span>
            </form>

            <div className="function-keys">
                <button type="button" className="fkey" onClick={() => triggerHotkey('HELP')}>F1 HELP</button>
                <button type="button" className="fkey" onClick={() => triggerHotkey('TICK')}>F2 TICK</button>
                <button type="button" className="fkey" onClick={() => triggerHotkey('VOLS')}>F3 VOLS</button>
                <button type="button" className="fkey" onClick={() => triggerHotkey('RISK')}>F4 RISK</button>
                <button type="button" className="fkey" onClick={() => triggerHotkey('MARGIN')}>F5 MARGIN</button>
                <button type="button" className="fkey" onClick={handleLogout} style={{ borderColor: '#FF3D00', color: '#FF3D00' }}>SIGN OUT</button>
            </div>

            <div className="sys-status">
                <span className="status-badge" style={{color: '#FF9900', borderColor: '#FF9900'}}>
                    {spotInfo.symbol}: ${spotInfo.spotPrice ? spotInfo.spotPrice.toFixed(2) : '--'} 
                    [{spotInfo.source ? spotInfo.source : 'N/A'}]
                </span>
                <span className="status-badge" style={{
                    color: (spotInfo.status === 'LIVE' || spotInfo.status === 'DELAYED') ? '#00E676'
                        : spotInfo.status === 'STALE' ? '#FF9900' : '#FF3D00'
                }}>
                    {spotInfo.status}
                </span>
                <span className="status-badge">
                    {spotInfo.timestamp ? new Date(spotInfo.timestamp).toLocaleTimeString() : '--:--:--'}
                </span>
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', marginTop: '10px', justifyContent: 'flex-end' }}>
                {Object.entries(healthInfo.providers || {}).map(([provider, status]) => (
                    <span
                        key={provider}
                        className="status-badge"
                        style={{
                            color: status === 'LIVE' ? '#00E676' : status === 'DELAYED' || status === 'STALE' ? '#FF9900' : '#FF3D00',
                            borderColor: status === 'LIVE' ? '#00E676' : status === 'DELAYED' || status === 'STALE' ? '#FF9900' : '#FF3D00'
                        }}
                    >
                        {provider}: {status}
                    </span>
                ))}
                {Object.keys(healthInfo.providers || {}).length === 0 && (
                    <span className="status-badge" style={{ color: '#FF3D00', borderColor: '#FF3D00' }}>
                        FEEDS: UNAVAILABLE
                    </span>
                )}
            </div>
        </header>

        {/* Main 4-Quadrant Terminal Grid */}
        <div className="layout-grid">
            {/* Left Quadrant: Risk Matrix & MARGIN Optimizer */}
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
                                <td className="align-right mono val-white" id="val-delta">{Number.isFinite(displayedRisk.netDelta) ? formatNumber(displayedRisk.netDelta) : '--'}</td>
                            </tr>
                            <tr id="row-gamma">
                                <td className="val-amber">NET GAMMA</td>
                                <td className="align-right mono val-white" id="val-gamma">{Number.isFinite(displayedRisk.netGamma) ? formatNumber(displayedRisk.netGamma) : '--'}</td>
                            </tr>
                            <tr id="row-vega">
                                <td className="val-amber">NET VEGA</td>
                                <td className="align-right mono val-white" id="val-vega">{Number.isFinite(displayedRisk.netVega) ? formatNumber(displayedRisk.netVega) : '--'}</td>
                            </tr>
                        </tbody>
                    </table>

                    <div className="margin-block" id="margin-block">
                        <div className="margin-label">SCENARIO MARGIN</div>
                        <div className="margin-value mono" id="val-margin">{Number.isFinite(metrics.scenarioMargin) ? '$' + formatNumber(metrics.scenarioMargin) : '--'}</div>
                        <div style={{ color: '#9EC1FF', fontSize: '11px', marginTop: '6px' }}>
                          EXECUTION BOOK NOTIONAL: <span style={{ color: '#00E5FF', fontWeight: 'bold' }}>{Number.isFinite(displayedRisk.trackedNotional) ? formatCurrency(displayedRisk.trackedNotional) : '--'}</span>
                        </div>
                    </div>

                    {/* Scenario Margin Optimization */}
                    <div style={{marginTop: '10px', background: '#080C10', border: '1px solid #FF9900', padding: '10px'}}>
                        <div style={{color: '#FF9900', fontSize: '15px', fontWeight: 'bold'}}>SCENARIO MARGIN OPTIMIZER [F5]</div>
                        <div style={{color: '#FFFFFF', fontSize: '16px', marginTop: '4px'}}>
                          OPTIMIZED MARGIN: <span style={{color: '#00E676', fontWeight: 'bold'}}>{Number.isFinite(metrics.optimizedMargin) ? '$' + formatNumber(metrics.optimizedMargin) : '--'}</span>
                        </div>
                        <div style={{color: '#00E5FF', fontSize: '15px', marginTop: '2px'}}>
                          REDUCTION: <span style={{fontWeight: 'bold'}}>{Number.isFinite(metrics.marginReductionPct) ? '-' + metrics.marginReductionPct + '%' : '--%'}</span> | HEDGE: <span style={{color: '#FF9900'}}>{Number.isFinite(metrics.recommendedHedge) ? '+' + metrics.recommendedHedge + ' SH' : '-- SH'}</span>
                        </div>
                    </div>

                    <div className="panel-header mt-auto" style={{marginTop: 'auto', borderTop: '1px solid #1C232D'}}>TERMINAL EVENT LOG</div>
                    <div className="log-container" id="sys-logs">
                        {logs.map((log, idx) => (
                            <div key={idx} className="log-entry"><span className="log-time">[{log.time}]</span> {log.msg}</div>
                        ))}
                    </div>
                </div>
            </div>

            {/* Center Quadrant: 3D Vol Surface & 2D Graphs with Model Switcher */}
            <div className="middle-column">
                <div className={`panel chart-panel main-chart ${expandedChart === '3D' ? 'expanded' : ''}`}>
                    <div className="panel-header">
                        <div style={{display: 'flex', gap: '8px', alignItems: 'center'}}>
                            <span>SURFACE ({surfaceModel})</span>
                            <button type="button" className={`fkey ${surfaceModel === 'SSVI' ? 'active' : ''}`} onClick={() => setSurfaceModel('SSVI')}>SSVI</button>
                            <button type="button" className={`fkey ${surfaceModel === 'FREE_SABR' ? 'active' : ''}`} onClick={() => setSurfaceModel('FREE_SABR')}>FREE-SABR</button>
                            <button type="button" className={`fkey ${surfaceModel === 'SABR' ? 'active' : ''}`} onClick={() => setSurfaceModel('SABR')}>SABR 2002</button>
                        </div>
                        <button type="button" className="expand-btn" onClick={() => setExpandedChart(expandedChart === '3D' ? null : '3D')}>
                          {expandedChart === '3D' ? '[SHRINK]' : '[EXPAND]'}
                        </button>
                    </div>
                    <div className="panel-content no-padding">
                        <div id="volatilityChart3D" ref={chartRef3D}></div>
                    </div>
                </div>
                <div className="bottom-charts">
                    <div className={`panel chart-panel sub-chart ${expandedChart === 'SMILE' ? 'expanded' : ''}`}>
                        <div className="panel-header">
                            <span>VOLATILITY SMILE (2D)</span>
                            <button type="button" className="expand-btn" onClick={() => setExpandedChart(expandedChart === 'SMILE' ? null : 'SMILE')}>
                              {expandedChart === 'SMILE' ? '[SHRINK]' : '[EXPAND]'}
                            </button>
                        </div>
                        <div className="panel-content no-padding">
                            <div id="volatilityChartSmile" ref={chartRefSmile}></div>
                        </div>
                    </div>
                    <div className={`panel chart-panel sub-chart ${expandedChart === 'TERM' ? 'expanded' : ''}`}>
                        <div className="panel-header">
                            <span>TERM STRUCTURE (2D)</span>
                            <button type="button" className="expand-btn" onClick={() => setExpandedChart(expandedChart === 'TERM' ? null : 'TERM')}>
                              {expandedChart === 'TERM' ? '[SHRINK]' : '[EXPAND]'}
                            </button>
                        </div>
                        <div className="panel-content no-padding">
                            <div id="volatilityChartTerm" ref={chartRefTerm}></div>
                        </div>
                    </div>
                </div>
            </div>

            {/* Right Quadrant: Live Market Tape & SOR Router Status */}
            <div className="panel tape-panel">
                <div className="panel-header">
                    <span>LIVE TAPE &amp; SOR ROUTER</span>
                    <span className="tag" style={{color: '#00E676'}}>L3 FILL {Number.isFinite(metrics.l3FillProb) ? metrics.l3FillProb + '%' : 'N/A'}</span>
                </div>
                <div style={{background: '#080A0E', borderBottom: '1px solid #1C232D', padding: '6px 8px', fontSize: '14px', color: '#00E5FF'}}>
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
                            {tape.map(t => (
                                <tr key={t.id} className={`${t.isBuy ? 'row-up flash-up' : 'row-down flash-down'}`}>
                                    <td>SPY {t.strike} C</td>
                                    <td className="align-right mono">{t.qty}</td>
                                    <td className="align-right mono">${t.price}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>

        {/* Bottom Terminal Market Status Ribbon */}
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

