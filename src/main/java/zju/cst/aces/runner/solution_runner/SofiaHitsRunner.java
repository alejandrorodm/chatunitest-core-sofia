package zju.cst.aces.runner.solution_runner;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import zju.cst.aces.api.Logger;
import zju.cst.aces.api.Task;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.api.impl.PromptConstructorImpl;
import zju.cst.aces.api.impl.obfuscator.Obfuscator;
import zju.cst.aces.api.phase.PhaseImpl;
import zju.cst.aces.api.phase.solution.SOFIA_HITS;
import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.runner.MethodRunner;
import zju.cst.aces.util.JsonResponseProcessor;
import zju.cst.aces.parser.EmbeddingClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

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

        if (config.isEnableObfuscate()) {
            Obfuscator obfuscator = new Obfuscator(config);
            PromptInfo obfuscatedPromptInfo = new PromptInfo(promptInfo);
            obfuscator.obfuscatePromptInfo(obfuscatedPromptInfo);

            phase_hits.generateMethodSlice(pc);
        } else {
            phase_hits.generateMethodSlice(pc);
        }
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
                            exportRecord(pc.getPromptInfo(), classInfo, num);
                            hasErrors = false;
                            break; // successfully
                        }

                    }
                }

                exportSliceRecord(pc.getPromptInfo(), classInfo, num, i); //todo 检测是否顺利生成信息
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
                methodInfo.methodSignature);
        promptInfo.setClassInfo(classInfo);
        promptInfo.setMethodInfo(methodInfo);
        // List<String> otherBriefMethods = new ArrayList<>();
        // List<String> otherMethodBodies = new ArrayList<>();

        for (Map.Entry<String, Set<String>> entry : classInfo.constructorDeps.entrySet()) {
            String depClassName = entry.getKey();
            Set<String> depMethods = entry.getValue();
            if (methodInfo.dependentMethods.containsKey(depClassName)) {
                continue;
            }

            promptInfo.addConstructorDeps(depClassName, SofiaHitsRunner.getDepInfo(config, depClassName, depMethods));
        }

        // for (Map.Entry<String, Set<String>> entry : methodInfo.dependentMethods.entrySet()) {
        //     String depClassName = entry.getKey();
        //     if (depClassName.equals(classInfo.getClassName())) {
        //         Set<String> otherSig = methodInfo.dependentMethods.get(depClassName);
        //         for (String otherMethod : otherSig) {
        //             MethodInfo otherMethodInfo = getMethodInfo(config, classInfo, otherMethod);
        //             if (otherMethodInfo == null) {
        //                 continue;
        //             }
        //             // only add the methods in focal class that are invoked
        //             otherBriefMethods.add(otherMethodInfo.brief);
        //             otherMethodBodies.add(otherMethodInfo.sourceCode);
        //         }
        //         continue;
        //     }

        //     Set<String> depMethods = entry.getValue();
        //     promptInfo.addMethodDeps(depClassName, SofiaHitsRunner.getDepInfo(config, depClassName, depMethods));
        //     addMethodDepsByDepth(config, depClassName, depMethods, promptInfo, config.getDependencyDepth());
        // }

        String fields = joinLines(classInfo.fields);
        String imports = joinLines(classInfo.imports);

        String information = classInfo.packageName
                + "\n" + imports
                + "\n" + classInfo.classSignature
                + " {\n";
        //TODO: handle used fields instead of all fields
        // String otherMethods = "";
        String otherFullMethods = "";

        // if (classInfo.hasConstructor) {
        //     otherMethods += joinLines(classInfo.constructorBrief) + "\n";
        //     otherFullMethods += getBodies(config, classInfo, classInfo.constructorSigs) + "\n";
        // }
//        if (methodInfo.useField) {
//            information += fields + "\n";
//            otherMethods +=  joinLines(classInfo.getterSetterBrief) + "\n";
//            otherFullMethods += getBodies(config, classInfo, classInfo.getterSetterSigs) + "\n";
//        }
        information += fields + "\n";
        // otherMethods +=  joinLines(classInfo.getterSetterBrief) + "\n";
        // otherFullMethods += getBodies(config, classInfo, classInfo.getterSetterSigs) + "\n";

        // otherMethods += joinLines(otherBriefMethods) + "\n";
        // otherFullMethods += joinLines(otherMethodBodies) + "\n";
        // information += methodInfo.sourceCode + "\n}";

        otherFullMethods += embeddingClient.searchCode(methodInfo.className, methodInfo.methodSignature, methodInfo.sourceCode, 6);
        promptInfo.setContext(information);

        //meterlo en el contexto es suficiente???
        promptInfo.setOtherMethodBrief(otherFullMethods); 
        promptInfo.setOtherMethodBodies("");
        
        //promptInfo.setOtherMethodBrief(otherMethods); 
        //promptInfo.setOtherMethodBodies(otherFullMethods);

        // Write the promptInfo to a file
        Path promptsFilePath = Path.of("prompts.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(promptsFilePath, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            writer.write(promptInfo.toString());
            writer.newLine();
            writer.close();
        } catch (IOException e) {
            logger.error("Failed to write promptInfo to file");
        }

        return promptInfo;
    }

    public static String getDepInfo(Config config, String depClassName, Set<String> depMethods) throws IOException {
        ClassInfo depClassInfo = getClassInfo(config, depClassName);
        if (depClassInfo == null) {
            try {
                return getSourceCode(depClassName);
            } catch (Exception e) {
                return null;
            }
        }

        String classSig = depClassInfo.classSignature;
        String fields = joinLines(depClassInfo.fields);

        String basicInfo = depClassInfo.packageName + "\n" + joinLines(depClassInfo.imports) + "\n"
                + classSig + " {\n" + fields + "\n";
        if (depClassInfo.hasConstructor) {
            String constructors = "";
            for (String sig : depClassInfo.constructorSigs) {
                MethodInfo depConstructorInfo = getMethodInfo(config, depClassInfo, sig);
                if (depConstructorInfo == null) {
                    continue;
                }
                constructors += depConstructorInfo.getSourceCode() + "\n";
            }

            basicInfo += constructors + "\n";
        }

        String sourceDepMethods = "";
        for (String sig : depMethods) {
            //TODO: identify used fields in dependent class
            MethodInfo depMethodInfo = getMethodInfo(config, depClassInfo, sig);
            if (depMethodInfo == null) {
                continue;
            }
            sourceDepMethods += depMethodInfo.getSourceCode() + "\n";
        }
        String getterSetter = joinLines(depClassInfo.getterSetterBrief) + "\n";
        return basicInfo + getterSetter + sourceDepMethods + "}";
    }

    public static String getSourceCode(String className) throws IOException {
        String classPath = className.replace('.', '/') + ".class";
        for (String dependency : dependencies) {
            try {
                File jarFile = new File(dependency);
                if (jarFile.exists()) {
                    String decompiledClass = decompileClassFromJar(jarFile, classPath);
                    if (decompiledClass != null) {
                        return decompiledClass;
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

}


