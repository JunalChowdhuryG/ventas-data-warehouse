package pe.ventasdw.generator.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvOnlineWriterTest {

    private static LineaOnline linea(String descripcion, BigDecimal precio) {
        return new LineaOnline(1, 1, LocalDate.of(2026, 3, 10), "ENT", 7, "Ana \"Anita\" Ruiz", null, 3, 12,
                descripcion, "Snacks", 2, precio, new BigDecimal("0.05"), "USD",
                OffsetDateTime.parse("2026-03-10T15:30:00-05:00"));
    }

    @Test
    void losNulosSeEscribenComoCeldaVacia() {
        assertThat(CsvOnlineWriter.celda(null)).isEmpty();
    }

    @Test
    void entrecomillaCeldasConComaComillasOSaltosDeLinea() {
        assertThat(CsvOnlineWriter.celda("Galletas, vainilla")).isEqualTo("\"Galletas, vainilla\"");
        assertThat(CsvOnlineWriter.celda("Ana \"Anita\"")).isEqualTo("\"Ana \"\"Anita\"\"\"");
        assertThat(CsvOnlineWriter.celda("sin especiales")).isEqualTo("sin especiales");
    }

    @Test
    void laFilaTieneUnaCeldaPorColumnaYPrecioNuloVacio() {
        String fila = CsvOnlineWriter.fila(linea("Papas fritas grande", null));
        assertThat(fila).startsWith("1,1,2026-03-10,ENT,7,\"Ana \"\"Anita\"\" Ruiz\",,3,12,Papas fritas grande,Snacks,2,,0.05,USD,");
    }

    @Test
    void escribeEncabezadoYFilasSinDejarArchivosTemporales(@TempDir Path dir) throws IOException {
        Path archivo = CsvOnlineWriter.escribir(dir, LocalDate.of(2026, 3, 10),
                List.of(linea("Galletas, vainilla", new BigDecimal("1.80"))));

        assertThat(archivo.getFileName().toString()).isEqualTo("ventas_online_20260310.csv");
        List<String> lineas = Files.readAllLines(archivo);
        assertThat(lineas).hasSize(2);
        assertThat(lineas.get(0)).isEqualTo(String.join(",", CsvOnlineWriter.ENCABEZADO));
        assertThat(lineas.get(1)).contains("\"Galletas, vainilla\"");
        try (Stream<Path> archivos = Files.list(dir)) {
            assertThat(archivos.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    @Test
    void siYaExisteUnArchivoDeEsaFechaUsaSufijo(@TempDir Path dir) throws IOException {
        LocalDate fecha = LocalDate.of(2026, 3, 10);
        List<LineaOnline> una = List.of(linea("Papas", new BigDecimal("1.00")));
        CsvOnlineWriter.escribir(dir, fecha, una);
        Path segundo = CsvOnlineWriter.escribir(dir, fecha, una);
        Path tercero = CsvOnlineWriter.escribir(dir, fecha, una);

        assertThat(segundo.getFileName().toString()).isEqualTo("ventas_online_20260310_2.csv");
        assertThat(tercero.getFileName().toString()).isEqualTo("ventas_online_20260310_3.csv");
    }
}
