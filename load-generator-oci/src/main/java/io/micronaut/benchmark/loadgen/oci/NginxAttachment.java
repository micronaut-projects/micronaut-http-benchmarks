package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.benchmark.loadgen.oci.exec.CommandRunner;
import io.micronaut.benchmark.loadgen.oci.resource.AbstractDecoratedResource;
import io.micronaut.benchmark.loadgen.oci.resource.ResourceContext;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;

import java.nio.file.Files;

@Singleton
@Requires(env = "loop")
final class NginxAttachment implements Infrastructure.Attachment {
    private static final String NGINX_IP = "10.0.0.10";

    private final ResourceContext context;

    NginxAttachment(ResourceContext context) {
        this.context = context;
    }

    @Override
    public void setUp(Infrastructure infrastructure) throws Exception {
        Compute.Launch launch = infrastructure.computeBuilder("nginx").privateIp(NGINX_IP);
        NginxResource resource = new NginxResource(context, infrastructure, launch);
        resource.dependOn(launch.resource().require());
        infrastructure.addLifecycleDependency(resource.require());
        AbstractInfrastructure.launch(resource, resource::manage);
    }

    private static final class NginxResource extends AbstractDecoratedResource {
        private final Infrastructure infrastructure;
        private final Compute.Launch launch;
        private Compute.Instance instance;

        public NginxResource(ResourceContext context, Infrastructure infrastructure, Compute.Launch launch) {
            super(context);
            this.infrastructure = infrastructure;
            this.launch = launch;
        }

        @Override
        protected void launchDependencies() throws Exception {
            instance = launch.launch();
        }

        @Override
        protected void setUp() throws Exception {
            try (CommandRunner ssh = instance.connectSsh();
                 OutputListener.Write log = new OutputListener.Write(Files.newOutputStream(infrastructure.logDirectory.resolve("nginx.log")))) {
                SshUtil.openFirewallPorts(ssh, log);
                SshUtil.run(ssh, "sudo yum install -y nginx", log);
                SshUtil.run(ssh, "echo -n 'Hello World' | sudo tee /usr/share/nginx/html/hello", log);
                SshUtil.run(ssh, "sudo systemctl start nginx", log);
            }
        }
    }
}
