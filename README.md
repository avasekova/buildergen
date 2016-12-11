# Builder Generator

Automatic generation of builders for Java classes. Entity hierarchies are supported, 
i.e. the generated builders are capable of setting both the declared and the 
inherited fields.

## Usage

Maven dependency:

```xml
<dependency>
    <groupId>me.deadcode.adka</groupId>
    <artifactId>buildergen</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

Then register the annotation processor, e.g.:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <version>3.6.0</version>
            <configuration>
                <source>1.8</source>
                <target>1.8</target>
                <annotationProcessors>
                    <annotationProcessor>
                        me.deadcode.adka.buildergen.BuilderGeneratorProcessor
                    </annotationProcessor>
                </annotationProcessors>
            </configuration>
        </plugin>
    </plugins>
</build>
```

Annotate entity classes with `@GenerateBuilders` to request the generated 
builders. All generated classes are regenerated from scratch every time the 
annotation processor is invoked, so any changes to the generated code will 
be lost as long as the enclosing class is annotated with `@GenerateBuilders`. 
Getters and setters (conforming to the standard naming conventions) are 
expected to be present in the entity classes, but will not be generated.