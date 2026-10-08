package pe.ventasdw.common;

import java.util.Arrays;

/**
 * Canales de venta. Las claves coinciden con las filas sembradas en dwh.dim_canal (migración V5).
 */
public enum Canal {
    DESCONOCIDO((short) -1),
    TIENDA((short) 1),
    ONLINE((short) 2);

    private final short clave;

    Canal(short clave) {
        this.clave = clave;
    }

    /** Clave sustituta en dwh.dim_canal. */
    public short clave() {
        return clave;
    }

    /** Resuelve un código de origen (TIENDA, ONLINE...) ignorando mayúsculas; si no existe, DESCONOCIDO. */
    public static Canal desdeCodigo(String codigo) {
        if (codigo == null) {
            return DESCONOCIDO;
        }
        return Arrays.stream(values())
                .filter(c -> c.name().equalsIgnoreCase(codigo.trim()))
                .findFirst()
                .orElse(DESCONOCIDO);
    }
}
