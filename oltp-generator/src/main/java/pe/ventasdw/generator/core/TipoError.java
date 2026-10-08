package pe.ventasdw.generator.core;

/** Errores de calidad que el generador inyecta a propósito para ejercitar las reglas del ETL. */
public enum TipoError {
    /** precio_unitario nulo o vacío (missing value). El ETL lo imputa con precio_lista. */
    PRECIO_NULO,
    /** cantidad menor o igual a 0. El ETL rechaza la línea. */
    CANTIDAD_NO_POSITIVA,
    /** precio unitario 50 a 100 veces el normal (outlier). El ETL lo carga y registra una advertencia. */
    PRECIO_ATIPICO,
    /** producto_id nulo en el OLTP. El ETL usa el miembro Desconocido. */
    PRODUCTO_NULO,
    /** producto_id que no existe en el catálogo (solo CSV). El ETL usa el miembro Desconocido. */
    PRODUCTO_INEXISTENTE,
    /** fecha de pedido futura. El ETL rechaza el registro. */
    FECHA_FUTURA,
    /** línea repetida en el CSV. El ETL conserva una sola. */
    LINEA_DUPLICADA
}
