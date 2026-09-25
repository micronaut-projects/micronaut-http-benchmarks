const throughputCharts = [];
const throughputHiddenStages = new Set();
const throughputHiddenRuns = new Set();
const throughputNumber = new Intl.NumberFormat('en-US', {maximumFractionDigits: 1});
const throughputInteger = new Intl.NumberFormat('en-US', {maximumFractionDigits: 0});
function throughputTime(nanos) { return formatTime(Number(nanos.toPrecision(3))); }
function throughputRate(value) { return value === 0 ? '0' : `${throughputNumber.format(value / 1000)}k`; }
function showThroughputStage(stage, visible) {
    if (visible) throughputHiddenStages.delete(stage); else throughputHiddenStages.add(stage);
    updateThroughputVisibility();
}
function showThroughputRun(run, visible) {
    if (visible) throughputHiddenRuns.delete(run); else throughputHiddenRuns.add(run);
    updateThroughputVisibility();
}
function updateThroughputVisibility() {
    for (const chart of throughputCharts) {
        chart.data.datasets.forEach((dataset, i) => {
            if (dataset.stage != null) chart.setDatasetVisibility(i,
                !throughputHiddenStages.has(dataset.stage) && !throughputHiddenRuns.has(dataset.runLabel));
        });
        chart.update();
    }
}
Chart.register({
    id: 'throughputDecorations',
    beforeDraw(chart, args, options) {
        if (options.sla == null || !chart.chartArea) return;
        const {ctx, chartArea: area, scales: {y}} = chart;
        const line = Math.max(area.top, Math.min(area.bottom, y.getPixelForValue(options.sla)));
        ctx.save();
        ctx.fillStyle = 'rgba(194,59,77,.10)';
        ctx.fillRect(area.left, area.top, area.width, line - area.top);
        ctx.strokeStyle = '#b33348'; ctx.setLineDash([3, 4]); ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(area.left, line); ctx.lineTo(area.right, line); ctx.stroke();
        ctx.setLineDash([]); ctx.fillStyle = '#8e2638'; ctx.font = '12px sans-serif';
        ctx.fillText(`SLA violation ≥ ${throughputTime(options.sla)}`, area.left + 8, area.top + 16);
        ctx.restore();
    },
    afterDatasetsDraw(chart, args, options) {
        if (!options.bounds) return;
        const {ctx, chartArea: area, scales: {x}} = chart;
        const marks = chart.getDatasetMeta(0).data;
        ctx.save(); ctx.font = '12px sans-serif'; ctx.textBaseline = 'middle';
        options.bounds.forEach((bound, i) => {
            const mark = marks[i];
            if (!mark) return;
            ctx.fillStyle = '#182631';
            if (bound.passing == null) {
                ctx.fillText(bound.outcome.replaceAll('_', ' ').toLowerCase(), area.left + 8, mark.y);
                return;
            }
            if (bound.failing != null) {
                const end = x.getPixelForValue(bound.failing);
                ctx.strokeStyle = '#b33348'; ctx.lineWidth = 2;
                ctx.beginPath(); ctx.moveTo(mark.x, mark.y); ctx.lineTo(end, mark.y);
                ctx.moveTo(end, mark.y - 6); ctx.lineTo(end, mark.y + 6); ctx.stroke();
            }
            const text = `${bound.outcome === 'LOWER_BOUND' ? '≥ ' : ''}${throughputInteger.format(bound.passing)}`;
            const position = Math.min(mark.x + 8, area.right - ctx.measureText(text).width - 4);
            ctx.fillText(text, position, mark.y - 18);
        });
        ctx.restore();
    }
});
function throughputChart(kind, data) {
    const animation = matchMedia('(prefers-reduced-motion: reduce)').matches ? false : {duration: 150};
    const common = {
        responsive: true, maintainAspectRatio: false, animation,
        interaction: {mode: 'nearest', intersect: false},
        plugins: {legend: {position: 'bottom', labels: {boxWidth: 24, boxHeight: 8}}, tooltip: {padding: 12}}
    };
    if (kind === 'capacity') {
        const bounds = data.runs.flatMap(run => run.bounds.map(bound => ({...bound, run})));
        const max = Math.max(1, ...bounds.flatMap(b => [b.passing || 0, b.failing || 0]));
        return {
            type: 'bar',
            data: {
                labels: bounds.map(b => [...(b.run.label.match(/.{1,28}/g) || []), b.label]),
                datasets: [{data: bounds.map(b => b.passing), backgroundColor: bounds.map(b => b.run.color), barThickness: 22}]
            },
            options: {...common, indexAxis: 'y',
                scales: {
                    x: {min: 0, max: max * 1.18, title: {display: true, text: 'Offered requests / second'}, ticks: {callback: throughputRate, maxTicksLimit: 5}},
                    y: {grid: {display: false}, border: {display: false}, ticks: {autoSkip: false}}
                },
                plugins: {legend: {display: false}, throughputDecorations: {bounds}, tooltip: {callbacks: {
                    title: contexts => contexts.length ? bounds[contexts[0].dataIndex].run.label : '',
                    label: context => {
                        const b = bounds[context.dataIndex];
                        return [b.label, b.outcome.replaceAll('_', ' ').toLowerCase(), `Passing: ${throughputInteger.format(b.passing)} offered RPS`,
                            b.failing == null ? 'No failing validation rate' : `First failure: ${throughputInteger.format(b.failing)} offered RPS`];
                    }
                }}}
            }
        };
    }
    const field = `${kind}LatencyMs`;
    const sla = data.sla[Number(kind.substring(1)) / 100];
    const cap = sla == null ? null : sla * 2;
    const datasets = data.runs.flatMap(run => run.curves.map(curve => ({
        label: `${run.label} · ${curve.label}`, stage: curve.stage, runLabel: run.label,
        // Leave gaps at off-scale observations so lines cannot connect across hidden points.
        data: curve.phases.map(p => ({x: p.targetRate,
            y: Number.isFinite(p[field]) && p[field] > 0 && (cap == null || p[field] * 1e6 <= cap) ? p[field] * 1e6 : null,
            phase: p})),
        spanGaps: false,
        borderColor: run.color, backgroundColor: 'transparent', borderWidth: 2,
        borderDash: curve.stage === 'discovery' ? [6, 5] : [], tension: 0,
        pointStyle: context => context.raw?.phase.status === 'FAIL' ? 'crossRot' : 'circle',
        pointRadius: context => context.raw?.phase.status === 'PASS' ? 3 : 6,
        pointHoverRadius: 6,
        pointBorderWidth: context => context.raw?.phase.status === 'PASS' ? 1.5 : 3,
        pointBorderColor: context => context.raw?.phase.status === 'FAIL' ? '#b33348' : run.color,
        pointBackgroundColor: curve.stage === 'discovery' ? '#f7f9fb' : run.color
    }))).filter(dataset => dataset.data.length);
    const ys = datasets.flatMap(d => d.data.map(p => p.y)).filter(y => y != null);
    const references = sla == null ? ys : [...ys, sla];
    const min = Math.min(...references.filter(y => y > 0));
    const max = Math.max(...references);
    return {
        type: 'line', data: {datasets},
        options: {...common,
            scales: {
                x: {type: 'logarithmic', suggestedMax: Math.max(1, ...datasets.flatMap(d => d.data.map(p => p.x))) * 1.04,
                    title: {display: true, text: 'Offered requests / second (log scale)'}, ticks: {callback: throughputRate, maxTicksLimit: 5}},
                y: {type: 'custom-log', min: Number.isFinite(min) ? min * .7 : 1,
                    max: cap ?? (Number.isFinite(max) ? max * 1.2 : 1e6),
                    title: {display: true, text: 'Request latency (log scale)'},
                    ticks: {callback: formatYTicks, maxTicksLimit: 7}}
            },
            plugins: {...common.plugins, legend: {display: false}, throughputDecorations: {sla}, tooltip: {callbacks: {
                title: contexts => contexts.length ? `${contexts[0].dataset.label} · ${contexts[0].raw.phase.name}` : '',
                label: context => {
                    const p = context.raw.phase;
                    return [`Offered: ${throughputInteger.format(p.targetRate)} RPS`, `Achieved: ${throughputNumber.format(p.responsesPerSecond)} responses/s`,
                        `P50: ${p.p50LatencyMs == null ? 'unavailable' : throughputTime(p.p50LatencyMs * 1e6)}`,
                        `P95: ${p.p95LatencyMs == null ? 'unavailable' : throughputTime(p.p95LatencyMs * 1e6)}`,
                        `P99: ${p.p99LatencyMs == null ? 'unavailable' : throughputTime(p.p99LatencyMs * 1e6)}`,
                        `Measured: ${throughputNumber.format(p.seconds)} s`,
                        p.status === 'FAIL' ? 'First failed phase (SLA or session limit)' : 'Eligible passing phase'];
                }
            }}}
        }
    };
}
