package zju.cst.aces.dto;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class ClassInfo {
    public String fullClassName;
    public String className;
    public int index;
    public String modifier;
    public String extend;
    public String implement;
    public String packageName;
    public String packageDeclaration;
    public String classSignature;
    public boolean hasConstructor;
    public boolean isPublic;
    public boolean isInterface;
    public boolean isAbstract;
    public List<String> imports;
    public List<String> fields;
    public List<String> superClasses;
    public List<String> implementedTypes;
    public Map<String, String> methodSigs;
    public List<String> methodsBrief;
    public List<String> constructorSigs;
    public List<String> constructorBrief;
    public List<String> getterSetterSigs;
    public List<String> getterSetterBrief;
    public Map<String, Set<String>> constructorDeps;
    public String compilationUnitCode;
    public String classDeclarationCode;
    public List<String> subClasses;

    /**
     * Constructor for the ClassInfo class, which encapsulates detailed information
     * about a class or interface within a Java source file.
     *
     * @param cu                The CompilationUnit representing the parsed Java source file.
     * @param classNode         The ClassOrInterfaceDeclaration node representing the class or interface.
     * @param index             The index of the class within the source file, useful for ordering or referencing.
     * @param classSignature    The signature of the class, including modifiers and name.
     * @param imports           A list of import statements used in the class.
     * @param fields            A list of fields (attributes) declared in the class.
     * @param superClasses      A list of parent classes from which this class inherits.
     * @param methodSigs        A map of method signatures (name and parameters) in the class.
     * @param methodsBrief      A list of brief descriptions of the methods' implementations.
     * @param hasConstructor    A boolean indicating whether the class has an explicit constructor.
     * @param constructorSigs   A list of signatures of the constructors in the class.
     * @param constructorBrief  A list of brief descriptions of the constructors' implementations.
     * @param getterSetterSigs  A list of getter and setter method signatures in the class.
     * @param getterSetterBrief A list of brief descriptions of the getter and setter methods.
     * @param constructorDeps   A map of constructor dependencies, indicating which classes are used in each constructor.
     * @param subClasses        A list of nested or inner classes within this class.
     */
    public ClassInfo(CompilationUnit cu, ClassOrInterfaceDeclaration classNode, int index, String classSignature,
                     List<String> imports, List<String> fields, List<String> superClasses, Map<String, String> methodSigs,
                     List<String> methodsBrief, boolean hasConstructor, List<String> constructorSigs,
                     List<String> constructorBrief, List<String> getterSetterSigs, List<String> getterSetterBrief, Map<String, Set<String>> constructorDeps,List<String> subClasses) {
        this.className = classNode.getNameAsString(); // Nombre de la clase
        this.index = index; // Índice de la clase dentro del archivo, útil para ordenamiento o referencia
        this.modifier = classNode.getModifiers().toString(); // Modificadores de acceso de la clase (public, private, abstract, etc.)
        this.extend = classNode.getExtendedTypes().toString(); // Clases de las que extiende (herencia)
        this.implement = classNode.getImplementedTypes().toString(); // Interfaces que implementa la clase
        this.packageName = cu.getPackageDeclaration().orElse(null) == null 
            ? "" 
            : cu.getPackageDeclaration().get().getNameAsString(); // Nombre del paquete al que pertenece la clase
        this.packageDeclaration = getPackageDeclaration(cu); // Código de la declaración del paquete
        this.classSignature = classSignature; // Firma de la clase (incluye modificadores y nombre)
        this.imports = imports; // Lista de imports utilizados en la clase
        this.fields = fields; // Lista de campos (atributos) de la clase
        this.superClasses = superClasses; // Lista de clases padre de las que hereda
        this.methodSigs = methodSigs; // Mapa de métodos con sus firmas (nombre y parámetros)
        this.methodsBrief = methodsBrief; // Lista de métodos con una breve descripción de su implementación
        this.hasConstructor = hasConstructor; // Indica si la clase tiene un constructor explícito
        this.constructorSigs = constructorSigs; // Lista de firmas de los constructores de la clase
        this.constructorBrief = constructorBrief; // Lista con detalles breves sobre la implementación de los constructores
        this.getterSetterSigs = getterSetterSigs; // Lista de métodos getter y setter con sus firmas
        this.getterSetterBrief = getterSetterBrief; // Breve descripción de los métodos getter y setter
        this.constructorDeps = constructorDeps; // Mapa con dependencias de los constructores, indicando qué clases se utilizan en cada uno
        this.subClasses = subClasses; // Lista de clases anidadas o internas dentro de esta clase
    }

    public void setCode(String compilationUnitCode, String classDeclarationCode) {
        this.compilationUnitCode = compilationUnitCode;
        this.classDeclarationCode = classDeclarationCode;
    }

    private String getPackageDeclaration(CompilationUnit compilationUnit) {
        if (compilationUnit.getPackageDeclaration().isPresent()) {
            return compilationUnit.getPackageDeclaration().get().toString().trim();
        } else {
            return "";
        }
    }

    @Override
    public String toString() {
        return "ClassInfo{" +
                "fullClassName='" + fullClassName + '\'' +
                ", className='" + className + '\'' +
                ", index=" + index +
                ", modifier='" + modifier + '\'' +
                ", extend='" + extend + '\'' +
                ", implement='" + implement + '\'' +
                ", packageName='" + packageName + '\'' +
                ", packageDeclaration='" + packageDeclaration + '\'' +
                ", classSignature='" + classSignature + '\'' +
                ", hasConstructor=" + hasConstructor +
                ", isPublic=" + isPublic +
                ", isInterface=" + isInterface +
                ", isAbstract=" + isAbstract +
                ", imports=" + imports +
                ", fields=" + fields +
                ", superClasses=" + superClasses +
                ", implementedTypes=" + implementedTypes +
                ", methodSigs=" + methodSigs +
                ", methodsBrief=" + methodsBrief +
                ", constructorSigs=" + constructorSigs +
                ", constructorBrief=" + constructorBrief +
                ", getterSetterSigs=" + getterSetterSigs +
                ", getterSetterBrief=" + getterSetterBrief +
                ", constructorDeps=" + constructorDeps +
                ", compilationUnitCode='" + compilationUnitCode + '\'' +
                ", classDeclarationCode='" + classDeclarationCode + '\'' +
                ", subClasses=" + subClasses +
                '}';
    }
}
