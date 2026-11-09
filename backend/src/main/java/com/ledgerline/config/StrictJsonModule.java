package com.ledgerline.config;

import com.ledgerline.validation.RejectUnknownProperties;
import org.springframework.stereotype.Component;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.module.SimpleModule;

/**
 * Rejects unknown JSON properties only for types annotated with {@link RejectUnknownProperties}.
 *
 * Jackson asks every DeserializationProblemHandler about an unknown property before applying the global
 * FAIL_ON_UNKNOWN_PROPERTIES setting (which Spring Boot leaves off). This handler throws for annotated types and
 * returns false for all others, so their behaviour is unchanged. Spring Boot registers JacksonModule beans with
 * the JsonMapper that Spring MVC uses; the resulting exception becomes a 400 response.
 */
@Component
public class StrictJsonModule extends SimpleModule {

    public StrictJsonModule() {
        super("ledgerline-strict-json");
    }

    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        context.addHandler(new RejectUnknownForAnnotatedTypes());
    }

    static final class RejectUnknownForAnnotatedTypes extends DeserializationProblemHandler {

        @Override
        public boolean handleUnknownProperty(DeserializationContext context, JsonParser parser,
                ValueDeserializer<?> deserializer, Object beanOrClass, String propertyName) {
            // Records are built through their constructor, so Jackson passes the class rather than an instance.
            Class<?> type = beanOrClass instanceof Class<?> c ? c : beanOrClass.getClass();
            if (type.isAnnotationPresent(RejectUnknownProperties.class)) {
                throw UnrecognizedPropertyException.from(parser, beanOrClass, propertyName, null);
            }
            return false;
        }
    }
}
