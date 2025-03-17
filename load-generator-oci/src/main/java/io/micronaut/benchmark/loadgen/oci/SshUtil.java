package io.micronaut.benchmark.loadgen.oci;

import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.util.buffer.Buffer;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.stream.IntStream;

public final class SshUtil {
    private SshUtil() {}

    /**
     * Forward stdout/err output to the given listeners.
     */
    public static void forwardOutput(ChannelExec command, OutputListener... listeners) throws IOException {
        OutputListener.Stream stream = new OutputListener.Stream(List.of(listeners));
        command.setOut(stream);
        command.setErr(stream);
    }

    /**
     * Join a given command and make sure it exits with status 0.
     */
    public static void joinAndCheck(ChannelExec cmd) throws IOException {
        joinAndCheck(cmd, 0);
    }

    /**
     * Join a given command and make sure it exits with one of the given statuses.
     */
    public static void joinAndCheck(ChannelExec cmd, int... expectedStatus) throws IOException {
        cmd.waitFor(ClientSession.REMOTE_COMMAND_WAIT_EVENTS, 0);
        if (cmd.getExitSignal() != null) {
            throw new IOException(cmd.getExitSignal());
        }
        if (cmd.getExitStatus() == null || IntStream.of(expectedStatus).noneMatch(i -> i == cmd.getExitStatus())) {
            throw new IOException("Exit status: " + cmd.getExitStatus());
        }
    }

    /**
     * Open the firewall ports on the given machine using {@code main.nft}.
     */
    public static void openFirewallPorts(ClientSession benchmarkServerClient, OutputListener... log) throws IOException {
        try (ChannelExec session = benchmarkServerClient.createExecChannel("sudo tee /etc/nftables/main.nft");
             InputStream nft = Infrastructure.class.getResourceAsStream("/main.nft")) {
            session.setIn(nft);
            forwardOutput(session, log);
            session.open().await();
            joinAndCheck(session);
        }
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
    public static void run(ClientSession client, String command, OutputListener log) throws IOException {
        run(client, command, new OutputListener[]{log});
    }

    /**
     * Run the given command and wait until it completes.
     *
     * @param client  The client to run the command on
     * @param command The command
     * @param log     Loggers for the output
     */
    public static void run(ClientSession client, String command, OutputListener... log) throws IOException {
        try (ChannelExec chan = client.createExecChannel(command)) {
            forwardOutput(chan, log);
            chan.open().await();
            joinAndCheck(chan);
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
    public static void run(ClientSession client, String command, OutputListener log, int... allowedStatus) throws IOException {
        try (ChannelExec chan = client.createExecChannel(command)) {
            forwardOutput(chan, log);
            chan.open().await();
            joinAndCheck(chan, allowedStatus);
        }
    }

    /**
     * Send a SIGINT to the given command.
     */
    public static void interrupt(ChannelExec cmd) throws IOException {
        signal(cmd, "INT");
    }

    /**
     * Send a signal to the given command.
     */
    public static void signal(ChannelExec cmd, String signal) throws IOException {
        Buffer buffer = cmd.getSession().createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST, 0);
        buffer.putInt(cmd.getRecipient());
        buffer.putString("signal");
        buffer.putBoolean(false);
        buffer.putString(signal);
        cmd.writePacket(buffer);
    }
}
