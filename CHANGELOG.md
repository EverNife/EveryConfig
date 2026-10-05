# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Published artifacts, group `br.com.finalcraft.everyconfig`:

| Artifact | What it is |
|---|---|
| `everyconfig-core` | the whole library: the tree, the codecs, the comment overlay, typed binding, and the rule seam with no rule in it |
| `everyconfig-rules` | optional: the rule vocabulary — jakarta's constraints read natively, plus `@Explicit`/`@OneOf`/`@Unique` |

## [Unreleased]

A collection of `@KeyIndex` entities is a keyed section as a bean field too, not only when written by path.

### Changed

- **A bean's `List`/`Set`/array field of a `@KeyIndex` type is stored key-major.** The keyed layout existed
  only on the path API (`setValue("kits", list)` / `getList`); the same type as a field of a bound bean went to
  the file as a plain list, so a block of named entries could not live in the settings bean with a `@Comment`
  on it. The mapper now writes such a field as a section per element, keyed by the id and with the id left out
  of the body, at any depth the mapper reaches (a nested bean, a bean inside a `Map` value), and reads it back
  from there. It is by type, as on the path - no opt-in annotation. An empty collection is still `[]`.
- **A field already stored as a plain list still reads, and the next binding write turns it into the keyed
  section.** Nothing changes for such a file until the bean is written back (`mergeValue`, `setValue`,
  `getOrMergeValue`) and saved; from then on the layout is the keyed one.
- **The collection owns the section's membership.** On a merge each element merges into its own section (a key
  the entity does not declare survives inside it), and the section of an element that left the collection is
  removed with its comments. A section the read could not bind is therefore dropped by the next binding write.
