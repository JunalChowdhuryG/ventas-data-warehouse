package pe.ventasdw.olap.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

 
public final class CuboVentas {

    public enum Tipo { ENTERO, TEXTO, FECHA }
 
    public record Atributo(String id, String dimension, String etiqueta, String expresion, Tipo tipo) {
    }

    public record Dimension(String id, String etiqueta, String tabla, String alias, String union, List<String> jerarquia) {
    }

    public record Medida(String id, String etiqueta, String descripcion, String sql, boolean aditiva) {
    } 
    public record Agregado(String tabla, Map<String, String> columnasPorAtributo, Map<String, String> medidasSql) {
    }
 
    private static final String PEDIDOS = "COUNT(DISTINCT (f.pedido_id::bigint * 16 + f.canal_key + 1))";

    private static final Map<String, Dimension> DIMENSIONES = new LinkedHashMap<>();
    private static final Map<String, Atributo> ATRIBUTOS = new LinkedHashMap<>();
    private static final Map<String, Medida> MEDIDAS = new LinkedHashMap<>();
    private static final List<Agregado> AGREGADOS;

    static {
        dimension("fecha", "Fecha", "dwh.dim_fecha", "d", "d.fecha_key = f.fecha_key",
                a("anio", "Año", "d.anio", Tipo.ENTERO), a("trimestre", "Trimestre", "d.trimestre", Tipo.ENTERO),
                a("mes", "Mes", "d.mes", Tipo.ENTERO), a("dia", "Día", "d.fecha", Tipo.FECHA));
        dimension("producto", "Producto", "dwh.dim_producto", "p", "p.producto_key = f.producto_key",
                a("categoria", "Categoría", "p.categoria", Tipo.TEXTO), a("producto", "Producto", "p.producto", Tipo.TEXTO));
        dimension("cliente", "Cliente", "dwh.dim_cliente", "c", "c.cliente_key = f.cliente_key",
                a("cliente", "Cliente", "c.cliente", Tipo.TEXTO));
        dimension("geografia", "Geografía", "dwh.dim_geografia", "g", "g.geografia_key = f.geografia_key",
                a("pais", "País", "g.pais", Tipo.TEXTO), a("departamento", "Departamento", "g.departamento", Tipo.TEXTO),
                a("provincia", "Provincia", "g.provincia", Tipo.TEXTO), a("ciudad", "Ciudad", "g.ciudad", Tipo.TEXTO));
        dimension("empleado", "Empleado", "dwh.dim_empleado", "e", "e.empleado_key = f.empleado_key",
                a("cargo", "Cargo", "e.cargo", Tipo.TEXTO), a("empleado", "Empleado", "e.empleado", Tipo.TEXTO));
        dimension("canal", "Canal", "dwh.dim_canal", "k", "k.canal_key = f.canal_key",
                a("canal", "Canal", "k.canal", Tipo.TEXTO));

        medida("ventas", "Ventas (S/)", "Suma del importe total", "SUM(f.importe_total)", true);
        medida("unidades", "Unidades", "Suma de unidades vendidas", "SUM(f.cantidad)", true);
        medida("pedidos", "Pedidos", "Pedidos distintos (no es aditiva entre niveles)", PEDIDOS, false);
        medida("lineas", "Líneas", "Líneas de pedido", "COUNT(*)", true);
        medida("ticket_promedio", "Ticket promedio (S/)", "Ventas entre pedidos",
                "round(SUM(f.importe_total) / NULLIF(" + PEDIDOS + ", 0), 2)", false);
        medida("precio_promedio", "Precio promedio (S/)", "Promedio del precio unitario", "round(AVG(f.precio_unitario), 2)", false);
        medida("descuento_promedio", "Descuento promedio (%)", "Promedio del descuento aplicado",
                "round(AVG(f.descuento) * 100, 2)", false);

        AGREGADOS = List.of(
                new Agregado("dwh.mv_ventas_mensual_categoria",
                        Map.of("fecha.anio", "anio", "fecha.mes", "mes", "producto.categoria", "categoria"),
                        Map.of("ventas", "SUM(m.ventas)", "unidades", "SUM(m.unidades)::bigint")),
                new Agregado("dwh.mv_ventas_mensual_departamento",
                        Map.of("fecha.anio", "anio", "fecha.mes", "mes", "geografia.departamento", "departamento", "canal.canal", "canal"),
                        Map.of("ventas", "SUM(m.ventas)", "unidades", "SUM(m.unidades)::bigint")));
    }

    private CuboVentas() {
    }

    private record NivelDef(String id, String etiqueta, String expresion, Tipo tipo) {
    }

    private static NivelDef a(String id, String etiqueta, String expresion, Tipo tipo) {
        return new NivelDef(id, etiqueta, expresion, tipo);
    }

    private static void dimension(String id, String etiqueta, String tabla, String alias, String union, NivelDef... niveles) {
        List<String> jerarquia = java.util.Arrays.stream(niveles).map(n -> id + "." + n.id()).toList();
        DIMENSIONES.put(id, new Dimension(id, etiqueta, tabla, alias, union, jerarquia));
        for (NivelDef n : niveles) {
            ATRIBUTOS.put(id + "." + n.id(), new Atributo(id + "." + n.id(), id, n.etiqueta(), n.expresion(), n.tipo()));
        }
    }

    private static void medida(String id, String etiqueta, String descripcion, String sql, boolean aditiva) {
        MEDIDAS.put(id, new Medida(id, etiqueta, descripcion, sql, aditiva));
    }
 
    public static Map<String, Dimension> dimensiones() {
        return java.util.Collections.unmodifiableMap(DIMENSIONES);
    }

    public static Map<String, Atributo> atributos() {
        return java.util.Collections.unmodifiableMap(ATRIBUTOS);
    }

    public static Map<String, Medida> medidas() {
        return java.util.Collections.unmodifiableMap(MEDIDAS);
    }

    public static List<Agregado> agregados() {
        return AGREGADOS;
    }

    public static Atributo atributo(String id) {
        Atributo a = id == null ? null : ATRIBUTOS.get(id);
        if (a == null) {
            throw new OlapException("Atributo desconocido: " + id + ". Atributos válidos: " + ATRIBUTOS.keySet());
        }
        return a;
    }

    public static Medida medida(String id) {
        Medida m = id == null ? null : MEDIDAS.get(id);
        if (m == null) {
            throw new OlapException("Medida desconocida: " + id + ". Medidas válidas: " + MEDIDAS.keySet());
        }
        return m;
    }

    public static Dimension dimension(String id) {
        Dimension d = id == null ? null : DIMENSIONES.get(id);
        if (d == null) {
            throw new OlapException("Dimensión desconocida: " + id + ". Dimensiones válidas: " + DIMENSIONES.keySet());
        }
        return d;
    }
}
