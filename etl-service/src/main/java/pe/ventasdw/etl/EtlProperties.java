package pe.ventasdw.etl;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

 
@ConfigurationProperties(prefix = "etl")
public record EtlProperties(
        String csvDirectorio,
        BigDecimal tipoCambioUsd,
        int solapeMinutos,
        String cron,
        boolean activo) {
}
