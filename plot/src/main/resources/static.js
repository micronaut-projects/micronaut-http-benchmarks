class PercentileScale extends Chart.Scale {
    determineDataLimits() {
        this.min = 0;
        this.max = 0.999;
    }

    buildTicks() {
        const ticks = [];
        ticks.push({value: 0.5, major: true});
        for (let i = -1; ; i--) {
            const percentile = 1 - Math.pow(10, i);
            if (percentile > this.max) {
                break;
            }
            ticks.push({value: percentile, major: true});
        }
        return ticks;
    }

    generateTickLabels() {
        for (const tick of this.ticks) {
            tick.label = "P" + (tick.value * 100);
        }
    }

    percentileTransform(value) {
        return -Math.log10(1 - value)
    }

    percentileTransformReverse(value) {
        return 1 - Math.pow(10, -value)
    }

    getLabelForValue(value) {
        return value;
    }

    getPixelForValue(value, index) {
        return this.percentileTransform(value) / this.percentileTransform(this.max) * this.width + this.left
    }

    getValueForPixel(pixel) {
        return this.percentileTransformReverse((pixel - this.left) / this.width * this.percentileTransform(this.max))
    }
}

PercentileScale.id = 'percentile';
Chart.register(PercentileScale);

class CustomLogScale extends Chart.LogarithmicScale {
    buildTicks() {
        const ticks = [];
        for (let exp = Math.floor(Math.log10(this.min)); exp <= Math.floor(Math.log10(this.max)); exp++) {
            for (let factor = 1; factor < 10; factor++) {
                const value = Math.pow(10, exp) * factor;
                if (value < this.min || value > this.max) continue;
                ticks.push({
                    value: value,
                    major: factor === 1,
                });
            }
        }
        return ticks;
    }
}

CustomLogScale.id = 'custom-log';
Chart.register(CustomLogScale);

function formatTime(value) {
    if (value >= 1_000_000_000) return (value / 1_000_000_000) + "s"
    if (value >= 1_000_000) return (value / 1_000_000) + "ms"
    if (value >= 1_000) return (value / 1_000) + "µs"
    return value + "ns"
}

function formatYTicks(value) {
    if (Math.round(Math.log10(value)) !== Math.log10(value)) return "";
    return formatTime(value)
}

function noTicks() {
    return '';
}

timedCharts = [];

function updateMaxTimeLabel(maxTime) {
    const roundingFactor = Math.pow(10, Math.floor(Math.log10(maxTime) / 3) * 3);
    maxTime = Math.round(maxTime / roundingFactor) * roundingFactor;
    document.getElementById("max-time").getElementsByTagName("span").item(0).textContent = formatTime(maxTime);
}

function updateMaxTime(maxTime) {
    for (const chart of timedCharts) {
        chart.options.scales.y.max = maxTime;
        chart.update();
    }
    updateMaxTimeLabel(maxTime);
}

function setCpuUsageMetric(metricClass) {
    for (const element of document.getElementsByClassName("cpu-usage-metric")) {
        if (element.classList.contains(metricClass)) {
            element.style.display = "block";
        } else {
            element.style.display = "none";
        }
    }
}

window.onload = function () {
    updateMaxTimeLabel(Math.pow(10, document.getElementById("max-time").getElementsByTagName("input").item(0).value));
    setCpuUsageMetric(document.getElementById("cpu-usage-metric-select").value);
};
