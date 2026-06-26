package br.com.finalcraft.finalconfig.binding.introspect;

import br.com.finalcraft.finalconfig.annotation.KeyTransformCase;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * A class-wide naming strategy that applies one {@link KeyTransformCase} to every property, so a whole
 * config class can opt into kebab/snake keys without a per-field {@code @Key}. Set it with
 * {@code @JsonNaming(KeyCaseStrategy.Kebab.class)} or by configuring the mapper.
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

    /** Ready-to-reference strategy for {@code @JsonNaming}. */
    public static final class Kebab extends KeyCaseStrategy {
        private static final long serialVersionUID = 1L;

        public Kebab() {
            super(KeyTransformCase.KEBAB_CASE);
        }
    }

    /** Ready-to-reference strategy for {@code @JsonNaming}. */
    public static final class Snake extends KeyCaseStrategy {
        private static final long serialVersionUID = 1L;

        public Snake() {
            super(KeyTransformCase.SNAKE_CASE);
        }
    }
}
