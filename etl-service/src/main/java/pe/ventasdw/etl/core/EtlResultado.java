package pe.ventasdw.etl.core;

import java.util.List;
import java.util.Map;
 
public record EtlResultado(
        long ejecucionId,
        String tipoCarga,
        String estado,
        long filasExtraidas,
        long filasCargadas,
        long filasRechazadas,
        long filasExcluidas,
        Map<String, Long> excepcionesPorRegla,
        Map<String, Long> detalle,
        Map<String, Long> duracionesMs,
        long duracionTotalMs,
        List<String> archivosCsv) {
}
