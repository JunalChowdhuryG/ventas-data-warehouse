package pe.ventasdw.common;

/**
 * Tipos de carga del ETL. Coinciden con el CHECK de etl.ejecucion.tipo_carga.
 */
public enum TipoCarga {
    INICIAL,
    INCREMENTAL,
    TOTAL
}
