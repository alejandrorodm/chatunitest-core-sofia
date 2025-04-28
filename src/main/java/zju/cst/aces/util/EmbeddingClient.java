package zju.cst.aces.util;


import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.IOException;
import zju.cst.aces.dto.MethodInfo;


public class EmbeddingClient {

    private final String BASE_URL = "http://localhost:5000/";
    public Process process = null;

    public EmbeddingClient() {
        //startPythonServer();
    }

    public boolean isServerRunning(){
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:5000").openConnection();
            connection.setRequestMethod("GET");
            connection.connect();
            int responseCode = connection.getResponseCode();
            if (responseCode == 200)  {
                System.out.println("El servidor Flask ya está corriendo.");
                return true;
            }else{
                return false;
            }
        } catch (IOException e) {
            System.out.println("El servidor Flask no está corriendo");
            return false;
        }
    }

    // AÑADIR METODOS DE LA CABECERA AL RAG
    
    public void startPythonServer() {
        // if(isServerRunning()){
        //     return;
        // }else{
        //     System.out.println("Iniciando el servidor...");
        //     String pythonScriptPath = "C:\\Users\\Alejandro\\Documents\\UMA\\Practicas\\RAG\\chatunitest-core-sofia\\src\\main\\java\\zju\\cst\\aces\\util\\embeddingrest.py";  
        //     ProcessBuilder processBuilder = new ProcessBuilder("python", pythonScriptPath);
        //     processBuilder.redirectErrorStream(true);
        //     try {
        //         this.process = processBuilder.start();
        //         System.out.println("Servidor iniciado.");
        //     } catch (IOException e) {
        //         System.out.println("Error al iniciar el servidor: " + e.getMessage());
        //         throw new RuntimeException("No se pudo iniciar el servidor Flask. Deteniendo la ejecución.", e);
        //     }    
            
        //     // Esperar a que el servidor inicie
        //     boolean started = false;
        //     int attempts = 0;
        //     while (!started && attempts < 10) {
        //         try {
        //             Thread.sleep(2000);
        //             started = isServerRunning();
        //         } catch (InterruptedException e) {
        //             System.out.println("Error al esperar a que el servidor inicie: " + e.getMessage());
        //         }
        //         attempts++;
        //     }

        // }            
    }

    private String sendGetRequest(String endpoint) {
        try {
            URL url = new URL(BASE_URL + "/" + endpoint);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
    
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                StringBuilder response = new StringBuilder();
                String inputLine;
    
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
    
                in.close();
                return response.toString();
            } else {
                System.err.println("GET request failed: " + responseCode);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
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
            System.out.println("Error: " + e.getMessage() + e.getCause());
            return null;

            }
        }

    public JSONObject saveCode(String className, String methodName, String code, String signature, String comment, String annotations, List<String> dependentMethods) {
        JSONObject inputJson = new JSONObject();
        inputJson.put("class_name", className);
        inputJson.put("method_name", methodName);
        inputJson.put("code", code);
        inputJson.put("signature", signature);
        inputJson.put("comment", comment);
        inputJson.put("annotations", annotations);
        inputJson.put("dependent_methods", new JSONArray(dependentMethods)); 
        inputJson.put("is_constructor", methodName.equals(className)); // true si es constructor, false si no
        //System.out.println("Code to be saved: " + signature);
        String response = sendPostRequest("save_code", inputJson.toString());

        if(response == null) {
            System.out.println("Error al guardar el código: " + response);
        } 

        return response != null ? new JSONObject(response) : null;
    }


    // Guardar múltiples métodos de una dependencia
    public void save_methods(String className, List<Map<String, String>> methods) {
        JSONArray methodsArray = new JSONArray();
        for (Map<String, String> method : methods) {
            JSONObject methodJson = new JSONObject()
                    .put("class_name", className)
                    .put("method_name", method.get("method_name"))
                    .put("code", method.get("code"))
                    .put("signature", method.get("signature"));
            methodsArray.put(methodJson);
        }

        JSONObject inputJson = new JSONObject().put("methods", methodsArray);
        String response = sendPostRequest("save_methods", inputJson.toString());

        if (response != null) {
            System.out.println("Métodos de dependencia guardados exitosamente.");
        }
    }

    // Buscar métodos de una dependencia en la base de datos
    public List<Map<String, String>> search_similar_methodsMap(String code, int limit) {
        String inputJson = new JSONObject()
                .put("code", code)
                .put("max_neighbours", limit)
                .toString();

        String response = sendPostRequest("search_similar_methods", inputJson);
        List<Map<String, String>> results = new ArrayList<>();

        if (response != null) {
            JSONObject jsonResponse = new JSONObject(response);
            if (jsonResponse.has("results")) {
                JSONArray jsonResults = jsonResponse.getJSONArray("results");
                for (int i = 0; i < jsonResults.length(); i++) {
                    JSONObject result = jsonResults.getJSONObject(i);
                    Map<String, String> methodData = new HashMap<>();
                    methodData.put("class_name", result.optString("class_name", ""));
                    methodData.put("method_name", result.optString("method_name", ""));
                    methodData.put("signature", result.optString("signature", ""));
                    methodData.put("code", result.optString("code", ""));
                    methodData.put("comment", result.optString("comment", ""));
                    methodData.put("annotations", result.optString("annotations", ""));
                    methodData.put("dependent_methods", result.optString("dependent_methods", "")); //METODOS MISMA CLASE
                    results.add(methodData);
                }
            }
        }
        return results;
    }

    public Map<String, List<MethodInfo>> search_similar_methods(String code, int limit) {
        String inputJson = new JSONObject()
                .put("code", code)
                .put("max_neighbours", limit)
                .toString();
    
        String response = sendPostRequest("search_similar_methods", inputJson);
        Map<String, List<MethodInfo>> methodsByClass = new HashMap<>();
    
        if (response != null) {
            //System.out.println("Response: " + response + " code: " + code + "\n\n");
            JSONObject jsonResponse = new JSONObject(response);
            if (jsonResponse.has("results")) {
                JSONArray jsonResults = jsonResponse.getJSONArray("results");
                for (int i = 0; i < jsonResults.length(); i++) {
                    JSONObject result = jsonResults.getJSONObject(i);
    
                    String className = result.optString("class_name", "");
    
                    // Convertir dependencias a un Map<String, Set<String>> vacío por ahora
                    Map<String, Set<String>> dependentMethods = new HashMap<>();
    
                    MethodInfo methodInfo = new MethodInfo(
                            className,
                            result.optString("method_name", ""),
                            "",  // `brief`
                            result.optString("signature", ""),
                            result.optString("code", ""),
                            null,  // `parameters`
                            dependentMethods,
                            "",  // `full_method_info`
                            result.optString("comment", ""),
                            result.optString("annotations", ""),
                            response
                    );
    
                    // Añadir al mapa por clase
                    methodsByClass
                        .computeIfAbsent(className, k -> new ArrayList<>())
                        .add(methodInfo);
                }
            }
        }
    
        return methodsByClass;
    }    

    public int countElements() {
        String response = sendGetRequest("count_elements");
        if (response != null) {
            JSONObject jsonResponse = new JSONObject(response);
            if (jsonResponse.has("total_elements")) {
                return jsonResponse.getInt("total_elements");
            } else if (jsonResponse.has("error")) {
                System.err.println("Error al contar elementos: " + jsonResponse.getString("error"));
            }
        }
        return -1; // Indicador de error
    }
    
    public static void main(String[] args) {
    //     EmbeddingClient client = new EmbeddingClient();

    //     // Prueba del método saveCode
    //     String className = "DummyClass1";
    //     String methodName = "dummyMethod1";
    //     String code = "public void dummyMethod() { System.out.println(\"Hello, World!\"); }";
    //     String signature = "void dummyMethod()1";
    //     String comment = "This is a dummy method.1";
    //     String annotations = "[\"Override\"]";
    //     List<String> dependentMethods = new ArrayList<>();
    //     dependentMethods.add("helperMethod");

    //     JSONObject saveResponse = client.saveCode(className, methodName, code, signature, comment, annotations, dependentMethods);
    //     System.out.println("Respuesta de saveCode: " + saveResponse);

    //     // Prueba del método search_similar_methods
    //     String searchCode = "public void dummyMethod() { System.out.println(\"Hello, Girl!\"); }";
    //     int limit = 5;

    //     List<MethodInfo> searchResults = client.search_similar_methods(searchCode, limit);
    //     System.out.println("Resultados de search_similar_methods:");
    //     for (MethodInfo methodInfo : searchResults) {
    //         System.out.println(methodInfo);
    //     }
    }
}
