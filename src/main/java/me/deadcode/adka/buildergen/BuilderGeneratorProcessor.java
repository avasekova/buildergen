package me.deadcode.adka.buildergen;

import me.deadcode.adka.buildergen.annotation.GenerateBuilder;
import org.jboss.forge.roaster.Roaster;
import org.jboss.forge.roaster.model.Type;
import org.jboss.forge.roaster.model.source.FieldSource;
import org.jboss.forge.roaster.model.source.JavaClassSource;
import org.jboss.forge.roaster.model.source.JavaDocSource;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

import static java.lang.System.lineSeparator;
import static me.deadcode.adka.buildergen.JavaElements.*;

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

    public static final String NOTE_GENERATED_CODE = "Note: generated code. All changes will be undone on the next build " +
            "as long as the enclosing class is annotated with @GenerateBuilder.";

    private static final String CLASSES_PATH_PREFIX = "src" + File.separator + "main" + File.separator + "java" + File.separator;
    private static final String DOT_JAVA = ".java";

    private static final String GET_OBJ = "getObj";
    private static final String GET_OBJ__ = GET_OBJ + "()";
    private static final String BUILD = "build";
    private static final String GET_THIS_BUILDER = "getThisBuilder";
    private static final String GET_THIS_BUILDER__ = GET_THIS_BUILDER + "()";
    private static final String FROM = "from";
    private static final String FROM_IGNORE_NULL = "fromIgnoreNull";
    private static final String ADD_TO = "addTo";
    private static final String ADD_ALL_TO = "addAllTo";
    private static final String ELEMENT = "Element";
    private static final String OBJECT = "object";

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

            //hack to save the FQN for nested classes (later when getting types from Roaster, the enclosing class is not included in it)
            Map<String, String> nestedClassesFullTypeToRoasterType = new HashMap<>();
            for (Element el : roundEnv.getElementsAnnotatedWith(GenerateBuilder.class)) {
                for (Element enclosed : el.getEnclosedElements()) {
                    if ((enclosed.getKind() == ElementKind.ENUM) || (enclosed.getKind() == ElementKind.CLASS) ||
                            (enclosed.getKind() == ElementKind.ANNOTATION_TYPE) || (enclosed.getKind() == ElementKind.INTERFACE)) {

                        String fullName = ((TypeElement) enclosed).getQualifiedName().toString();

                        String beginning = fullName.substring(0, fullName.lastIndexOf('.'));
                        String nameAsReturnedByRoaster = beginning.substring(0, beginning.lastIndexOf('.') + 1) +
                                fullName.substring(fullName.lastIndexOf('.') + 1);

                        nestedClassesFullTypeToRoasterType.put(nameAsReturnedByRoaster, fullName);
                    }
                }
            }

            //then process them one by one and only use this helper collection to find the parents
            for (Element c : roundEnv.getElementsAnnotatedWith(GenerateBuilder.class)) {
                if (c.getEnclosingElement().getKind() != ElementKind.PACKAGE) {
                    //this is not a top-level class but rather a nested class/enum, local or anonymous class; ignore for simplicity
                    messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, "BuilderGenerator: Ignoring nested class/enum '" +
                            ((TypeElement) c).getQualifiedName() + "'");
                    continue;
                }

                if (c.getKind() == ElementKind.ENUM) {
                    //ignore enums as well
                    messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, "BuilderGenerator: Ignoring enum '" +
                            ((TypeElement) c).getQualifiedName() + "'");
                    continue;
                }


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

                    String objectOfThisClass = decapitalize(javaClass.getName());
                    StringBuilder from = new StringBuilder();
                    StringBuilder fromIgnoreNull = new StringBuilder();

                    if (! extendsAnnotatedClass.isEmpty()) { //if it has a parent
                        from.append(_super() + "." + _from(OBJECT)).append(lineSeparator()).append(lineSeparator());
                        fromIgnoreNull.append(_super() + "." + _fromIgnoreNull(OBJECT)).append(lineSeparator()).append(lineSeparator());
                    }

                    StringBuilder insideFrom = new StringBuilder();
                    StringBuilder insideFromIgnoreNull = new StringBuilder();

                    for (FieldSource<JavaClassSource> attribute : javaClass.getFields()) {
                        //ignore static and final fields
                        if (attribute.isStatic()) {
                            messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, "BuilderGenerator: Ignoring static attribute '" +
                                    attribute.getName() + "'");
                            continue;
                        }

                        if (attribute.isFinal()) {
                            messager.printMessage(Diagnostic.Kind.MANDATORY_WARNING, "BuilderGenerator: Ignoring final attribute '" +
                                    attribute.getName() + "'");
                            continue;
                        }


                        String setterName = _set(attribute.getName());

                        //for each attribute of the parent class, add a setter in this form:
                        String attributeType = nestedClassesFullTypeToRoasterType.getOrDefault(attribute.getType().getQualifiedName(),
                                attribute.getType().getQualifiedNameWithGenerics());
                        abstractBuilder.addMethod()
                                .setPublic()
                                .setName(attribute.getName())
                                .setReturnType("B")
                                .setBody(GET_OBJ__ + "." + setterName + _return(GET_THIS_BUILDER__))
                                .addParameter(attributeType, attribute.getName());

                        //for each attribute that is a Collection, add also methods for adding elements
                        if (isCollection(attribute.getType())) {
                            abstractBuilder.addMethod()
                                    .setPublic()
                                    .setName(ADD_TO + capitalize(attribute.getName()))
                                    .setReturnType("B")
                                    .setBody( //TODO instantiate the collection if null
                                            GET_OBJ__ + "." + _get(attribute.getName()) + "." + _add(attribute.getName() +
                                                    ELEMENT) + lineSeparator() + _return(GET_THIS_BUILDER__))
                                    .addParameter(getElementType(attribute.getType()), attribute.getName() + ELEMENT);

                            abstractBuilder.addMethod()
                                    .setPublic()
                                    .setName(ADD_ALL_TO + capitalize(attribute.getName()))
                                    .setReturnType("B")
                                    .setBody( //TODO instantiate the collection if null
                                            GET_OBJ__ + "." + _get(attribute.getName()) + "." + _addAll(attribute.getName()) +
                                                    lineSeparator() + _return(GET_THIS_BUILDER__))
                                    .addParameter(attribute.getType().getQualifiedNameWithGenerics(), attribute.getName());
                        }


                        //generate parts of methods to set all values from an object of the type being built (or its [sub|super]type)
                        String setThisAttributeFromGivenObject = GET_OBJ__ + "." + _set(attribute.getName(),
                                objectOfThisClass + "." + _get(attribute));

                        insideFrom.append(setThisAttributeFromGivenObject);

                        //a very similar method, but ignoring null values
                        if (! attribute.getType().isPrimitive()) {
                            insideFromIgnoreNull.append(_if(_notNull(objectOfThisClass + "." + _get(attribute)), setThisAttributeFromGivenObject));
                        } else {
                            insideFromIgnoreNull.append(setThisAttributeFromGivenObject);
                        }
                    }


                    from.append(_if(_instanceof(OBJECT, javaClass.getName()),
                            javaClass.getName() + " " + objectOfThisClass + " = " + _cast(OBJECT, javaClass.getName()) + ";" +
                                    insideFrom));
                    fromIgnoreNull.append(_if(_instanceof(OBJECT, javaClass.getName()),
                            javaClass.getName() + " " + objectOfThisClass + " = " + _cast(OBJECT, javaClass.getName()) + ";" +
                                    insideFromIgnoreNull));


                    from.append(lineSeparator()).append(_return(GET_THIS_BUILDER__));
                    abstractBuilder.addMethod()
                            .setPublic()
                            .setName(FROM)
                            .setReturnType("B")
                            .setBody(from.toString())
                            .addParameter(Object.class, OBJECT);

                    fromIgnoreNull.append(lineSeparator()).append(_return(GET_THIS_BUILDER__));
                    abstractBuilder.addMethod()
                            .setPublic()
                            .setName(FROM_IGNORE_NULL)
                            .setReturnType("B")
                            .setBody(fromIgnoreNull.toString())
                            .addParameter(Object.class, OBJECT);


                    //and these exact three methods (if not inherited from parent):
                    if (extendsAnnotatedClass.isEmpty()) {
                        abstractBuilder.addMethod()
                                .setPublic()
                                .setAbstract(true)
                                .setName(BUILD)
                                .setReturnType("T");

                        abstractBuilder.addMethod()
                                .setPublic()
                                .setAbstract(true)
                                .setName(GET_THIS_BUILDER)
                                .setReturnType("B");

                        abstractBuilder.addMethod()
                                .setPublic()
                                .setAbstract(true)
                                .setName(GET_OBJ)
                                .setReturnType("T");
                    }

                    //add the JavaDoc
                    JavaDocSource abstractBuilderJavaDoc = abstractBuilder.getJavaDoc();
                    abstractBuilderJavaDoc.setText(NOTE_GENERATED_CODE);


                    //--------------------------------------------------------------------------------------------
                    generateConcreteBuilder(javaClass);

                    try (BufferedWriter bw = new BufferedWriter(new FileWriter(classFile));
                         InputStream is = BuilderGeneratorProcessor.class.getClassLoader().getResourceAsStream("options.properties")) {
                        Properties formattingProperties = new Properties();
                        formattingProperties.load(is);
                        bw.write(Roaster.format(formattingProperties, javaClass.toString()));
                    }

                } catch (IOException e) {
                    messager.printMessage(Diagnostic.Kind.ERROR, e.toString());
                }
            }

            firstRound = false;
        }

        return true;
    }



    private void generateConcreteBuilder(JavaClassSource javaClass) {
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
                .setLiteralInitializer(_new(javaClass.getName()));

        concreteBuilder.addMethod()
                .setPublic()
                .setName(BUILD)
                .setReturnType(javaClass.getName())
                .setBody(_return(createdObjectAttributeName))
                .addAnnotation(Override.class);

        concreteBuilder.addMethod()
                .setPublic()
                .setName(GET_THIS_BUILDER)
                .setReturnType(concreteBuilderName)
                .setBody(_return(_this()))
                .addAnnotation(Override.class);

        concreteBuilder.addMethod()
                .setPublic()
                .setName(GET_OBJ)
                .setReturnType(javaClass.getName())
                .setBody(_return(createdObjectAttributeName))
                .addAnnotation(Override.class);

        //add the JavaDoc
        JavaDocSource concreteBuilderJavaDoc = concreteBuilder.getJavaDoc();
        concreteBuilderJavaDoc.setText(NOTE_GENERATED_CODE);
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

    private static String _from(String object) {
        return FROM + "(" + object + ");";
    }

    private static String _fromIgnoreNull(String object) {
        return FROM_IGNORE_NULL + "(" + object + ");";
    }

}
