package pe.ventasdw.etl.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**

 */
@Configuration(proxyBeanMethods = false)
public class DataSourcesConfig {

    @Bean
    @Primary
    @ConfigurationProperties("dwh.datasource")
    public HikariDataSource dwhDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @ConfigurationProperties("oltp.datasource")
    public HikariDataSource oltpDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @Primary
    public JdbcTemplate dwhJdbcTemplate(@Qualifier("dwhDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public JdbcTemplate oltpJdbcTemplate(@Qualifier("oltpDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
