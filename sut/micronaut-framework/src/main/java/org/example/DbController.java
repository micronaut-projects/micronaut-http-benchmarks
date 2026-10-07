package org.example;

import io.agroal.api.AgroalDataSource;
import io.agroal.api.configuration.supplier.AgroalPropertiesReader;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class DbController {
    // JDBC always blocks, so the endpoint always runs on the blocking executor, whatever execute-on says.
    @Controller("/db")
    public static class BlockingJdbc {
        private final DataSource dataSource;

        BlockingJdbc(@Value("${db-remote:10.0.0.11}") String remote) throws Exception {
            dataSource = AgroalDataSource.from(new AgroalPropertiesReader().readProperties(Map.of(
                    "jdbcUrl", "jdbc:postgresql://" + remote + "/benchmark",
                    "principal", "benchmark",
                    "credential", "Benchmark1!",
                    "maxSize", "100"
            )));
        }

        @Get
        @ExecuteOn(TaskExecutors.BLOCKING)
        public String get() throws SQLException {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement("select value from values where index = ?")) {
                ps.setInt(1, ThreadLocalRandom.current().nextInt(1024));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        }
    }
}
