package com.copybot.plugin.api.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A String field that only takes these values, as written in the pipeline JSON: its kind is
 * {@link FieldKind#ENUM} (the action keeps a String to report an unknown value itself).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface AllowedValues {
    String[] value();
}
