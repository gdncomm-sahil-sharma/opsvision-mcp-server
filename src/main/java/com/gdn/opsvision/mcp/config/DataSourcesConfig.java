package com.gdn.opsvision.mcp.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Three independent read-only DataSources, one per warehouse-domain DB.
 * <p>
 * Read-only enforcement is layered:
 * <ol>
 *   <li>Hikari {@code read-only: true} → JDBC Connection.setReadOnly(true)</li>
 *   <li>{@code connection-init-sql} → {@code SET default_transaction_read_only = on}</li>
 *   <li>{@code @Transactional(readOnly = true)} on every repository method</li>
 * </ol>
 * <p>
 * The two-bean pattern (DataSourceProperties + DataSource) is the idiomatic Spring Boot
 * way to wire a custom DataSource. The properties bean picks up {@code url/username/
 * password/driver-class-name}; the DataSource bean binds Hikari-specific properties
 * under the {@code .hikari} subkey. Doing this via a single
 * {@code DataSourceBuilder().build()} bean fails with "jdbcUrl is required" because
 * Hikari expects {@code jdbcUrl} rather than {@code url}.
 */
@Configuration
public class DataSourcesConfig {

    // ─── stockholm_marunda_restore ───────────────────────────────────────────

    @Primary
    @Bean(name = "stockholmDataSourceProperties")
    @ConfigurationProperties("opsvision.datasource.stockholm")
    DataSourceProperties stockholmDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Primary
    @Bean(name = "stockholmDataSource")
    @ConfigurationProperties("opsvision.datasource.stockholm.hikari")
    HikariDataSource stockholmDataSource(
            @Qualifier("stockholmDataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Primary
    @Bean(name = "stockholmJdbcClient")
    JdbcClient stockholmJdbcClient(@Qualifier("stockholmDataSource") DataSource ds) {
        return JdbcClient.create(ds);
    }

    // ─── warehouse_inventory_marunda ─────────────────────────────────────────

    @Bean(name = "inventoryDataSourceProperties")
    @ConfigurationProperties("opsvision.datasource.inventory")
    DataSourceProperties inventoryDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "inventoryDataSource")
    @ConfigurationProperties("opsvision.datasource.inventory.hikari")
    HikariDataSource inventoryDataSource(
            @Qualifier("inventoryDataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "inventoryJdbcClient")
    JdbcClient inventoryJdbcClient(@Qualifier("inventoryDataSource") DataSource ds) {
        return JdbcClient.create(ds);
    }

    // ─── warehouse_stock_movement_marunda ────────────────────────────────────

    @Bean(name = "movementDataSourceProperties")
    @ConfigurationProperties("opsvision.datasource.movement")
    DataSourceProperties movementDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "movementDataSource")
    @ConfigurationProperties("opsvision.datasource.movement.hikari")
    HikariDataSource movementDataSource(
            @Qualifier("movementDataSourceProperties") DataSourceProperties props) {
        return props.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "movementJdbcClient")
    JdbcClient movementJdbcClient(@Qualifier("movementDataSource") DataSource ds) {
        return JdbcClient.create(ds);
    }
}
