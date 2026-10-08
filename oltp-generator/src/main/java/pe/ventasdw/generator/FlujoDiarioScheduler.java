package pe.ventasdw.generator;

import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pe.ventasdw.generator.core.GeneradorVentas;

/** Genera el flujo del día según generador.cron (por defecto, todos los días a la 1:00, hora de Lima). */
@Component
public class FlujoDiarioScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(FlujoDiarioScheduler.class);

    private final GeneradorVentas generador;
    private final GeneradorProperties props;

    public FlujoDiarioScheduler(GeneradorVentas generador, GeneradorProperties props) {
        this.generador = generador;
        this.props = props;
    }

    @Scheduled(cron = "${generador.cron}", zone = "America/Lima")
    public void generarFlujoDelDia() {
        if (!props.flujoDiarioActivo()) {
            return;
        }
        LocalDate hoy = LocalDate.now(ZoneId.of("America/Lima"));
        LOG.info("Flujo diario {}: {}", hoy, generador.generarDia(hoy));
    }
}
