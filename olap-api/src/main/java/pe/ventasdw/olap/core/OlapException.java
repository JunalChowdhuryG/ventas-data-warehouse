package pe.ventasdw.olap.core;

/** Solicitud OLAP inválida (atributo desconocido, operación imposible, etc.). La API la responde como 400. */
public class OlapException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OlapException(String mensaje) {
        super(mensaje);
    }
}
