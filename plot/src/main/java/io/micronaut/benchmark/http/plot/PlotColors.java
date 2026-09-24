package io.micronaut.benchmark.http.plot;

import java.awt.Color;
import java.util.List;
import java.util.Locale;

/** Shared palette for fixed-rate and throughput plots. */
final class PlotColors {
    private static final List<Double> HUES = List.of(278., 230., 157., 70., 0., 25., 120.);

    static String color(int index, double saturation, double brightness) {
        double hue = index < HUES.size() ? HUES.get(index) / 360 : (index * 0.618033988749895) % 1;
        return String.format(Locale.ROOT, "#%06x", Color.getHSBColor((float) hue, (float) saturation, (float) brightness).getRGB() & 0xffffff);
    }
}
