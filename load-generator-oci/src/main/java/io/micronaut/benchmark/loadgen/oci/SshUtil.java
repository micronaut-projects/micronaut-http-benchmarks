package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.cmd.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.cmd.OutputListener;
import io.micronaut.benchmark.loadgen.oci.cmd.ProcessHandle;

import java.io.IOException;
import java.io.InterruptedIOException;

public final class SshUtil {
    private SshUtil() {}

    /**
     * Run the given command and wait until it completes.
     *
     * @param client  The client to run the command on
     * @param command The command
     * @param log     Loggers for the output
     */
    public static void run(CommandRunner client, String command, OutputListener log) throws IOException {
        run(client, command, new OutputListener[]{log});
    }

    /**
     * Run the given command and wait until it completes.
     *
     * @param client  The client to run the command on
     * @param command The command
     * @param log     Loggers for the output
     */
    public static void run(CommandRunner client, String command, OutputListener... log) throws IOException {
        client.runAndCheck(command, log);
    }

    /**
     * Run the given command and wait until it completes.
     *
     * @param client        The client to run the command on
     * @param command       The command
     * @param log           Loggers for the output
     * @param allowedStatus Allowed exit statuses
     */
    public static void run(CommandRunner client, String command, OutputListener log, int... allowedStatus) throws IOException {
        try (ProcessHandle handle = client.run(command, log)) {
            handle.waitFor().checkStatus(allowedStatus);
        } catch (InterruptedException e) {
            throw new InterruptedIOException();
        }
    }
}
