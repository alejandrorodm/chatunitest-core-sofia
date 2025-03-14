package zju.cst.aces.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;


public class EmbeddingClient {

    private final String BASE_URL = "http://localhost:5000/";
    public Process process = null;

    public EmbeddingClient() {
        startPythonServer();
    }

    public void startPythonServer() {
        try {
            // Verifica si el servidor ya está corriendo
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:5000").openConnection();
                connection.setRequestMethod("GET");
                connection.connect();
                int responseCode = connection.getResponseCode();
                if (responseCode == 200)  {
                    System.out.println("El servidor Flask ya está corriendo.");
                    return;
                }
            } catch (IOException e) {
                System.out.println("El servidor Flask no está corriendo, iniciando uno nuevo.");
            }

            // Ruta del script Flask en Python
            String pythonScriptPath = "C:\\Users\\Alejandro\\Documents\\UMA\\Curso24-25\\PracticasCurriculares\\ProyectoRAGJava\\chatunitest-core-sofia\\src\\main\\java\\zju\\cst\\aces\\util\\embeddingrest.py";  
            ProcessBuilder processBuilder = new ProcessBuilder("python", pythonScriptPath);
            processBuilder.redirectErrorStream(true);

            // Inicia el proceso del servidor Flask
            this.process = processBuilder.start();

            // Lee la salida del servidor para asegurarte de que arrancó bien
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                if (line.contains("Running on")) {
                    System.out.println("Servidor Flask iniciado correctamente.");
                    break;
                }
            }

        } catch (IOException e) {
            System.out.println("Error al iniciar el servidor Flask: " + e.getMessage());
        }
    }


    // Método genérico para hacer peticiones POST
    private String sendPostRequest(String endpoint, String inputJson) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(BASE_URL + endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = inputJson.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), "utf-8"))) {
                StringBuilder response = new StringBuilder();
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                return response.toString();
            }
        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
            return null;
        }
    }

    // Guardar el código y su embedding
    public void saveCode(String className, String code) {
        String inputJson = new JSONObject()
        .put("class_name", className)
        .put("code", code)
        .toString();        
        String response = sendPostRequest("save_code", inputJson);

        if (response != null) {
            JSONObject jsonResponse = new JSONObject(response);
            System.out.println("Respuesta al guardar código: " + jsonResponse);
        }
    }

    // Buscar código por nombre o similitud
    public List<String> searchCode(String query) {
        String inputJson = new JSONObject()
        .put("query", query)
        .toString();

        String response = sendPostRequest("search_code", inputJson);
        List<String> results = new ArrayList<>();

        if (response != null) {
            JSONObject jsonResponse = new JSONObject(response);

            if (jsonResponse.has("results")) {
                JSONArray jsonResults = jsonResponse.getJSONArray("results");
                for (int i = 0; i < jsonResults.length(); i++) {
                    results.add(jsonResults.getString(i));
                }

            JSONArray codes = jsonResponse.optJSONArray("codes");
            if (codes != null) {
                System.out.println("Códigos similares encontrados:");
                for (int i = 0; i < codes.length(); i++) {
                    String code = codes.getString(i);
                    results.add(code);
                    System.out.println(code);
                }
            }
            } else {
            System.out.println("No se encontraron resultados.");
            }
        }

        return results;
    }

    public static void main(String[] args) {
        EmbeddingClient client = new EmbeddingClient();

        // Guardar un código
        String className = "ExampleClass";
        String code = "public class ExampleClass { public static void main(String[] args) { System.out.println(\"Hello, world!\"); }}";
        client.saveCode(className, code);

        // Buscar por similitud
        System.out.println("Buscando código similar...");
        List<String> results = client.searchCode("System.out.println(\"Hello\")");

        System.out.println("Resultados de la búsqueda: " + results);
        
        if(client.process != null){
            client.process.destroy();
        }
            
    }
}
