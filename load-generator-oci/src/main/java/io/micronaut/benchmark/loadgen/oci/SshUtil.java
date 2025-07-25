package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.exec.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.exec.ProcessHandle;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.scp.common.helpers.ScpTimestampCommandDetails;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class SshUtil {
    private SshUtil() {}

    public static final Set<PosixFilePermission> DEFAULT_PERMISSIONS = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    public static final ScpTimestampCommandDetails DEFAULT_TIME = new ScpTimestampCommandDetails(0, 0);


    /**
     * Forward stdout/err output to the given listeners.
     */
    public static void forwardOutput(ChannelExec command, OutputListener... listeners) throws IOException {
        OutputListener.Stream stream = new OutputListener.Stream(List.of(listeners));
        command.setOut(stream);
        command.setErr(stream);
    }

    /**
     * Open the firewall ports on the given machine using {@code main.nft}.
     */
    public static void openFirewallPorts(CommandRunner benchmarkServerClient, OutputListener... log) throws IOException {
        byte[] bytes;
        try (InputStream s = Infrastructure.class.getResourceAsStream("/main.nft")) {
            bytes = Objects.requireNonNull(s).readAllBytes();
        }
        benchmarkServerClient.upload(bytes, "/tmp/main.nft", SshUtil.DEFAULT_PERMISSIONS);
        run(benchmarkServerClient, "sudo ln -fs /tmp/main.nft /etc/nftables/main.nft");
        run(benchmarkServerClient, "sudo systemctl stop firewalld", log);
        run(benchmarkServerClient, "sudo systemctl restart nftables", log);
    }

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
        try (ProcessHandle handle = client.run(command, log)) {
            handle.waitFor().check();
        }
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
        }
    }
}
