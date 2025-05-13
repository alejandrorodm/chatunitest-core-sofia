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

    public static int saveExtractedMethodsAndConstructors(String className, String depClassName, String sourceCode) {
        List<String> methods = extractMethodsFromCode(depClassName, sourceCode);
        List<String> constructors = extractConstructorsFromCode(depClassName, sourceCode);
        System.out.println("ClassName: " + className);
        System.out.println("DepClassName: " + depClassName);
        
        for (String methodCode : methods) {
            String firstLine = methodCode.split("\n")[0].trim();
            String signature = extractSignature(firstLine);
            String methodName = signature;
            embeddingClient.saveCode(depClassName, methodName, methodCode, signature, "", "", null, className);
        }
    
        for (String constructorCode : constructors) {
            String firstLine = constructorCode.split("\n")[0].trim();
            String signature = extractSignature(firstLine);
            String methodName = depClassName;  // o className() + " constructor" para evitar ambigüedad
            embeddingClient.saveCode(depClassName, methodName, constructorCode, signature, "", "", null, className);
        }

        return 0;
    }

    /**
     * Extrae la parte inicial de la clase: package, imports, declaración de clase
     * y campos (variables) hasta el primer método o constructor.
     */
    public static String extractClassHeader(String depClassName, String sourceCode) {
        String[] lines = sourceCode.split("\n");
        StringBuilder header = new StringBuilder();
        boolean inClass      = false;
        int braceCount       = 0;

        for (String rawLine : lines) {
            String line = rawLine.trim();

            // Siempre copiar package e imports
            if (line.startsWith("package ") || line.startsWith("import ")) {
                header.append(rawLine).append("\n");
                continue;
            }

            // Detectar inicio de la clase
            if (!inClass && (line.startsWith("public class " + depClassName)
                        || line.startsWith("class " + depClassName)
                        || line.contains(" class " + depClassName + " "))) {
                inClass = true;
                // Añadimos la línea de declaración de la clase
                header.append(rawLine).append("\n");
                // Cuenta las llaves de la declaración
                if (line.contains("{")) {
                    braceCount += countChar(line, '{') - countChar(line, '}');
                }
                continue;
            }

            // Una vez dentro de la clase (braceCount >= 1), copiamos todo hasta antes de métodos
            if (inClass) {
                // Actualiza conteo de llaves
                if (line.contains("{") || line.contains("}")) {
                    braceCount += countChar(line, '{') - countChar(line, '}');
                }

                // Detectar inicio de método o constructor: 
                //   una firma que contenga "(" y "{" y no acabe en ";"
                boolean isMethodOrCtor = line.contains("(")
                                    && line.contains(")")
                                    && line.contains("{");
                if (isMethodOrCtor && braceCount>=2) {
                    // hemos llegado al cuerpo del primer método/ctor
                    break;
                }

                // Copiamos línea (campos, comentarios, anotaciones…)
                header.append(rawLine).append("\n");
            }
        }

        return header.toString();
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (char x : s.toCharArray()) if (x == c) count++;
        return count;
    }
    

    public static void main(String[] args) {

        String sourceCode = "package es.juntadeandalucia.ceceu.sede.libreriadto.gestionexpedientes;\r\n" + //
                        "\r\n" + //
                        "import es.juntadeandalucia.ceceu.sede.libreriadto.gestionexpedientes.DatabaseDTO;\r\n" + //
                        "import javax.validation.constraints.NotNull;\r\n" + //
                        "\r\n" + //
                        "public class DocumentoDto\r\n" + //
                        "extends DatabaseDTO {\r\n" + //
                        "    @NotNull\r\n" + //
                        "    private Character lgFirmado;\r\n" + //
                        "    @NotNull\r\n" + //
                        "    private String cdTipo;\r\n" + //
                        "    private byte[] lbDocumento;\r\n" + //
                        "    private Long idSolicitud;\r\n" + //
                        "    private Long idConvocatoria;\r\n" + //
                        "    private Long idProcedimiento;\r\n" + //
                        "    private String dsNombre;\r\n" + //
                        "    private String dsDescripcion;\r\n" + //
                        "    private String tNombreArchivo;\r\n" + //
                        "    private Long idExpTrewa;\r\n" + //
                        "    private String cdTrnFirma;\r\n" + //
                        "    private String cdNumRegistro;\r\n" + //
                        "    private String cdDocumentoAlfresco;\r\n" + //
                        "    private String txDniInteresado;\r\n" + //
                        "    private Long idAnexoPresenta;\r\n" + //
                        "    private Long nuOrden;\r\n" + //
                        "    private String idTrnfirmaDoc;\r\n" + //
                        "    private Long idFirmaPct;\r\n" + //
                        "    private Long idPlantillasSolicitudesPdf;\r\n" + //
                        "    private Long idDocTrewa;\r\n" + //
                        "    private Long idDocTrewaCompulsa;\r\n" + //
                        "    private String extensionFormato;\r\n" + //
                        "    private Long tamnyoFichero;\r\n" + //
                        "\r\n" + //
                        "    public DocumentoDto(){\r\n" + //
                        "        System.out.println('Hola');\r\n" + //
                        "    }\r\n" + //
                        "    public Character getLgFirmado() {\r\n" + //
                        "        return this.lgFirmado;\r\n" + //
                        "    }\r\n" + //
                        "\r\n" + //
                        "    public String getCdTipo() {\r\n" + //
                        "        return this.cdTipo;\r\n" + //
                        "    }\r\n" + //
                        "\r\n" + //
                        "    public byte[] getLbDocumento() {\r\n" + //
                        "        return this.lbDocumento;\r\n" + //
                        "    }\r\n" + //
                        "\r\n" + //
                        "    public Long getIdSolicitud() {\r\n" + //
                        "        return this.idSolicitud;\r\n" + //
                        "    }\r\n" + //
                        "\r\n" + //
                        "    public Long getIdConvocatoria() {\r\n" + //
                        "        return this.idConvocatoria;\r\n" + //
                        "    }\r\n" + //
                        "\r\n" + //
                        "    public Long getIdProcedimiento() {\r\n" + //
                        "        return this.idProcedimiento;\r\n" + //
                        "    }\r\n" + //
                        "\r\n" + //
                        "    public String getDsNombre() {\r\n" + //
                        "        return this.dsNombre;\r\n" + //
                        "    }\r\n" + //
                        "\r\n" + //
                        "";

        String res = extractClassHeader("DocumentoDto", sourceCode);
        String constructors = extractConstructorsFromCode("DocumentoDto", sourceCode).toString();
        System.out.println(res);
        System.out.println(constructors);
        System.out.println("===================================");
        // List<String> methods = extractMethodsFromCode("HTTP", "public class HttpEntity<T> {\r\n" + //
        //                 "public static final HttpEntity<?> EMPTY = new HttpEntity();\r\n" + //
        //                 "private final HttpHeaders headers;\r\n" + //
        //                 "@Nullable\r\n" + //
        //                 "private final T body;\r\n" + //
        //                 "protected HttpEntity() {\r\n" + //
        //                 "this(null, null);\r\n" + //
        //                 "}\r\n" + //
        //                 "public HttpEntity(T body) {\r\n" + //
        //                 "this(body, null);\r\n" + //
        //                 "}\r\n" + //
        //                 "public HttpEntity(MultiValueMap<String, String> headers) {\r\n" + //
        //                 "this(null, headers);\r\n" + //
        //                 "}\r\n" + //
        //                 "public HttpEntity(@Nullable T body, @Nullable MultiValueMap<String, String> headers) {\r\n" + //
        //                 "this.body = body;\r\n" + //
        //                 "HttpHeaders tempHeaders = new HttpHeaders();\r\n" + //
        //                 "if (headers != null) {\r\n" + //
        //                 "tempHeaders.putAll(headers);\r\n" + //
        //                 "}\r\n" + //
        //                 "this.headers = HttpHeaders.readOnlyHttpHeaders((HttpHeaders)tempHeaders);\r\n" + //
        //                 "}\r\n" + //
        //                 "public HttpHeaders getHeaders() {\r\n" + //
        //                 "return this.headers;\r\n" + //
        //                 "}\r\n" + //
        //                 "@Nullable\r\n" + //
        //                 "public T getBody() {\r\n" + //
        //                 "return this.body;\r\n" + //
        //                 "}\r\n" + //
        //                 "public boolean hasBody() {\r\n" + //
        //                 "return this.body != null;\r\n" + //
        //                 "}\r\n" + //
        //                 "public boolean equals(@Nullable Object other) {\r\n" + //
        //                 "if (this == other) {\r\n" + //
        //                 "return true;\r\n" + //
        //                 "}\r\n" + //
        //                 "if (other == null || other.getClass() != this.getClass()) {\r\n" + //
        //                 "return false;\r\n" + //
        //                 "}\r\n" + //
        //                 "HttpEntity otherEntity = (HttpEntity)other;\r\n" + //
        //                 "return ObjectUtils.nullSafeEquals((Object)this.headers, (Object)otherEntity.headers) && ObjectUtils.nullSafeEquals(this.body, otherEntity.body);\r\n" + //
        //                 "}\r\n" + //
        //                 "public int hashCode() {\r\n" + //
        //                 "return ObjectUtils.nullSafeHashCode((Object)this.headers) * 29 + ObjectUtils.nullSafeHashCode(this.body);\r\n" + //
        //                 "}\r\n" + //
        //                 "public String toString() {\r\n" + //
        //                 "StringBuilder builder = new StringBuilder(\"<\");\r\n" + //
        //                 "if (this.body != null) {\r\n" + //
        //                 "builder.append(this.body);\r\n" + //
        //                 "builder.append(',');\r\n" + //
        //                 "}\r\n" + //
        //                 "builder.append(this.headers);\r\n" + //
        //                 "builder.append('>');\r\n" + //
        //                 "return builder.toString();\r\n" + //
        //                 "}\r\n" + //
        //                 "}\r\n" + //
        //                 "");
        // for (String method : methods) {
        //     System.out.println("Metodo:");
        //     System.out.println(method);
        // }
        // System.out.println("===================================");

    }

}
