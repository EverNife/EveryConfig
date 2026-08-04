package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.annotation.Comment;
import br.com.finalcraft.everyconfig.annotation.Section;
import br.com.finalcraft.everyconfig.binding.schema.BindingNames;
import br.com.finalcraft.everyconfig.core.coerce.TypeFamily;

import java.lang.annotation.Annotation;
import java.lang.annotation.Repeatable;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The rule sites of a type, resolved once per class. Works with NO config and NO mapper: this is the
 * introspection surface a screen builder reads a form off, and the cheap gate a bind consults before doing
 * any rule work at all.
 *
 * <p><b>Paths.</b> A site's path is the FILE path of its value, produced by the same grammar the value write
 * and the comment seeding use ({@link BindingNames#sectionAwarePath}), through the same descent: only a user
 * POJO field is entered, a {@code @Section} field is NOT entered (its value relocates whole, so its own site
 * sits at the relocated path), the DECLARED field type drives the walk, and a class already on the current
 * path is not re-entered — so a self-referential type stops while a diamond is still resolved on both
 * branches. Static and synthetic fields are skipped: neither is ever persisted, so neither has a path.
 *
 * <p><b>Index.</b> The cache holds the RAW index — every site of every candidate annotation, whatever
 * vocabulary it belongs to — because a gate that looked only for {@link ConfigRule}-marked annotations would
 * skip a type whose rules come from a foreign API that can never carry the marker. An annotation is a
 * candidate unless it is structural — EveryConfig's own binding annotations and its rule plumbing,
 * Jackson's, and anything under {@code java.} or {@code kotlin.} — so a vocabulary nobody registered is
 * still seen. {@link #of} filters that index in memory, which is cheap and — unlike the index — is not
 * cached, since a selector lambda is no cache key.
 *
 * <p><b>Order.</b> Fully specified, so a report or a generated screen is reproducible: for each visited
 * class, its field sites in {@link BindingNames#allFields} order (subclass first), then its type sites
 * (hierarchy, subclass first), then its method sites (by method name); after that block, the blocks of its
 * nested-POJO fields, in the same field order. Within one member, annotations are ordered by their type's
 * qualified name and then by declaration order, so a repeated annotation keeps the order it was written in.
 * A repeatable rule annotation must be PUBLIC, container included: on a Java 8 runtime the JDK cannot read
 * a package-private container and rejects the whole lookup.
 */
public final class RuleModel {

    /** Structural vocabularies: annotations that describe how a value is STORED, never what it must be.
     *  Everything else is a candidate — jakarta, a consumer's own vocabulary, anything. */
    private static final String[] STRUCTURAL_PREFIXES = {
            "br.com.finalcraft.everyconfig.annotation.",
            "com.fasterxml.jackson.",
            "java.",
            "kotlin."
    };

    /** The raw index of each type, resolved once. Reflection over a class is stable, so re-walking it on
     *  every bind is wasted work; the lists are unmodifiable because they are shared. */
    private static final ConcurrentHashMap<Class<?>, List<RuleSite>> INDEX = new ConcurrentHashMap<>();

    private static final Comparator<Class<?>> BY_QUALIFIED_NAME = new Comparator<Class<?>>() {
        @Override
        public int compare(final Class<?> left, final Class<?> right) {
            return left.getName().compareTo(right.getName());
        }
    };

    private static final Comparator<Method> BY_METHOD_NAME = new Comparator<Method>() {
        @Override
        public int compare(final Method left, final Method right) {
            return left.getName().compareTo(right.getName());
        }
    };

    private RuleModel() {
    }

    /** The sites whose annotation is {@link ConfigRule}-marked — the built-in vocabulary. */
    public static List<RuleSite> of(final Class<?> type) {
        return of(type, RuleSelector.CONFIG_RULE_MARKED);
    }

    /** The sites whose annotation {@code selector} claims — the way in for a vocabulary EveryConfig does
     *  not own. */
    public static List<RuleSite> of(final Class<?> type, final RuleSelector selector) {
        final List<RuleSite> all = index(type);
        if (all.isEmpty()) {
            return Collections.emptyList();
        }
        final List<RuleSite> claimed = new ArrayList<>();
        for (final RuleSite site : all) {
            if (selector.claims(site.rule())) {
                claimed.add(site);
            }
        }
        return claimed.isEmpty() ? Collections.<RuleSite>emptyList()
                : Collections.unmodifiableList(claimed);
    }

    /** Whether the type carries ANY candidate annotation at all — one lookup in the resolved index, and the
     *  only cost a config with no rules ever pays. */
    public static boolean hasRules(final Class<?> type) {
        return !index(type).isEmpty();
    }

    private static List<RuleSite> index(final Class<?> type) {
        return INDEX.computeIfAbsent(type, RuleModel::resolve);
    }

    private static List<RuleSite> resolve(final Class<?> type) {
        final List<RuleSite> sites = new ArrayList<>();
        collect(type, type, "", new ArrayList<Field>(), new HashSet<Class<?>>(), sites);
        return sites.isEmpty() ? Collections.<RuleSite>emptyList() : Collections.unmodifiableList(sites);
    }

    /**
     * Collect the sites of {@code clazz} at {@code basePath}, then descend. {@code chain} is the field path
     * from the resolved type down to an instance of {@code clazz}; {@code onPath} holds the classes of the
     * current branch, added on entry and removed on exit so a diamond is still resolved twice.
     */
    private static void collect(final Class<?> entryType, final Class<?> clazz, final String basePath,
                                final List<Field> chain, final Set<Class<?>> onPath,
                                final List<RuleSite> sites) {
        final List<Field> nested = new ArrayList<>();
        final List<String> nestedPaths = new ArrayList<>();
        for (final Field field : BindingNames.allFields(clazz)) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue; // never persisted, so it has no path to report at
            }
            final String fieldPath = BindingNames.sectionAwarePath(basePath, field);
            for (final Annotation rule : candidatesOf(field)) {
                sites.add(new RuleSite(RuleSite.Kind.FIELD, fieldPath, rule, field.getType(), field, null,
                        field.getDeclaringClass(), commentOf(field), entryType, snapshot(chain)));
            }
            final Section section = field.getAnnotation(Section.class);
            if (section != null && !section.value().isEmpty()) {
                continue; // a sectioned field's value relocates whole; its type is not descended
            }
            if (TypeFamily.isUserPojoType(field.getType())) {
                nested.add(field);
                nestedPaths.add(fieldPath);
            }
        }
        collectTypeSites(entryType, clazz, basePath, chain, sites);
        collectMethodSites(entryType, clazz, basePath, chain, sites);
        for (int i = 0; i < nested.size(); i++) {
            final Field field = nested.get(i);
            if (onPath.add(field.getType())) {
                chain.add(field);
                collect(entryType, field.getType(), nestedPaths.get(i), chain, onPath, sites);
                chain.remove(chain.size() - 1);
                onPath.remove(field.getType());
            }
        }
    }

    /** Type-level rules, up the hierarchy: the annotation states an invariant of the entity, so the site
     *  sits at the entity's own path with the visited class as its value type. */
    private static void collectTypeSites(final Class<?> entryType, final Class<?> clazz, final String basePath,
                                         final List<Field> chain, final List<RuleSite> sites) {
        Class<?> declaring = clazz;
        while (declaring != null && declaring != Object.class) {
            for (final Annotation rule : candidatesOf(declaring)) {
                sites.add(new RuleSite(RuleSite.Kind.TYPE, basePath, rule, clazz, null, null, declaring,
                        commentOf(declaring), entryType, snapshot(chain)));
            }
            declaring = declaring.getSuperclass();
        }
    }

    /**
     * Rules on a no-argument, non-static method: the computed invariant, whose value is what the method
     * RETURNS. A method has no key of its own, so its site reports at the entity's path. Fields remain the
     * persistence model — pair such a method with {@code @JsonIgnore}, or the mapper writes it into the file
     * as a key. An overridden method is taken once, from the most derived class.
     */
    private static void collectMethodSites(final Class<?> entryType, final Class<?> clazz,
                                           final String basePath, final List<Field> chain,
                                           final List<RuleSite> sites) {
        final List<Method> candidates = new ArrayList<>();
        final Set<String> seenNames = new HashSet<>();
        Class<?> declaring = clazz;
        while (declaring != null && declaring != Object.class) {
            for (final Method method : declaring.getDeclaredMethods()) {
                if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())
                        || method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                if (seenNames.add(method.getName())) {
                    candidates.add(method);
                }
            }
            declaring = declaring.getSuperclass();
        }
        Collections.sort(candidates, BY_METHOD_NAME); // getDeclaredMethods has no guaranteed order
        for (final Method method : candidates) {
            for (final Annotation rule : candidatesOf(method)) {
                sites.add(new RuleSite(RuleSite.Kind.METHOD, basePath, rule, method.getReturnType(), null,
                        method, method.getDeclaringClass(), commentOf(method), entryType, snapshot(chain)));
            }
        }
    }

    /**
     * The candidate annotations declared on {@code element}, ordered by annotation type name and then by
     * declaration order. A repeatable annotation is read through its element type, so the container is
     * unwrapped and each occurrence becomes its own site in the order it was written.
     */
    private static List<Annotation> candidatesOf(final AnnotatedElement element) {
        final Annotation[] declared = element.getDeclaredAnnotations();
        if (declared.length == 0) {
            return Collections.emptyList();
        }
        final List<Class<? extends Annotation>> types = new ArrayList<>();
        for (final Annotation annotation : declared) {
            final Class<? extends Annotation> repeated = repeatableElement(annotation.annotationType());
            final Class<? extends Annotation> candidate = repeated != null ? repeated
                    : annotation.annotationType();
            if (isCandidate(candidate) && !types.contains(candidate)) {
                types.add(candidate);
            }
        }
        if (types.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.sort(types, BY_QUALIFIED_NAME); // getDeclaredAnnotations has no guaranteed order
        final List<Annotation> out = new ArrayList<>();
        for (final Class<? extends Annotation> type : types) {
            out.addAll(Arrays.asList(element.getDeclaredAnnotationsByType(type)));
        }
        return out;
    }

    /** The repeated annotation type {@code containerType} holds, or null when it is not a container. */
    private static Class<? extends Annotation> repeatableElement(final Class<? extends Annotation> containerType) {
        final Method value;
        try {
            value = containerType.getDeclaredMethod("value");
        } catch (final NoSuchMethodException notAContainer) {
            return null;
        }
        final Class<?> returned = value.getReturnType();
        if (!returned.isArray() || !returned.getComponentType().isAnnotation()) {
            return null;
        }
        final Class<? extends Annotation> element = returned.getComponentType().asSubclass(Annotation.class);
        final Repeatable repeatable = element.getAnnotation(Repeatable.class);
        return repeatable != null && repeatable.value() == containerType ? element : null;
    }

    private static boolean isCandidate(final Class<? extends Annotation> type) {
        if (type == RuleReview.class || type == ConfigRule.class) {
            return false; // review is imperative; what introspection reads is the DECLARED facts
        }
        final String name = type.getName();
        for (final String prefix : STRUCTURAL_PREFIXES) {
            if (name.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private static List<String> commentOf(final AnnotatedElement element) {
        final Comment comment = element.getAnnotation(Comment.class);
        return comment == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(Arrays.asList(comment.value()));
    }

    private static List<Field> snapshot(final List<Field> chain) {
        return chain.isEmpty() ? Collections.<Field>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(chain));
    }
}
