import { surfaceHistorySeries } from './history';

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

// The toolbar (zoom, pan, reset) shows on hover and the wheel zooms, so it is visible that the charts can be moved.
const PLOT_CONFIG = { responsive: true, displayModeBar: 'hover', displaylogo: false, scrollZoom: true };

/**
 * The dashboard redraws every chart every few seconds with plotly.react. Without a uirevision Plotly treats each
 * redraw as a brand-new figure and throws away what the user did (a rotated surface, a zoomed smile). The same
 * key on every redraw keeps it; a different model gets a different key, so a new surface starts framed afresh.
 */
const viewKey = (model) => `view-${model || 'surface'}`;
/** At most this many quote markers per expiry; more hides the surface under dots. */
const MAX_POINTS_PER_EXPIRY = 40;

const AXIS_2D = { gridcolor: '#1C232D', zerolinecolor: '#FF9900', titlefont: { size: 14 } };
const LAYOUT_2D = {
    ...LAYOUT_BASE,
    margin: { t: 10, r: 20, l: 40, b: 40 },
    showlegend: true,
    dragmode: 'pan',
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

/** Linear interpolation of ys over ascending xs at x; clamped at the ends. */
export function interpolate(xs, ys, x) {
    if (x <= xs[0]) return ys[0];
    for (let i = 1; i < xs.length; i++) {
        if (x <= xs[i]) {
            const w = (x - xs[i - 1]) / (xs[i] - xs[i - 1]);
            return ys[i - 1] + w * (ys[i] - ys[i - 1]);
        }
    }
    return ys[ys.length - 1];
}

/** model minus quote, in vol points, for every quote of one expiry slice. */
export function residuals(model, quotes, t) {
    if (!model || !Array.isArray(model.z) || !model.z.length) return [];
    const row = model.z[nearestRow(model.y, t)];
    return quotes.map(q => ({ strike: q.strike, error: pct(interpolate(model.x, row, q.strike) - q.marketVol) }));
}

/**
 * |model - quote| in vol points at every grid node, from the full quote set. For a fitted expiry the quotes of
 * that slice are interpolated across strike; rows between fitted expiries interpolate the error in time. Nodes
 * outside the quoted strike range of a slice are NaN: no quote there means no error to report.
 */
export function errorGrid(surface, points) {
    const { x: strikes, y: expiries, z: vols } = surface;
    const fitted = (surface.fittedExpiries && surface.fittedExpiries.length ? surface.fittedExpiries : expiries).slice().sort((a, b) => a - b);
    const sliceError = new Map();
    for (const t of fitted) {
        const quotes = (points || []).filter(p => Math.abs(p.t - t) < 1e-6).sort((a, b) => a.strike - b.strike);
        const row = vols[nearestRow(expiries, t)];
        if (quotes.length < 2) { sliceError.set(t, strikes.map(() => NaN)); continue; }
        const qx = quotes.map(q => q.strike), qv = quotes.map(q => q.marketVol);
        sliceError.set(t, strikes.map((k, j) => (k < qx[0] || k > qx[qx.length - 1]) ? NaN : Math.abs(pct(row[j] - interpolate(qx, qv, k)))));
    }
    return expiries.map(t => {
        let hi = 0;
        while (hi < fitted.length - 1 && fitted[hi] < t) hi++;
        const lo = Math.max(0, hi - 1);
        if (Math.abs(fitted[hi] - t) < 1e-9 || hi === lo) return sliceError.get(Math.abs(fitted[hi] - t) < 1e-9 ? fitted[hi] : fitted[lo]);
        const w = (t - fitted[lo]) / (fitted[hi] - fitted[lo]);
        const a = sliceError.get(fitted[lo]), b = sliceError.get(fitted[hi]);
        return strikes.map((_, j) => (Number.isNaN(a[j]) || Number.isNaN(b[j])) ? NaN : a[j] + w * (b[j] - a[j]));
    });
}

const ERROR_COLORSCALE = [[0.0, '#0C1A2E'], [0.25, '#00E5FF'], [0.6, '#FF9900'], [1.0, '#FF3D00']];
/** Same colour scale for every model so a reviewer can compare them: 0 to this many vol points of error. */
const ERROR_SCALE_MAX_VOL_PTS = 3;

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

    // Shape: the fitted surface. Colour: this model's error against the quotes, on one fixed scale for all models,
    // so three fits to the same market (which look alike as shapes) show how well each explains the quotes.
    const hasQuotes = Array.isArray(surface.points) && surface.points.length > 0;
    const traces3d = [{
        x: strikes,
        y: expiries,
        z: vols.map(row => row.map(pct)),
        type: 'surface',
        colorscale: hasQuotes ? ERROR_COLORSCALE : AMBER_CYAN_COLORSCALE,
        surfacecolor: hasQuotes ? errorGrid(surface, surface.points) : undefined,
        cmin: hasQuotes ? 0 : undefined,
        cmax: hasQuotes ? ERROR_SCALE_MAX_VOL_PTS : undefined,
        showscale: hasQuotes,
        colorbar: { title: { text: 'fit error<br>(vol pts)', font: { size: 11 } }, thickness: 9, len: 0.55, x: 1.0, tickfont: { size: 10 } },
        opacity: points.length ? 0.92 : 1.0,
        contours: { z: { show: true, usecolormap: false, color: '#1C232D', project: { z: true } } }
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
        uirevision: viewKey(surface.model),
        scene: {
            xaxis: sceneAxis('STRIKE', [Math.min(...strikes), Math.max(...strikes)]),
            yaxis: sceneAxis('EXPIRY (y)', [Math.min(...expiries), Math.max(...expiries)]),
            zaxis: sceneAxis('IV (%)', zRange),
            camera: { eye: { x: 1.4, y: -1.4, z: 0.8 } }
        },
        margin: { t: 0, r: 0, l: 0, b: 0 }
    }, PLOT_CONFIG);

    // Top: the selected model's slices with the quotes. Bottom: every model's error against the quotes at the
    // shortest expiry, in vol points. Fits to the same quotes look identical as curves; they differ as errors.
    const smileTraces = [];
    const slices = smileExpiries(expiries, surface.fittedExpiries);
    slices.forEach((t, n) => {
        const row = nearestRow(expiries, t);
        smileTraces.push(lineTrace(strikes, vols[row].map(pct), `${surface.model || 'model'} ${expiries[row]}y`));
        const quotes = points.filter(p => Math.abs(p.t - t) < 1e-6);
        if (quotes.length) {
            smileTraces.push({
                x: quotes.map(p => p.strike), y: quotes.map(p => pct(p.marketVol)),
                type: 'scatter', mode: 'markers', name: `quotes ${t}y`, showlegend: false,
                marker: { size: 6, symbol: 'x', color: LAYOUT_BASE.colorway[n % LAYOUT_BASE.colorway.length] }
            });
        }
    });
    const shortest = slices[0];
    const shortQuotes = points.filter(p => Math.abs(p.t - shortest) < 1e-6);
    const errorModels = [surface, ...otherModels.filter(m => m && m.model !== surface.model)];
    let errorBound = 0.5;
    errorModels.forEach((model, n) => {
        const r = residuals(model, shortQuotes, shortest);
        if (!r.length) return;
        errorBound = Math.max(errorBound, ...r.map(p => Math.abs(p.error)));
        smileTraces.push({
            x: r.map(p => p.strike), y: r.map(p => p.error), type: 'scatter', mode: 'lines+markers', yaxis: 'y2',
            name: `${model.model} error`, line: { width: 1.5 }, marker: { size: 4, color: LAYOUT_BASE.colorway[n % LAYOUT_BASE.colorway.length] }
        });
    });
    plotly.react(elements.smile, smileTraces, {
        ...LAYOUT_2D,
        uirevision: viewKey(surface.model),
        margin: { t: 10, r: 20, l: 48, b: 40 },
        xaxis: { title: 'STRIKE', ...AXIS_2D, range: [Math.min(...strikes), Math.max(...strikes)] },
        yaxis: { title: 'IV (%)', ...AXIS_2D, range: zRange, domain: [0.42, 1] },
        yaxis2: {
            title: `error vs quotes, ${shortest}y (vol pts)`, ...AXIS_2D, domain: [0, 0.3],
            range: [-errorBound * 1.15, errorBound * 1.15], zeroline: true, zerolinecolor: '#E0E6ED', titlefont: { size: 11 }
        }
    }, PLOT_CONFIG);

    const strikeIdx = [Math.floor(strikes.length * 0.2), Math.floor(strikes.length / 2), Math.floor(strikes.length * 0.8)];
    plotly.react(elements.term,
        strikeIdx.map(i => lineTrace(expiries, vols.map(row => pct(row[i])), `K=${Math.round(strikes[i])}`)),
        { ...LAYOUT_2D, uirevision: viewKey(surface.model), xaxis: { title: 'EXPIRY (y)', ...AXIS_2D }, yaxis: { title: 'IV (%)', ...AXIS_2D } },
        PLOT_CONFIG);
}

