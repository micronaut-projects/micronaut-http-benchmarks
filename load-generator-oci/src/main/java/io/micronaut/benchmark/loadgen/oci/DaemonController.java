package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.api.BatchRequest;
import io.micronaut.benchmark.api.ExperimentRequest;
import io.micronaut.benchmark.api.RunRecord;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@Controller("/v1")
@ExecuteOn(TaskExecutors.BLOCKING)
@Produces(MediaType.APPLICATION_JSON)
public final class DaemonController {
    private final ExperimentQueue queue;
    private final ApplicationContext context;

    public DaemonController(ExperimentQueue queue, ApplicationContext context) {
        this.queue = queue;
        this.context = context;
    }

    @Post("/runs")
    public ExperimentQueue.BatchView submit(@Body ExperimentRequest request) throws Exception {
        return queue.submit(new BatchRequest(List.of(request)));
    }

    @Post("/batches")
    public ExperimentQueue.BatchView batch(@Body BatchRequest request) throws Exception {
        return queue.submit(request);
    }

    @Get("/runs")
    public List<RunRecord> runs() {
        return queue.runs();
    }

    @Get("/runs/{id}")
    public RunRecord run(String id) {
        return queue.run(id);
    }

    @Get("/batches/{id}")
    public ExperimentQueue.BatchView batchStatus(String id) {
        return queue.batch(id);
    }

    @Post("/runs/{id}/cancel")
    public RunRecord cancel(String id) throws Exception {
        return queue.cancel(id);
    }

    @Get("/environment")
    public Map<String, Object> environment() {
        return queue.environmentStatus();
    }

    @Post("/shutdown")
    public Map<String, Boolean> shutdown() {
        queue.stopAccepting();
        Thread.ofPlatform().name("daemon-shutdown").start(() -> {
            try {
                Thread.sleep(200);
                queue.close();
            } catch (Exception e) {
                LoggerFactory.getLogger(getClass()).error("Shutdown cleanup failed", e);
            } finally {
                context.close();
            }
        });
        return Map.of("stopping", true);
    }

    @Error(exception = IllegalArgumentException.class)
    public HttpResponse<Map<String, String>> badRequest(IllegalArgumentException failure) {
        return HttpResponse.badRequest(Map.of("error", failure.getMessage()));
    }
}
