const API_BASE = "http://localhost:8080/api";

function formatNumber(num) {
    return new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(num);
}

function flashElement(id, isPositive) {
    const el = document.getElementById(id);
    if (!el) return;
    
    // Add transition class if not present
    if (!el.classList.contains('tr-transition')) {
        el.classList.add('tr-transition');
    }
    
    const flashClass = isPositive ? 'flash-up' : 'flash-down';
    
    el.classList.remove('flash-up', 'flash-down');
    // Force reflow
    void el.offsetWidth;
    el.classList.add(flashClass);
    
    setTimeout(() => {
        el.classList.remove(flashClass);
    }, 300);
}

// 1. Fetch and update Risk Metrics
let lastMetrics = { netDelta: null, netGamma: null, netVega: null, spanMargin: null };

async function updateRiskMetrics() {
    try {
        const response = await fetch(`${API_BASE}/risk`);
        const data = await response.json();
        
        const metrics = [
            { key: 'netDelta', id: 'val-delta', row: 'row-delta', val: data.netDelta },
            { key: 'netGamma', id: 'val-gamma', row: 'row-gamma', val: data.netGamma },
            { key: 'netVega', id: 'val-vega', row: 'row-vega', val: data.netVega }
        ];
        
        metrics.forEach(m => {
            if (lastMetrics[m.key] !== m.val) {
                document.getElementById(m.id).textContent = formatNumber(m.val);
                if (lastMetrics[m.key] !== null) {
                    flashElement(m.row, m.val > lastMetrics[m.key]);
                }
                lastMetrics[m.key] = m.val;
            }
        });

        if (lastMetrics.spanMargin !== data.spanMargin) {
            document.getElementById("val-margin").textContent = "$" + formatNumber(data.spanMargin);
            if (lastMetrics.spanMargin !== null) {
                flashElement('margin-block', false); // Margin increases are bad (red)
            }
            lastMetrics.spanMargin = data.spanMargin;
        }

    } catch (error) {
        logToTerminal("ERR_API_FETCH_FAIL");
    }
}

// 2. Fetch and render True 3D Volatility Surface
async function render3DVolatilitySurface() {
    try {
        const response = await fetch(`${API_BASE}/surface3d`);
        const data = await response.json();

        const zVols = data.z.map(row => row.map(v => v * 100));

        const trace = {
            x: data.x,
            y: data.y,
            z: zVols,
            type: 'surface',
            colorscale: 'Blues', // Professional institutional color scale
            reversescale: true,
            showscale: false,
            contours: {
                z: { show: true, usecolormap: true, highlightcolor: "#C9D1D9", project: { z: true } }
            }
        };

        const layout = {
            paper_bgcolor: '#161B22',
            plot_bgcolor: '#161B22',
            font: { family: 'Inter', color: '#8B949E' },
            scene: {
                xaxis: { title: 'STRIKE', gridcolor: '#30363D', backgroundcolor: '#161B22', zerolinecolor: '#30363D' },
                yaxis: { title: 'EXPIRY (YRS)', gridcolor: '#30363D', backgroundcolor: '#161B22', zerolinecolor: '#30363D' },
                zaxis: { title: 'IV (%)', gridcolor: '#30363D', backgroundcolor: '#161B22', zerolinecolor: '#30363D' },
                camera: { eye: { x: -1.5, y: -1.5, z: 1.0 } }
            },
            margin: { t: 20, r: 0, l: 0, b: 20 }
        };

        Plotly.newPlot('volatilityChart', [trace], layout, {responsive: true, displayModeBar: false});
        logToTerminal("SABR 3D Volatility Surface rendered (WebGL).");

    } catch (error) {
        logToTerminal("ERR_SURFACE_RENDER_FAIL");
    }
}

// 3. Simulate Live Feed (Options Tape)
const strikes = [490, 495, 500, 505, 510];
function generateLiveTrade() {
    const isBuy = Math.random() > 0.5;
    const strike = strikes[Math.floor(Math.random() * strikes.length)];
    const price = (Math.random() * 5 + 1).toFixed(2);
    const qty = Math.floor(Math.random() * 100) + 1;
    
    const tr = document.createElement("tr");
    tr.className = `tr-transition ${isBuy ? 'row-up flash-up' : 'row-down flash-down'}`;
    tr.innerHTML = `
        <td>SPY ${strike} C</td>
        <td class="align-right mono">${qty}</td>
        <td class="align-right mono">${price}</td>
    `;
    
    const container = document.getElementById("tape-body");
    container.prepend(tr);
    if(container.children.length > 25) {
        container.removeChild(container.lastChild);
    }
    
    // Remove flash class after render
    setTimeout(() => {
        tr.classList.remove('flash-up', 'flash-down');
    }, 50);
}

// 4. Terminal Logger
function logToTerminal(msg) {
    const time = new Date().toISOString().split('T')[1].substring(0, 8);
    const div = document.createElement("div");
    div.className = "log-entry";
    div.innerHTML = `<span class="log-time">[${time}]</span> ${msg}`;
    const container = document.getElementById("sys-logs");
    container.prepend(div);
    if(container.children.length > 10) container.removeChild(container.lastChild);
}

// Init
document.addEventListener("DOMContentLoaded", () => {
    updateRiskMetrics();
    render3DVolatilitySurface();
    
    setInterval(updateRiskMetrics, 1000);
    setInterval(generateLiveTrade, 200); 
    setInterval(() => {
        document.getElementById("latency").textContent = Math.floor(Math.random() * 5 + 10) + "ms";
    }, 1000);
});
