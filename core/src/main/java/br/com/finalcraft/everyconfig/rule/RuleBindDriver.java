package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.binding.BindException;
import br.com.finalcraft.everyconfig.binding.LoadIssue;
import br.com.finalcraft.everyconfig.binding.schema.BindingNames;
import br.com.finalcraft.everyconfig.config.Config;
import br.com.finalcraft.everyconfig.config.section.ConfigSection;
import br.com.finalcraft.everyconfig.core.coerce.TypeFamily;
import br.com.finalcraft.everyconfig.core.tree.DPath;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Applies a config's rules around one bind: it resolves the entity's sites, hands each to the attached
 * engine, lets every reviewing instance decide what the engine found, and only then turns what survived into
 * load issues, warnings or a failure.
 *
 * <p>It lives here, next to the types it builds, because a {@link RuleContext} and a {@link RuleReviewContext}
 * are constructed by the bind and by nobody else. The binder drives it; the direction is deliberate — the
 * alternative was a public nine-argument constructor leaking the bind's internals into the rule surface.
 *
 * <p>Three passes, in this order and for a reason: the engines see every site before anyone decides, so a
 * review reads a complete picture of its instance; the reviews all run before any severity is resolved, so a
 * failing instance cannot silence a later one's review; the outcome is resolved last, in site order, and the
 * first {@code THROW} ends the bind.
 *
 * <p>An instance is reviewed when it is the bound entity itself or when it owns at least one site — resolving
 * the sites IS how nested instances are reached, so a nested type declaring a review but no rule at all is
 * never visited. The bound entity always is, which is what a check reaching outside the config needs.
 */
public final class RuleBindDriver {

    private static final Logger LOG = Logger.getLogger(RuleBindDriver.class.getName());

