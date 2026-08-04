package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.annotation.Comment;
import br.com.finalcraft.everyconfig.annotation.Key;
import br.com.finalcraft.everyconfig.annotation.KeyTransformCase;
import br.com.finalcraft.everyconfig.annotation.Section;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/** The entity shapes the rule-introspection suites resolve sites from. */
final class RuleFixtures {

    private RuleFixtures() {
    }

    /** The third level of the nesting. */
    static class LevelTwo {

        @Comment("deep value")
        @TestRule("deep")
        public int deep = 3;
    }

    static class LevelOne {

        @Comment("mid value")
        @TestRule("mid")
        public int mid = 2;

        @Comment("level two")
        public LevelTwo two = new LevelTwo();
    }

    /** Reached through two sibling fields: the diamond must be resolved on both branches. */
    static class Leaf {

        @Comment("leaf tag")
        @TestRule("tag")
        public String tag = "t";
    }

    /** Self-referential: the descent must stop at the first recurrence of the class on the path. */
    static class SelfRef {

        @Comment("self name")
        @TestRule("name")
        public String name = "n";

        @Comment("next")
        public SelfRef next = null;
    }

    /** Every naming shape at once: a renamed + case-transformed key, a Jackson-named key, a relocated
     *  field, three levels of nesting, a diamond and a self-reference. */
    @Comment("Hairy header")
    @TestRule("entity")
    static class Hairy {

        @Comment("plain")
        @TestRule("plain")
        public int plain = 1;

        @Key(value = "maxPoolSize", transformCase = KeyTransformCase.KEBAB_CASE)
        @Comment("renamed")
        @TestRule("renamed")
        public String renamed = "x";

        @JsonProperty("json-named")
        @Comment("json named")
        @TestRule("json")
        public String jsonNamed = "y";

        @Section("database.pool")
        @Comment("sectioned")
        @TestRule("sectioned")
        public int maxSize = 5;

        @Comment("level one")
        public LevelOne one = new LevelOne();

        @Comment("left leaf")
        public Leaf left = new Leaf();

        @Comment("right leaf")
        public Leaf right = new Leaf();

        @Comment("self")
        public SelfRef self = new SelfRef();
    }

    /** Only a vocabulary the built-in selector does not claim. */
    static class ForeignOnly {

        @PlainMark("check")
        public int value = 1;
    }

    /** Nothing but structural annotations: none of them is a rule. */
    static class Structural {

        @Comment("documented")
        @Key("renamed")
        @JsonProperty("named")
        @Deprecated
        public int value = 1;

        @Section("a.b")
        public String moved = "m";
    }

    /** No annotation at all — the type a config with no rules is made of. */
    static class Clean {

        public int value = 1;

        public String name = "n";
    }

    /** Two vocabularies on one member, one of them repeated: the ordering fixture. */
    static class Ordered {

        @TestRule("alpha")
        @TestRule("beta")
        @PlainMark("mark")
        public int value = 1;
    }

    /** Declared identically to {@link Ordered}: two distinct classes must resolve to the same shape. */
    static class OrderedTwin {

        @TestRule("alpha")
        @TestRule("beta")
        @PlainMark("mark")
        public int value = 1;
    }

    /** A computed invariant: the method has no key of its own, so its site reports at the entity's path. */
    static class Computed {

        public int left = 1;

        public int right = 2;

        @JsonIgnore
        @TestRule("left + right must be positive")
        public boolean isConsistent() {
            return left + right > 0;
        }
    }

    /** Defaults reachable directly, through a field chain, and as a shared mutable instance. */
    static class Defaults {

        @TestRule("scalar")
        public int scalar = 42;

        @TestRule("tags")
        public List<String> tags = new ArrayList<>();

        public LevelOne nested = new LevelOne();
    }

    /** Its constructor blows up, so no default instance can ever be built — and the failure is remembered
     *  instead of being retried for every site. */
    static class ExplodingDefaults {

        static int constructions = 0;

        @TestRule("a")
        public int a = 1;

        @TestRule("b")
        public int b = 2;

        ExplodingDefaults() {
            constructions++;
            throw new IllegalStateException("this type has no usable default instance");
        }
    }
}
