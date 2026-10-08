package pe.ventasdw.olap.core;

import java.math.BigDecimal;
import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.Map;

/**
 * Serializador JSON mínimo para las respuestas OLAP (mapas, listas, textos, números, booleanos, fechas y nulos).
 * Genera el texto directamente: así la respuesta se puede guardar tal cual en la caché y devolver sin reprocesar.
 */
public final class Json {

    private Json() {
    }

    public static String escribir(Object valor) {
        StringBuilder sb = new StringBuilder(256);
        escribir(sb, valor);
        return sb.toString();
    }

    private static void escribir(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            texto(sb, s);
        } else if (v instanceof BigDecimal d) {
            sb.append(d.toPlainString());
        } else if (v instanceof Double d) {
            sb.append(Double.isFinite(d) ? BigDecimal.valueOf(d).toPlainString() : "null");
        } else if (v instanceof Float f) {
            sb.append(Float.isFinite(f) ? BigDecimal.valueOf(f).toPlainString() : "null");
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof TemporalAccessor || v instanceof Enum<?>) {
            texto(sb, v.toString());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean primero = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!primero) {
                    sb.append(',');
                }
                primero = false;
                texto(sb, String.valueOf(e.getKey()));
                sb.append(':');
                escribir(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean primero = true;
            for (Object o : c) {
                if (!primero) {
                    sb.append(',');
                }
                primero = false;
                escribir(sb, o);
            }
            sb.append(']');
        } else {
            texto(sb, v.toString());
        }
    }

    private static void texto(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
