const API_BASE = "http://localhost:8080/api";

function formatNumber(num) {
    return new Intl.NumberFormat('en-US').format(Math.round(num));
}

function formatCurrency(num) {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }).format(num);
}

// 1. Fetch and update Risk Metrics
async function updateRiskMetrics() {
    try {
        const response = await fetch(`${API_BASE}/risk`);
        const data = await response.json();
        document.getElementById("netDelta").textContent = formatNumber(data.netDelta);
        document.getElementById("netGamma").textContent = formatNumber(data.netGamma);
        document.getElementById("netVega").textContent = formatCurrency(data.netVega);
        document.getElementById("spanMargin").textContent = formatCurrency(data.spanMargin);
    } catch (error) {
        logToTerminal("ERROR: Failed to fetch risk metrics from core engine.");
    }
}

// 2. Fetch and render True 3D Volatility Surface
async function render3DVolatilitySurface() {
    try {
        const response = await fetch(`${API_BASE}/surface3d`);
        const data = await response.json();

        // data.z is a 2D array [expiries][strikes]
        // Convert the Z values to percentages for display
        const zVols = data.z.map(row => row.map(v => v * 100));

        const trace = {
            x: data.x, // Strikes
            y: data.y, // Expiries
            z: zVols,  // Implied Vols
            type: 'surface',
            colorscale: 'Portland', // A high-contrast heat map
            showscale: false,
            contours: {
                z: { show: true, usecolormap: true, highlightcolor: "white", project: { z: true } }
            }
        };

        const layout = {
            paper_bgcolor: 'rgba(0,0,0,0)',
            plot_bgcolor: 'rgba(0,0,0,0)',
            font: { family: 'Rajdhani', color: '#e0e6ed' },
            scene: {
                xaxis: { title: 'STRIKE', gridcolor: 'rgba(255,255,255,0.1)', backgroundcolor: 'rgba(0,0,0,0)' },
                yaxis: { title: 'EXPIRY (YRS)', gridcolor: 'rgba(255,255,255,0.1)', backgroundcolor: 'rgba(0,0,0,0)' },
                zaxis: { title: 'IMPLIED VOL (%)', gridcolor: 'rgba(255,255,255,0.1)', backgroundcolor: 'rgba(0,0,0,0)' },
                camera: { eye: { x: 1.5, y: -1.5, z: 0.8 } }
            },
            margin: { t: 0, r: 0, l: 0, b: 0 }
        };

        Plotly.newPlot('volatilityChart', [trace], layout, {responsive: true, displayModeBar: false});
        logToTerminal("SABR 3D Volatility Surface calibrated and rendered.");

    } catch (error) {
        logToTerminal("ERROR: Failed to map 3D volatility surface.");
    }
}

// 3. Simulate Live Feed (Options Tape)
const strikes = [490, 495, 500, 505, 510];
function generateLiveTrade() {
    const isBuy = Math.random() > 0.5;
    const strike = strikes[Math.floor(Math.random() * strikes.length)];
    const price = (Math.random() * 10 + 2).toFixed(2);
    const qty = Math.floor(Math.random() * 50) + 1;
    
    const div = document.createElement("div");
    div.className = `trade-row ${isBuy ? 'buy' : 'sell'}`;
    div.innerHTML = `
        <span>SPY ${strike} C</span>
        <span>${isBuy ? 'BUY' : 'SELL'}</span>
        <span>${qty} @ $${price}</span>
    `;
    
    const container = document.getElementById("tapeFeed");
    container.prepend(div);
    if(container.children.length > 20) {
        container.removeChild(container.lastChild);
    }
}

// 4. Terminal Logger
function logToTerminal(msg) {
    const time = new Date().toISOString().split('T')[1].substring(0, 12);
    const li = document.createElement("li");
    li.innerHTML = `<span class="timestamp">[${time}]</span> ${msg}`;
    const ul = document.getElementById("liveLogs");
    ul.prepend(li);
    if(ul.children.length > 5) ul.removeChild(ul.lastChild);
}

// Init
document.addEventListener("DOMContentLoaded", () => {
    updateRiskMetrics();
    render3DVolatilitySurface();
    
    setInterval(updateRiskMetrics, 2000);
    setInterval(generateLiveTrade, 400); // Super fast ticking tape
    setInterval(() => {
        const lat = (0.05 + Math.random() * 0.05).toFixed(2);
        document.getElementById("latency").textContent = lat;
    }, 1000);
});
