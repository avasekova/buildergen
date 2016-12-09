package me.deadcode.adka.buildergen;

import me.deadcode.adka.buildergen.annotation.GenerateBuilder;
import org.jboss.forge.roaster.Roaster;
import org.jboss.forge.roaster.model.Type;
import org.jboss.forge.roaster.model.source.FieldSource;
import org.jboss.forge.roaster.model.source.JavaClassSource;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

@SupportedAnnotationTypes("me.deadcode.adka.buildergen.annotation.GenerateBuilder")
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class BuilderGeneratorProcessor extends AbstractProcessor {

    private static final String CONCRETE_BUILDER_NAME = "%sBuilder";
    private static final String ABSTRACT_BUILDER_NAME = "Abstract" + CONCRETE_BUILDER_NAME;
    private static final String EXTENDS_PARENT_CLASS_ABSTRACT_BUILDER = " extends " + ABSTRACT_BUILDER_NAME + "<T, B>";

    private static final String CONCRETE_BUILDER_CLASS_TEMPLATE = "public static class " + CONCRETE_BUILDER_NAME +
            " extends " + ABSTRACT_BUILDER_NAME + "<%s, " + CONCRETE_BUILDER_NAME + "> { }";
    private static final String ABSTRACT_BUILDER_CLASS_TEMPLATE = "public static abstract class " + ABSTRACT_BUILDER_NAME +
            "<T extends %s, B extends " + ABSTRACT_BUILDER_NAME + "<T, B>>%s { }";


    private static final String CLASSES_PATH_PREFIX = "src" + File.separator + "main" + File.separator + "java" + File.separator;
    private static final String DOT_JAVA = ".java";
    private Messager messager;

    private boolean firstRound = true;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        this.messager = processingEnv.getMessager();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (firstRound) {

            //hack: first pass through them and get all FQ class names
            //TODO find a better way to see if the parent class is annotated?
            Set<String> annotatedClasses = roundEnv.getElementsAnnotatedWith(GenerateBuilder.class)
                    .stream().map(el -> ((TypeElement) el).getQualifiedName().toString()).collect(Collectors.toSet());

            //then process them one by one and only use this helper collection to find the parents
            for (Element c : roundEnv.getElementsAnnotatedWith(GenerateBuilder.class)) {
                TypeElement classs = (TypeElement) c;

                String pathToClass = classs.getQualifiedName().toString().replace('.', File.separatorChar);
                try {
                    File classFile = new File(CLASSES_PATH_PREFIX + pathToClass + DOT_JAVA);
                    JavaClassSource javaClass = Roaster.parse(JavaClassSource.class, classFile);

                    //--------------------------------------------------------------------------------------------

                    if (javaClass.hasNestedType(abstractBuilderName(javaClass.getName()))) {
                        //remove so it can be regenerated - just to be sure all the properties are ok etc.
                        javaClass.removeNestedType(javaClass.getNestedType(abstractBuilderName(javaClass.getName())));
                    }

                    //generate the inner class for the abstract builder
                    //check if the parent of this class is also annotated with this annotation, i.e. will also get a generated
                    //  builder. if so, add the extension clause to the builder
                    String extendsAnnotatedClass = "";
                    String parentClass = classs.getSuperclass().toString();
                    if (annotatedClasses.contains(parentClass)) {
                        String parentClassSimpleName = (parentClass.contains(".")) ?
                                parentClass.substring(parentClass.lastIndexOf(".") + 1) : parentClass;
                        extendsAnnotatedClass = String.format(EXTENDS_PARENT_CLASS_ABSTRACT_BUILDER, parentClassSimpleName);
                    }

                    JavaClassSource abstractBuilder = javaClass.addNestedType(
                            abstractBuilderClassTemplate(javaClass.getName(), extendsAnnotatedClass));

                    //for each attribute of the parent class, add a setter in this form:
                    for (FieldSource<JavaClassSource> attribute : javaClass.getFields()) {
                        String setterName = "set" + capitalize(attribute.getName()) + "(" + attribute.getName() + ")";

                        abstractBuilder.addMethod()
                                .setPublic()
                                .setName(attribute.getName())
                                .setReturnType("B")
                                .setBody("getObj()." + setterName + ";" + System.lineSeparator() +
                                        "return getThisBuilder();")
                                .addParameter(attribute.getType().getQualifiedNameWithGenerics(), attribute.getName());
                    }

                    //for each attribute that is a Collection, add also a method for adding individual elements:
                    for (FieldSource<JavaClassSource> attribute : javaClass.getFields()) {
                        if (isCollection(attribute.getType())) {
                            abstractBuilder.addMethod()
                                    .setPublic()
                                    .setName("addTo" + capitalize(attribute.getName()))
                                    .setReturnType("B")
                                    .setBody("getObj().get" + capitalize(attribute.getName()) + "().add(" + attribute.getName() + "Element" + ");" +
                                            System.lineSeparator() + "return getThisBuilder();")
                                    .addParameter(getElementType(attribute.getType()), attribute.getName() + "Element");
                        }
                    }

                    //and these exact three methods (if not inherited from parent):
                    if (extendsAnnotatedClass.isEmpty()) {
                        abstractBuilder.addMethod()
                                .setPublic()
                                .setAbstract(true)
                                .setName("build")
                                .setReturnType("T");

                        abstractBuilder.addMethod()
                                .setPublic()
                                .setAbstract(true)
                                .setName("getThisBuilder")
                                .setReturnType("B");

                        abstractBuilder.addMethod()
                                .setPublic()
                                .setAbstract(true)
                                .setName("getObj")
                                .setReturnType("T");
                    }


                    //--------------------------------------------------------------------------------------------

                    //generate the inner class for the implementation of the abstract builder
                    String concreteBuilderName = concreteBuilderName(javaClass.getName());
                    if (javaClass.hasNestedType(concreteBuilderName)) {
                        //remove so it can be regenerated - just to be sure all the properties are ok etc.
                        javaClass.removeNestedType(javaClass.getNestedType(concreteBuilderName));
                    }

                    //generate the inner class for the concrete builder
                    JavaClassSource concreteBuilder = javaClass.addNestedType(concreteBuilderClassTemplate(javaClass.getName()));

                    String createdObjectAttributeName = decapitalize(javaClass.getName());
                    concreteBuilder.addField()
                            .setPrivate()
                            .setName(createdObjectAttributeName)
                            .setType(javaClass.getName())
                            .setLiteralInitializer("new " + javaClass.getName() + "()");

                    concreteBuilder.addMethod()
                            .setPublic()
                            .setName("build")
                            .setReturnType(javaClass.getName())
                            .setBody("return " + createdObjectAttributeName + ";")
                            .addAnnotation(Override.class);

                    concreteBuilder.addMethod()
                            .setPublic()
                            .setName("getThisBuilder")
                            .setReturnType(concreteBuilderName)
                            .setBody("return this;")
                            .addAnnotation(Override.class);

                    concreteBuilder.addMethod()
                            .setPublic()
                            .setName("getObj")
                            .setReturnType(javaClass.getName())
                            .setBody("return " + createdObjectAttributeName + ";")
                            .addAnnotation(Override.class);




                    try (BufferedWriter bw = new BufferedWriter(new FileWriter(classFile))) {
                        bw.write(Roaster.format(javaClass.toString()));
                    }

                } catch (IOException e) {
                    messager.printMessage(Diagnostic.Kind.ERROR, e.toString());
                }
            }
        } else {
            firstRound = false;
        }

        return true;
    }

    private String capitalize(String attributeName) {
        return attributeName.substring(0,1).toUpperCase() + (attributeName.length() == 1 ? "" : attributeName.substring(1));
    }

    private String decapitalize(String className) {
        return className.substring(0,1).toLowerCase() + (className.length() == 1 ? "" : className.substring(1));
    }

    private String concreteBuilderName(String baseClass) {
        return String.format(CONCRETE_BUILDER_NAME, baseClass);
    }

    private String abstractBuilderName(String baseClass) {
        return String.format(ABSTRACT_BUILDER_NAME, baseClass);
    }

    private String concreteBuilderClassTemplate(String baseClass) {
        return String.format(CONCRETE_BUILDER_CLASS_TEMPLATE, baseClass, baseClass, baseClass, baseClass);
    }

    private String abstractBuilderClassTemplate(String baseClass, String extendsAnnotatedClass) {
        return String.format(ABSTRACT_BUILDER_CLASS_TEMPLATE, baseClass, baseClass, baseClass, extendsAnnotatedClass);
    }

    private boolean isCollection(Type<JavaClassSource> type) {
        try {
            Class c = Class.forName(type.getQualifiedName());
            return Collection.class.isAssignableFrom(c);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private String getElementType(Type<JavaClassSource> type) {
        return type.getTypeArguments().isEmpty() ? Object.class.getCanonicalName()
                : type.getTypeArguments().get(0).getQualifiedNameWithGenerics();
    }

}