- **A section that does not bind is one `LoadIssue` at `field.<key>`**, or at `field.<key>.<leaf>` when only one
  value inside it is bad, and its siblings still read; `Coercion.STRICT` throws. A stray id inside a body loses
  to the section key without an issue (the path API's `getListResult` still reports it).
- **Element hooks fire at `owner.field.<id>`** for such a field, and at `owner.field[i]` while the file still
  holds the plain list.

## [1.3.1]

A key that is markup in its format - a PlaceholderAPI `%placeholder%` in YAML - survives a save and a reopen.

### Fixed

- **A YAML key that is markup is written quoted.** The commented YAML writer emitted every key bare, so a key
  starting with an indicator (`%`, `@`, `&`, `*`, `!`, `|`, `>`, `'`, `"`, `#`, `{`, `[`, `` ` ``, `,`) or
  holding `: `/` #` produced a file that no longer parsed: the next open backed it up to `.bak` and started
  empty, and the save after that wrote the defaults over the user's edits. A PlaceholderAPI placeholder
  (`%player_name%`) used as a key was enough. Such a key is now single-quoted (double-quoted with escapes when
  it holds a control character), and the comment parser reads the quoted form back, so its comment survives.
  A key that was valid bare is still written bare, byte for byte.
- **A JSONC key with an escape keeps its comment.** The comment pass unescaped only `\"` and `\\`, so a key
  holding a tab or another escaped character read back under a different path and its comment was dropped.

## [1.3.0]

A typed read of an absent path is `null` for every type: absence is no longer a value of all defaults.

### Changed

- **`getValue(path, type)` reads an absent path as `null` for a bean too.** It returned a fresh instance with
  every field at its default, so a caller could not tell "never written" from "written with defaults" without a
  separate `contains` - a never-set spawn came back as a position in no world at `0,0,0`. Absence now reads
  `null` for every type; `getValueInto(path, new T())` and `getOrSetValueIfAbsent` are the ways to ask for the
  defaults. `readInto` is unchanged. A present-but-empty object (`settings: {}`) still binds to the defaults.

## [1.2.0]

A type whose stored form is a single token — a reference, an id, a platform type a Jackson module owns — now
crosses the binder as a **leaf**. Teaching the mapper is the whole gesture: the module that already says how
the value is written now also says where the lifecycle walk stops, so nothing inside such a value is visited,
fired, or held against the config that carries it. No new annotation, no registry, no API to opt into.

### Fixed

- **The lifecycle walk no longer steps inside a custom-serialized value.** It descended into any type that was
  not a JDK type, `Map`, `Collection` or array — including one a Jackson module writes as a single token. A
  reference type holding a live registry therefore had its internals walked, and a hook found down there fired
  with a `ConfigSection` pointing at `owner.refs[0].registry`, a path the file does not contain. The walk now
  stops at any value the config's mapper does not write as an object of fields.

- **The hook gate reads generic containers.** It resolved a collection/map type argument only when that
  argument was a plain class, so `List<Box<K,V>>` resolved to nothing and forced the walk on every read and
  write. A container is now judged by what it ultimately holds, at any nesting depth — which also closes a
  false negative in the other direction: `Hooked[][]` counted as "provably hook-free" (an array class declares
  no fields), so hooks nested inside a two-dimensional array were silently skipped.

- **A typed read of an absent path no longer hands an empty object to a value type.** `getValue(path, type)`,
  `getValueInto` and `EntityBinder.read`/`readInto` bound an absent path as `{}`, which is right for a bean (every
  field keeps its default) and wrong for anything else: a type with its own deserializer — a Jackson module's
  codec, `@JsonDeserialize(using = ...)` — was called with fields that are not there and failed the bind (a
  `Location` codec died in `node.get("x")`). An absent path now reads `null` (and `readInto` returns the target
  untouched) for every type the mapper does not read as a bean, without calling its deserializer. Scalars already
  read `null`; `Map`, `Object` and `JsonNode`, which read an empty `{}`, now read `null` as well.

### Added

- **`LifecycleGraphWalker.mayContainHooks(Class, ObjectMapper)`** — the same gate, judged with the mapper the
  value will actually be written by. A `final` type that mapper does not write as an object of fields is a
  proven leaf, so a config type holding nothing but such values skips the walk entirely. A non-final one keeps
  being walked (a subtype could be written as a bean after all), because no hook may be skipped on a guess.
  The mapper-free `mayContainHooks(Class)` keeps its contract unchanged.

- **`SerializedShape.emitsAsBean(ObjectMapper, Class)`** — the classifier underneath: does this mapper write
  this type as an object of fields, or as something else? Cached per (mapper, class), like the schema caches.
  Its read-side twin, `readsAsBean(ObjectMapper, JavaType)`, decides which absent paths still bind to defaults.

- **A warning when hooks are declared on such a type.** `@PostLoad` (or `ConfigLifecycle`) on a value stored as
  one token cannot fire — there is no sub-path for its `ConfigSection` — so EveryConfig says so once per type
  instead of skipping in silence, the treatment the compact list element already had.

### Changed

- **`LifecycleGraphWalker.anyMayHaveHooks` takes the mapper.** `anyMayHaveHooks(values, mapper)` replaces the
  one-argument form, so the dynamic collection seams gate on the same evidence the walk itself uses; pass
  `null` for the mapper-free answer.

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

- **`KeyIndexer.fromIndexed` takes the section's path.** It keys its issues at the entry's own path now, which
  it cannot build without knowing where the section sits: `fromIndexed(node, "accounts", type, mapper, issues)`.

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

- **Rules can be judged outside a bind.** `RuleEvaluator` runs one `RuleSite` against a value the caller
  already holds — for a scanner with its own field walk and key grammar, a screen validating what a user
  just typed, a value assembled in memory. Everything a bind reads off the tree becomes a parameter (where
  the value lives, what it is, where it came from, which instance declares it), and the violations come back
  reported at the path the caller evaluated at rather than at the site's own.

  ```java
  RuleEvaluation outcome = RuleEvaluator.of(StandardRules.engine())
          .withPolicy(RulePolicy.defaults().withSeverity(RulePolicy.Severity.LOG).withCorrections(true))
          .evaluate(site, config.getConfigSection(key), value, ValueSource.FILE, owner);

  for (RuleFinding finding : outcome.findings()) {   // violation() + severity() + message()
      ...
  }
  ```

  It never throws for a violation — the severity comes back on the finding and the caller decides — while a
  handler that FAILS while judging still throws. `@RuleReview` does not run (it reads every violation of an
  instance at once), and a correction reaches the field but not the tree. `owner` may be null for a value
  that belongs to no instance yet; the other arguments are required, and a missing one says what to pass.

- **`RuleContext.engine()`.** The engine the config attached, handed to the handler it is running. A rule that
  has to judge something OTHER than its own site evaluates it through this one, so an annotation whose engine
  nobody attached stays inert instead of deciding a bind behind the config's back.

- **`@Explicit` refuses a declaration with no legal first run.** `@Explicit @NotBlank String token = ""` asks
  the operator to overwrite a default that the rule beside it rejects — a fresh file is seeded with exactly
  that value, so there is nothing valid to write and no first run that can pass. It fails on every
  application, not only on the run where the key happens to be missing, and the message names both
  annotations. Only a neighbour the ATTACHED engine claims counts.

### Fixed

- **A lenient list read names the entry it dropped.** `getListResult` used to report only the `@KeyIndex`
  reconciliations, so a skipped element could be counted but not found. Every dropped entry is now one issue
  keyed at its own path — `weights[1]` for a list, `accounts.alice` for a `@KeyIndex` section, and, for the
  legacy numeric-keyed layout, the key the file uses rather than the position it landed at.

## [1.0.1]

The last release of the single-module line, published as `br.com.finalcraft:EveryConfig`. It carried the
tree/codec/comment core, typed entity binding, `@KeyIndex` collections, the compact list-element form, key
order pinning and the comment spacing policy.
