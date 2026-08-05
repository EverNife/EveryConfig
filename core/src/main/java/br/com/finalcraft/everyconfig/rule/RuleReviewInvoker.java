package br.com.finalcraft.everyconfig.rule;

import br.com.finalcraft.everyconfig.binding.BindException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs an entity's rule review: its {@link RuleReview @RuleReview} methods first — hierarchy subclass-first,
 * de-duped by name so an overridden method runs once — then the {@link RuleReviewer} callback, both with the
 * same context and the same authority. The arrangement mirrors the lifecycle hooks, and so does the failure
 * mode: whatever a review throws surfaces as a {@link BindException} naming the review and the path.
 */
final class RuleReviewInvoker {

    /** The review methods of each class, resolved once — the method set depends only on the class, so
     *  re-walking the hierarchy on every bind is wasted reflection. */
    private static final ConcurrentHashMap<Class<?>, List<Method>> METHODS = new ConcurrentHashMap<>();

    private static final Comparator<Method> BY_NAME = new Comparator<Method>() {
        @Override
        public int compare(final Method left, final Method right) {
            return left.getName().compareTo(right.getName());
        }
    };

    private RuleReviewInvoker() {
    }

    /** Whether {@code type} reviews at all: it implements {@link RuleReviewer} or declares a
     *  {@code @RuleReview} method anywhere in its hierarchy. The gate a bind consults before building a
     *  review context, backed by the same per-class cache the invocation uses. */
    static boolean reviews(final Class<?> type) {
        return RuleReviewer.class.isAssignableFrom(type) || !methodsOf(type).isEmpty();
    }

    static void invoke(final Object entity, final RuleReviewContext review, final String path) {
        for (final Method method : methodsOf(entity.getClass())) {
            invokeOne(entity, method, review, path);
        }
        if (entity instanceof RuleReviewer) {
            try {
                ((RuleReviewer) entity).reviewRules(review);
            } catch (final BindException alreadyExplained) {
                throw alreadyExplained;
            } catch (final Exception failed) {
                throw new BindException("RuleReviewer.reviewRules of " + entity.getClass().getSimpleName()
                        + " at '" + path + "' failed: " + failed.getMessage(), failed);
            }
        }
    }

    private static void invokeOne(final Object entity, final Method method, final RuleReviewContext review,
                                  final String path) {
        final String tag = "@RuleReview '" + method.getName() + "' at '" + path + "'";
        final Class<?>[] params = method.getParameterTypes();
        if (params.length != 1 || !params[0].isAssignableFrom(RuleReviewContext.class)) {
            throw new BindException(tag + " must take a single RuleReviewContext parameter - it is the only "
                    + "way to see the violations and decide their outcome");
        }
        try {
            method.setAccessible(true);
            method.invoke(entity, review);
        } catch (final InvocationTargetException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof BindException) {
                throw (BindException) cause;
            }
            throw new BindException(tag + " failed: " + cause.getMessage(), cause);
        } catch (final BindException alreadyExplained) {
            throw alreadyExplained;
        } catch (final Exception e) {
            throw new BindException(tag + " could not be invoked", e);
        }
    }

    private static List<Method> methodsOf(final Class<?> type) {
        return METHODS.computeIfAbsent(type, RuleReviewInvoker::resolve);
    }

    /** Subclass first, and by name within one class: {@code getDeclaredMethods} promises no order, and two
     *  reviews of the same entity deciding in a different order on two runs would be irreproducible. */
    private static List<Method> resolve(final Class<?> type) {
        final List<Method> found = new ArrayList<>();
        final Set<String> seenNames = new HashSet<>();
        Class<?> declaring = type;
        while (declaring != null && declaring != Object.class) {
            final List<Method> level = new ArrayList<>();
            for (final Method method : declaring.getDeclaredMethods()) {
                if (method.isAnnotationPresent(RuleReview.class) && seenNames.add(method.getName())) {
                    level.add(method);
                }
            }
            Collections.sort(level, BY_NAME);
            found.addAll(level);
            declaring = declaring.getSuperclass();
        }
        return found.isEmpty() ? Collections.<Method>emptyList() : Collections.unmodifiableList(found);
    }
}