/**
 * ATM vol (left axis, %) and 95/105 skew (right axis, vol points) of one model over time, one point per
 * calibration. With nothing recorded yet the chart says so rather than drawing an empty frame.
 */
export function renderHistoryChart(plotly, element, points, model) {
    const s = surfaceHistorySeries(points);
    const traces = [];
    if (s.atm.y.length) {
        traces.push({ x: s.atm.x, y: s.atm.y, type: 'scatter', mode: 'lines+markers', name: `${model} ATM vol (%)`, line: { width: 2 }, marker: { size: 4 } });
    }
    if (s.skew.y.length) {
        traces.push({
            x: s.skew.x, y: s.skew.y, type: 'scatter', mode: 'lines+markers', name: 'skew 95/105 (vol pts)', yaxis: 'y2',
            line: { width: 1.5, dash: 'dot' }, marker: { size: 4 }
        });
    }
    const layout = {
        ...LAYOUT_2D,
        uirevision: viewKey(model),
        margin: { t: 10, r: 52, l: 48, b: 40 },
        xaxis: { title: 'TIME', ...AXIS_2D, type: 'date' },
        yaxis: { title: 'ATM IV (%)', ...AXIS_2D },
        yaxis2: { title: 'SKEW (vol pts)', ...AXIS_2D, overlaying: 'y', side: 'right', titlefont: { size: 11 }, showgrid: false }
    };
    if (!traces.length) {
        layout.annotations = [{
            text: 'no calibration recorded yet<br>one point per calibration, kept across restarts',
            showarrow: false, x: 0.5, y: 0.5, xref: 'paper', yref: 'paper', font: { color: '#5C6B73', size: 12 }
        }];
    }
    plotly.react(element, traces, layout, PLOT_CONFIG);
}
