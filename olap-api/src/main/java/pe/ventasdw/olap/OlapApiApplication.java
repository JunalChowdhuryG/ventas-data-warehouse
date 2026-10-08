package pe.ventasdw.olap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Query Manager. En la Fase 4 se agregan los endpoints OLAP (drill-down, drill-up, drill-across,
 * roll-across, pivot, page y metadatos). Se conecta al DWH con un usuario de solo lectura.
 */
@SpringBootApplication
public class OlapApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(OlapApiApplication.class, args);
    }
}
