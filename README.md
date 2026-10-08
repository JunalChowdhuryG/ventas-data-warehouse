# VentasDW

Plataforma de **Data Warehouse y análisis OLAP para ventas**: un OLTP simulado, un ETL con carga inicial e
incremental, un DWH en esquema estrella (PostgreSQL) y una API con las operaciones OLAP clásicas
(drill-down, drill-up, drill-across, roll-across, pivot y page), con observabilidad y CI.

El diseño completo (arquitectura, modelos, secuencias y roadmap) está en el documento de diseño del proyecto.

## Arquitectura

| Capa del DWH | Componente | Módulo / servicio |
|---|---|---|
| OLTP | PostgreSQL OLTP + generador de ventas | `oltp-generator`, `postgres-oltp` |
| Load Manager (ETL) | Extracción, staging, transformación, carga | `etl-service` (JDBC y SQL por conjuntos) |
| DW Manager | PostgreSQL DWH: estrella, particiones, agregados | `postgres-dwh` (migraciones en `etl-service`) |
| Query Manager | Operaciones OLAP + caché | `olap-api` (Redis) |
| Observabilidad | Métricas y dashboards | Prometheus + Grafana |

## Requisitos

- Java 21 y Maven 3.9+
- Docker y Docker Compose (también lo usan las pruebas con Testcontainers)

## Inicio rápido

```bash
make init            # crea .env desde .env.example (solo valores de desarrollo)
make infra-up        # PostgreSQL x2, Redis, Prometheus y Grafana
make run-generator   # aplica las migraciones del OLTP        (puerto 8082)
make run-etl         # aplica las migraciones del DWH         (puerto 8081)
make verify-dwh      # prueba el DWH con datos de ejemplo (hace ROLLBACK)
make run-api         #                                        (puerto 8080)
```

Alternativa: `make apps-up` construye y levanta los tres servicios como contenedores.
No combines ambas formas a la vez: los contenedores y `make run-*` usan los mismos puertos (8080 a 8082).
`make verify-dwh` necesita que `etl-service` ya haya arrancado una vez, porque es quien aplica las migraciones del DWH.
Si ejecutas los servicios desde Maven o el IDE, ajusta `infra/prometheus/prometheus.yml`
(hay un job comentado para `host.docker.internal`).

| Servicio | URL / puerto |
|---|---|
| PostgreSQL OLTP | `localhost:5432` (base `oltp`) |
| PostgreSQL DWH | `localhost:5433` (base `dwh`) |
| Redis | `localhost:6379` |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (usuario `admin`) |
| Métricas de cada servicio | `/actuator/prometheus` |

## Generador de datos (`oltp-generator`)

Al arrancar con el OLTP vacío carga **dos años de ventas** (unos 125 mil pedidos y 290 mil líneas en
unos 30 segundos). Cada día genera tres cosas:

- **Canal tienda:** pedidos y líneas en el OLTP (PostgreSQL).
- **Canal online:** un archivo CSV por día en `generador.directorio-csv` (en Docker, el volumen `csv-data`,
  que el ETL monta en solo lectura). Los precios vienen en USD (85 %) o PEN, y los `pedido_online_id`
  pueden coincidir con ids del OLTP a propósito: la clave de la tabla de hechos incluye el canal.
- **Flujo diario:** pedidos nuevos, avance de estados (PENDIENTE a ENTREGADO, con variantes de codificación
  como `E`, `ENT` o `Entregado`) y cambios en maestros (un producto cambia de categoría, clientes cambian de
  correo, nombre o ciudad). Esos cambios son los que el ETL debe versionar con SCD tipo 2.

El modelo tiene estacionalidad (diciembre, Fiestas Patrias, fin de semana), popularidad desigual de productos y
clientes, y descuentos discretos. Con la misma semilla produce los mismos datos del histórico.

**Errores inyectados a propósito** (1 % de las líneas, `generador.tasa-errores`), para ejercitar las reglas de
calidad del ETL. Cada uno queda registrado en `qa_error_inyectado` (OLTP), de modo que se puede comparar con
`etl.excepcion`:

| Tipo | Dónde | Qué debe hacer el ETL |
|---|---|---|
| `PRECIO_NULO` | OLTP y CSV | Imputar con `precio_lista` y registrar «corregido» |
| `CANTIDAD_NO_POSITIVA` | OLTP y CSV | Rechazar y registrar |
| `PRECIO_ATIPICO` (50 a 100 veces el normal) | OLTP y CSV | Cargar y registrar advertencia |
| `PRODUCTO_NULO` | OLTP | Usar el miembro Desconocido y registrar |
| `PRODUCTO_INEXISTENTE` | CSV | Usar el miembro Desconocido y registrar |
| `FECHA_FUTURA` | OLTP y CSV | Rechazar y registrar |
| `LINEA_DUPLICADA` | CSV | Conservar una sola |

Controles útiles:

