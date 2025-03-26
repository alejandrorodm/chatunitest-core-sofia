package zju.cst.aces.util.hits;

import java.io.*;
import java.nio.file.*;
import java.util.regex.*;

public class processNameForHITS {

    public static void main(String[] args) {
        // 文件夹路径
        String folderPath = "C:/Users/Alejandro/Documents/UMA/Practicas/RAG/01-OV3/gestion-expedientes-master/chatunitest-tests/es/juntadeandalucia/ceceu/sede/gesexpedientes/service/impl";
        System.out.println("Processing files in folder: " + folderPath);
        // 正则表达式匹配文件名
        Pattern filePattern = Pattern.compile("^(.*)_Test_slice(\\d+)\\.java$");
        Pattern suitePattern = Pattern.compile("^(.*)_Suite\\.java$");

        // 遍历文件夹
        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            System.out.println("The specified folder does not exist or is not a directory.");
            return;
        }

        File[] files = folder.listFiles();

        if (files != null && files.length > 0) {
            for (File file : files) {
            // 只处理 .java 文件
            if (file.isFile() && file.getName().endsWith(".java")) {
                Matcher matcher = filePattern.matcher(file.getName());
                Matcher suiteMatcher = suitePattern.matcher(file.getName());

                if (suiteMatcher.matches()) {
                String baseName = suiteMatcher.group(1);

                // Read the file content and replace class names
                try {
                    String content = new String(Files.readAllBytes(file.toPath()));

                    // Replace all occurrences of Test with slice and slice with Test
                    // This handles the classes that are referenced in @SelectClasses

                    // Replace all occurrences of _Test_slice with _slice_Test
                    content = content.replaceAll("(MetodosEntregaServiceImpl_.*?)(_Test)(_slice\\d+)", "$1$3_Test");

                    // Write the updated content back to the file
                    Files.write(file.toPath(), content.getBytes());
                    System.out.println("Updated content in: " + file.getName());
                } catch (IOException e) {
                    System.err.println("Error processing file: " + file.getName());
                    e.printStackTrace();
                }
                }

                    if (matcher.matches()) {
                        String xxx = matcher.group(1);
                        String n = matcher.group(2);
                        String newClassName = xxx + "_slice" + n + "_Test";
                        String newFileName = xxx + "_slice" + n + "_Test.java";

                        // 读取文件内容并替换类名
                        try {
                            String content = new String(Files.readAllBytes(file.toPath()));
                            content = content.replaceAll("class " + Pattern.quote(xxx + "_Test"), "class " + newClassName);

                            // 写入新的文件内容
                            Files.write(file.toPath(), content.getBytes());

                            // 重命名文件
                            File newFile = new File(folderPath, newFileName);
                            if (file.renameTo(newFile)) {
                                System.out.println("Renamed: " + file.getName() + " to " + newFileName);
                            } else {
                                System.out.println("Failed to rename: " + file.getName());
                            }
                        } catch (IOException e) {
                            System.err.println("Error processing file: " + file.getName());
                            e.printStackTrace();
                        }
                    }
                }
            }
        } else {
            System.out.println("The specified folder does not exist or is empty.");
        }
    }
}


