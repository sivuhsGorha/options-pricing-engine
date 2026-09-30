const API_BASE = "http://localhost:8080/api";

function formatNumber(num) {
    return new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(num);
}

function flashElement(id, isPositive) {
    const el = document.getElementById(id);
    if (!el) return;
    
    if (!el.classList.contains('tr-transition')) {
        el.classList.add('tr-transition');
    }
    
    const flashClass = isPositive ? 'flash-up' : 'flash-down';
    
    el.classList.remove('flash-up', 'flash-down');
    void el.offsetWidth; // trigger reflow
    el.classList.add(flashClass);
    
    setTimeout(() => {
        el.classList.remove(flashClass);
    }, 400);
}

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
                flashElement('margin-block', false);
            }
            lastMetrics.spanMargin = data.spanMargin;
        }

    } catch (error) {
        logToTerminal("Network desync: Retrying risk engine connection...");
    }
}

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
            colorscale: 'Portland', // A vivid, soulful heatmap
            reversescale: false,
            showscale: false,
            contours: {
                z: { show: true, usecolormap: true, highlightcolor: "#ffffff", project: { z: true } }
            }
        };

        const layout = {
            paper_bgcolor: 'rgba(0,0,0,0)',
            plot_bgcolor: 'rgba(0,0,0,0)',
            font: { family: 'Inter', color: '#94a3b8' },
            scene: {
                xaxis: { title: 'STRIKE', gridcolor: 'rgba(148, 163, 184, 0.1)', backgroundcolor: 'rgba(0,0,0,0)', zerolinecolor: 'rgba(148, 163, 184, 0.2)' },
                yaxis: { title: 'EXPIRY', gridcolor: 'rgba(148, 163, 184, 0.1)', backgroundcolor: 'rgba(0,0,0,0)', zerolinecolor: 'rgba(148, 163, 184, 0.2)' },
                zaxis: { title: 'IV (%)', gridcolor: 'rgba(148, 163, 184, 0.1)', backgroundcolor: 'rgba(0,0,0,0)', zerolinecolor: 'rgba(148, 163, 184, 0.2)' },
                camera: { eye: { x: 1.5, y: -1.5, z: 0.8 } }
            },
            margin: { t: 0, r: 0, l: 0, b: 0 }
        };

        Plotly.newPlot('volatilityChart', [trace], layout, {responsive: true, displayModeBar: false});
        logToTerminal("SABR Model synchronized. Surface rendered.");

    } catch (error) {
        logToTerminal("Failed to map Volatility Surface.");
    }
}

const strikes = [490, 495, 500, 505, 510];
function generateLiveTrade() {
    const isBuy = Math.random() > 0.5;
    const strike = strikes[Math.floor(Math.random() * strikes.length)];
    const price = (Math.random() * 5 + 1).toFixed(2);
    const qty = Math.floor(Math.random() * 500) + 10;
    
    const tr = document.createElement("tr");
    tr.className = `tr-transition ${isBuy ? 'row-up flash-up' : 'row-down flash-down'}`;
    tr.innerHTML = `
        <td>SPY ${strike} C</td>
        <td class="align-right mono">${qty}</td>
        <td class="align-right mono">$${price}</td>
    `;
    
    const container = document.getElementById("tape-body");
    container.prepend(tr);
    if(container.children.length > 15) {
        container.removeChild(container.lastChild);
    }
    
    setTimeout(() => {
        tr.classList.remove('flash-up', 'flash-down');
    }, 100);
}

function logToTerminal(msg) {
    const time = new Date().toISOString().split('T')[1].substring(0, 8);
    const div = document.createElement("div");
    div.className = "log-entry";
    div.innerHTML = `<span class="log-time">[${time}]</span> ${msg}`;
    const container = document.getElementById("sys-logs");
    container.prepend(div);
    if(container.children.length > 8) container.removeChild(container.lastChild);
}

document.addEventListener("DOMContentLoaded", () => {
    updateRiskMetrics();
    render3DVolatilitySurface();
    
    setInterval(updateRiskMetrics, 1000);
    setInterval(generateLiveTrade, 300); 
    setInterval(() => {
        document.getElementById("latency").textContent = Math.floor(Math.random() * 3 + 6) + "µs";
    }, 1000);
});
