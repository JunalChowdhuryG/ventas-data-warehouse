package pe.ventasdw.generator;

import org.springframework.boot.context.properties.ConfigurationProperties;


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
