package pe.ventasdw.etl;

import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import pe.ventasdw.etl.core.EtlConfig;
import pe.ventasdw.etl.core.EtlService;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(EtlProperties.class)
public class EtlBeans {

    @Bean
    public EtlService etlService(@Qualifier("dwhDataSource") DataSource dwh,
                                 @Qualifier("oltpDataSource") DataSource oltp,
                                 EtlProperties p) {
        return new EtlService(dwh, oltp, new EtlConfig(Path.of(p.csvDirectorio()), p.tipoCambioUsd(), p.solapeMinutos(), 5_000));
    }
}
