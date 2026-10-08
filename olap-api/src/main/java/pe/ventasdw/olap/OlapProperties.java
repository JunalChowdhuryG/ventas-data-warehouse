package pe.ventasdw.olap;

import org.springframework.boot.context.properties.ConfigurationProperties;
 
@ConfigurationProperties(prefix = "olap")
public record OlapProperties(boolean cacheActiva, int cacheTtlSegundos) {
}
