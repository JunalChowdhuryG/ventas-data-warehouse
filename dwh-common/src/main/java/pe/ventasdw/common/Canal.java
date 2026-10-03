package pe.ventasdw.common;

import java.util.Arrays;


public enum Canal {
    DESCONOCIDO((short) -1),
    TIENDA((short) 1),
    ONLINE((short) 2);

    private final short clave;

    Canal(short clave) {
        this.clave = clave;
    }

    /** Clave sustituta en dwh.dim_canal*/
    public short clave() {
        return clave;
    }

    /** Resuelve un codigo de origen (TIENDA, ONLINE...) */
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
