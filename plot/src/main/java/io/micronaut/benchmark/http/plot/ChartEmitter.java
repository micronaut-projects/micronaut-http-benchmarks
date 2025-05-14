package io.micronaut.benchmark.http.plot;

import software.xdev.chartjs.model.charts.Chart;

import java.util.UUID;

final class ChartEmitter {
    private final Chart<?, ?, ?> chart;
    private String collection = null;
    private String wrapperClass = "";

    ChartEmitter(Chart<?, ?, ?> chart) {
        this.chart = chart;
    }

    public ChartEmitter collection(String collection) {
        this.collection = collection;
        return this;
    }

    public ChartEmitter wrapperClass(String wrapperClass) {
        this.wrapperClass = wrapperClass;
        return this;
    }

    void emit(StringBuilder html) {
        String id = UUID.randomUUID().toString();
        html.append("<div class='").append(wrapperClass).append("'><canvas id='").append(id).append("'></canvas><script>");
        if (collection != null) {
            html.append(collection).append(".push(");
        }
        html.append("new Chart(document.getElementById('")
                .append(id)
                .append("').getContext('2d'), ")
                .append(chart.toJson())
                .append(")");
        if (collection != null) {
            html.append(")");
        }
        html.append("</script></div>");
    }
}
