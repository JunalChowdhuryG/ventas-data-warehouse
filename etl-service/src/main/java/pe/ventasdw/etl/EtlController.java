package pe.ventasdw.etl;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.ventasdw.etl.core.EtlService;
import pe.ventasdw.etl.core.QaComparador;

@RestController
@RequestMapping("/api/v1/etl")
public class EtlController {

    private final EtlOrquestador orquestador;
    private final JdbcTemplate dwh;
    private final DataSource dwhDataSource;
    private final DataSource oltpDataSource;

    public EtlController(EtlOrquestador orquestador, JdbcTemplate dwh,
                         @Qualifier("dwhDataSource") DataSource dwhDataSource,
                         @Qualifier("oltpDataSource") DataSource oltpDataSource) {
        this.orquestador = orquestador;
        this.dwh = dwh;
        this.dwhDataSource = dwhDataSource;
        this.oltpDataSource = oltpDataSource;
    }
    
    @PostMapping("/ejecutar")
    public ResponseEntity<Object> ejecutar(@RequestParam(defaultValue = "auto") String modo) {
        EtlService.Modo m = "total".equalsIgnoreCase(modo) ? EtlService.Modo.TOTAL : EtlService.Modo.AUTO;
        try {
            return ResponseEntity.ok(orquestador.ejecutar(m));
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Ya hay una ejecucion")) {
                return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
            }
            throw e;
        }
    }
 
    @GetMapping("/ejecuciones")
    public List<Map<String, Object>> ejecuciones(@RequestParam(defaultValue = "20") int limite) {
        return dwh.queryForList("SELECT id, tipo_carga, estado, inicio, fin, filas_extraidas, filas_cargadas, "
                + "filas_rechazadas, mensaje FROM etl.ejecucion ORDER BY id DESC LIMIT ?", Math.max(1, Math.min(limite, 200)));
    }
 
    @GetMapping("/excepciones/resumen")
    public List<Map<String, Object>> resumenExcepciones(@RequestParam(required = false) Long ejecucion) {
        String base = "SELECT regla, severidad, count(*) AS filas FROM etl.excepcion";
        if (ejecucion == null) {
            return dwh.queryForList(base + " GROUP BY regla, severidad ORDER BY filas DESC");
        }
        return dwh.queryForList(base + " WHERE ejecucion_id = ? GROUP BY regla, severidad ORDER BY filas DESC", ejecucion);
    }
 
    @GetMapping("/calidad/comparacion-qa")
    public List<QaComparador.Fila> comparacionQa() {
        return QaComparador.comparar(oltpDataSource, dwhDataSource);
    }
}
