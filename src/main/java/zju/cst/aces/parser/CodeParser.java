package zju.cst.aces.parser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.json.JSONObject;

import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.util.EmbeddingClient;

public class CodeParser {

    public String sourceCode = null;
    public static EmbeddingClient embeddingClient = new EmbeddingClient();
    public List<String> constructors = new ArrayList<>();
    public List<String> methods = new ArrayList<>();
    public List<String> fields = new ArrayList<>();
    public List<String> methodSignatures = new ArrayList<>();
    public List<String> fieldSignatures = new ArrayList<>();
    public List<String> constructorSignatures = new ArrayList<>();

    public CodeParser(){
    }

    public CodeParser(String sourceCode) {
        this.sourceCode = sourceCode;
        
    }

    public static List<String> extractConstructorsFromCode(String depClassName, String sourceCode) {
        List<String> constructors = new ArrayList<>();
        String[] lines = sourceCode.split("\n");
        StringBuilder constructorBuilder = new StringBuilder();
        boolean inConstructor = false;
        int braceCount = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            // Identify constructors
            if (!inConstructor && line.contains(depClassName) && line.contains("(") && line.contains(")") && line.contains("{")) {
                inConstructor = true;
            }

            if (inConstructor) {
                constructorBuilder.append(line).append("\n");
                if (line.contains("{")) {
                    braceCount++;
                }
                if (line.contains("}")) {
                    braceCount--;
                }
                if (braceCount == 0) {
                    constructors.add(constructorBuilder.toString());
                    constructorBuilder.setLength(0);
                    inConstructor = false;
                }
            }
        }

        return constructors;
    }

    
    public static List<String> extractMethodsFromCode(String depClassName, String sourceCode) {
        List<String> methods = new ArrayList<>();
        String[] lines = sourceCode.split("\n");
        StringBuilder methodBuilder = new StringBuilder();
        boolean inMethod = false;
        int braceCount = 0;

        boolean insideClass = false;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            // Skip the class declaration
            if (line.startsWith("public class") || line.startsWith("class")) {
                insideClass = true;
                continue;
            }

            if (insideClass && !inMethod && (line.startsWith("public") || line.startsWith("private") || line.startsWith("protected") || line.startsWith("static")) && line.contains("(")) {
                inMethod = true;
            }

            if (inMethod) {
                methodBuilder.append(line).append("\n");
                if (line.contains("{")) {
                    braceCount++;
                }
                if (line.contains("}")) {
                    braceCount--;
                }
                if (braceCount == 0) {
                    methods.add(methodBuilder.toString());
                    methodBuilder.setLength(0);
                    inMethod = false;
                }
            }
        }

        return methods;
    }

    public static List<String> extractFieldsFromCode(String depClassName, String sourceCode) {
        List<String> fields = new ArrayList<>();
        String[] lines = sourceCode.split("\n");

        boolean insideClass = false;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            // Skip the class declaration
            if (line.startsWith("public class") || line.startsWith("class")) {
                insideClass = true;
                continue;
            }

            // Identify fields (non-methods)
            if (insideClass && (line.startsWith("public") || line.startsWith("private") || line.startsWith("protected") || line.startsWith("static")) && !line.contains("(")) {
                fields.add(line);
            }
        }

        return fields;
    }
    
    public static String extractSignature(String methodHeader) {
        int parenIndex = methodHeader.indexOf("(");
        if (parenIndex == -1) {
            return "unknown";
        }
        String[] parts = methodHeader.substring(0, parenIndex).split(" ");
        return parts[parts.length - 1] + methodHeader.substring(parenIndex);
    }

    public static void saveExtractedMethods(String depClassName, String sourceCode) {
        List<String> methods = extractMethodsFromCode(depClassName, sourceCode);
        for (String methodCode : methods) {
            String firstLine = methodCode.split("\n")[0].trim();
            String signature = extractSignature(firstLine);
            String methodName = signature;
            embeddingClient.saveCode(depClassName, methodName, methodCode, signature, "", "", null);
        }
    }

}
