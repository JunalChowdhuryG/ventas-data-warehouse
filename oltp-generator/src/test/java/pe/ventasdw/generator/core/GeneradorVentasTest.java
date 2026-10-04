package pe.ventasdw.generator.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
 
@Testcontainers
class GeneradorVentasTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16"));

    static PGSimpleDataSource dataSource;

    @BeforeAll
    static void migrar() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/oltp")
                .load()
                .migrate();
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
    }

    @Test
    void generaHistoricoYFlujoDiarioConsistentes(@TempDir Path csv) throws SQLException, IOException { 
        var cfg = new GeneradorVentas.Config(1L, 1, 40, 100, 0.20, 0.30, csv);
        var generador = new GeneradorVentas(dataSource, cfg);
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Lima"));

        assertThat(generador.oltpVacio()).isTrue();
 
        var historico = generador.cargarHistorico(hoy.minusDays(1));
        assertThat(generador.oltpVacio()).isFalse();
        assertThat(historico.dias()).isBetween(365, 366);
        assertThat(historico.pedidos()).isGreaterThan(historico.dias());
 
        assertThat(contar("SELECT count(*) FROM detalle_pedido") + historico.lineasOnline())
                .isEqualTo(historico.lineas()); 
        assertThat(contar("SELECT count(*) FROM qa_error_inyectado")).isEqualTo(historico.erroresInyectados());
        assertThat(contar("SELECT count(*) FROM qa_error_inyectado WHERE fuente = 'OLTP' AND tipo = 'PRECIO_NULO'"))
                .isEqualTo(contar("SELECT count(*) FROM detalle_pedido WHERE precio_unitario IS NULL")); 
        assertThat(contar("SELECT count(*) FROM qa_error_inyectado WHERE fuente = 'OLTP' AND tipo = 'FECHA_FUTURA'"))
                .isEqualTo(contar("SELECT count(*) FROM pedido WHERE fecha_pedido > DATE '" + hoy + "'")); 
        assertThat(contar("SELECT count(*) FROM pedido WHERE updated_at < now() - interval '1 day'")).isPositive(); 
        assertThat(archivosCsv(csv)).isEqualTo(historico.archivosCsv());
 
        var dia = generador.generarDia(hoy);
        assertThat(dia.dias()).isEqualTo(1);
        assertThat(dia.pedidos()).isPositive();
        assertThat(archivosCsv(csv)).isEqualTo(historico.archivosCsv() + dia.archivosCsv());
 
        generador.generarDia(hoy);
        try (Stream<Path> archivos = Files.list(csv)) {
            assertThat(archivos.map(p -> p.getFileName().toString()))
                    .contains("ventas_online_" + hoy.toString().replace("-", "") + "_2.csv");
        }
    }

    private static long contar(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long archivosCsv(Path dir) throws IOException {
        try (Stream<Path> archivos = Files.list(dir)) {
            return archivos.filter(p -> p.getFileName().toString().endsWith(".csv")).count();
        }
    }
}
