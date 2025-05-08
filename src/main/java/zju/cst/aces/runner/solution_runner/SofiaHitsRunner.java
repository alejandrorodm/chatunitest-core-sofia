package zju.cst.aces.runner.solution_runner;

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import zju.cst.aces.api.Logger;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.api.impl.obfuscator.Obfuscator;
import zju.cst.aces.api.phase.PhaseImpl;
import zju.cst.aces.api.phase.solution.SOFIA_HITS;
import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.parser.CodeParser;
import zju.cst.aces.runner.MethodRunner;
import zju.cst.aces.util.JsonResponseProcessor;
import zju.cst.aces.util.EmbeddingClient;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class SofiaHitsRunner extends MethodRunner {

    private static List<String> dependencies;
    private static Logger logger;
    public static EmbeddingClient embeddingClient = new EmbeddingClient();

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

            //Para los metodos que se encuentran dentro de la misma clase
            if (depClassName.equals(classInfo.getClassName())) {
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
            //System.out.println("generatePromptInfoWithDep: " + depClassName + " " + depMethods);
            promptInfo.addMethodDeps(depClassName, getDepInfo(config, depClassName, depMethods));
            SofiaHitsRunner.saveDepInfo(config, classInfo.className, depClassName, promptInfo);

            //EL EXTERNAL METHOD DEPS DE AQUI DEBE SUSTITUIR TAL Y COMO SE HACE EN LA EJECUCION PRIMERA
            promptInfo.addExternalMethodDeps(depClassName, SofiaHitsRunner.getHeaderDepInfo(config, depClassName, promptInfo));
            //promptInfo.addExternalMethodDeps(depClassName, SofiaHitsRunner.getDepInfo(config, depClassName, promptInfo));

            addMethodDepsByDepth(config, depClassName, depMethods, promptInfo, config.getDependencyDepth());
        }
        System.out.println("CONFIG RAG PERCENT " + config.getRagPercent());
        double ragPercent = config.getRagPercent() / 100.0;
        int num_elements = (int) (embeddingClient.countElements() * ragPercent);
        System.out.println("Elementos totales BD: " + embeddingClient.countElements() + " RAG Percent " + ragPercent + " num_elements " + num_elements);

        if(num_elements > 0){
            Map<String, List<MethodInfo>> rag_results = embeddingClient.search_similar_methods(methodInfo.getSourceCode(), num_elements, classInfo.className);


            for (Map.Entry<String, List<MethodInfo>> entry : rag_results.entrySet()) {
                String key = entry.getKey();
                List<MethodInfo> methods = entry.getValue();
                System.out.println("///////////////// RAG /////////////////: ");

                for (MethodInfo method : methods) {
                    System.out.print("Key: " + key + ", Method: " + method.getMethodName() + ", ");

                    //Si la cabecera no existe, no guardamos el metodo
                    if (!promptInfo.getExternalMethodDeps().containsKey(method.getClassName())) {
                        continue;
                    }
                    promptInfo.addMethodsExternalMethodDeps(method.getClassName(), method.getSourceCode());
                }
                System.out.println("\n\n");
            }
        }else{
            System.out.println("Se han seleccionado 0 elementos como petición para el RAG.\n\n");
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

        System.out.println("PROMPTINFO EXTERNAL METHODS " + promptInfo.getExternalMethodDeps().toString());
        System.out.println("\n\n");
        return promptInfo;
    }

    /**
     * Saves dependency information for a given class name by extracting and storing its methods.
     * If the class information is already available, no action is taken.
     *
     * @param config       The configuration object containing necessary settings.
     * @param depClassName The fully qualified name of the dependency class.
     * @param promptInfo   Additional prompt information (not used in the current implementation).
     * @return The source code of the dependency class if successfully retrieved and saved, 
     *         or {@code null} if the class information is already available or an error occurs.
     * @throws IOException If an I/O error occurs during the process.
     */
    public static String saveDepInfo(Config config, String className, String depClassName, PromptInfo promptInfo) throws IOException {
        ClassInfo depClassInfo = getClassInfo(config, depClassName);
        if (depClassInfo == null) {
            try {
                String sourceCode = getSourceCode(depClassName);
                if (sourceCode != null) {
                    CodeParser.saveExtractedMethodsAndConstructors(className, depClassName, sourceCode);
                }
                return sourceCode;
            } catch (Exception e) {
                return null;
            }
        } else {
            return null;
        }
    }

    public static String getHeaderDepInfo(Config config, String depClassName, PromptInfo promptInfo) throws IOException {
        ClassInfo depClassInfo = getClassInfo(config, depClassName);
        if (depClassInfo == null) {
            try {
                String sourceCode = getSourceCode(depClassName);
                if (sourceCode != null) {
                    promptInfo.incrementSofiaActivations();
                }
                //El sourceCode es la clase completa.
                String classHeader = CodeParser.extractClassHeader(depClassName, sourceCode);
                //System.out.println("SofiaHITS HEADER: " + depClassName + " " + classHeader);

                return classHeader;
            } catch (Exception e) {
                return null;
            }
        } else {
            return null;
        }
    }

    public static String getDepInfo(Config config, String depClassName, PromptInfo promptInfo) throws IOException {
        ClassInfo depClassInfo = getClassInfo(config, depClassName);
        if (depClassInfo == null) {
            try {
                String sourceCode = getSourceCode(depClassName);
                if (sourceCode != null) {
                    promptInfo.incrementSofiaActivations();
                }
                System.out.println("SofiaHITS: " + depClassName + " " + sourceCode);
                //El sourceCode es la clase completa.


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


