package br.com.finalcraft.everyconfig.ruleset;

import br.com.finalcraft.everyconfig.rule.ConfigRule;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The value has to come from the FILE, not from the entity's own default: provenance, not content. A
 * {@code @NotBlank} on a field whose default is {@code "changeme"} passes; this does not. It is what a token,
 * a host or a credential needs — the things an operator MUST fill in consciously.
 *
 * <p>Its violation is always default-sourced by construction, so the handler stamps it with the severity
 * file data would get: a lenient bind reports it (the caller, or a {@code @PostLoad}, decides whether to
 * refuse), a strict one throws. Escalating default-sourced violations stays reserved for what it was meant
 * to catch — a CONTENT rule the entity's own default breaks.
 *
 * <p>Honest limit: seeding writes the default into the file, so after the first save the key exists and this
 * rule is satisfied. It catches the FIRST run — exactly when the warning matters — not forever.
 *
 * <p>It may not sit beside a content rule the field's own default breaks — {@code @Explicit @NotBlank String
 * token = ""}. The default is what a fresh file is seeded with and what this annotation asks the operator to
 * overwrite, so a default the neighbouring rule refuses leaves nothing to write and no first run that can
 * pass. That combination fails on the first application, every run, with a message naming both annotations.
 */
@Documented
@ConfigRule(ExplicitHandler.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Explicit {
}
