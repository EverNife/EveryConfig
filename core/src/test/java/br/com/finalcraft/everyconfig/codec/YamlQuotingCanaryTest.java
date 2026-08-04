package br.com.finalcraft.everyconfig.codec;

import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CANARY — this whole file exists only to notice when Jackson stops mangling ambiguous scalars. It asserts
 * that the defect is STILL THERE, so it turns RED on the day the defect is gone. Nothing here protects
 * production behavior; deleting it costs no coverage.
 *
 * <p>The defect: with {@code MINIMIZE_QUOTES}, a String whose text reads like a YAML 1.1 number is emitted
 * WITHOUT quotes ({@code v: 1.10}). The document no longer distinguishes it from a number, so re-reading it
 * through a tree yields the resolved number and the original text is gone ({@code "1.10"} comes back as
 * {@code "1.1"}). YAML is the only affected codec: JSON/JSONC/TOML always delimit a string. The library
 * cannot dodge it, because its canonical state IS the tree.
 *
 * <p>Deliberately self-contained (raw Jackson, its own input list, no shared helper): the subject under
 * test is the upstream emitter, and a file meant to be deleted whole must not be entangled with anything.
 *
 * <p><b>WHEN THIS TEST TURNS RED, the upstream emitter started quoting ambiguous scalars. Then:</b>
 * <ol>
 *   <li>DELETE this entire file — its only job is done;</li>
 *   <li>in {@code AbstractConfigTest}, delete {@code YAML_VALUE_DEFECT}, {@code YAML_TYPE_DEFECT} and the
 *       two {@code skipKnownYamlDefect} bypasses that reference them, so the full input list is asserted
 *       on every codec again.</li>
 * </ol>
 */
@DisplayName("CANARY: Jackson still emits ambiguous Strings unquoted under MINIMIZE_QUOTES")
class YamlQuotingCanaryTest {

    private static final YAMLMapper MINIMIZE = YAMLMapper.builder()
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .build();

    /** The same surface {@code AbstractConfigTest} probes, kept here independently so this file is deletable. */
    private static final List<String> AMBIGUOUS = Arrays.asList(
            "176543210987654321", "9999999999999999999999", "0176", "007", "00", "0",
            "1.10", "1.0", "3.14", "1e5", "0x1F", "12_34", "+55", "-7", ".5", "1:30",
            "true", "no", "null", "~", "hello");

    /** Exactly the inputs whose TEXT changes today. Asserted as a whole so the canary also fires if the
     *  upstream defect merely shifts (a case added or removed) rather than disappearing outright. */
    private static final List<String> EXPECTED_LOSSY = Arrays.asList(
            "1.10", "1e5", "0x1F", "12_34", "+55", ".5");

    private static String treeRoundTrip(final String value) throws Exception {
        final String yaml = MINIMIZE.writeValueAsString(Collections.singletonMap("v", value));
        return MINIMIZE.readTree(yaml).get("v").asText();
    }

    @Test
    @DisplayName("MINIMIZE_QUOTES still loses exactly these six Strings (delete this file when it goes red)")
    void minimizeQuotes_stillLosesAmbiguousStrings() throws Exception {
        final List<String> lossy = new ArrayList<String>();
        for (final String s : AMBIGUOUS) {
            if (!s.equals(treeRoundTrip(s))) {
                lossy.add(s);
            }
        }
        assertEquals(EXPECTED_LOSSY, lossy,
                "upstream quoting behavior CHANGED. If the list is now empty the defect is fixed: delete this "
                        + "file and the YAML_VALUE_DEFECT / YAML_TYPE_DEFECT bypasses in AbstractConfigTest.");
    }
}
