package pe.ventasdw.generator;

import java.nio.file.Path;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import pe.ventasdw.generator.core.GeneradorVentas;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(GeneradorProperties.class)
public class GeneradorConfig {

    @Bean
    public GeneradorVentas generadorVentas(DataSource dataSource, GeneradorProperties p) {
        return new GeneradorVentas(dataSource, new GeneradorVentas.Config(
                p.semilla(),
                p.anosHistorico(),
                p.pedidosPorDia(),
                p.clientes(),
                p.tasaErrores(),
                p.proporcionOnline(),
                Path.of(p.directorioCsv())));
    }
}
