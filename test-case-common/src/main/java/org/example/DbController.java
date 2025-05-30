package org.example;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;

public class DbController {
    @Controller("/db")
    @Requires(property = "execute-on", value = "blocking")
    public static class BlockingJdbc {
        private final HikariDataSource dataSource;

        BlockingJdbc(@Value("${db-remote:10.0.0.11}") String remote) {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:postgresql://" + remote + "/benchmark");
            config.setUsername("benchmark");
            config.setPassword("Benchmark1!");
            config.setInitializationFailTimeout(-1);
            config.setThreadFactory(Thread.ofVirtual().factory());
            config.setMaximumPoolSize(100);
            dataSource = new HikariDataSource(config);
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
