package pe.ventasdw.etl.core;

import java.math.BigDecimal;
import java.nio.file.Path;

/**
 * Parametros del ETL.
 *
 * @param directorioCsv   carpeta con los CSV del canal online
 * @param tipoCambioUsd   soles por dolar para convertir los precios del CSV
 * @param solapeMinutos   se relee esta ventana antes del watermark; es seguro porque las cargas son idempotentes
 *                        y protege contra transacciones del origen que se confirmaron tarde
 * @param tamanoLote      filas por lote al volcar a staging
 */
public record EtlConfig(Path directorioCsv, BigDecimal tipoCambioUsd, int solapeMinutos, int tamanoLote) {

    public EtlConfig {
        if (tipoCambioUsd == null || tipoCambioUsd.signum() <= 0) {
            throw new IllegalArgumentException("tipoCambioUsd debe ser positivo");
        }
        if (solapeMinutos < 0 || tamanoLote <= 0) {
            throw new IllegalArgumentException("solapeMinutos >= 0 y tamanoLote > 0");
        }
    }
}
