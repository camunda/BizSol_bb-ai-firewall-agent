package io.camunda.bizsol.bb.ai_firewall_agent.config;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("wiremock-test")
public class WireMockConfig {

    private static final int WIREMOCK_PORT = 8089;

    @Bean(initMethod = "start", destroyMethod = "stop")
    public WireMockServer wireMockServer() {
        // Use WireMock's built-in file loading from mappings directory
        // This is the same directory used by docker-compose
        WireMockServer wireMockServer =
                new WireMockServer(
                        WireMockConfiguration.options()
                                .port(WIREMOCK_PORT)
                                .usingFilesUnderDirectory("wiremock"));

        WireMock.configureFor("localhost", WIREMOCK_PORT);

        return wireMockServer;
    }
}
