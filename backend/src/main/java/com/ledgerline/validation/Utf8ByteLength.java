package com.ledgerline.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Length measured in UTF-8 bytes, not characters. BCrypt only reads the first 72 bytes of a password, and
 * Spring Security refuses longer ones, so "72 characters" would be wrong for any non-ASCII password.
 * Null values are left to {@code @NotNull}.
 */
@Documented
@Constraint(validatedBy = Utf8ByteLengthValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Utf8ByteLength {

    int min() default 0;

    int max();

    String message() default "must be between {min} and {max} bytes when UTF-8 encoded";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