```bash
make generar-dia                        # fuerza el flujo de hoy (cambios en maestros, estados y pedidos)
make generar-dia FECHA=2026-10-03       # o el de una fecha concreta
```

Parámetros (en `application.yml` o variables de entorno `GENERADOR_*`, escribiendo el nombre sin guiones: `GENERADOR_PEDIDOSPORDIA`): semilla, años de histórico,
pedidos por día, clientes, tasa de errores, proporción online y cron del flujo diario (1:00, hora de Lima).
Para una prueba rápida: `GENERADOR_ANOSHISTORICO=1`.

Formato del CSV (`ventas_online_AAAAMMDD.csv`, UTF-8, coma como separador, celda vacía = nulo):

```
pedido_online_id,linea,fecha_pedido,estado,cliente_id,razon_social,email,ciudad_id,producto_id,
descripcion,categoria,cantidad,precio_unitario,descuento,moneda,updated_at
```

## ETL (`etl-service`)

Un ejecución hace, en este orden: **extracción** a staging (maestros, líneas del OLTP y CSV nuevos),
**dimensiones** (SCD1 en geografía y empleado, SCD2 en producto y cliente), **reglas de calidad** sobre las
líneas, **carga de hechos** con claves sustitutas y **control** (watermarks y archivos procesados). Las cuatro
últimas fases van en una sola transacción: si algo falla, no cambia nada y el watermark no avanza.

- **Inicial / incremental / total:** el ETL decide solo entre inicial e incremental (según `etl.control`).
  La incremental extrae por `updated_at` desde el watermark menos 5 minutos de solape; repetirla no cambia nada
  (las cargas son idempotentes). La total reconstruye el DWH desde cero.
- **Programación:** todos los días a la 1:30 (hora de Lima), configurable en `etl.cron`.
- **Cancelaciones:** un pedido que pasa a CANCELADO o DEVUELTO sale del DWH.

Reglas de calidad (cada excepción queda en `etl.excepcion` con su regla y severidad):

| Regla | Severidad | Acción |
|---|---|---|
| `LINEA_DUPLICADA` | Advertencia | Se conserva el registro más reciente |
| `CLAVE_NULA`, `FECHA_FUTURA`, `FECHA_FUERA_DE_RANGO`, `CANTIDAD_NO_POSITIVA`, `PRECIO_NO_IMPUTABLE` | Error | La línea no se carga |
| `PRECIO_NULO` | Corregido | Se imputa con el precio de lista y se registra |
| `PRODUCTO_NULO`, `PRODUCTO_INEXISTENTE`, `CLIENTE_NULO`, `CLIENTE_INEXISTENTE`, `EMPLEADO_INEXISTENTE` | Corregido | Se usa el miembro Desconocido (clave -1) |
| `PRECIO_ATIPICO` (más de 5 veces el precio de lista) | Advertencia | Se carga y se registra |
| `ESTADO_DESCONOCIDO` | Advertencia | Se carga y se registra |

**Limitación conocida:** una línea rechazada por `FECHA_FUTURA` no se vuelve a evaluar cuando llega su fecha, porque
la carga incremental solo relee lo que cambió en el origen. Si necesitas que entre, corrígela en el origen (eso
actualiza `updated_at`) o ejecuta `make etl-total`, que reevalúa todo.

Los precios en dólares se convierten a soles con `etl.tipo-cambio-usd`. Los estados con variantes de escritura
(`E`, `ENT`, `Entregado`) se normalizan a `ENTREGADO`.

```bash
make etl                # ejecuta el ETL ahora
make etl-historial      # últimas ejecuciones
make etl-qa             # errores inyectados por el generador vs detectados por el ETL
make etl-total          # reconstruye el DWH desde cero
```

Métricas en `/actuator/prometheus`: `etl_ejecuciones_total`, `etl_filas_extraidas_total`,
`etl_filas_cargadas_total`, `etl_excepciones_total{regla,severidad}`, `etl_duracion_seconds{fase}` y
`etl_ultima_ejecucion_exitosa_timestamp`.

## API OLAP (`olap-api`)

Sirve el cubo de ventas sobre el esquema estrella, con las seis operaciones OLAP del curso. El servidor no guarda
estado: cada operación recibe un **contexto** (qué atributos van en filas y columnas, qué medidas, qué filtros) y
devuelve el contexto resultante junto con los datos y las operaciones que siguen disponibles.

