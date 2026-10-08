package pe.ventasdw.olap;

import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pe.ventasdw.olap.core.OlapService;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OlapProperties.class)
public class OlapBeans {

    @Bean
    public OlapService olapService(DataSource dataSource) {
        return new OlapService(dataSource);
    }
}
