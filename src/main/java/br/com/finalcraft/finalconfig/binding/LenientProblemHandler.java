package br.com.finalcraft.finalconfig.binding;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;

import java.io.IOException;

/**
 * Turns a per-value coercion failure into a recorded {@link LoadIssue} plus a benign fallback, instead of
 * aborting the whole bind. A field whose stored value cannot be coerced ends up at a type-appropriate
 * zero/false/null and the problem is reported; absent keys are untouched and keep their default. The
 * handler is stateless — it records into the active {@link LoadIssueCollector} — so one instance is
 * shared across binds.
 */
final class LenientProblemHandler extends DeserializationProblemHandler {

    @Override
    public Object handleWeirdStringValue(final DeserializationContext ctxt, final Class<?> targetType,
                                         final String valueToConvert, final String failureMsg) {
        record(ctxt, targetType, valueToConvert, failureMsg);
        return fallback(targetType);
    }

    @Override
    public Object handleWeirdNumberValue(final DeserializationContext ctxt, final Class<?> targetType,
                                         final Number valueToConvert, final String failureMsg) {
        record(ctxt, targetType, valueToConvert, failureMsg);
        return fallback(targetType);
    }

    @Override
    public Object handleWeirdKey(final DeserializationContext ctxt, final Class<?> rawKeyType,
                                 final String keyValue, final String failureMsg) {
        record(ctxt, rawKeyType, keyValue, failureMsg);
        return DeserializationProblemHandler.NOT_HANDLED; // drop the unmappable map key, keep binding
    }

    @Override
    public Object handleUnexpectedToken(final DeserializationContext ctxt, final JavaType targetType,
                                        final JsonToken t, final JsonParser p, final String failureMsg)
            throws IOException {
        final Class<?> raw = targetType == null ? null : targetType.getRawClass();
        record(ctxt, raw, p == null ? null : p.getText(), failureMsg);
        if (p != null && (p.isExpectedStartArrayToken() || p.isExpectedStartObjectToken())) {
            p.skipChildren(); // consume the mismatched structure so parsing can continue past it
        }
        return fallback(raw);
    }

    private static void record(final DeserializationContext ctxt, final Class<?> targetType,
                               final Object rawValue, final String message) {
        LoadIssueCollector.record(new LoadIssue(currentPath(ctxt), rawValue, targetType, message));
    }

    private static String currentPath(final DeserializationContext ctxt) {
        try {
            final JsonParser p = ctxt.getParser();
            if (p != null && p.getParsingContext() != null) {
                String s = p.getParsingContext().pathAsPointer().toString();
                if (s.startsWith("/")) {
                    s = s.substring(1);
                }
                return s.replace('/', '.');
            }
        } catch (final Exception ignored) {
            // fall through to the placeholder below
        }
        return "?";
    }

    /** A type-appropriate value that will not crash a primitive setter; null for reference types. */
    private static Object fallback(final Class<?> t) {
        if (t == int.class || t == Integer.class) {
            return 0;
        }
        if (t == long.class || t == Long.class) {
            return 0L;
        }
        if (t == double.class || t == Double.class) {
            return 0.0d;
        }
        if (t == float.class || t == Float.class) {
            return 0.0f;
        }
        if (t == short.class || t == Short.class) {
            return (short) 0;
        }
        if (t == byte.class || t == Byte.class) {
            return (byte) 0;
        }
        if (t == boolean.class || t == Boolean.class) {
            return Boolean.FALSE;
        }
        return null;
    }
}
