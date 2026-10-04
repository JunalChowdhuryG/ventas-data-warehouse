package pe.ventasdw.generator.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Escribe los archivos CSV del canal online 
 */
public final class CsvOnlineWriter {

    static final String[] ENCABEZADO = {
        "pedido_online_id", "linea", "fecha_pedido", "estado", "cliente_id", "razon_social", "email",
        "ciudad_id", "producto_id", "descripcion", "categoria", "cantidad", "precio_unitario",
        "descuento", "moneda", "updated_at"
    };

    private static final DateTimeFormatter DIA = DateTimeFormatter.BASIC_ISO_DATE;

    private CsvOnlineWriter() {
    }
 
    public static Path escribir(Path directorio, LocalDate fecha, List<LineaOnline> lineas) throws IOException {
        Files.createDirectories(directorio);
        Path destino = nombreDisponible(directorio, fecha);
        Path temporal = directorio.resolve(destino.getFileName() + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(temporal, StandardCharsets.UTF_8)) {
            w.write(String.join(",", ENCABEZADO));
            w.write('\n');
            for (LineaOnline l : lineas) {
                w.write(fila(l));
                w.write('\n');
            }
        }
        Files.move(temporal, destino, StandardCopyOption.ATOMIC_MOVE);
        return destino;
    }

    static Path nombreDisponible(Path directorio, LocalDate fecha) {
        Path candidato = directorio.resolve("ventas_online_" + DIA.format(fecha) + ".csv");
        int n = 2;
        while (Files.exists(candidato)) {
            candidato = directorio.resolve("ventas_online_" + DIA.format(fecha) + "_" + n + ".csv");
            n++;
        }
        return candidato;
    }

    static String fila(LineaOnline l) {
        return String.join(",",
                celda(l.pedidoOnlineId()), celda(l.linea()), celda(l.fechaPedido()), celda(l.estado()),
                celda(l.clienteId()), celda(l.razonSocial()), celda(l.email()), celda(l.ciudadId()),
                celda(l.productoId()), celda(l.descripcion()), celda(l.categoria()), celda(l.cantidad()),
                celda(l.precioUnitario()), celda(l.descuento()), celda(l.moneda()), celda(l.updatedAt()));
    }
 
    static String celda(Object valor) {
        if (valor == null) {
            return "";
        }
        String s = valor.toString();
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
