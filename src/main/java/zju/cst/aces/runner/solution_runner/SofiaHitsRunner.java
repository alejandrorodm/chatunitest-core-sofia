package zju.cst.aces.runner.solution_runner;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.json.JSONObject;

import zju.cst.aces.api.Logger;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.api.impl.obfuscator.Obfuscator;
import zju.cst.aces.api.phase.PhaseImpl;
import zju.cst.aces.api.phase.solution.SOFIA_HITS;
import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.runner.MethodRunner;
import zju.cst.aces.util.EmbeddingClient;
import zju.cst.aces.util.JsonResponseProcessor;

import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import zju.cst.aces.dto.ClassInfo;

public class SofiaHitsRunner extends MethodRunner {

    private static List<String> dependencies;
    private static Logger logger;
    private static EmbeddingClient embeddingClient = new EmbeddingClient();

    public SofiaHitsRunner(Config config, String fullClassName, MethodInfo methodInfo) throws IOException {
        super(config, fullClassName, methodInfo);
        dependencies = new ArrayList<>(config.getDependencyPaths());

        logger = config.getLogger();
    }

    /**
     * Main process of HITS, including:
     * @param num
     * @return If the generation process is successful
     */
    @Override
    public boolean startRounds(final int num) {
        PhaseImpl phase = PhaseImpl.createPhase(config);
        config.useSlice = true;

        // Prompt Construction Phase
        PromptConstructorImpl pc = phase.generatePrompt(classInfo, methodInfo,num);
        PromptInfo promptInfo = pc.getPromptInfo();
        promptInfo.setRound(0);

        SOFIA_HITS phase_hits = (SOFIA_HITS) phase;

        long startTime = System.nanoTime();
        if (config.isEnableObfuscate()) {
            Obfuscator obfuscator = new Obfuscator(config);
            PromptInfo obfuscatedPromptInfo = new PromptInfo(promptInfo);
            obfuscator.obfuscatePromptInfo(obfuscatedPromptInfo);

            phase_hits.generateMethodSlice(pc);
        } else {
            phase_hits.generateMethodSlice(pc);
        }

        int successCount = 0;
        JsonResponseProcessor.JsonData methodSliceInfo = JsonResponseProcessor.readJsonFromFile(promptInfo.getMethodSlicePath().resolve("slice.json"));
        if (methodSliceInfo != null) {
            // Accessing the steps
            boolean hasErrors = false;
            for (int i = 0; i < methodSliceInfo.getSteps().size(); i++) {
                // Test Generation Phase
                hasErrors = false;
                if (methodSliceInfo.getSteps().get(i) == null) continue;
                promptInfo.setSliceNum(i);
                promptInfo.setSliceStep(methodSliceInfo.getSteps().get(i)); // todo 存储切片信息到promptInfo
                phase_hits.generateSliceTest(pc); //todo 改成新的hits对切片生成单元测试方法
                // Validation
                if (phase_hits.validateTest(pc)) {
                    successCount++;
                    exportRecord(pc.getPromptInfo(), classInfo, num);
                    continue;
                } else {
                    hasErrors = true;
                }
                if (hasErrors) {
                    // Validation and Repair Phase
                    for (int rounds = 1; rounds < config.getMaxRounds(); rounds++) {

                        promptInfo.setRound(rounds);

                        // Repair
                        phase_hits.generateSliceTest(pc);
                        // Validation and process
                        if (phase_hits.validateTest(pc)) { // if passed validation
                            successCount++;
                            exportRecord(pc.getPromptInfo(), classInfo, num);
                            hasErrors = false;
                            break; // successfully
                        }

                    }
                }

                exportSliceRecord(pc.getPromptInfo(), classInfo, num, i); //todo 检测是否顺利生成信息
            }
            if (config.generateJsonReport) {
                long endTime = System.nanoTime();
                float duration = (float) (endTime - startTime) / 1_000_000_000;
                generateJsonReportHITS(pc.getPromptInfo(), duration, methodSliceInfo.getSteps().size(), successCount);
            }
            return !hasErrors;
        }
        return true;
    }

