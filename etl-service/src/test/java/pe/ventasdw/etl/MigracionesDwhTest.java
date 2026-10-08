package pe.ventasdw.etl;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Comprueba que las ocho migraciones del DWH se aplican sobre una base vacía de PostgreSQL 16
 * y que los datos iniciales quedaron cargados. Requiere Docker.
 */
@Testcontainers
class MigracionesDwhTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16"));

    @Test
    void aplicaTodasLasMigracionesYSiembraLosDatosIniciales() throws SQLException {
        MigrateResult resultado = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration/dwh")
                .load()
                .migrate();

        assertThat(resultado.success).isTrue();
        assertThat(resultado.migrationsExecuted).isEqualTo(8);

        try (Connection c = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = c.createStatement()) {
            // Calendario 2020-01-01 a 2035-12-31: 16 años * 365 + 4 bisiestos
            assertThat(escalar(s, "SELECT count(*) FROM dwh.dim_fecha")).isEqualTo(5844L);
            // Miembros "Desconocido" y canales
            assertThat(escalar(s, "SELECT count(*) FROM dwh.dim_producto WHERE producto_key = -1")).isEqualTo(1L);
            assertThat(escalar(s, "SELECT count(*) FROM dwh.dim_canal")).isEqualTo(3L);
            // Particiones anuales 2024-2030 más la partición por defecto
            assertThat(escalar(s, "SELECT count(*) FROM pg_inherits WHERE inhparent = 'dwh.fact_ventas'::regclass"))
                    .isEqualTo(8L);
            // Control del ETL listo para la carga inicial
            assertThat(escalar(s, "SELECT count(*) FROM etl.control")).isEqualTo(7L);
        }
    }

    /**
     * Regresión: con el usuario dueño llamado "dwh" (como en docker-compose), tras la primera migración
     * existe un esquema "dwh" y PostgreSQL lo toma como esquema actual. Sin defaultSchema=public, el segundo
     * arranque fallaba con "Found non-empty schema(s) dwh but no schema history table".
     */
    @Test
    void sigueFuncionandoAlReiniciarConUsuarioDuenoLlamadoDwh() throws SQLException {
        try (Connection admin = DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement s = admin.createStatement()) {
            s.execute("CREATE ROLE dwh SUPERUSER LOGIN PASSWORD 'dwh'");
            s.execute("CREATE DATABASE dwh OWNER dwh");
        }
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/dwh";

        MigrateResult primero = flywayComoDwh(url).migrate();
        assertThat(primero.success).isTrue();
        assertThat(primero.migrationsExecuted).isEqualTo(8);

        // Segundo arranque: ya existe el esquema dwh
        MigrateResult reinicio = flywayComoDwh(url).migrate();
        assertThat(reinicio.success).isTrue();
        assertThat(reinicio.migrationsExecuted).isZero();
    }

    private static Flyway flywayComoDwh(String url) {
        return Flyway.configure()
                .dataSource(url, "dwh", "dwh")
                .locations("classpath:db/migration/dwh")
                .defaultSchema("public")
                .load();
    }

    private static long escalar(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
