package com.ledgerline.money;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Accepts only a JSON string. Jackson would otherwise turn a JSON number such as 10.5 into the string "10.5", so
 * a client could send a floating-point amount and have it accepted. With this, 10.5 is a 400.
 */
public final class JsonStringOnly extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            return parser.getString();
        }
        return (String) context.handleUnexpectedToken(String.class, parser);
    }
}