    /*
    Just in case the constructor is not invoked before 'generatePromptInfoWithDep'
     */
    public static void setStaticParams(Config config) {
        dependencies = new ArrayList<>(config.getDependencyPaths());
    }

    public static PromptInfo generatePromptInfoWithDep(Config config, ClassInfo classInfo, MethodInfo methodInfo) throws IOException {
        PromptInfo promptInfo = new PromptInfo(
                true,
                classInfo.fullClassName,
                methodInfo.methodName,
                methodInfo.methodSignature,
                methodInfo.methodDescriptor);
        promptInfo.setClassInfo(classInfo);
        promptInfo.setMethodInfo(methodInfo);
        List<String> otherBriefMethods = new ArrayList<>();
        List<String> otherMethodBodies = new ArrayList<>();


        System.out.println("Prompt para " + classInfo.getFullClassName() + " " + methodInfo.getMethodName());

        for (Map.Entry<String, Set<String>> entry : classInfo.constructorDeps.entrySet()) {
            String depClassName = entry.getKey();
            Set<String> depMethods = entry.getValue();
            if (methodInfo.dependentMethods.containsKey(depClassName)) {
                continue;
            }

            promptInfo.addConstructorDeps(depClassName, getDepInfo(config, depClassName, depMethods));
            promptInfo.addExternalConstructorDeps(depClassName, SofiaHitsRunner.getDepInfo(config, depClassName, promptInfo));
        }

        for (Map.Entry<String, Set<String>> entry : methodInfo.dependentMethods.entrySet()) {
            String depClassName = entry.getKey();
            System.out.println("depClassName: " + depClassName);
            
            if (depClassName.equals(classInfo.getFullClassName())) {
                Set<String> otherSig = methodInfo.dependentMethods.get(depClassName);
                for (String otherMethod : otherSig) {
                    MethodInfo otherMethodInfo = getMethodInfo(config, classInfo, otherMethod);
                    if (otherMethodInfo == null) {
                        continue;
                    }
                    // only add the methods in focal class that are invoked
                    otherBriefMethods.add(otherMethodInfo.brief);
                    otherMethodBodies.add(otherMethodInfo.sourceCode);
                }
                continue;
            }
            Set<String> depMethods = entry.getValue();
            System.out.println("DEPMETHODS " + depMethods.toString());

            //Guardar en la base de datos vectorial
            saveDepInfo(config, depClassName, depMethods, promptInfo);
            addMethodDepsByDepth(config, depClassName, depMethods, promptInfo, config.getDependencyDepth());

            //promptInfo.addMethodDeps(depClassName, getDepInfo(config, depClassName, depMethods));
            //promptInfo.addExternalMethodDeps(depClassName, SofiaHitsRunner.getDepInfo(config, depClassName, promptInfo));
        }


        //COMPROBAR FUNCIONAMIENTO
        List <MethodInfo> rag_results = embeddingClient.search_similar_methods(methodInfo.getSourceCode(), 3);
        for(MethodInfo meth : rag_results) {
            promptInfo.addMethodDeps(meth.getClassName(), meth.getSourceCode());;
        }

        String fields = joinLines(classInfo.fields);
        String imports = joinLines(classInfo.imports);

        String information = classInfo.packageName
                + "\n" + imports
                + "\n" + classInfo.classSignature
                + " {\n";
        //TODO: handle used fields instead of all fields
        String otherMethods = "";
        String otherFullMethods = "";
        if (classInfo.hasConstructor) {
            otherMethods += joinLines(classInfo.constructorBrief) + "\n";
            otherFullMethods += getBodies(config, classInfo, classInfo.constructorSigs) + "\n";
        }
//        if (methodInfo.useField) {
//            information += fields + "\n";
//            otherMethods +=  joinLines(classInfo.getterSetterBrief) + "\n";
//            otherFullMethods += getBodies(config, classInfo, classInfo.getterSetterSigs) + "\n";
//        }
        information += fields + "\n";
        otherMethods +=  joinLines(classInfo.getterSetterBrief) + "\n";
        otherFullMethods += getBodies(config, classInfo, classInfo.getterSetterSigs) + "\n";

        otherMethods += joinLines(otherBriefMethods) + "\n";
        otherFullMethods += joinLines(otherMethodBodies) + "\n";
        information += methodInfo.sourceCode + "\n}";

        promptInfo.setContext(information);
        promptInfo.setOtherMethodBrief(otherMethods);
        promptInfo.setOtherMethodBodies(otherFullMethods);

        return promptInfo;
    }

