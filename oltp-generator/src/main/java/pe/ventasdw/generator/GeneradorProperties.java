package pe.ventasdw.generator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Parámetros del generador (prefijo "generador"). Los valores por defecto están en application.yml y se
 * pueden sobrescribir con variables de entorno, por ejemplo GENERADOR_ANOS_HISTORICO=1.
 */
@ConfigurationProperties(prefix = "generador")
public record GeneradorProperties(
        long semilla,
        int anosHistorico,
        int pedidosPorDia,
        int clientes,
        double tasaErrores,
        double proporcionOnline,
        boolean cargaInicial,
        boolean flujoDiarioActivo,
        String cron,
        String directorioCsv) {
}
