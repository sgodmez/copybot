package com.copybot.plugin.api.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** An output pattern: the editor lists the known pattern variables ({@link ConfigSchema#PATTERN_VARIABLES}). */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface PatternField {
}
