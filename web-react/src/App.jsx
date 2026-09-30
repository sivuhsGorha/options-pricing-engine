import { useEffect, useState, useRef } from 'react';
import PatternWaves from './PatternWaves';
import './App.css';

const API_BASE = "http://localhost:8080/api";
const strikes = [490, 495, 500, 505, 510];

function formatNumber(num) {
    return new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(num);
}

function App() {
  const [metrics, setMetrics] = useState({ netDelta: null, netGamma: null, netVega: null, spanMargin: null });
  const [tape, setTape] = useState([]);
  const [logs, setLogs] = useState([{ time: '12:00:00', msg: 'Engine started.' }]);
  const [latency, setLatency] = useState(0);
  const [surfaceData, setSurfaceData] = useState(null);
  const [expandedChart, setExpandedChart] = useState(null);
  const chartRef3D = useRef(null);
  const chartRefSmile = useRef(null);
  const chartRefTerm = useRef(null);

  const addLog = (msg) => {
      setLogs(prev => {
          const time = new Date().toISOString().split('T')[1].substring(0, 8);
          const newLogs = [{ time, msg }, ...prev];
          if (newLogs.length > 8) newLogs.pop();
          return newLogs;
      });
  };

  useEffect(() => {
      const updateRiskMetrics = async () => {
          try {
              const response = await fetch(`${API_BASE}/risk`);
              const data = await response.json();
              setMetrics(data);
          } catch (error) {
              addLog("Network desync: Retrying risk engine connection...");
          }
      };

      const fetchSurfaceData = async () => {
          try {
              const response = await fetch(`${API_BASE}/surface3d`);
              const data = await response.json();
              setSurfaceData(data);
              addLog("SABR Model synchronized.");
          } catch (error) {
              addLog("Failed to map Volatility Surface.");
          }
      };

      updateRiskMetrics();
      fetchSurfaceData();

      const metricsInterval = setInterval(updateRiskMetrics, 1000);
      const surfaceInterval = setInterval(fetchSurfaceData, 10000);
      const latencyInterval = setInterval(() => {
          setLatency(Math.floor(Math.random() * 3 + 6));
      }, 1000);

      return () => {
          clearInterval(metricsInterval);
          clearInterval(surfaceInterval);
          clearInterval(latencyInterval);
      };
  }, []);

  useEffect(() => {
      if (!surfaceData || !chartRef3D.current || !chartRefSmile.current || !chartRefTerm.current || !window.Plotly) return;

      const layoutBase = {
          paper_bgcolor: 'rgba(0,0,0,0)',
          plot_bgcolor: 'rgba(0,0,0,0)',
          font: { family: 'Inter', color: '#94a3b8' },
          colorway: ['#38bdf8', '#10b981', '#f43f5e', '#a855f7', '#f59e0b']
      };

      // 3D Surface
      const zVols = surfaceData.z.map(row => row.map(v => v * 100));
      const trace3D = {
          x: surfaceData.x,
          y: surfaceData.y,
          z: zVols,
          type: 'surface',
          colorscale: 'Portland',
          reversescale: false,
          showscale: false,
          contours: {
              z: { show: true, usecolormap: true, highlightcolor: "#ffffff", project: { z: true } }
          }
      };
      const layout3D = {
          ...layoutBase,
          scene: {
              xaxis: { title: 'STRIKE', gridcolor: 'rgba(148, 163, 184, 0.1)', backgroundcolor: 'rgba(0,0,0,0)', zerolinecolor: 'rgba(148, 163, 184, 0.2)' },
              yaxis: { title: 'EXPIRY', gridcolor: 'rgba(148, 163, 184, 0.1)', backgroundcolor: 'rgba(0,0,0,0)', zerolinecolor: 'rgba(148, 163, 184, 0.2)' },
              zaxis: { title: 'IV (%)', gridcolor: 'rgba(148, 163, 184, 0.1)', backgroundcolor: 'rgba(0,0,0,0)', zerolinecolor: 'rgba(148, 163, 184, 0.2)' },
              camera: { eye: { x: 1.5, y: -1.5, z: 0.8 } }
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
          name: `Exp ${surfaceData.y[idx]}`,
          line: { shape: 'spline', smoothing: 1.3, width: 2 },
          marker: { size: 6 }
      }));
      const layoutSmile = {
          ...layoutBase,
          margin: { t: 10, r: 20, l: 40, b: 40 },
          xaxis: { title: 'Strike', gridcolor: 'rgba(148, 163, 184, 0.1)', zerolinecolor: 'rgba(148, 163, 184, 0.2)', titlefont: { size: 10 } },
          yaxis: { title: 'IV (%)', gridcolor: 'rgba(148, 163, 184, 0.1)', zerolinecolor: 'rgba(148, 163, 184, 0.2)', titlefont: { size: 10 } },
          showlegend: true,
          legend: { orientation: 'h', y: -0.3, x: 0.5, xanchor: 'center', font: { size: 10 } }
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
          name: `Str ${surfaceData.x[idx]}`,
          line: { shape: 'spline', smoothing: 1.3, width: 2 },
          marker: { size: 6 }
      }));
      const layoutTerm = {
          ...layoutBase,
          margin: { t: 10, r: 20, l: 40, b: 40 },
          xaxis: { title: 'Expiry', gridcolor: 'rgba(148, 163, 184, 0.1)', zerolinecolor: 'rgba(148, 163, 184, 0.2)', titlefont: { size: 10 } },
          yaxis: { title: 'IV (%)', gridcolor: 'rgba(148, 163, 184, 0.1)', zerolinecolor: 'rgba(148, 163, 184, 0.2)', titlefont: { size: 10 } },
          showlegend: true,
          legend: { orientation: 'h', y: -0.3, x: 0.5, xanchor: 'center', font: { size: 10 } }
      };
      window.Plotly.react(chartRefTerm.current, tracesTerm, layoutTerm, {responsive: true, displayModeBar: false});

  }, [surfaceData]);

  useEffect(() => {
      setTimeout(() => {
          window.dispatchEvent(new Event('resize'));
      }, 300);
  }, [expandedChart]);

  useEffect(() => {
      const generateLiveTrade = () => {
          const isBuy = Math.random() > 0.5;
          const strike = strikes[Math.floor(Math.random() * strikes.length)];
          const price = (Math.random() * 5 + 1).toFixed(2);
          const qty = Math.floor(Math.random() * 500) + 10;
          
          setTape(prev => {
              const newTape = [{ id: Date.now() + Math.random(), isBuy, strike, price, qty }, ...prev];
              if (newTape.length > 15) newTape.pop();
              return newTape;
          });
      };
      const tapeInterval = setInterval(generateLiveTrade, 300);
      return () => clearInterval(tapeInterval);
  }, []);

  return (
    <>
      <PatternWaves
        preset="mesh"
        color="#38bdf8"
        backgroundColor="#020617"
        fade="edges"
        interactive
        cursorSize={50}
        cursorStrength={0.6}
      />
      <div className="app-container">
          <header className="sys-header">
              <div className="sys-title">SBK Options Pricing Engine</div>
              <div className="sys-status">
                  <span className="status-dot"></span>
                  <span className="status-text">CONNECTED</span>
                  <span className="status-ping" id="latency">{latency}ms</span>
              </div>
          </header>

          <div className="layout-grid">
              <div className="panel risk-panel">
                  <div className="panel-header">PORTFOLIO RISK MATRIX</div>
                  <div className="panel-content">
                      <table className="data-table">
                          <thead>
                              <tr>
                                  <th>METRIC</th>
                                  <th className="align-right">VALUE</th>
                              </tr>
                          </thead>
                          <tbody>
                              <tr id="row-delta">
                                  <td>NET DELTA</td>
                                  <td className="align-right mono" id="val-delta">{metrics.netDelta !== null ? formatNumber(metrics.netDelta) : '--'}</td>
                              </tr>
                              <tr id="row-gamma">
                                  <td>NET GAMMA</td>
                                  <td className="align-right mono" id="val-gamma">{metrics.netGamma !== null ? formatNumber(metrics.netGamma) : '--'}</td>
                              </tr>
                              <tr id="row-vega">
                                  <td>NET VEGA</td>
                                  <td className="align-right mono" id="val-vega">{metrics.netVega !== null ? formatNumber(metrics.netVega) : '--'}</td>
                              </tr>
                          </tbody>
                      </table>

                      <div className="margin-block" id="margin-block">
                          <div className="margin-label">SPAN INITIAL MARGIN</div>
                          <div className="margin-value mono" id="val-margin">{metrics.spanMargin !== null ? '$' + formatNumber(metrics.spanMargin) : '--'}</div>
                      </div>

                      <div className="panel-header mt-auto">SYSTEM LOG</div>
                      <div className="log-container" id="sys-logs">
                          {logs.map((log, idx) => (
                              <div key={idx} className="log-entry"><span className="log-time">[{log.time}]</span> {log.msg}</div>
                          ))}
                      </div>
                  </div>
              </div>

              <div className="middle-column">
                  <div className={`panel chart-panel main-chart ${expandedChart === '3D' ? 'expanded' : ''}`}>
                      <div className="panel-header chart-header-row">
                          <span>SABR 3D VOLATILITY SURFACE</span>
                          <button className="expand-btn" onClick={() => setExpandedChart(expandedChart === '3D' ? null : '3D')}>{expandedChart === '3D' ? 'Shrink' : 'Expand'}</button>
                      </div>
                      <div className="panel-content no-padding">
                          <div id="volatilityChart3D" ref={chartRef3D}></div>
                      </div>
                  </div>
                  <div className="bottom-charts">
                      <div className={`panel chart-panel sub-chart ${expandedChart === 'SMILE' ? 'expanded' : ''}`}>
                          <div className="panel-header chart-header-row">
                              <span>VOLATILITY SMILE (2D)</span>
                              <button className="expand-btn" onClick={() => setExpandedChart(expandedChart === 'SMILE' ? null : 'SMILE')}>{expandedChart === 'SMILE' ? 'Shrink' : 'Expand'}</button>
                          </div>
                          <div className="panel-content no-padding">
                              <div id="volatilityChartSmile" ref={chartRefSmile}></div>
                          </div>
                      </div>
                      <div className={`panel chart-panel sub-chart ${expandedChart === 'TERM' ? 'expanded' : ''}`}>
                          <div className="panel-header chart-header-row">
                              <span>TERM STRUCTURE (2D)</span>
                              <button className="expand-btn" onClick={() => setExpandedChart(expandedChart === 'TERM' ? null : 'TERM')}>{expandedChart === 'TERM' ? 'Shrink' : 'Expand'}</button>
                          </div>
                          <div className="panel-content no-padding">
                              <div id="volatilityChartTerm" ref={chartRefTerm}></div>
                          </div>
                      </div>
                  </div>
              </div>

              <div className="panel tape-panel">
                  <div className="panel-header">LIVE MARKET TAPE</div>
                  <div className="panel-content no-padding">
                      <table className="data-table tape-table">
                          <thead>
                              <tr>
                                  <th>INSTRUMENT</th>
                                  <th className="align-right">SIZE</th>
                                  <th className="align-right">PRICE</th>
                              </tr>
                          </thead>
                          <tbody id="tape-body">
                              {tape.map(t => (
                                  <tr key={t.id} className={`tr-transition ${t.isBuy ? 'row-up flash-up' : 'row-down flash-down'}`}>
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
      </div>
    </>
  );
}

export default App;
