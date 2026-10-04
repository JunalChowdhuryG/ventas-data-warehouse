package pe.ventasdw.generator.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
 
public record LineaOnline(
        long pedidoOnlineId,
        int linea,
        LocalDate fechaPedido,
        String estado,
        int clienteId,
        String razonSocial,
        String email,
        int ciudadId,
        Integer productoId,
        String descripcion,
        String categoria,
        Integer cantidad,
        BigDecimal precioUnitario,
        BigDecimal descuento,
        String moneda,
        OffsetDateTime updatedAt) {
}
