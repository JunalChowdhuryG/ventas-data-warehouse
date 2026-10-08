package pe.ventasdw.etl;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Parámetros del ETL (prefijo "etl"). Los valores por defecto están en application.yml. */
@ConfigurationProperties(prefix = "etl")
public record EtlProperties(
        String csvDirectorio,
        BigDecimal tipoCambioUsd,
        int solapeMinutos,
        String cron,
        boolean activo) {
}
