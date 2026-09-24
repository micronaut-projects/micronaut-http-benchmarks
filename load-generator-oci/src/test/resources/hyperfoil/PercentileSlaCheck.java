import io.hyperfoil.api.config.SLABuilder;
import io.hyperfoil.api.statistics.StatisticsSnapshot;

/** Runs against HYPERFOIL_HOME, independent of the Maven client dependency. */
class PercentileSlaCheck {
    public static void main(String[] args) {
        // Each distribution has 10,000 responses: 1ms fast responses and 500ms slow responses.
        // The 200ms limit must distinguish both sides of each configured percentile.
        check("0.50", 4_000, true);
        check("0.50", 6_000, false);
        check("0.95", 9_400, true);
        check("0.95", 9_600, false);
        check("0.99", 9_800, true);
        check("0.99", 9_950, false);
        check("0.999", 9_980, true);
        check("0.999", 9_995, false);
        check("0.0", 1, false);
        check("1.0", 9_999, true);
        check("1.0", 10_000, false);
    }

    private static void check(String percentile, int fastResponses, boolean expectedFailure) {
        var builder = new SLABuilder<Void>(null);
        builder.limits().accept(percentile, "200ms");
        var statistics = new StatisticsSnapshot();
        statistics.requestCount = 10_000;
        statistics.responseCount = 10_000;
        statistics.histogram.recordValueWithCount(1_000_000, fastResponses);
        if (fastResponses < 10_000) {
            statistics.histogram.recordValueWithCount(500_000_000, 10_000 - fastResponses);
        }
        boolean failed = builder.build().validate("validation", "test", statistics) != null;
        if (failed != expectedFailure) {
            throw new AssertionError("SLA percentile " + percentile + " with " + fastResponses
                    + " fast responses: expected failure=" + expectedFailure + ", actual=" + failed);
        }
    }
}