| Operación | Endpoint | Efecto |
|---|---|---|
| Consulta | `POST /api/v1/olap/consulta` | Agrega medidas por los atributos elegidos, con filtros, orden, límite y subtotales |
| Drill-down | `POST /api/v1/olap/drill-down` | Baja un nivel en la jerarquía de una dimensión (año a trimestre); acepta un miembro para ver su detalle |
| Drill-up | `POST /api/v1/olap/drill-up` | Sube un nivel |
| Drill-across | `POST /api/v1/olap/drill-across` | Agrega un criterio de análisis (por ejemplo, desglosar por canal) |
| Roll-across | `POST /api/v1/olap/roll-across` | Quita un criterio y vuelve a agregar |
| Pivot | `POST /api/v1/olap/pivot` | Mueve atributos entre filas y columnas; la respuesta trae la tabla dinámica |
| Page | `POST /api/v1/olap/page` | Divide el cubo por los valores de un atributo, como páginas de un libro |
| Metadatos | `GET /api/v1/olap/metadatos` | Dimensiones, jerarquías, atributos, medidas y agregados |
| Valores | `GET /api/v1/olap/metadatos/valores?atributo=...&q=...` | Valores distintos de un atributo, para armar filtros |

Ejemplos de todas las operaciones en [`requests/olap.http`](requests/olap.http) (VS Code con REST Client o IntelliJ).
Un atajo: `make olap-ejemplo`.

```bash
curl -s -X POST localhost:8080/api/v1/olap/consulta -H 'Content-Type: application/json' \
  -d '{"filas":["fecha.anio","producto.categoria"],"medidas":["ventas","unidades"]}'
```

- **Cubo:** seis dimensiones (fecha, producto, cliente, geografía, empleado y canal) con jerarquías (año, trimestre,
  mes, día; categoría y producto; país, departamento, provincia y ciudad; cargo y empleado) y siete medidas
  (ventas, unidades, pedidos, líneas, ticket promedio, precio promedio y descuento promedio). Un atributo se escribe
  `dimension.nivel`, por ejemplo `fecha.anio`.
- **Agregados materializados:** si la consulta lo permite (medidas aditivas y atributos cubiertos), se responde desde
  una vista materializada; el campo `origen` de la respuesta lo indica. El resultado es idéntico al de la tabla de
  hechos (hay pruebas que lo comprueban). `"usarAgregados": false` lo desactiva.
- **Caché en Redis:** la clave incluye la última carga exitosa del ETL, así que al entrar datos nuevos las respuestas
  viejas dejan de usarse solas. El encabezado `X-Cache` dice `HIT`, `MISS` o `BYPASS`. Si Redis falla, la API sigue
  sin caché. `?sql=true` devuelve además el SQL generado y se salta la caché.
- **Seguridad:** ningún identificador SQL viene de la solicitud (todo sale de una lista blanca del cubo) y los valores
  de los filtros viajan como parámetros. La API se conecta con un usuario de solo lectura (`u_api`).
- **Errores:** una solicitud inválida responde `400` con `{"error": "..."}`.
- **Métricas:** `olap_consultas_total{operacion}`, `olap_consulta_duracion_seconds{operacion,origen}`,
  `olap_cache_aciertos_total` y `olap_cache_fallos_total`.

Latencia medida sobre 273 mil hechos (sin caché, equipo de pruebas): de 26 a 190 ms en consultas por agregados,
y hasta 480 ms en la peor (ventas y pedidos distintos por cada uno de los 730 días); todas con p95 por debajo de 500 ms.

## Estructura

```
ventas-dw/
├── dwh-common/        modelos compartidos (Canal, TipoCarga)
├── oltp-generator/    migraciones del OLTP y, en la Fase 1, el generador de datos
├── etl-service/       migraciones del DWH (V1 a V8), jobs de ETL, DataSources
├── olap-api/          Query Manager: operaciones OLAP, caché y métricas
├── infra/             init de PostgreSQL, Prometheus y Grafana (provisioning)
├── db/                verificar_dwh.sql
├── requests/          ejemplos de la API OLAP (olap.http)
└── docker-compose.yml
```

## Usuarios de base de datos

| Usuario | Base | Uso |
|---|---|---|
| `oltp` (dueño) | OLTP | migraciones y generador |
| `oltp_ro` | OLTP | extracción del ETL (solo lectura) |
| `dwh` (dueño) | DWH | migraciones de Flyway |
| `u_etl` (grupo `dwh_etl_rw`) | DWH | runtime del ETL |
| `u_api` (grupo `dwh_api_ro`) | DWH | `olap-api` y Grafana (solo lectura) |

## Estado del roadmap

| Fase | Contenido | Estado |
|---|---|---|
| F1 | Repositorio, compose, DDL del OLTP, generador de datos | Listo |
| F2 | Modelo dimensional (DDL del DWH) | DDL listo y verificado |
| F3 | ETL (staging, calidad, SCD2, cargas) | Listo |
| F4 | API OLAP + caché | Listo |
| F5 | Dashboards y alertas | Pendiente |
| F6 | CI/CD, README final, demo | CI base lista |

## Versiones

Spring Boot 4.1 (Spring Batch 6, Testcontainers 2.x), Java 21, PostgreSQL 16.
Fija versiones concretas de las imágenes de Prometheus y Grafana en `docker-compose.yml` antes de publicar.
