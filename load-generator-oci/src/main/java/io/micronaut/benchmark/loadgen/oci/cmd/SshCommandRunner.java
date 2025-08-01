package io.micronaut.benchmark.loadgen.oci.cmd;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.config.hosts.HostConfigEntry;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.ExplicitPortForwardingTracker;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.apache.sshd.scp.common.helpers.ScpTimestampCommandDetails;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SshCommandRunner implements CommandRunner {
    private static final ScpTimestampCommandDetails DEFAULT_TIME = new ScpTimestampCommandDetails(0, 0);

    private final ClientSession session;

    private SshCommandRunner(ClientSession session) {
        this.session = session;
    }

    public static SshCommandRunner connect(SshClient client, HostConfigEntry hostConfigEntry) throws IOException {
        return connect(client.connect(hostConfigEntry));
    }

    public static SshCommandRunner connect(SshClient client, String uri) throws IOException {
        return connect(client.connect(uri));
    }

    private static SshCommandRunner connect(ConnectFuture future) throws IOException {
        ClientSession session = future.verify().getClientSession();
        session.auth().verify();
        return new SshCommandRunner(session);
    }

    public ClientSession getSession() {
        return session;
    }

    @Override
    public ProcessBuilder builder(String command) throws IOException {
        return new SshProcessBuilder(command);
    }

    @Override
    public void upload(Path local, String remote, Set<PosixFilePermission> permissions) throws IOException {
        try (InputStream stream = Files.newInputStream(local)) {
            ScpClientCreator.instance().createScpClient(session).upload(stream, remote, Files.size(local), permissions, DEFAULT_TIME);
        }
    }

    @Override
    public void upload(Path local, String remote) throws IOException {
        ScpClientCreator.instance().createScpClient(session).upload(local, remote);
    }

    @Override
    public void uploadRecursive(Path local, String remote) throws IOException {
        ScpClientCreator.instance().createScpClient(session).upload(local, remote, ScpClient.Option.Recursive, ScpClient.Option.PreserveAttributes);
    }

    @Override
    public void upload(byte[] local, String remote, Set<PosixFilePermission> permissions) throws IOException {
        ScpClientCreator.instance().createScpClient(session).upload(local, remote, permissions, DEFAULT_TIME);
    }

    @Override
    public void download(String remote, Path local) throws IOException {
        ScpClientCreator.instance().createScpClient(session).download(remote, local);
    }

    @Override
    public void downloadRecursive(String remote, Path local) throws IOException {
        ScpClientCreator.instance().createScpClient(session).download(remote, local, ScpClient.Option.Recursive);
    }

    @Override
    public byte[] downloadBytes(String path) throws IOException {
        return ScpClientCreator.instance().createScpClient(session).downloadBytes(path);
    }

    @Override
    public PortForwardHandle portForward(InetSocketAddress remoteAddress) throws IOException {
        ExplicitPortForwardingTracker forwardingTracker = session.createLocalPortForwardingTracker(
                new SshdSocketAddress("localhost", 0),
                new SshdSocketAddress(remoteAddress.getHostString(), remoteAddress.getPort()));
        return new PortForwardHandle() {
            @Override
            public InetSocketAddress localAddress() {
                return forwardingTracker.getBoundAddress().toInetSocketAddress();
            }

            @Override
            public void close() throws IOException {
                forwardingTracker.close();
            }
        };
    }

    @Override
    public void close() throws IOException {
        session.close();
    }

    private final class SshProcessBuilder implements ProcessBuilder {
        ChannelExec channel;

        public SshProcessBuilder(String command) throws IOException {
            channel = session.createExecChannel(command);
        }

        @Override
        public void close() throws IOException {
            if (channel != null) {
                channel.close();
                channel = null;
            }
        }

        @Override
        public void setOut(OutputStream stream) {
            channel.setOut(stream);
        }

        @Override
        public void setErr(OutputStream stream) {
            channel.setErr(stream);
        }

        @Override
        public void setIn(InputStream in) {
            channel.setIn(in);
        }

        @Override
        public ProcessHandle start() throws IOException {
            channel.open().await();
            SshProcessHandle handle = new SshProcessHandle(channel);
            channel = null;
            return handle;
        }
    }

    private record SshProcessHandle(ChannelExec channel) implements ProcessHandle {
        @Override
        public void interrupt() throws IOException {
            signal("INT");
        }

        @Override
        public void kill() throws IOException {
            signal("KILL");
        }

        void signal(String signal) throws IOException {
            Buffer buffer = channel.getSession().createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST, 0);
            buffer.putInt(channel.getRecipient());
            buffer.putString("signal");
            buffer.putBoolean(false);
            buffer.putString(signal);
            channel.writePacket(buffer).await();
        }

        private SshCommandResult result() {
            return new SshCommandResult(
                    channel.getExitSignal(),
                    channel.getExitStatus()
            );
        }

        @Override
        public CommandResult waitFor() {
            channel.waitFor(ClientSession.REMOTE_COMMAND_WAIT_EVENTS, 0);
            return result();
        }

        @Override
        public CommandResult waitFor(long timeout, TimeUnit unit) throws TimeoutException {
            if (channel.waitFor(ClientSession.REMOTE_COMMAND_WAIT_EVENTS, Duration.of(timeout, unit.toChronoUnit())).contains(ClientChannelEvent.TIMEOUT)) {
                throw new TimeoutException();
            }
            return result();
        }

        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private record SshCommandResult(
            String exitSignal,
            Integer exitStatus
    ) implements CommandResult {

        @Override
        public int status() throws IOException {
            if (exitSignal != null || exitStatus == null) {
                throw new IOException(exitSignal);
            }
            return exitStatus;
        }
    }
}
