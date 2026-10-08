package pe.ventasdw.generator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Fase 1 del roadmap: aquí irá el generador de datos (histórico de dos años y flujo diario).
 * Por ahora el servicio solo aplica las migraciones del OLTP (Flyway) y expone Actuator.
 */
@SpringBootApplication
public class OltpGeneratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(OltpGeneratorApplication.class, args);
    }
}
