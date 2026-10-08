package pe.ventasdw.olap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Parámetros de la API OLAP (prefijo "olap"). */
@ConfigurationProperties(prefix = "olap")
public record OlapProperties(boolean cacheActiva, int cacheTtlSegundos) {
}
