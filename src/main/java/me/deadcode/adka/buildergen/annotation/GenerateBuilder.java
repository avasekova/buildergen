package me.deadcode.adka.buildergen.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that a builder will be generated for the annotated class.
 * The generated builder will take into account parent builders if parent class is annotated as well.
 *
 * Relies on existing setters for all attributes, but does not generate them.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface GenerateBuilder {

}
