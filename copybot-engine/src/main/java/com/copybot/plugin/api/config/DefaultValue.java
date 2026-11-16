package com.copybot.plugin.api.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value the action uses when the field is absent, as written in the pipeline JSON (e.g. "size",
 * "true", "8"). Shown by the editor; never written into the pipeline.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface DefaultValue {
    String value();
}
