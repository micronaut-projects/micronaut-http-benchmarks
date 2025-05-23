package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.apache.sshd.client.session.ClientSession;

import java.nio.file.Files;

@Singleton
@Requires(env = "loop")
final class NginxAttachment implements Infrastructure.Attachment<NginxAttachment.Context> {
    private static final String NGINX_IP = "10.0.0.10";

    @Override
    public Context setUp(Infrastructure infrastructure) throws Exception {
        Compute.Instance instance = infrastructure.computeBuilder("nginx")
                .privateIp(NGINX_IP)
                .launch();
        instance.awaitStartup();

        try (ClientSession ssh = instance.connectSsh();
             OutputListener.Write log = new OutputListener.Write(Files.newOutputStream(infrastructure.logDirectory.resolve("nginx.log")))) {
            SshUtil.openFirewallPorts(ssh, log);
            SshUtil.run(ssh, "sudo yum install -y nginx", log);
            SshUtil.run(ssh, "echo -n 'Hello World' | sudo tee /usr/share/nginx/html/hello", log);
            SshUtil.run(ssh, "sudo systemctl start nginx", log);
        }
        return new Context(instance);
    }

    @Override
    public void tearDown(Infrastructure infrastructure, Context context) {
        context.instance.close();
    }

    record Context(Compute.Instance instance) {
    }
}
