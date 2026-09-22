package io.micronaut.benchmark.cli;

import io.micronaut.benchmark.api.BatchRequest;
import io.micronaut.benchmark.api.ExperimentRequest;
import io.micronaut.benchmark.api.Nix;
import io.micronaut.benchmark.http.plot.Main;
import io.micronaut.benchmark.http.plot.Results;
import io.micronaut.configuration.picocli.MicronautFactory;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.BeanProvider;
import jakarta.inject.Singleton;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Singleton
@Command(name = "bench", mixinStandardHelpOptions = true,
        description = {
                "Run benchmarks on a shared OCI daemon and analyze saved results.",
                "Use 'cases' to discover suite, run, protocol, and document selectors.",
                "The daemon starts automatically when needed and exits after two idle hours.",
                "Summary, comparison, plotting, and profile queries work without the daemon."
        },
        footerHeading = "%nExamples:%n",
        footer = {
                "  bench cases",
                "  bench run --suite standard --run pure-netty --protocol https2 \\",
                "    --document 6-6 --rate 1000 --wait",
                "  bench wait --batch BATCH_ID",
                "  bench summary output/runs/RUN_ID",
                "",
                "Use 'bench <command> --help' for command options."
        },
        subcommands = {Bench.Run.class, Bench.Submit.class, Bench.Suite.class, Bench.Cases.class,
                Bench.Status.class, Bench.Wait.class, Bench.Cancel.class, Bench.Stop.class,
                Bench.Summary.class, Bench.Compare.class, Bench.Plot.class, Bench.Profile.class})
public final class Bench implements Runnable {
    static final JsonMapper JSON = JsonMapper.builder().build();
    private final Nix nix;
    private final BeanProvider<LocalDaemon> daemon;

    public Bench(Nix nix, BeanProvider<LocalDaemon> daemon) {
        this.nix = nix;
        this.daemon = daemon;
    }

    public static void main(String[] args) {
        System.setProperty("logback.configurationFile", "logback-cli.xml");
        int result;
        try (ApplicationContext context = ApplicationContext.builder().environments("cli").start()) {
            result = new CommandLine(context.getBean(Bench.class), new MicronautFactory(context))
                    .setExecutionExceptionHandler((failure, command, parsed) -> {
                        command.getErr().println(failure.getMessage());
                        return 1;
                    }).execute(args);
        }
        System.exit(result);
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    LocalDaemon daemon() {
        return daemon.get();
    }

    JsonNode request(String method, String path, Object body) throws Exception {
        return daemon().request(method, path, body);
    }

    static void print(Object value) {
        System.out.println(JSON.writeValueAsString(value));
    }

    int await(String id, boolean batch) throws Exception {
        for (; ; ) {
            JsonNode result = request("GET", (batch ? "/batches/" : "/runs/") + id, null);
            boolean done = batch ? result.get("finished").booleanValue() : result.hasNonNull("finished");
            if (done) {
                print(result);
                if (!batch) {
                    return "SUCCEEDED".equals(result.get("state").stringValue()) ? 0 : 1;
                }
                if (result.hasNonNull("failure")) {
                    return 1;
                }
                for (JsonNode run : result.get("runs"))
                    if (!"SUCCEEDED".equals(run.get("state").stringValue())) {
                        return 1;
                    }
                return 0;
            }
            Thread.sleep(1000);
        }
    }

    static abstract class Subcommand {
        @ParentCommand
        Bench parent;
    }

    static class SourceOptions {
        @Option(names = "--flake", paramLabel = "FLAKE", defaultValue = "nix",
                description = "Benchmark flake path or reference; may be in another worktree. Relative paths use the current directory (default: ${DEFAULT-VALUE}).")
        String flake;
        @Option(names = "--override-input", paramLabel = "NAME=REFERENCE",
                description = "Override a flake input with a path or reference; repeatable.")
        Map<String, String> inputs = new LinkedHashMap<>();
    }

    static class SubmissionOptions {
        @Option(names = "--output-root", paramLabel = "DIR", defaultValue = "output/runs",
                description = "Parent for new run directories, relative to the current directory (default: ${DEFAULT-VALUE}).")
        Path output;
        @Option(names = "--wait", description = "Wait for completion instead of returning after submission; fail if any run fails or is cancelled.")
        boolean wait;
    }

    @Command(name = "cases", mixinStandardHelpOptions = true, description = "Print suites, run selectors, protocols, and documents as JSON.")
    static final class Cases extends Subcommand implements Callable<Integer> {
        @Mixin
        SourceOptions source;

        @Override
        public Integer call() throws Exception {
            print(new Preparation(parent.nix, source.flake, source.inputs).catalog());
            return 0;
        }
    }

    @Command(name = "run", mixinStandardHelpOptions = true, description = "Prepare and submit one experiment at a fixed request rate.")
    static final class Run extends Subcommand implements Callable<Integer> {
        @Mixin
        SourceOptions source;
        @Mixin
        SubmissionOptions submission;
        @Option(names = "--suite", paramLabel = "SUITE", required = true, description = "Suite name from 'bench cases'.")
        String suite;
        @Option(names = "--run", paramLabel = "RUN", required = true, description = "Run selector within the chosen suite, from 'bench cases'.")
        String run;
        @Option(names = "--protocol", paramLabel = "PROTOCOL", required = true, description = "Protocol name from the suite catalog, e.g. https2.")
        String protocol;
        @Option(names = "--document", paramLabel = "DOCUMENT", required = true, description = "Document/workload name from the suite catalog, e.g. 6-6.")
        String document;
        @Option(names = "--rate", paramLabel = "REQUESTS_PER_SECOND", required = true, description = "Positive target request rate in requests per second.")
        int rate;
        @Option(names = "--warmup", paramLabel = "DURATION", defaultValue = "60s",
                description = "Warmup duration: positive number with s, m, or h (default: ${DEFAULT-VALUE}).")
        String warmup;
        @Option(names = "--duration", paramLabel = "DURATION", defaultValue = "60s",
                description = "Measurement duration: positive number with s, m, or h (default: ${DEFAULT-VALUE}).")
        String duration;

        @Override
        public Integer call() throws Exception {
            Preparation preparation = new Preparation(parent.nix, source.flake, source.inputs);
            var request = preparation.prepare(Map.of("suite", suite, "run", run, "protocol", protocol,
                    "document", document, "rate", rate, "warmupDuration", warmup, "benchmarkDuration", duration), submission.output);
            JsonNode receipt = parent.request("POST", "/runs", request);
            print(receipt);
            return submission.wait ? parent.await(receipt.get("id").stringValue(), true) : 0;
        }
    }

    @Command(name = "submit", mixinStandardHelpOptions = true, description = "Submit an existing experiment derivation without evaluating a flake.")
    static final class Submit extends Subcommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "DERIVATION", description = "Absolute /nix/store/...drv path of an experiment.")
        String derivation;
        @Option(names = "--output", paramLabel = "NAME", defaultValue = "out",
                description = "Derivation output containing the experiment (default: ${DEFAULT-VALUE}).")
        String output;
        @Option(names = "--annotation", paramLabel = "KEY=VALUE", description = "Caller metadata recorded with the run; repeatable.")
        Map<String, String> annotations = new LinkedHashMap<>();
        @Mixin
        SubmissionOptions submission;

