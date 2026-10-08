package pe.ventasdw.olap.core;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Ejecuta las pruebas del motor OLAP contra un PostgreSQL 16 de Testcontainers (requiere Docker). */
@Testcontainers
class OlapIntegracionTest extends OlapIntegracionBase {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse("postgres:16"));

    @Override
    protected String urlServidor() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/";
    }

    @Override
    protected String usuario() {
        return POSTGRES.getUsername();
    }

    @Override
    protected String clave() {
        return POSTGRES.getPassword();
    }

    @Override
    protected String baseAdministrativa() {
        return POSTGRES.getDatabaseName();
    }

    /** Las migraciones del DWH se copian al classpath de pruebas desde etl-service (ver pom.xml). */
    @Override
    protected void migrarDwh(PGSimpleDataSource dwh) {
        Flyway.configure().dataSource(dwh).locations("classpath:db/dwh").defaultSchema("public").load().migrate();
    }
}
