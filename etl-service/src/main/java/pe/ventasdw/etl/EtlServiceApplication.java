package pe.ventasdw.etl;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Load Manager. Al arrancar aplica las migraciones del DWH (Flyway). El ETL se ejecuta por el scheduler
 * (etl.cron), por la API (/api/v1/etl/ejecutar) o desde las pruebas.
 */
@SpringBootApplication
public class EtlServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EtlServiceApplication.class, args);
    }
}
