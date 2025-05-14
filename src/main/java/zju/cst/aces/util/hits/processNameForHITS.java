package zju.cst.aces.util.hits;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.*;

public class processNameForHITS {

    public static void main(String[] args) {
        String rootFolder = "C:/RAG/01-OV3/gestion-expedientes-master/chatunitest-tests/";
        System.out.println("Processing files in folder (recursively): " + rootFolder);

        Pattern testPattern = Pattern.compile("^(.*)_Test_slice(\\d+)\\.java$");
        Pattern suitePattern = Pattern.compile("^(.*)_Suite\\.java$");

        try {
            Files.walkFileTree(Paths.get(rootFolder), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path filePath, BasicFileAttributes attrs) {
                    File file = filePath.toFile();
                    String fileName = file.getName();

                    if (!fileName.endsWith(".java")) return FileVisitResult.CONTINUE;

                    Matcher suiteMatcher = suitePattern.matcher(fileName);
                    Matcher testMatcher = testPattern.matcher(fileName);

                    // Eliminar *_Suite.java
                    if (suiteMatcher.matches()) {
                        if (file.delete()) {
                            System.out.println("Deleted suite file: " + filePath);
                        } else {
                            System.out.println("Failed to delete suite file: " + filePath);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    // Renombrar *_Test_sliceN.java → *_sliceN_Test.java y modificar contenido
                    if (testMatcher.matches()) {
                        String baseName = testMatcher.group(1);
                        String sliceNum = testMatcher.group(2);

                        String oldClassName = baseName + "_Test";
                        String newClassName = baseName + "_slice" + sliceNum + "_Test";
                        String newFileName = baseName + "_slice" + sliceNum + "_Test.java";

                        try {
                            String content = Files.readString(filePath);

                            // Reemplazar todas las instancias del nombre antiguo por el nuevo
                            content = content.replaceAll("\\b" + Pattern.quote(oldClassName) + "\\b", Matcher.quoteReplacement(newClassName));

                            // Añadir `public` a la línea de clase si no lo tiene ya
                            content = content.replaceAll(
                                "(?m)^\\s*(?!public\\s)(class\\s+\\w+.*\\{)",
                                "public $1"
                            );

                            Files.writeString(filePath, content);

                            // Renombrar el archivo si es necesario
                            File newFile = new File(file.getParentFile(), newFileName);
                            if (!file.getName().equals(newFileName)) {
                                if (file.renameTo(newFile)) {
                                    System.out.println("Renamed file: " + filePath + " -> " + newFile.getPath());
                                } else {
                                    System.out.println("Failed to rename file: " + filePath);
                                }
                            } else {
                                System.out.println("No rename needed: " + filePath);
                            }

                        } catch (IOException e) {
                            System.err.println("Error processing file: " + filePath);
                            e.printStackTrace();
                        }
                    }

                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            System.err.println("Error walking file tree");
            e.printStackTrace();
        }
    }
}