    public static void addMethodDepsByDepth(Config config, String className, Set<String> methodSigs, PromptInfo promptInfo, int depth) throws IOException {
        if (depth <= 1) {
            return;
        }
    
        Set<String> processedMethods = new HashSet<>(); // Evita procesar el mismo método más de una vez
    
        for (String dm : methodSigs) {
            ClassInfo depClassInfo = getClassInfo(config, className);
            if (depClassInfo == null) {
                continue;
            }
    
            addConstructorDepsByDepth(config, depClassInfo, promptInfo); // Agregar dependencias de constructores
    
            MethodInfo depMethodInfo = getMethodInfo(config, depClassInfo, dm);
            if (depMethodInfo == null || processedMethods.contains(depMethodInfo.getMethodSignature())) {
                continue;
            }
            processedMethods.add(depMethodInfo.getMethodSignature()); // Marcar método como procesado
    
            // Lista de métodos dependientes
            List<String> dependent_methods_list = new ArrayList<>();
    
            for (String depClassName : depMethodInfo.dependentMethods.keySet()) {
                Set<String> depMethods = depMethodInfo.dependentMethods.get(depClassName);

                for (String depMethodSig : depMethods) {
                    MethodInfo dependentMethod = getMethodInfo(config, getClassInfo(config, depClassName), depMethodSig);
                    if (dependentMethod != null) {
                        dependent_methods_list.add(dependentMethod.getMethodSignature());
                    }

                }
                System.out.println("Saving method: " + depMethodInfo.getMethodName() + " with dependencies: " + dependent_methods_list);


                // Guardar método en la base de datos con sus dependencias
                JSONObject response = embeddingClient.saveCode(
                    className,
                    depMethodInfo.getMethodName(),
                    depMethodInfo.getSourceCode(),
                    depMethodInfo.getMethodSignature(),
                    depMethodInfo.getMethod_comment(),
                    depMethodInfo.getMethod_annotation(),
                    dependent_methods_list
                );

                if (response == null) {
                    logger.error("Error al guardar el método: " + depMethodInfo.getMethodName());
                    return ;
                }else{
                    System.out.println("Method saved successfully: " + depMethodInfo.getMethodName());
                    System.out.println("Response: " + response.toString());
                }

                promptInfo.addMethodDeps(depClassName, getDepInfo(config, depClassName, depMethods));
                addMethodDepsByDepth(config, depClassName, depMethods, promptInfo, depth - 1);
            }
        }
    }

