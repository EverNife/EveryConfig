package br.com.finalcraft.everyconfig.rule;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rule introspection on its own: no {@code Config}, no codec, no mapper, no file — the path a screen
 * builder takes when it wants the declared facts of a type before anything is loaded. This class
 * deliberately imports nothing from the config or codec packages; that absence is the contract.
 */
class RuleModelIntrospectionTest {

    /** Claims everything, so a suite can see the raw index the bind gate is built on. */
    private static final RuleSelector ANY = annotation -> true;

    @Test
    void sitesComeInTheDocumentedOrder() {
        assertEquals(Arrays.asList(
                "FIELD plain @TestRule(plain)",
                "FIELD max-pool-size @TestRule(renamed)",
                "FIELD json-named @TestRule(json)",
                "FIELD database.pool.maxSize @TestRule(sectioned)",
                "TYPE  @TestRule(entity)",
                "FIELD one.mid @TestRule(mid)",
                "FIELD one.two.deep @TestRule(deep)",
                "FIELD left.tag @TestRule(tag)",
                "FIELD right.tag @TestRule(tag)",
                "FIELD self.name @TestRule(name)"),
                signatures(RuleModel.of(RuleFixtures.Hairy.class)));
    }

    @Test
    void repeatedAndMixedAnnotationsAreOrderedByTypeThenOccurrence() {
        final List<String> first = signatures(RuleModel.of(RuleFixtures.Ordered.class, ANY));
        assertEquals(Arrays.asList(
                "FIELD value @PlainMark(mark)",   // sorted by qualified name, though declared last
                "FIELD value @TestRule(alpha)",   // repeated occurrences keep declaration order
                "FIELD value @TestRule(beta)"),
                first);
        assertEquals(first, signatures(RuleModel.of(RuleFixtures.Ordered.class, ANY)));
        assertEquals(first, signatures(RuleModel.of(RuleFixtures.OrderedTwin.class, ANY)));
    }

    @Test
    void hasRulesSeesTheRawIndexNotOnlyTheMarkedVocabulary() {
        assertTrue(RuleModel.hasRules(RuleFixtures.ForeignOnly.class));
        assertTrue(RuleModel.of(RuleFixtures.ForeignOnly.class).isEmpty()); // not @ConfigRule-marked
        assertEquals(1, RuleModel.of(RuleFixtures.ForeignOnly.class, ANY).size());

        assertFalse(RuleModel.hasRules(RuleFixtures.Clean.class));
        assertTrue(RuleModel.of(RuleFixtures.Clean.class, ANY).isEmpty());
    }

    @Test
    void structuralAnnotationsAreNotRules() {
        assertFalse(RuleModel.hasRules(RuleFixtures.Structural.class));
        assertTrue(RuleModel.of(RuleFixtures.Structural.class, ANY).isEmpty());
    }

    @Test
    void typeSiteCarriesTheEntityPathAndTheClass() {
        RuleSite typeSite = null;
        for (final RuleSite site : RuleModel.of(RuleFixtures.Hairy.class)) {
            if (site.kind() == RuleSite.Kind.TYPE) {
                typeSite = site;
            }
        }
        assertNotNull(typeSite);
        assertEquals("", typeSite.path());
        assertNull(typeSite.field());
        assertNull(typeSite.method());
        assertEquals(RuleFixtures.Hairy.class, typeSite.valueType());
        assertEquals(RuleFixtures.Hairy.class, typeSite.owner());
        assertEquals(Arrays.asList("Hairy header"), typeSite.comment());
    }

    @Test
    void methodSiteCarriesTheReturnTypeAndTheEntityPath() {
        final List<RuleSite> sites = RuleModel.of(RuleFixtures.Computed.class);
        assertEquals(1, sites.size());
        final RuleSite site = sites.get(0);
        assertEquals(RuleSite.Kind.METHOD, site.kind());
        assertEquals("", site.path());
        assertNull(site.field());
        assertNotNull(site.method());
        assertEquals("isConsistent", site.method().getName());
        assertEquals(boolean.class, site.valueType());
        assertEquals(Boolean.TRUE, site.defaultValue());
    }

    @Test
    void defaultValueWalksTheFieldChain() {
        final List<RuleSite> sites = RuleModel.of(RuleFixtures.Defaults.class);
        assertEquals(Integer.valueOf(42), siteAt(sites, "scalar").defaultValue());
        assertEquals(Integer.valueOf(2), siteAt(sites, "nested.mid").defaultValue());
        assertEquals(Integer.valueOf(3), siteAt(sites, "nested.two.deep").defaultValue());
    }

    @Test
    void defaultValueIsTheLiveDefaultAndIsResolvedOnce() {
        final RuleSite tags = siteAt(RuleModel.of(RuleFixtures.Defaults.class), "tags");
        final Object first = tags.defaultValue();
        assertNotNull(first);
        assertSame(first, tags.defaultValue()); // memoized, and shared with every caller: read-only
    }

    @Test
    void anUnbuildableDefaultIsRememberedInsteadOfRetried() {
        final List<RuleSite> sites = RuleModel.of(RuleFixtures.ExplodingDefaults.class);
        assertEquals(2, sites.size());
        assertNull(sites.get(0).defaultValue());
        assertEquals(1, RuleFixtures.ExplodingDefaults.constructions);
        assertNull(sites.get(1).defaultValue());
        assertNull(sites.get(0).defaultValue());
        assertEquals(1, RuleFixtures.ExplodingDefaults.constructions); // never attempted a second time
    }

    @Test
    void returnedListsAreImmutable() {
        final List<RuleSite> sites = RuleModel.of(RuleFixtures.Hairy.class);
        assertThrows(UnsupportedOperationException.class, () -> sites.add(sites.get(0)));
    }

    private static RuleSite siteAt(final List<RuleSite> sites, final String path) {
        for (final RuleSite site : sites) {
            if (path.equals(site.path())) {
                return site;
            }
        }
        throw new AssertionError("no rule site at '" + path + "' in " + signatures(sites));
    }

    private static List<String> signatures(final List<RuleSite> sites) {
        final List<String> out = new ArrayList<>();
        for (final RuleSite site : sites) {
            final Annotation rule = site.rule();
            final String declared;
            if (rule instanceof TestRule) {
                declared = ((TestRule) rule).value();
            } else if (rule instanceof PlainMark) {
                declared = ((PlainMark) rule).value();
            } else {
                declared = "";
            }
            out.add(site.kind() + " " + site.path() + " @" + rule.annotationType().getSimpleName()
                    + "(" + declared + ")");
        }
        return out;
    }
}
