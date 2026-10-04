package pe.ventasdw.generator.core;

/** Errores de calidad   */
public enum TipoError {
    /** precio_unitario nulo o vacio  */
    PRECIO_NULO,
    /** cantidad menor o igual a 0 */
    CANTIDAD_NO_POSITIVA,
    /** precio unitario 50 a 100 veces el normal */
    PRECIO_ATIPICO,
    /** producto_id nulo en el OLTP  */
    PRODUCTO_NULO,
    /** producto_id que no existe en el catalogo  */
    PRODUCTO_INEXISTENTE,
    /** fecha de pedido futura  */
    FECHA_FUTURA,
    /** linea repetida en el CSV  */
    LINEA_DUPLICADA
}