    /** Sites already logged, keyed by owner + path + rule: a {@code LOG} violation warns once per site, not
     *  once per load, so a config reloaded on a timer does not flood the log. */
    private static final Set<String> LOGGED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** Element types already warned about carrying claimed rules in compact form — once per type. */
    private static final Set<Class<?>> WARNED_COMPACT =
            Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());

    private final Object entity;
    private final RulePhase phase;
    private final ConfigSection section;
    private final JsonNode loadedNode;
    private final ObjectMapper mapper;
    private final boolean strictCoercion;
    private final Config config;
    private final RuleEngine engine;
    private final RulePolicy policy;

    /** The site-owning instances in the order they were first reached, so issues come out in site order. */
    private final List<Owned> owners = new ArrayList<>();
    private final Map<Object, Owned> byInstance = new IdentityHashMap<>();

    private RuleBindDriver(final Object entity, final RulePhase phase, final ConfigSection section,
                           final JsonNode loadedNode, final ObjectMapper mapper, final boolean strictCoercion,
                           final Config config, final RuleEngine engine, final RulePolicy policy) {
        this.entity = entity;
        this.phase = phase;
        this.section = section;
        this.loadedNode = loadedNode;
        this.mapper = mapper;
        this.strictCoercion = strictCoercion;
        this.config = config;
        this.engine = engine;
        this.policy = policy;
    }

    /**
     * Run the config's rules over {@code entity}, bound at {@code section} as a {@code rootType}, and return
     * the issues the surviving violations produced.
     *
     * <p>{@code loadedNode} is the subtree AS LOADED (null when the file had nothing there) and decides each
     * site's {@link ValueSource} while reading — a value the file supplied and the lenient retry then threw
     * away is still file-sourced, because its defect is already a coercion issue. While writing there is no
     * such thing, so presence is asked of the live tree instead.
     *
     * <p>Empty and free when the config has no engine attached, when the type declares no candidate
     * annotation and does not review, or when the bind produced no entity at all.
     */
    public static List<LoadIssue> applyRules(@Nullable final Object entity, final Class<?> rootType,
                                             final RulePhase phase, final ConfigSection section,
                                             @Nullable final JsonNode loadedNode, final ObjectMapper mapper,
                                             final boolean strictCoercion) {
        if (entity == null || rootType == null || !TypeFamily.isUserPojoType(rootType)) {
            return Collections.emptyList();
        }
        final Config config = section.getConfig();
        final RuleEngine engine = config.getRuleEngine();
        if (engine == RuleEngine.NONE) {
            return Collections.emptyList();
        }
        final List<RuleSite> sites = RuleModel.hasRules(rootType)
                ? RuleModel.of(rootType, engine.selector())
                : Collections.<RuleSite>emptyList();
        if (sites.isEmpty() && !RuleReviewInvoker.reviews(rootType)) {
            return Collections.emptyList();
        }
        return new RuleBindDriver(entity, phase, section, loadedNode, mapper, strictCoercion, config, engine,
                config.getRulePolicy()).run(sites);
    }

    /**
     * Warn once that {@code elementType} carries rules this config's engine claims yet is persisted as a
     * compact element — one string, no sub-tree, so no site of it has a path to be judged or reported at.
     * The gate is the ENGINE's claim, not the raw index: an annotation nobody handles is not a rule anyone
     * expected to fire.
     */
    public static void warnCompactRules(@Nullable final RuleEngine engine, @Nullable final Class<?> elementType) {
        if (engine == null || engine == RuleEngine.NONE || elementType == null
                || !TypeFamily.isUserPojoType(elementType) || !RuleModel.hasRules(elementType)
                || RuleModel.of(elementType, engine.selector()).isEmpty()) {
            return;
        }
        if (WARNED_COMPACT.add(elementType)) {
            LOG.warning("rules declared on " + elementType.getName() + " are not applied in compact-element "
                    + "form (no sub-path to address a site at); persist it by path/field/Map/list, not as a "
                    + "compact list element, for its rules to be checked");
        }
    }

    private List<LoadIssue> run(final List<RuleSite> sites) {
        owned(entity, section.getPath()); // the root reviews even when it owns no site at all
        applyEngines(sites);
        review();
        return resolve();
    }

    // ==================== pass 1: the engines ====================

    private void applyEngines(final List<RuleSite> sites) {
        for (final RuleSite site : sites) {
            final Object owner = site.ownerIn(entity);
            if (owner == null) {
                continue; // no instance to examine here; a missing owner is reported one level up
            }
            final Owned target = owned(owner, ownerPath(site));
            final String sitePath = section.concatSubPath(site.path());
            final ValueSource source = sourceOf(site.path(), sitePath);
            final RuleContext context = new RuleContext(site, phase, valueAt(site, owner, sitePath), owner,
                    source, new ConfigSection(config, sitePath),
                    violation -> target.found(violation, site), engine, policy, strictCoercion);
            try {
                engine.apply(context);
            } catch (final BindException alreadyExplained) {
                throw alreadyExplained;
            } catch (final Exception failed) {
                throw new BindException("rule " + RuleMessages.describe(site.rule()) + " at '" + sitePath
                        + "' failed: " + failed.getMessage(), failed);
            }
            if (context.corrected()) {
                carryFix(sitePath, source, context.correctedValue());
            }
        }
    }

    /** Where the checked value came from: the tree as loaded while reading, the live tree while writing. */
    private ValueSource sourceOf(final String relativePath, final String absolutePath) {
        final boolean present = phase == RulePhase.VALIDATE
                ? nodeAt(loadedNode, relativePath) != null
                : config.getNode(absolutePath) != null;
        return present ? ValueSource.FILE : ValueSource.DEFAULT;
    }

    private Object valueAt(final RuleSite site, final Object owner, final String sitePath) {
        if (site.kind() == RuleSite.Kind.FIELD) {
            final Field target = site.field();
            try {
                target.setAccessible(true);
                return target.get(owner);
            } catch (final Exception unreadable) {
                return null;
            }
        }
        if (site.kind() == RuleSite.Kind.METHOD) {
            final Method target = site.method();
            final String tag = "rule " + RuleMessages.describe(site.rule()) + " at '" + sitePath + "'";
            try {
                target.setAccessible(true);
                return target.invoke(owner);
            } catch (final InvocationTargetException e) {
                final Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof BindException) {
                    throw (BindException) cause;
                }
                throw new BindException(tag + ": '" + target.getName() + "' failed: " + cause.getMessage(),
                        cause);
            } catch (final Exception e) {
                throw new BindException(tag + ": '" + target.getName() + "' could not be invoked", e);
            }
        }
        return owner;
    }

    // ==================== pass 2: the reviews ====================

    private void review() {
        for (final Owned owner : owners) {
            if (!RuleReviewInvoker.reviews(owner.instance.getClass())) {
                continue;
            }
            final RuleReviewContext context = new RuleReviewContext(phase,
                    new ConfigSection(config, owner.path), owner.violations);
            RuleReviewInvoker.invoke(owner.instance, context, owner.path);
            applyReviewCorrections(owner, context);
            owner.decide(context.outcomes());
        }
    }

    /** A review's correction is the author's explicit intent, so the {@code applyCorrections} gate — which
     *  governs AUTOMATIC rewriting by an engine — does not apply; where the fix lands still does. */
    private void applyReviewCorrections(final Owned owner, final RuleReviewContext context) {
        for (final RuleReviewContext.Correction correction : context.corrections()) {
            final int slot = owner.slotOf(correction.violation());
            if (slot < 0) {
                continue; // raised by the review itself: no site behind it, so there is nothing to write
            }
            final RuleSite site = owner.origins.get(slot);
            if (site == null || !site.writeInto(owner.instance, correction.newValue())) {
                continue;
            }
            carryFix(section.concatSubPath(site.path()), owner.violations.get(slot).source(),
                    correction.newValue());
        }
    }

    /** Carry a correction past the entity: reading a file value, the canonical tree has to change too, or the
     *  next read and the next save would both answer with the value that was just rejected. */
    private void carryFix(final String sitePath, final ValueSource source, final Object newValue) {
        if (phase != RulePhase.VALIDATE || source != ValueSource.FILE) {
            return;
        }
        config.applyRuleFix(sitePath, newValue == null ? NullNode.getInstance() : mapper.valueToTree(newValue));
    }

    // ==================== pass 3: the outcome ====================

    private List<LoadIssue> resolve() {
        final List<LoadIssue> issues = new ArrayList<>();
        for (final Owned owner : owners) {
            for (int i = 0; i < owner.violations.size(); i++) {
                final RuleViolation violation = owner.violations.get(i);
                final RuleSite site = owner.origins.get(i);
                final RulePolicy.Severity severity = effectiveSeverity(violation);
                if (severity == RulePolicy.Severity.THROW) {
                    throw new BindException(RuleMessages.rejection(violation, site,
                            owner.instance.getClass()));
                }
                if (severity == RulePolicy.Severity.LOG) {
                    logOnce(violation, site, owner.instance.getClass());
                }
                issues.add(new LoadIssue(violation,
                        site != null ? site.valueType() : owner.instance.getClass()));
            }
        }
        return issues.isEmpty() ? Collections.<LoadIssue>emptyList() : issues;
    }

    /**
     * What this violation costs: the stamp it carries wins (the engine knew the rule, and a review's decision
     * was written into that same slot), then the policy's answer for the value's origin, and finally — for
     * file data the policy said nothing about — the strictness the bind was already given.
     */
    private RulePolicy.Severity effectiveSeverity(final RuleViolation violation) {
        return violation.severity() != null ? violation.severity()
                : policy.severityFor(violation.source(), strictCoercion);
    }

    private void logOnce(final RuleViolation violation, final RuleSite site, final Class<?> entityType) {
        final String key = (site != null ? site.owner().getName() : entityType.getName()) + '#'
                + violation.path() + '#'
                + (violation.rule() != null ? violation.rule().annotationType().getName() : "entity");
        if (LOGGED.add(key)) {
            LOG.warning("config rule violated: " + violation);
        }
    }

    // ==================== plumbing ====================

    private Owned owned(final Object instance, final String path) {
        Owned found = byInstance.get(instance);
        if (found == null) {
            found = new Owned(instance, path);
            byInstance.put(instance, found);
            owners.add(found);
        }
        return found;
    }

    /** Where the instance a site belongs to sits in the file. The chain is walked through the one path
     *  grammar, so an owner is addressed exactly where its value was written. */
    private String ownerPath(final RuleSite site) {
        String relative = "";
        for (final Field link : site.ownerChain()) {
            relative = BindingNames.sectionAwarePath(relative, link);
        }
        return section.concatSubPath(relative);
    }

    private static JsonNode nodeAt(final JsonNode root, final String relativePath) {
        JsonNode current = root;
        if (current == null) {
            return null;
        }
        if (DPath.isRoot(relativePath)) {
            return current;
        }
        for (final String segment : DPath.split(relativePath)) {
            current = current.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    /** One instance that owns sites (or reviews), and what the engines found on it. */
    private static final class Owned {

        private final Object instance;
        private final String path;
        private final List<RuleViolation> violations = new ArrayList<>();

        /** The site each violation came from, index-aligned with {@link #violations}; null once a violation
         *  the entity raised itself takes the slot. */
        private final List<RuleSite> origins = new ArrayList<>();

        Owned(final Object instance, final String path) {
            this.instance = instance;
            this.path = path;
        }

        void found(final RuleViolation violation, final RuleSite site) {
            violations.add(violation);
            origins.add(site);
        }

        int slotOf(final RuleViolation violation) {
            for (int i = 0; i < violations.size(); i++) {
                if (violations.get(i) == violation) {
                    return i;
                }
            }
            return -1;
        }

        /** Adopt the review's verdict: accepted slots drop out, re-stamped ones replace their original, and
         *  the ones the review raised join at the end with no site behind them. */
        void decide(final List<RuleViolation> outcomes) {
            final List<RuleViolation> kept = new ArrayList<>();
            final List<RuleSite> keptOrigins = new ArrayList<>();
            for (int i = 0; i < outcomes.size(); i++) {
                final RuleViolation outcome = outcomes.get(i);
                if (outcome == null) {
                    continue;
                }
                kept.add(outcome);
                keptOrigins.add(i < origins.size() ? origins.get(i) : null);
            }
            violations.clear();
            violations.addAll(kept);
            origins.clear();
            origins.addAll(keptOrigins);
        }
    }
}
