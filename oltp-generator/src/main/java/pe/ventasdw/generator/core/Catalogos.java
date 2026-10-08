package pe.ventasdw.generator.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Datos semilla del catálogo: ciudades del Perú, categorías, productos y nombres de personas. */
final class Catalogos {

    record CiudadSemilla(String ciudad, String provincia, String departamento, int peso) {
    }

    record ProductoSemilla(String categoria, String nombre, double precio) {
    }

    static final List<CiudadSemilla> CIUDADES = List.of(
            new CiudadSemilla("Lima", "Lima", "Lima", 34),
            new CiudadSemilla("Callao", "Callao", "Callao", 6),
            new CiudadSemilla("Arequipa", "Arequipa", "Arequipa", 7),
            new CiudadSemilla("Trujillo", "Trujillo", "La Libertad", 6),
            new CiudadSemilla("Chiclayo", "Chiclayo", "Lambayeque", 5),
            new CiudadSemilla("Piura", "Piura", "Piura", 5),
            new CiudadSemilla("Cusco", "Cusco", "Cusco", 4),
            new CiudadSemilla("Huancayo", "Huancayo", "Junín", 3),
            new CiudadSemilla("Iquitos", "Maynas", "Loreto", 3),
            new CiudadSemilla("Pucallpa", "Coronel Portillo", "Ucayali", 2),
            new CiudadSemilla("Tacna", "Tacna", "Tacna", 2),
            new CiudadSemilla("Ica", "Ica", "Ica", 3),
            new CiudadSemilla("Juliaca", "San Román", "Puno", 2),
            new CiudadSemilla("Puno", "Puno", "Puno", 1),
            new CiudadSemilla("Cajamarca", "Cajamarca", "Cajamarca", 2),
            new CiudadSemilla("Ayacucho", "Huamanga", "Ayacucho", 2),
            new CiudadSemilla("Chimbote", "Santa", "Áncash", 2),
            new CiudadSemilla("Huaraz", "Huaraz", "Áncash", 1),
            new CiudadSemilla("Tarapoto", "San Martín", "San Martín", 1),
            new CiudadSemilla("Sullana", "Sullana", "Piura", 1),
            new CiudadSemilla("Huánuco", "Huánuco", "Huánuco", 1),
            new CiudadSemilla("Tumbes", "Tumbes", "Tumbes", 1),
            new CiudadSemilla("Moquegua", "Mariscal Nieto", "Moquegua", 1),
            new CiudadSemilla("Abancay", "Abancay", "Apurímac", 1));

    private static final String[][] PRESENTACION_VOLUMEN = {{"500 ml", "1.0"}, {"1 L", "1.7"}, {"1.5 L", "2.3"}, {"3 L", "3.8"}};
    private static final String[][] PRESENTACION_TAMANO = {{"pequeño", "1.0"}, {"mediano", "1.6"}, {"grande", "2.4"}, {"familiar", "3.5"}};

