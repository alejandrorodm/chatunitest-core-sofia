package zju.cst.aces.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
public class MethodInfo {
    public String className;
    public String methodName;
    public String brief;
    public String methodSignature;
    public String sourceCode;
    public boolean useField;
    public boolean isConstructor;
    public boolean isGetSet;
    public boolean isPublic;
    public boolean isBoolean;
    public boolean isAbstract;
    public List<String> parameters;
    public Map<String, Set<String>> dependentMethods;
    public String full_method_info;
    public String method_comment;
    public String method_annotation;
    public String methodDescriptor;
    public String dependent_classes;

    public MethodInfo(String className, String methodName, String brief, String methodSignature,
                      String sourceCode, List<String> parameters, Map<String, Set<String>> dependentMethods,String full_method_info,String method_comment,String method_annotation, String methodDescriptor){
        this.className = className;
        this.methodName = methodName;
        this.brief = brief;
        this.methodSignature = methodSignature;
        this.sourceCode = sourceCode;
        this.parameters = parameters;
        this.dependentMethods = dependentMethods;
        this.full_method_info=full_method_info;
        this.method_comment=method_comment;
        this.method_annotation=method_annotation;
        this.methodDescriptor=methodDescriptor;
    }

    public MethodInfo(String className, String methodName, String brief, String methodSignature,
                      String sourceCode, List<String> parameters, Map<String, Set<String>> dependentMethods,String full_method_info,String method_comment,String method_annotation, String methodDescriptor, String dependent_classes){
        this.className = className;
        this.methodName = methodName;
        this.brief = brief;
        this.methodSignature = methodSignature;
        this.sourceCode = sourceCode;
        this.parameters = parameters;
        this.dependentMethods = dependentMethods;
        this.full_method_info=full_method_info;
        this.method_comment=method_comment;
        this.method_annotation=method_annotation;
        this.methodDescriptor=methodDescriptor;
        this.dependent_classes = dependent_classes;
    }

    public MethodInfo(String className, String methodName, String brief, String methodSignature,
                      String sourceCode, List<String> parameters, Map<String, Set<String>> dependentMethods,String full_method_info,String methodDescriptor, String dependent_classes){
        this.className = className;
        this.methodName = methodName;
        this.brief = brief;
        this.methodSignature = methodSignature;
        this.sourceCode = sourceCode;
        this.parameters = parameters;
        this.dependentMethods = dependentMethods;
        this.full_method_info=full_method_info;
        this.methodDescriptor=methodDescriptor;
        this.dependent_classes = dependent_classes;
    }

}
