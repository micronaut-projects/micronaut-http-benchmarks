package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.core.annotation.Nullable;
import org.apache.sshd.client.session.ClientSession;

public interface FrameworkRun {
    String type();

    String name();

    @Nullable
    Object parameters();

    void setupAndRun(
            ClientSession benchmarkServerClient,
            OutputListener.Write log,
            BenchmarkClosure benchmarkClosure,
            PhaseTracker.PhaseUpdater progress) throws Exception;

    interface BenchmarkClosure {
        void benchmark(PhaseTracker.PhaseUpdater progress) throws Exception;

        void pgoLoad(PhaseTracker.PhaseUpdater progress) throws Exception;
    }
}
