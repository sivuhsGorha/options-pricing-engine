const LAYOUT_BASE = {
    paper_bgcolor: '#0C0F14',
    plot_bgcolor: '#05070A',
    font: { family: 'JetBrains Mono, Roboto Mono, monospace', color: '#00E5FF' },
    colorway: ['#FF9900', '#00E5FF', '#00E676', '#FF3D00', '#E0E6ED']
};

const AMBER_CYAN_COLORSCALE = [
    [0.0, '#05070A'],
    [0.2, '#1A1D24'],
    [0.5, '#FF9900'],
    [0.8, '#FF5500'],
    [1.0, '#00E5FF']
];

const PLOT_CONFIG = { responsive: true, displayModeBar: false };

const AXIS_2D = { gridcolor: '#1C232D', zerolinecolor: '#FF9900', titlefont: { size: 14 } };
const LAYOUT_2D = {
    ...LAYOUT_BASE,
    margin: { t: 10, r: 20, l: 40, b: 40 },
    showlegend: true,
    legend: { orientation: 'h', y: -0.3, x: 0.5, xanchor: 'center', font: { size: 14, color: '#FF9900' } }
};

const sceneAxis = (title) => ({ title, gridcolor: '#1C232D', backgroundcolor: '#05070A', zerolinecolor: '#FF9900' });
const lineTrace = (x, y, name) => ({ x, y, type: 'scatter', mode: 'lines+markers', name, line: { width: 2 }, marker: { size: 5 } });
const pct = (v) => v * 100;

/** Market quotes of one expiry slice (matched on the expiry value the API returned), as volatility percentages. */
function pointsForExpiry(points, t) {
    return points.filter(p => Math.abs(p.t - t) < 1e-6);
}

/**
 * Draws the 3D surface, the smile (three expiries) and the term structure (three strikes) with Plotly.
 * When the surface was fitted to quotes, the quotes are overlaid as markers so the fit can be judged by eye.
 */
export function renderSurfaceCharts(plotly, surface, elements) {
    const { x: strikes, y: expiries, z: vols } = surface;
    const points = Array.isArray(surface.points) ? surface.points : [];

    const traces3d = [{
        x: strikes,
        y: expiries,
        z: vols.map(row => row.map(pct)),
        type: 'surface',
        colorscale: AMBER_CYAN_COLORSCALE,
        reversescale: false,
        showscale: false,
        opacity: points.length ? 0.85 : 1.0,
        contours: { z: { show: true, usecolormap: true, highlightcolor: '#FF9900', project: { z: true } } }
    }];
    if (points.length) {
        traces3d.push({
            x: points.map(p => p.strike),
            y: points.map(p => p.t),
            z: points.map(p => pct(p.marketVol)),
            type: 'scatter3d',
            mode: 'markers',
            name: 'market quotes',
            marker: { size: 3, color: '#E0E6ED', opacity: 0.9 }
        });
    }
    plotly.react(elements.surface3d, traces3d, {
        ...LAYOUT_BASE,
        showlegend: false,
        scene: {
            xaxis: sceneAxis('STRIKE'),
            yaxis: sceneAxis('EXPIRY (y)'),
            zaxis: sceneAxis('IV (%)'),
            camera: { eye: { x: 1.4, y: -1.4, z: 0.8 } }
        },
        margin: { t: 0, r: 0, l: 0, b: 0 }
    }, PLOT_CONFIG);

    const expiryIdx = [0, Math.floor(expiries.length / 2), expiries.length - 1];
    const smileTraces = [];
    expiryIdx.forEach((i, n) => {
        const t = expiries[i];
        smileTraces.push(lineTrace(strikes, vols[i].map(pct), `Exp ${t}y`));
        const quotes = pointsForExpiry(points, t);
        if (quotes.length) {
            smileTraces.push({
                x: quotes.map(p => p.strike), y: quotes.map(p => pct(p.marketVol)),
                type: 'scatter', mode: 'markers', name: `quotes ${t}y`,
                marker: { size: 7, symbol: 'x', color: LAYOUT_BASE.colorway[n % LAYOUT_BASE.colorway.length] }
            });
        }
    });
    plotly.react(elements.smile, smileTraces,
        { ...LAYOUT_2D, xaxis: { title: 'STRIKE', ...AXIS_2D }, yaxis: { title: 'IV (%)', ...AXIS_2D } },
        PLOT_CONFIG);

    const strikeIdx = [0, Math.floor(strikes.length / 2), strikes.length - 1];
    plotly.react(elements.term,
        strikeIdx.map(i => lineTrace(expiries, vols.map(row => pct(row[i])), `K=${strikes[i]}`)),
        { ...LAYOUT_2D, xaxis: { title: 'EXPIRY (y)', ...AXIS_2D }, yaxis: { title: 'IV (%)', ...AXIS_2D } },
        PLOT_CONFIG);
}