        @Override
        public Integer call() throws Exception {
            var receipt = parent.request("POST", "/runs", new ExperimentRequest(derivation, output,
                    submission.output.toAbsolutePath().normalize().toString(), annotations));
            print(receipt);
            return submission.wait ? parent.await(receipt.get("id").stringValue(), true) : 0;
        }
    }

    @Command(name = "suite", mixinStandardHelpOptions = true, description = {
            "Submit all suite cases in a shuffled, exclusive batch.",
            "Uses the suite's full workload and the daemon's existing infrastructure."
    })
    static final class Suite extends Subcommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "SUITE", description = "Suite name from 'bench cases'.")
        String suite;
        @Mixin
        SourceOptions source;
        @Mixin
        SubmissionOptions submission;

        @Override
        public Integer call() throws Exception {
            Path root = submission.output.toAbsolutePath().normalize();
            Preparation preparation = new Preparation(parent.nix, source.flake, source.inputs);
            JsonNode catalog = preparation.catalog().get("suites").get(suite);
            if (catalog == null) {
                throw new IllegalArgumentException("Unknown suite: " + suite);
            }
            List<ExperimentRequest> requests = new ArrayList<>();
            for (JsonNode run : catalog.get("runs"))
                for (var protocol : catalog.get("protocols").properties())
                    for (JsonNode doc : catalog.get("documents")) {
                        requests.add(preparation.prepare(Map.of("suite", suite, "run", run.get("selector").stringValue(),
                                "protocol", protocol.getKey(), "document", doc.get("name").stringValue(), "full", true), root));
                    }
            Collections.shuffle(requests);
            JsonNode receipt = parent.request("POST", "/batches", new BatchRequest(requests));
            print(receipt);
            return submission.wait ? parent.await(receipt.get("id").stringValue(), true) : 0;
        }
    }

    @Command(name = "status", mixinStandardHelpOptions = true,
            description = "Print one run, or all runs known to the current daemon, as JSON.")
    static final class Status extends Subcommand implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", paramLabel = "RUN_ID", description = "Run ID from a submission receipt; omit to list all runs.")
        String id;

        @Override
        public Integer call() throws Exception {
            print(parent.request("GET", id == null ? "/runs" : "/runs/" + id, null));
            return 0;
        }
    }

    @Command(name = "wait", mixinStandardHelpOptions = true, description = {
            "Wait for a run or batch and print its final state as JSON.",
            "Returns a nonzero exit status if a selected run fails or is cancelled."
    })
    static final class Wait extends Subcommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "ID", description = "Run ID, or batch ID with --batch, from a submission receipt.")
        String id;
        @Option(names = "--batch", description = "Interpret ID as a batch ID and wait for all its runs.")
        boolean batch;

        @Override
        public Integer call() throws Exception {
            return parent.await(id, batch);
        }
    }

    @Command(name = "cancel", mixinStandardHelpOptions = true,
            description = "Cancel a queued or active run, retaining collected results.")
    static final class Cancel extends Subcommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "RUN_ID", description = "Run ID from a submission receipt.")
        String id;

        @Override
        public Integer call() throws Exception {
            print(parent.request("POST", "/runs/" + id + "/cancel", null));
            return 0;
        }
    }

    @Command(name = "stop", mixinStandardHelpOptions = true, description = "Cancel queued and active work, destroy infrastructure, and stop the daemon.")
    static final class Stop extends Subcommand implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            print(parent.daemon().stop());
            return 0;
        }
    }

    @Command(name = "summary", mixinStandardHelpOptions = true,
            description = "Summarize measurement phases from one saved run as JSON, excluding warmup.")
    static final class Summary extends Subcommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "RUN_DIR", description = "Saved run directory containing run.json and benchmark results.")
        Path directory;

        @Override
        public Integer call() throws Exception {
            print(Results.summary(directory));
            return 0;
        }
    }

    @Command(name = "compare", mixinStandardHelpOptions = true,
            description = "Compare measurement statistics for two saved runs as JSON.")
    static final class Compare extends Subcommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "BASELINE_DIR", description = "Saved run directory to use as the reference.")
        Path baseline;
        @Parameters(index = "1", paramLabel = "CANDIDATE_DIR", description = "Saved run directory to compare against the baseline.")
        Path candidate;

        @Override
        public Integer call() throws Exception {
            print(Results.compare(baseline, candidate));
            return 0;
        }
    }

    @Command(name = "profile", mixinStandardHelpOptions = true,
            description = {
                    "Import a saved JFR profile into a cached DuckDB database using the Nix-packaged jfr-query CLI.",
                    "Without a query or --context, print the database and tool paths as JSON. No daemon is started.",
                    "Queries cover the full recording; use WHERE benchmark_measured(startTime) to exclude warmup."
            })
    static final class Profile extends Subcommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "RUN_DIR", description = "Completed run directory with a declared JFR artifact.")
        Path directory;
        @Mixin
        SourceOptions source;
        @Option(names = "--stack-depth", defaultValue = "256", description = "Maximum imported stack depth, 1–4096 (default: ${DEFAULT-VALUE}).")
        int stackDepth;
        @Option(names = "--query", paramLabel = "SQL", description = "Execute arbitrary SQL or an upstream named view on the imported database.")
        String query;
        @Option(names = "--query-file", paramLabel = "FILE", description = "Read SQL from a UTF-8 file instead of --query.")
        Path queryFile;
        @Option(names = "--context", description = "Print the imported schema, views, and macros for writing SQL.")
        boolean context;
        @Option(names = "--csv", description = "Use jfr-query's CSV output for --query or --query-file.")
        boolean csv;

        @Override
        public Integer call() throws Exception {
            if ((context ? 1 : 0) + (query != null ? 1 : 0) + (queryFile != null ? 1 : 0) > 1) {
                throw new IllegalArgumentException("Choose one of --query, --query-file, or --context");
            }
            if (queryFile != null) query = java.nio.file.Files.readString(queryFile);
            if (csv && query == null) throw new IllegalArgumentException("--csv requires --query or --query-file");
            if (stackDepth < 1 || stackDepth > 4096) throw new IllegalArgumentException("Stack depth must be between 1 and 4096");
            Path tool = ProfileQuery.buildTool(parent.nix, source.flake, source.inputs);
            ProfileQuery profile = new ProfileQuery(tool);
            Path database = profile.prepare(directory, stackDepth);
            if (context) profile.context(database, System.out);
            else if (query != null) profile.query(database, query, csv, System.out);
            else print(Map.of("database", database.toString(), "tool", tool.toString(), "stackDepth", stackDepth));
            return 0;
        }
    }

    @Command(name = "plot", mixinStandardHelpOptions = true,
            description = "Write plot.html and profile visualizations from saved run directories.")
    static final class Plot extends Subcommand implements Callable<Integer> {
        @Parameters(index = "0", paramLabel = "DIR", description = "One run directory or a parent containing completed run directories.")
        Path directory;
        @Option(names = "--upload", description = "Upload plots and profiles to OCI and open the resulting URL in a browser.")
        boolean upload;

        @Override
        public Integer call() throws Exception {
            print(Map.of("plot", Main.generate(directory, upload).toString()));
            return 0;
        }
    }
}
