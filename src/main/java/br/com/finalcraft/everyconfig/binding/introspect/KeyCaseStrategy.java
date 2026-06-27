package br.com.finalcraft.everyconfig.binding.introspect;

import br.com.finalcraft.everyconfig.annotation.KeyTransformCase;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * A class-wide naming strategy that applies one {@link KeyTransformCase} to every property of a class, so
 * a whole config class can adopt kebab/snake keys without a per-field {@code @Key}. Opt in per class with
 * {@code @JsonNaming(KeyCaseStrategy.Kebab.class)} (or {@code .Snake}); it is not installed globally, so
 * only annotated classes are affected.
 *
 * <p>A field-level {@code @Key} overrides this strategy: {@code @Key("literal")} keeps its name verbatim
 * and {@code @Key(transformCase = ...)} applies its own case, because an explicitly resolved property name
 * bypasses the naming strategy.
 */
public class KeyCaseStrategy extends PropertyNamingStrategies.NamingBase {

    private static final long serialVersionUID = 1L;

    private final KeyTransformCase transform;

    public KeyCaseStrategy(final KeyTransformCase transform) {
        this.transform = transform == null ? KeyTransformCase.NONE : transform;
    }

    @Override
    public String translate(final String propertyName) {
        return transform.apply(propertyName);
    }

    /** Ready-to-reference kebab-case strategy for {@code @JsonNaming(KeyCaseStrategy.Kebab.class)}. */
    public static final class Kebab extends KeyCaseStrategy {
        private static final long serialVersionUID = 1L;

        public Kebab() {
            super(KeyTransformCase.KEBAB_CASE);
        }
    }

    /** Ready-to-reference snake-case strategy for {@code @JsonNaming(KeyCaseStrategy.Snake.class)}. */
    public static final class Snake extends KeyCaseStrategy {
        private static final long serialVersionUID = 1L;

        public Snake() {
            super(KeyTransformCase.SNAKE_CASE);
        }
    }
}
