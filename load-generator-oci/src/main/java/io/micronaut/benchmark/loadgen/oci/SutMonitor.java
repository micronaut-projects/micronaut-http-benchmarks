package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.impl.DefaultSftpClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Monitor the server-under-test system.
 */
@Singleton
public final class SutMonitor {
    private static final Logger LOG = LoggerFactory.getLogger(SutMonitor.class);

    private final MeminfoConfiguration meminfoConfiguration;
    private final ExecutorService executor;

    public SutMonitor(MeminfoConfiguration meminfoConfiguration, @Named(TaskExecutors.BLOCKING) ExecutorService executor) {
        this.meminfoConfiguration = meminfoConfiguration;
        this.executor = executor;
    }

    @SuppressWarnings("UnusedReturnValue")
    public <R> R monitorAndRun(
            ClientSession session,
            Path outputDirectory,
            Callable<R> task
    ) throws Exception {
        if (meminfoConfiguration.enabled()) {
            try (OutputStream meminfo = Files.newOutputStream(outputDirectory.resolve("meminfo.log"));
                 SftpClient sftpClient = DefaultSftpClientFactory.INSTANCE.createSftpClient(session)) {

                Future<Object> future = executor.submit(MdcTracker.copyMdc(() -> {
                    try {
                        while (!Thread.interrupted()) {
                            meminfo.write((Instant.now().toString() + "\n").getBytes(StandardCharsets.UTF_8));
                            sftpClient.read("/proc/meminfo").transferTo(meminfo);
                            TimeUnit.MILLISECONDS.sleep(meminfoConfiguration.interval().toMillis());
                        }
                    } catch (InterruptedException | InterruptedIOException ignored) {
                    } catch (Exception e) {
                        LOG.warn("Failed to monitor meminfo", e);
                    }
                    return null;
                }));
                try {
                    return task.call();
                } finally {
                    future.cancel(true);
                }
            }
        } else {
            return task.call();
        }
    }
}
