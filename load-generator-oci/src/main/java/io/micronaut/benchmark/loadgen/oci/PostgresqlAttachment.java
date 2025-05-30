package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.psql.model.CreateDbSystemDetails;
import com.oracle.bmc.psql.model.Credentials;
import com.oracle.bmc.psql.model.ManagementPolicyDetails;
import com.oracle.bmc.psql.model.NoneBackupPolicy;
import com.oracle.bmc.psql.model.PlainTextPasswordDetails;
import io.micronaut.benchmark.loadgen.oci.resource.AbstractDecoratedResource;
import io.micronaut.benchmark.loadgen.oci.resource.PostgresqlResource;
import io.micronaut.benchmark.loadgen.oci.resource.ResourceContext;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Singleton;
import org.apache.sshd.common.util.net.SshdSocketAddress;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.UUID;

@Singleton
@Requires(env = "db")
final class PostgresqlAttachment implements Infrastructure.Attachment {
    private static final String POSTGRES_IP = "10.0.0.11";
    private static final String USERNAME = "benchmark";
    private static final String PASSWORD = "Benchmark1!";
    private static final String DATABASE = "benchmark";
    private static final int PORT = 5432;

    private final ResourceContext context;
    private final Compute compute;
    private final ResilientSshPortForwarder.Factory sshPortForwarderFactory;

    public PostgresqlAttachment(ResourceContext context, Compute compute, ResilientSshPortForwarder.Factory sshPortForwarderFactory) {
        this.context = context;
        this.compute = compute;
        this.sshPortForwarderFactory = sshPortForwarderFactory;
    }

    @Override
    public void setUp(Infrastructure infrastructure) throws Exception {
        PostgresqlResource resource = new PostgresqlResource(context);
        resource.networkDetails(infrastructure.getPrivateSubnet(), POSTGRES_IP);
        Compute.ComputeConfiguration.InstanceType instanceType = compute.getInstanceType("postgresql");
        Compute.Instance benchmarkServer = infrastructure.benchmarkServer;
        DbWithSchema dbWithSchema = new DbWithSchema(context, resource, benchmarkServer);
        dbWithSchema.dependOn(benchmarkServer.resource().require());
        infrastructure.addLifecycleDependency(dbWithSchema.require());
        AbstractInfrastructure.launch(resource, () -> resource.manageNew(infrastructure.location, CreateDbSystemDetails.builder()
                .displayName("PostgreSQL database")
                .dbVersion("15")
                .shape(instanceType.shape())
                .instanceOcpuCount((int) instanceType.ocpus())
                .instanceMemorySizeInGBs((int) instanceType.memoryInGb())
                .credentials(Credentials.builder()
                        .username(USERNAME)
                        .passwordDetails(PlainTextPasswordDetails.builder().password(PASSWORD).build())
                        .build())
                .managementPolicy(ManagementPolicyDetails.builder()
                        .backupPolicy(NoneBackupPolicy.builder().build())
                        .build())
                .instanceCount(1)
        ));
        AbstractInfrastructure.launch(dbWithSchema, dbWithSchema::manage);
    }

    private final class DbWithSchema extends AbstractDecoratedResource {
        private final Compute.Instance relay;

        public DbWithSchema(ResourceContext context, PostgresqlResource dbResource, Compute.Instance relay) {
            super(context);
            this.relay = relay;
            dependOn(dbResource.require());
        }

        @Override
        protected void setUp() throws Exception {
            try (ResilientSshPortForwarder fwd = sshPortForwarderFactory.create(relay::connectSsh, new SshdSocketAddress(POSTGRES_IP, PORT))) {
                InetSocketAddress bound = fwd.bind();
                String s = "jdbc:postgresql://" + bound.getHostString() + ":" + bound.getPort();
                try (Connection conn = AbstractInfrastructure.retry(() -> DriverManager.getConnection(s + "/postgres", USERNAME, PASSWORD))) {
                    conn.prepareStatement("CREATE DATABASE " + DATABASE).execute();
                }
                try (Connection conn = AbstractInfrastructure.retry(() -> DriverManager.getConnection(s + "/" + DATABASE, USERNAME, PASSWORD))) {
                    conn.prepareStatement("CREATE TABLE values (index integer not null primary key, value varchar(40) not null)").execute();
                    PreparedStatement ins = conn.prepareStatement("INSERT INTO values (index,value) VALUES (?,?)");
                    for (int i = 0; i < 1024; i++) {
                        ins.setInt(1, i);
                        ins.setString(2, UUID.randomUUID().toString());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
            }
        }
    }
}
