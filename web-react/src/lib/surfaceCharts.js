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
/** At most this many quote markers per expiry; more hides the surface under dots. */
const MAX_POINTS_PER_EXPIRY = 40;

const AXIS_2D = { gridcolor: '#1C232D', zerolinecolor: '#FF9900', titlefont: { size: 14 } };
const LAYOUT_2D = {
    ...LAYOUT_BASE,
    margin: { t: 10, r: 20, l: 40, b: 40 },
    showlegend: true,
    legend: { orientation: 'h', y: -0.3, x: 0.5, xanchor: 'center', font: { size: 14, color: '#FF9900' } }
};

const sceneAxis = (title, range) => ({ title, range, gridcolor: '#1C232D', backgroundcolor: '#05070A', zerolinecolor: '#FF9900' });
const lineTrace = (x, y, name) => ({ x, y, type: 'scatter', mode: 'lines+markers', name, line: { width: 2 }, marker: { size: 4 } });
const pct = (v) => v * 100;

/** Quotes inside the drawn strike band, thinned evenly so each expiry shows at most MAX_POINTS_PER_EXPIRY. */
export function displayablePoints(points, strikes, perExpiry = MAX_POINTS_PER_EXPIRY) {
    if (!Array.isArray(points) || !points.length || !strikes.length) return [];
    const lo = Math.min(...strikes), hi = Math.max(...strikes);
    const byExpiry = new Map();
    for (const p of points) {
        if (p.strike < lo || p.strike > hi) continue;
        if (!byExpiry.has(p.t)) byExpiry.set(p.t, []);
        byExpiry.get(p.t).push(p);
    }
    const out = [];
    for (const slice of byExpiry.values()) {
        slice.sort((a, b) => a.strike - b.strike);
        const step = Math.max(1, Math.ceil(slice.length / perExpiry));
        for (let i = 0; i < slice.length; i += step) out.push(slice[i]);
    }
    return out;
}

/** Three expiries to show as smile slices: first, middle and last of the expiries that carry quotes. */
export function smileExpiries(expiries, fittedExpiries) {
    const candidates = Array.isArray(fittedExpiries) && fittedExpiries.length ? fittedExpiries : expiries;
    const idx = [0, Math.floor(candidates.length / 2), candidates.length - 1];
    return [...new Set(idx.map(i => candidates[i]))];
}

const nearestRow = (expiries, t) => {
    let best = 0;
    for (let i = 1; i < expiries.length; i++) if (Math.abs(expiries[i] - t) < Math.abs(expiries[best] - t)) best = i;
    return best;
};

/**
 * Draws the 3D surface, the smile (three expiries) and the term structure (three strikes) with Plotly.
 * When the surface was fitted to quotes, a thinned set of the quotes inside the drawn band is overlaid as
 * markers so the fit can be judged by eye without hiding the surface; axis ranges are pinned to the surface.
 */
export function renderSurfaceCharts(plotly, surface, elements, otherModels = []) {
    const { x: strikes, y: expiries, z: vols } = surface;
    const points = displayablePoints(surface.points, strikes);
    const zValues = vols.flat().map(pct);
    const zRange = [Math.max(0, Math.min(...zValues) - 3), Math.max(...zValues) + 3];

    const traces3d = [{
        x: strikes,
        y: expiries,
        z: vols.map(row => row.map(pct)),
        type: 'surface',
        colorscale: AMBER_CYAN_COLORSCALE,
        showscale: false,
        opacity: points.length ? 0.92 : 1.0,
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
            marker: { size: 2.5, color: '#E0E6ED', opacity: 0.8 }
        });
    }
    plotly.react(elements.surface3d, traces3d, {
        ...LAYOUT_BASE,
        showlegend: false,
        scene: {
            xaxis: sceneAxis('STRIKE', [Math.min(...strikes), Math.max(...strikes)]),
            yaxis: sceneAxis('EXPIRY (y)', [Math.min(...expiries), Math.max(...expiries)]),
            zaxis: sceneAxis('IV (%)', zRange),
            camera: { eye: { x: 1.4, y: -1.4, z: 0.8 } }
        },
        margin: { t: 0, r: 0, l: 0, b: 0 }
    }, PLOT_CONFIG);

    const smileTraces = [];
    const slices = smileExpiries(expiries, surface.fittedExpiries);
    slices.forEach((t, n) => {
        const row = nearestRow(expiries, t);
        smileTraces.push(lineTrace(strikes, vols[row].map(pct), `${surface.model || 'model'} ${expiries[row]}y`));
        if (n === 0) {
            // Other models at the shortest expiry, dashed: fits to the same quotes differ most where the skew is steepest.
            for (const other of otherModels) {
                if (!other || !Array.isArray(other.z) || !other.z.length) continue;
                const otherRow = nearestRow(other.y, t);
                smileTraces.push({
                    x: other.x, y: other.z[otherRow].map(pct), type: 'scatter', mode: 'lines',
                    name: `${other.model} ${other.y[otherRow]}y`, line: { width: 2, dash: 'dot' }
                });
            }
        }
        const quotes = points.filter(p => Math.abs(p.t - t) < 1e-6);
        if (quotes.length) {
            smileTraces.push({
                x: quotes.map(p => p.strike), y: quotes.map(p => pct(p.marketVol)),
                type: 'scatter', mode: 'markers', name: `quotes ${t}y`, showlegend: false,
                marker: { size: 6, symbol: 'x', color: LAYOUT_BASE.colorway[n % LAYOUT_BASE.colorway.length] }
            });
        }
    });
    plotly.react(elements.smile, smileTraces,
        { ...LAYOUT_2D, xaxis: { title: 'STRIKE', ...AXIS_2D, range: [Math.min(...strikes), Math.max(...strikes)] }, yaxis: { title: 'IV (%)', ...AXIS_2D, range: zRange } },
        PLOT_CONFIG);

    const strikeIdx = [Math.floor(strikes.length * 0.2), Math.floor(strikes.length / 2), Math.floor(strikes.length * 0.8)];
    plotly.react(elements.term,
        strikeIdx.map(i => lineTrace(expiries, vols.map(row => pct(row[i])), `K=${Math.round(strikes[i])}`)),
        { ...LAYOUT_2D, xaxis: { title: 'EXPIRY (y)', ...AXIS_2D }, yaxis: { title: 'IV (%)', ...AXIS_2D } },
        PLOT_CONFIG);
}