    public static List<String> extractMethodsFromCode(String depClassName, String sourceCode) {
        List<String> methods = new ArrayList<>();
        String[] lines = sourceCode.split("\n");
        StringBuilder methodBuilder = new StringBuilder();
        boolean inMethod = false;
        int braceCount = 0;
    
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
    
            if (!inMethod && (line.startsWith("public") || line.startsWith("private") || line.startsWith("protected") || line.startsWith("static"))) {
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
    
    private static String extractSignature(String methodHeader) {
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
            embeddingClient.saveCode(depClassName, methodName, methodCode, methodName, "", "", null);
        }
    }

    public static String saveDepInfo(Config config, String depClassName, Set<String> depMethods, PromptInfo promptInfo) throws IOException {

        try{
            ClassInfo depClassInfo = getClassInfo(config, depClassName);

            if (depClassInfo == null) {
                try {
                    String sourceCode = getSourceCode(depClassName);
                    if (sourceCode != null) {
                        promptInfo.incrementSofiaActivations();
                        saveExtractedMethods(depClassName, sourceCode);
                    }
                    return sourceCode;
                } catch (Exception e) {
                    return null;
                }
            } else {
                depMethods = depClassInfo.getMethodSigs().keySet();

                for (String sig : depMethods) {
                    MethodInfo depMethodInfo = getMethodInfo(config, depClassInfo, sig);
                    if (depMethodInfo == null) {
                        continue;
                    }else{
                        System.out.println("Saving method: " + depMethodInfo.getMethodName());
                        // Guardar método en la base de datos vectorial
                        JSONObject response = embeddingClient.saveCode(
                            depClassName,
                            depMethodInfo.getMethodName(),
                            depMethodInfo.getSourceCode(),
                            depMethodInfo.getMethodSignature(),
                            depMethodInfo.getMethod_comment(),
                            depMethodInfo.getMethod_annotation(),
                            new ArrayList<>(depMethodInfo.getDependentMethods().keySet())
                        );
                    }
                }
                return "OK" + depClassInfo.toString();
            }
        }catch (Exception e) {
            System.out.println("Error al guardar el método: " + e.getMessage());
            return null;
        }
        //     // Dependencias del metodo
        // List<String> dependent_methods_list = new ArrayList<>();

        // for (String sig : depMethods) {
        //     MethodInfo depMethodInfo = getMethodInfo(config, depClassInfo, sig);
        //     if (depMethodInfo == null) {
        //         continue;
        //     }

        //     // Guardar dependencias del método (pero no sus códigos)
        //     if (depMethodInfo.getDependentMethods() != null) {
        //         for (String depMethodSig : depMethodInfo.getDependentMethods().keySet()) {
        //             MethodInfo dependentMethod = getMethodInfo(config, depClassInfo, depMethodSig);
        //             if (dependentMethod != null) {
        //                 dependent_methods_list.add(dependentMethod.getMethodSignature());
        //             }
        //         }
        //     }
        
    }

    public static String getDepInfo(Config config, String depClassName, PromptInfo promptInfo) throws IOException {
        ClassInfo depClassInfo = getClassInfo(config, depClassName);
        if (depClassInfo == null) {
            try {
                String sourceCode = getSourceCode(depClassName);
                if (sourceCode != null) {
                    promptInfo.incrementSofiaActivations();
                }
                return sourceCode;
            } catch (Exception e) {
                return null;
            }
        } else {
            return null;
        }
    }

    public static String getSourceCode(String className) {
        String classPath = className.replace('.', '/') + ".class";
        for (String dependency : dependencies) {
            try {
                File jarFile = new File(dependency);
                if (jarFile.exists()) {
                    String decompiledClass = decompileClassFromJar(jarFile, classPath);
                    if (decompiledClass != null) {
                        return removeLeadingJavadoc(decompiledClass);
                    }
                }
            } catch(Exception e) {
                continue;
            }
        }
        return null;
    }

    private static String decompileClassFromJar(File jarFile, String classPath) throws IOException {
        // Extract .class from JAR
        File tempClassFile = extractClassFile(jarFile, classPath);
        if (tempClassFile == null) {
            return null;
        }

        // Use CFR decompiler
        StringWriter writer = new StringWriter();

        OutputSinkFactory mySink = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                return Arrays.asList(SinkClass.STRING);
            }

            @Override
            public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                return message -> writer.write(message.toString() + "\n");
            }
        };

        CfrDriver driver = new CfrDriver.Builder()
                .withOutputSink(mySink)
                .build();

        driver.analyse(Arrays.asList(tempClassFile.getAbsolutePath()));

        return writer.toString();
    }


    private static File extractClassFile(File jarFile, String classPath) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry(classPath);
            if (entry == null) {
                return null;
            }

            File tempFile = Files.createTempFile("class", ".class").toFile();
            try (InputStream in = jar.getInputStream(entry);
                 OutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            return tempFile;
        }
    }

    public static String removeLeadingJavadoc(String source) {
        return source.replaceFirst("(?s)^Analysing.*?\\R*/\\*.*?\\*/\\s*", "");
    }

}


