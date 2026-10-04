package pe.ventasdw.generator;

import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import pe.ventasdw.generator.core.GeneradorVentas;


@Component
public class CargaInicialRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CargaInicialRunner.class);

    private final GeneradorVentas generador;
    private final GeneradorProperties props;

    public CargaInicialRunner(GeneradorVentas generador, GeneradorProperties props) {
        this.generador = generador;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.cargaInicial()) {
            LOG.info("Carga inicial desactivada (generador.carga-inicial=false)");
            return;
        }
        if (!generador.oltpVacio()) {
            LOG.info("El OLTP ya tiene pedidos: no se carga el historico");
            return;
        }
        LocalDate ayer = LocalDate.now(ZoneId.of("America/Lima")).minusDays(1);
        LOG.info("Cargando historico de {} año(s) hasta {} ...", props.anosHistorico(), ayer);
        long inicio = System.currentTimeMillis();
        var resumen = generador.cargarHistorico(ayer);
        LOG.info("Historico cargado en {} s: {}", (System.currentTimeMillis() - inicio) / 1000, resumen);
    }
}
