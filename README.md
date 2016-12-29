# Builder Generator

Automatic generation of builders for Java classes. Entity hierarchies are supported, 
i.e. the generated builders are capable of setting both the declared and the 
inherited fields (in any order).

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

The generated builders will be included directly in the entity class as `public 
static` classes. For each entity class, two classes are generated: an abstract 
builder and a subclass extending it (in order to support builder inheritance). 

The following methods are included:
* a setter for each attribute
* `addToX` and `addAllToX` methods for `Collection` attributes
* `from` and `fromIgnoreNull` methods
* a `build` method to construct the resulting object

The `from` and `fromIgnoreNull` methods serve for replacing the values of all 
attributes with the ones provided in the entity object given as a parameter. In the 
case of `fromIgnoreNull`, `null` values are skipped.
These methods accept any `Object` and set as many attributes as possible from an 
entity of the type being built or _compatible_ types. All superclasses and subclasses 
of the type being built are considered compatible. For other types, no values are 
replaced.

### Note

For now, a standard Maven project structure is expected. When looking up the 
annotated class files for parsing, the expected relative path to a particular 
class consists of `src/main/java` prepended to its fully qualified name (i.e. the 
directory structure representing its package).