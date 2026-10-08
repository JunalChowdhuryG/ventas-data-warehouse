package pe.ventasdw.etl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pe.ventasdw.etl.core.EtlResultado;
import pe.ventasdw.etl.core.EtlService;

/** Ejecuta el ETL según etl.cron (por defecto a la 1:30, hora de Lima). El tipo de carga lo decide el propio ETL. */
@Component
public class EtlScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(EtlScheduler.class);

    private final EtlOrquestador orquestador;
    private final EtlProperties props;

    public EtlScheduler(EtlOrquestador orquestador, EtlProperties props) {
        this.orquestador = orquestador;
        this.props = props;
    }

    @Scheduled(cron = "${etl.cron}", zone = "America/Lima")
    public void programado() {
        if (!props.activo()) {
            return;
        }
        try {
            EtlResultado r = orquestador.ejecutar(EtlService.Modo.AUTO);
            LOG.info("ETL programado {}: extraídas={}, cargadas={}, rechazadas={}, {} ms",
                    r.tipoCarga(), r.filasExtraidas(), r.filasCargadas(), r.filasRechazadas(), r.duracionTotalMs());
        } catch (RuntimeException e) {
            LOG.error("El ETL programado falló", e);
        }
    }
}
