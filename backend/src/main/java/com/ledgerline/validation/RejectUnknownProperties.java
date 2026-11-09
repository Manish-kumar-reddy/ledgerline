package com.ledgerline.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a request type whose JSON must not contain properties the type does not define; such a request is
 * rejected with 400 instead of the extra property being silently ignored. Used on authentication requests,
 * so that, for example, {@code "role": "ADMIN"} in a registration is refused visibly.
 *
 * Other request types keep Jackson's default (unknown properties ignored). Enforced by {@code StrictJsonModule}.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RejectUnknownProperties {
}
