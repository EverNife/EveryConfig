package br.com.finalcraft.everyconfig.binding;

import br.com.finalcraft.everyconfig.annotation.PostLoad;
import br.com.finalcraft.everyconfig.annotation.PostSave;
import br.com.finalcraft.everyconfig.annotation.PreLoad;
import br.com.finalcraft.everyconfig.annotation.PreSave;
import br.com.finalcraft.everyconfig.codec.jackson.JsonCodec;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.section.ConfigSection;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The lifecycle hooks: the four method annotations fire around read/write in order, and the
 *  {@link ConfigLifecycle} interface receives the bound {@link ConfigSection}. */
class LifecycleHooksTest {

    private final JsonCodec codec = new JsonCodec();

    private Config configFrom(final String json) {
        return new Config((ObjectNode) codec.readTree(json));
    }

    /** Records every hook firing; {@code trace} is transient so binding never touches it. */
    static class Tracked {
        public String name = "def";
        transient List<String> trace = new ArrayList<String>();

        @PreLoad
        void before() {
            trace.add("preLoad:" + name); // name is still the default here (tree not applied yet)
        }

        @PostLoad
        void after() {
            trace.add("postLoad:" + name); // name now reflects the tree
        }

        @PreSave
        void beforeSave() {
            trace.add("preSave");
        }

        @PostSave
        void afterSave() {
            trace.add("postSave");
        }
    }

    @Test
    void annotationsFireAroundReadAndWriteInOrder() {
        final Config c = configFrom("{\"name\":\"file\"}");

        final Tracked read = c.bind(Tracked.class, codec).read("");
        // preLoad ran before the bind (name=def), postLoad after (name=file)
        assertEquals(Arrays.asList("preLoad:def", "postLoad:file"), read.trace);

        final Tracked write = new Tracked();
        c.bind(Tracked.class, codec).write("place", write);
        assertEquals(Arrays.asList("preSave", "postSave"), write.trace);
    }

    /** Implements the opt-in interface; each callback records the section path it was handed. */
    static class Lifecycled implements ConfigLifecycle {
        public String name = "def";
        transient List<String> calls = new ArrayList<String>();

        @Override
        public void preLoad(final ConfigSection section) {
            calls.add("preLoad@" + section.getPath());
        }

        @Override
        public void postLoad(final ConfigSection section) {
            calls.add("postLoad@" + section.getPath());
        }

        @Override
        public void preSave(final ConfigSection section) {
            calls.add("preSave@" + section.getPath());
        }

        @Override
        public void postSave(final ConfigSection section) {
            calls.add("postSave@" + section.getPath());
        }
    }

    @Test
    void interfaceCallbacksReceiveTheBoundSection() {
        final Config c = configFrom("{\"sub\":{\"name\":\"file\"}}");

        final Lifecycled read = c.bind(Lifecycled.class, codec).read("sub");
        assertTrue(read.calls.contains("preLoad@sub"));
        assertTrue(read.calls.contains("postLoad@sub"));

        final Lifecycled write = new Lifecycled();
        c.bind(Lifecycled.class, codec).write("sub", write);
        assertTrue(write.calls.contains("preSave@sub"));
        assertTrue(write.calls.contains("postSave@sub"));
    }
}
