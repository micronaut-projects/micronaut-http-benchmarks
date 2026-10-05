package org.example;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.ServerConnector;
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory;
import org.springframework.boot.web.server.servlet.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class ConnectorSetup {
    @Bean
    public ServletWebServerFactory container() {
        JettyServletWebServerFactory factory = new JettyServletWebServerFactory(8443);
        factory.addServerCustomizers(server -> {
            for (Connector connector : server.getConnectors()) {
                HTTP2ServerConnectionFactory http2 = connector.getConnectionFactory(HTTP2ServerConnectionFactory.class);
                if (http2 != null) {
                    http2.setMaxConcurrentStreams(Integer.MAX_VALUE);
                }
                connector.getConnectionFactory(HttpConnectionFactory.class)
                        .getHttpConfiguration()
                        .getCustomizer(SecureRequestCustomizer.class)
                        .setSniHostCheck(false);
            }
            ServerConnector httpConnector = new ServerConnector(server);
            httpConnector.setPort(8080);
            server.addConnector(httpConnector);
        });
        return factory;
    }
}
