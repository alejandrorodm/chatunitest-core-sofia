package zju.cst.aces.parser;

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
        // try {
        //     try {
        //         HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:5000").openConnection();
        //         connection.setRequestMethod("GET");
        //         connection.connect();
        //         int responseCode = connection.getResponseCode();
        //         if (responseCode == 200)  {
        //             System.out.println("El servidor Flask ya está corriendo.");
        //             return;
        //         }
        //     } catch (IOException e) {
        //         System.out.println("El servidor Flask no está corriendo, iniciando uno nuevo.");
        //     }

        //     String pythonScriptPath = "C:\\Users\\Alejandro\\Documents\\UMA\\Curso24-25\\PracticasCurriculares\\ProyectoRAGJava\\chatunitest-core-sofia\\src\\main\\java\\zju\\cst\\aces\\util\\embeddingrest.py";  
        //     ProcessBuilder processBuilder = new ProcessBuilder("python", pythonScriptPath);
        //     processBuilder.redirectErrorStream(true);
        //     this.process = processBuilder.start();

        //     BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        //     String line;
        //     while ((line = reader.readLine()) != null) {
        //         System.out.println(line);
        //         if (line.contains("Running on")) {
        //             System.out.println("Servidor Flask iniciado correctamente.");
        //             break;
        //         }
        //     }

        // } catch (IOException e) {
        //     System.out.println("Error al iniciar el servidor Flask: " + e.getMessage());
        // }
        System.out.println("El servidor FLask ya está corriendo.");
    }

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

    // Guardar el código y sus embeddings por clase o método
    public void saveCode(String className, String methodName, String code, String signature, String comment, List<String> annotations) {
        JSONObject inputJson = new JSONObject()
                .put("class_name", className)
                .put("method_name", methodName)
                .put("code", code)
                .put("signature", signature)
                .put("comment", comment)
                .put("annotations", annotations.toString());
    
        String response = sendPostRequest("save_code", inputJson.toString());
    
        if (response != null) {
            JSONObject jsonResponse = new JSONObject(response);
            System.out.println("Response: " + jsonResponse.toString());
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

    public List<String> searchCode(String query, int maxNeighbors) {
        String inputJson = new JSONObject()
                .put("query", query)
                .toString();
    
        String response = sendPostRequest("search_code", inputJson);
        List<String> results = new ArrayList<>();
    
        if (response != null) {
            JSONObject jsonResponse = new JSONObject(response);
    
            if (jsonResponse.has("results")) {
                JSONArray jsonResults = jsonResponse.getJSONArray("results");
                System.out.println("Resultados encontrados: " + jsonResults.length());
                if (jsonResults.length() > maxNeighbors) {
                    System.out.println("Mostrando solo los primeros " + maxNeighbors + " resultados.");
                }
                for (int i = 0; i < Math.min(jsonResults.length(), maxNeighbors); i++) {  // Limitar resultados
                    results.add(jsonResults.getString(i));
                }
    
                // Si hay códigos adicionales en la respuesta
                JSONArray codes = jsonResponse.optJSONArray("codes");
                if (codes != null) {
                    System.out.println("Códigos similares encontrados:");
                    for (int i = 0; i < Math.min(codes.length(), maxNeighbors); i++) {  // Limitar códigos
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

        // Datos de prueba para inserciones
        // String className1 = "ExampleClass";
        // String methodName1 = "exampleMethod";
        // String code1 = "public void exampleMethod() { System.out.println(\"¡Hola mundo!\"); }";
        // String signature1 = "public void exampleMethod()";
        // String comment1 = "Este método imprime Hello, world!";
        // List<String> annotations1 = List.of("@Test", "@Deprecated");

        // String className2 = "MathOperations";
        // String methodName2 = "addNumbers";
        // String code2 = "public int addNumbers(int a, int b) { return a + b; }";
        // String signature2 = "public int addNumbers(int a, int b)";
        // String comment2 = "Este método suma dos números enteros.";
        // List<String> annotations2 = List.of("@Override");

        // String className3 = "StringManipulator";
        // String methodName3 = "reverseString";
        // String code3 = "public String reverseString(String input) { return new StringBuilder(input).reverse().toString(); }";
        // String signature3 = "public String reverseString(String input)";
        // String comment3 = "Este método invierte una cadena de texto.";
        // List<String> annotations3 = List.of("@Deprecated");

        // // Más datos de prueba
        // String className4 = "ArrayHelper";
        // String methodName4 = "findMaxValue";
        // String code4 = "public int findMaxValue(int[] arr) { int max = arr[0]; for (int num : arr) { if (num > max) max = num; } return max; }";
        // String signature4 = "public int findMaxValue(int[] arr)";
        // String comment4 = "Este método encuentra el valor máximo en un arreglo de enteros.";
        // List<String> annotations4 = List.of("@Test");

        // String className5 = "NumberUtils";
        // String methodName5 = "isPrime";
        // String code5 = "public boolean isPrime(int number) { if (number <= 1) return false; for (int i = 2; i < number; i++) { if (number % i == 0) return false; } return true; }";
        // String signature5 = "public boolean isPrime(int number)";
        // String comment5 = "Este método determina si un número es primo.";
        // List<String> annotations5 = List.of("@Deprecated");

        // String className6 = "SortingAlgorithms";
        // String methodName6 = "bubbleSort";
        // String code6 = "public int[] bubbleSort(int[] arr) { for (int i = 0; i < arr.length - 1; i++) { for (int j = 0; j < arr.length - i - 1; j++) { if (arr[j] > arr[j + 1]) { int temp = arr[j]; arr[j] = arr[j + 1]; arr[j + 1] = temp; } } } return arr; }";
        // String signature6 = "public int[] bubbleSort(int[] arr)";
        // String comment6 = "Este método ordena un arreglo de enteros usando el algoritmo de ordenamiento burbuja.";
        // List<String> annotations6 = List.of("@Override");

        // // Guardar los códigos
        // client.saveCode(className1, methodName1, code1, signature1, comment1, annotations1);
        // client.saveCode(className2, methodName2, code2, signature2, comment2, annotations2);
        // client.saveCode(className3, methodName3, code3, signature3, comment3, annotations3);
        // client.saveCode(className4, methodName4, code4, signature4, comment4, annotations4);
        // client.saveCode(className5, methodName5, code5, signature5, comment5, annotations5);
        // client.saveCode(className6, methodName6, code6, signature6, comment6, annotations6);

        // // Realizar una búsqueda con el nombre del método como parámetro adicional
        // System.out.println("Buscando código similar con el método 'isMenor'...");
        // List<String> results1 = client.searchCode("public boolean isMenor(int number1, int number2) { return number1 < number2; }\"", 3);
        // System.out.println("Resultados de la búsqueda 1: " + results1);

        // // Buscar por consulta relacionada con 'método primo'
        // System.out.println("Buscando código similar con el método 'isPrime'...");
        // List<String> results2 = client.searchCode("isPrime", 3);
        // System.out.println("Resultados de la búsqueda 2: " + results2);

        // Buscar por consulta relacionada con 'maximo valor'
        System.out.println("Buscando código similar con el método 'bubbleSort'...");
        List<String> results3 = client.searchCode("print", 5);
        System.out.println("Resultados de la búsqueda 3: " + results3);

        // Terminar el proceso si es necesario
        if (client.process != null) {
            client.process.destroy();
        }
    }
}