    /** Categoría, presentaciones y artículos con su precio base en soles. */
    private static final Object[][] CATEGORIAS = {
        {"Bebidas", PRESENTACION_VOLUMEN, new Object[][] {{"Gaseosa cola", 2.0}, {"Agua mineral", 1.5}, {"Jugo de naranja", 2.8}, {"Té helado", 2.5}, {"Bebida energizante", 4.5}}},
        {"Snacks", PRESENTACION_TAMANO, new Object[][] {{"Papas fritas", 2.5}, {"Galletas de vainilla", 1.8}, {"Chocolate de leche", 3.0}, {"Maní salado", 2.2}, {"Barra de cereal", 1.9}}},
        {"Lácteos", PRESENTACION_TAMANO, new Object[][] {{"Leche entera", 4.2}, {"Yogurt natural", 3.5}, {"Queso fresco", 9.0}, {"Mantequilla", 6.5}, {"Leche evaporada", 3.9}}},
        {"Abarrotes", PRESENTACION_TAMANO, new Object[][] {{"Arroz extra", 4.5}, {"Azúcar rubia", 3.8}, {"Aceite vegetal", 9.5}, {"Fideos spaghetti", 2.9}, {"Lentejas", 5.5}}},
        {"Limpieza", PRESENTACION_TAMANO, new Object[][] {{"Detergente en polvo", 8.5}, {"Lejía", 3.2}, {"Lavavajilla", 4.8}, {"Limpiador multiusos", 5.2}, {"Suavizante", 7.5}}},
        {"Cuidado personal", PRESENTACION_TAMANO, new Object[][] {{"Shampoo", 9.9}, {"Jabón de tocador", 2.5}, {"Pasta dental", 5.5}, {"Desodorante", 8.9}, {"Papel higiénico", 7.0}}},
        {"Panadería", PRESENTACION_TAMANO, new Object[][] {{"Pan de molde", 7.5}, {"Keke de vainilla", 6.0}, {"Galleta de soda", 2.4}, {"Tostadas", 4.5}, {"Croissant", 3.0}}},
        {"Congelados", PRESENTACION_TAMANO, new Object[][] {{"Hamburguesa", 14.5}, {"Nuggets de pollo", 17.0}, {"Papas pre-fritas", 12.0}, {"Helado de vainilla", 11.0}, {"Pizza personal", 15.5}}},
        {"Mascotas", PRESENTACION_TAMANO, new Object[][] {{"Alimento para perro", 45.0}, {"Alimento para gato", 38.0}, {"Arena para gato", 22.0}, {"Snack para perro", 12.0}, {"Collar", 18.0}}},
        {"Hogar", PRESENTACION_TAMANO, new Object[][] {{"Foco LED", 6.5}, {"Pilas AA", 8.5}, {"Bolsa de basura", 5.0}, {"Esponja", 2.8}, {"Velas", 4.0}}},
    };

    static final String[] NOMBRES = {
        "Juan", "Carlos", "Luis", "Miguel", "José", "Jorge", "Pedro", "Ricardo", "Diego", "Andrés",
        "María", "Ana", "Carmen", "Rosa", "Lucía", "Patricia", "Sofía", "Valeria", "Gabriela", "Daniela",
        "Fernando", "Héctor", "Óscar", "Camila", "Paola", "Rocío", "Kevin", "Brenda", "Marco", "Elena"};

    static final String[] APELLIDOS = {
        "García", "Rodríguez", "Quispe", "Flores", "Sánchez", "Ramírez", "Torres", "Huamán", "Mendoza", "Rojas",
        "Castillo", "Vargas", "Chávez", "Gutiérrez", "Díaz", "Cruz", "Paredes", "Salazar", "Condori", "Ríos",
        "Medina", "Vega", "Campos", "Silva", "Ortiz", "Espinoza", "Ramos", "Soto", "Delgado", "Navarro"};

    static final String[] CARGOS = {"Vendedor", "Cajero", "Supervisor de tienda", "Asesor de ventas"};

    static final String[] DOMINIOS = {"correo.pe", "gmail.com", "hotmail.com", "outlook.com", "yahoo.com"};

    private Catalogos() {
    }

    /** 10 categorías x 5 artículos x 4 presentaciones = 200 productos. */
    static List<ProductoSemilla> productos() {
        List<ProductoSemilla> lista = new ArrayList<>();
        for (Object[] cat : CATEGORIAS) {
            String categoria = (String) cat[0];
            String[][] presentaciones = (String[][]) cat[1];
            for (Object[] articulo : (Object[][]) cat[2]) {
                for (String[] p : presentaciones) {
                    double precio = Math.round((Double) articulo[1] * Double.parseDouble(p[1]) * 10.0) / 10.0;
                    lista.add(new ProductoSemilla(categoria, articulo[0] + " " + p[0], Math.max(precio, 0.5)));
                }
            }
        }
        return lista;
    }

    static List<String> categorias() {
        List<String> nombres = new ArrayList<>();
        for (Object[] cat : CATEGORIAS) {
            nombres.add((String) cat[0]);
        }
        return nombres;
    }

    /** Minúsculas, sin tildes ni espacios: apto para la parte local de un correo. */
    static String paraCorreo(String texto) {
        String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
