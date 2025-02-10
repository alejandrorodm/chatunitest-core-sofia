package zju.cst.aces.runner.solution_runner;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.project.MavenProject;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.OutputSinkFactory;
import zju.cst.aces.api.Task;
import zju.cst.aces.api.config.Config;
import zju.cst.aces.dto.ClassInfo;
import zju.cst.aces.dto.MethodInfo;
import zju.cst.aces.dto.PromptInfo;
import zju.cst.aces.runner.MethodRunner;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class SofiaRunner extends MethodRunner {
    public SofiaRunner(Config config, String fullClassName, MethodInfo methodInfo) throws IOException {
        super(config, fullClassName, methodInfo);
    }

    public static PromptInfo generatePromptInfoWithDep(Config config, ClassInfo classInfo, MethodInfo methodInfo) throws IOException {
        PromptInfo promptInfo = new PromptInfo(
                true,
                classInfo.fullClassName,
                methodInfo.methodName,
                methodInfo.methodSignature);
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
            promptInfo.addConstructorDeps(depClassName, SofiaRunner.getDepInfo(config, depClassName, depMethods));
        }

        for (Map.Entry<String, Set<String>> entry : methodInfo.dependentMethods.entrySet()) {
            String depClassName = entry.getKey();
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
            promptInfo.addMethodDeps(depClassName, SofiaRunner.getDepInfo(config, depClassName, depMethods));
            addMethodDepsByDepth(config, depClassName, depMethods, promptInfo, config.getDependencyDepth());
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

    public static String getDepInfo(Config config, String depClassName, Set<String> depMethods) throws IOException {
        ClassInfo depClassInfo = getClassInfo(config, depClassName);
        if (depClassInfo == null) {
            try {
                return getSourceCodeFromExternalClass(depClassName);
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



    private static String getSourceCodeFromExternalClass(String className, MavenProject project) {
        for (Artifact artifact : project.getArtifacts()) {
            File jarFile = artifact.getFile();
            if (jarFile != null && jarFile.exists()) {
                try (JarFile jar = new JarFile(jarFile)) {
                    String classFilePath = className.replace(".", "/") + ".class";
                    JarEntry entry = jar.getJarEntry(classFilePath);
                    if (entry != null) {
                        InputStream is = jar.getInputStream(entry);
                        Path tempFile = Files.createTempFile("classfile", ".class");
                        Files.copy(is, tempFile, StandardCopyOption.REPLACE_EXISTING);

                        CfrDriver driver = new CfrDriver.Builder()
                                .withOptions(Collections.singletonMap("decodestringswitch", "true"))
                                .withOutputSink(new OutputSinkFactory() {
                                    @Override
                                    public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> collection) {
                                        return Collections.singletonList(SinkClass.STRING);
                                    }
                                    @Override
                                    public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                                        return (Sink<T>) System.out::println;
                                    }
                                })
                                .build();

                        driver.analyse(Collections.singletonList(tempFile.toString()));
                        return new String(Files.readAllBytes(tempFile));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    @Mojo(name = "my-goal", defaultPhase = LifecyclePhase.GENERATE_TEST_SOURCES, requiresDependencyResolution = ResolutionScope.COMPILE)
    public class MyPluginMojo extends AbstractMojo {

        @Parameter(defaultValue = "${project}", readonly = true, required = true)
        private MavenProject project;

        public void execute() {
            String sourceCode = getSourceCodeFromExternalClass("com.ejemplo.ClaseA", project);
            getLog().info("Código fuente: \n" + sourceCode);
        }
    }

}


