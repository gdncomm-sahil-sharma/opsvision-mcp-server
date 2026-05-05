package com.gdn.opsvision.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

// Exclude DataSourceAutoConfiguration — we wire our own DataSources per warehouse-domain DB
// in DataSourcesConfig, and we don't want the default spring.datasource.* auto-config (which
// flips the health endpoint to DOWN when there's no configured spring.datasource.url).
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class OpsvisionMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsvisionMcpApplication.class, args);
    }
}
