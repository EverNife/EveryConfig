# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with one project-specific rule: **the major
version tracks Jackson's major** (`EveryConfig 1.x` ⟷ Jackson `2.x`), because Jackson is deliberately part of
the public surface.

Published artifacts, group `br.com.finalcraft.everyconfig`:

| Artifact | What it is |
|---|---|
| `everyconfig-core` | the whole library: the tree, the codecs, the comment overlay, typed binding, and the rule seam with no rule in it |
| `everyconfig-rules` | optional: the rule vocabulary — jakarta's constraints read natively, plus `@Explicit`/`@OneOf`/`@Unique` |

## [Unreleased]

## [1.1.0]

A config POJO can now declare **meaning** on top of shape: `@Min(0) @Max(100)` on a percentage, `@Explicit`
on a token nobody should be allowed to leave at its default, `@OneOf` on a legacy `String`. Three axes stay
orthogonal by design — the FACT is declared on the field, the CONSUMER is attached at the point of use, and
the POLICY is chosen per config — so the same annotations feed a validating load, a screen generator that
never opens a file, and the comments written into the file itself.

The project became multi-module to keep that promise honest: the core carries the seam and not one rule, and
the vocabulary ships separately. That moved the coordinate, which is the one thing in this release that will
break a build.

### Changed

- **`br.com.finalcraft:EveryConfig` no longer exists.** The project is now published as
  `br.com.finalcraft.everyconfig:everyconfig-core` (and the optional `everyconfig-rules`):

  ```groovy
  // before
  implementation 'br.com.finalcraft:EveryConfig:1.0.1'
  // after
  implementation 'br.com.finalcraft.everyconfig:everyconfig-core:1.1.0'
  implementation 'br.com.finalcraft.everyconfig:everyconfig-rules:1.1.0'   // optional
  ```

  Because the coordinate CHANGED, dependency resolution will NOT deduplicate the old artifact: a build that
  still pulls `br.com.finalcraft:EveryConfig` transitively ends up with duplicated classes on the classpath.
  Remove/exclude the old coordinate everywhere.

- **`LoadIssue` gained a `Kind`.** Every issue is now `COERCION` (a value the file could not be read as its
  declared type) or `RULE` (a value a rule rejected), and a `RULE` issue carries the `RuleViolation` behind
  it — a stable message key plus its ordered arguments, for a consumer that localizes. Existing `@PostLoad`
  code keeps compiling: `key()`, `rawValue()`, `targetType()` and `message()` are unchanged.

- **`LoadIssueAware` is handed the finished list.** It used to receive only the coercion issues, because it
  was called before rules existed; it is now called after the rule pass, so it sees both kinds in one list —
  the same list `@PostLoad` already received.

- **`ConfigContext.issues()` is also populated at `@PostSave`.** A rule that finds something while the entity
  is being written has somewhere to report it, so the one channel now runs in both directions. It stays empty
  at `@PreLoad`/`@PreSave`.

### Added

- **Semantic rules around the bind.** `Config.withRuleEngine(...)` attaches what checks, `withRulePolicy(...)`
  chooses what a violation costs. Rules run INSIDE the bind — `VALIDATE` between the bind and `@PostLoad`,
  `NORMALIZE` before the projection on the way out — and what they find travels the channel the coercion
  issues already travelled, so a validator written before this release sees them without one line of change.

- **`RulePolicy` — one decision, three cases.** A FILE-sourced violation follows the policy, and with no
  policy declared it follows the bind's `Coercion` (STRICT throws, LENIENT reports), so a config that already
  said how strict it is does not say it twice. A DEFAULT-sourced violation throws out of the box: an entity
  breaking its own rule is a code defect no config file can fix. Severities are `REPORT`, `LOG` (warned once
  per site) and `THROW`, and every failure message teaches the way out.

- **`AnnotationRuleEngine`, attached by default.** A self-contained rule — an annotation marked
  `@ConfigRule(MyHandler.class)` — fires with no setup line at all. A type that declares none pays one cached
  lookup per bind, and `withRuleEngine(RuleEngine.NONE)` switches the whole subsystem off by identity.

- **Corrections.** With `RulePolicy.withCorrections(true)`, a handler may rewrite the value it rejected.
  Correcting file data rewrites the entity AND the canonical tree, then flags `Config.hasRuleFixes()` — the
  file itself changes only on an explicit `save()`, so persisting what a load repaired stays the caller's
  decision.

- **In-place review: `@RuleReview` / `RuleReviewer`.** The entity takes part in the OUTCOME. A review runs
  after the engines and before the policy, sees the violations on its own sites and decides them —
  `accept`, `override`, `correct` — and may raise violations of its own logic with `report`/`fail`,
  including logic that reaches outside the config (a `databaseId` that points at a database another file
  declares disabled). Precedence is closed: **review > engine > policy > `Coercion`**.

- **`RuleModel` — introspection with no `Config` and no mapper.** `RuleModel.of(type[, selector])` returns
  the declared rules of a type, each with the FILE path its value lands at (`@Key`/`@JsonProperty`/case
  transform/`@Section` applied), its `@Comment` lines and its default value. It is the surface a settings
  screen is generated from, resolved once per class.

- **`everyconfig-rules`: jakarta's constraints, read natively.** 22 constraints — presence, size, range,
  sign, digits, pattern, e-mail, boolean assertions, temporal — with no provider, no `ServiceLoader` and no
  Bean Validation implementation anywhere. Membership is by class reference, so the claim is exact and
  survives relocation. Declared divergences: `null` passes everything except presence, and the range
  constraints accept EVERY numeric type — `double` and `float` included, compared in `BigDecimal` — because
  a percentage bounded by `@Min(0) @Max(100)` is the ordinary case in a config file. A `message()` written by
  hand replaces the English text; Bean Validation's own `{key}` template does not.

- **`@Explicit`, `@OneOf`, `@Unique`.** What jakarta cannot say: the value must come from the FILE rather
  than the entity's default (provenance, not content); the value must belong to a set that may only exist at
  runtime (`OneOfSource`, plus `ignoreCase`); the list must hold no repeat. `StandardRules.engine()` bundles
  them with jakarta's and keeps a consumer's own `@ConfigRule` firing alongside.

- **Rule text in the file.** `Config.withRuleComments(true)` folds what each rule documents into the comment
  at its path — `"At most 65535."` under the `@Comment` already there. Off by default, composed into a single
  write (never appended, so repeated saves cannot grow the block), and a field documented only by its rules is
  written set-if-absent so library text never overwrites a hand-written comment.

## [1.0.1]

The last release of the single-module line, published as `br.com.finalcraft:EveryConfig`. It carried the
tree/codec/comment core, typed entity binding, `@KeyIndex` collections, the compact list-element form, key
order pinning and the comment spacing policy.
