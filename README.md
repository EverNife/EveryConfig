<div align="center">

# EveryConfig

### One config API. Every format. Comments included.

A Jackson-backed configuration library for the JVM. Write your config code **once** against a small, typed,
path-based API — then read and write it as **YAML, JSON, TOML or JSONC** without changing a line. Comments,
key order and unknown keys survive a round-trip; typed entity binding is a *derived view* that merges into the
data, never clobbers it.

![Runtime](https://img.shields.io/badge/runtime-Java%208%2B-blue)
![Build](https://img.shields.io/badge/build-JDK%2025-orange)
![Formats](https://img.shields.io/badge/formats-YAML%20%7C%20JSON%20%7C%20TOML%20%7C%20JSONC-green)
![Version](https://img.shields.io/badge/version-1.0.1-informational)

</div>

---

## Table of contents

- [Why](#why)
- [Supported formats](#supported-formats)
- [Install](#install)
- [Quick start](#quick-start)
- [Core concepts](#core-concepts)
- [The dynamic API](#the-dynamic-api)
- [Default values & comments](#default-values--comments)
- [Typed entity binding](#typed-entity-binding)
- [`@KeyIndex` collections](#keyindex-collections)
- [Lifecycle, reload & watching](#lifecycle-reload--watching)
- [Building & running the tests](#building--running-the-tests)
- [Project layout](#project-layout)
- [Compatibility notes](#compatibility-notes)

> 📖 **Full documentation lives on the [Wiki](https://github.com/EverNife/EveryConfig/wiki).** This README is
> the tour; the wiki has the per-topic deep dives, the API cheat sheet, and the gotchas.

---

## Why

Most config libraries marry you to one format, and lose your comments the first time the app rewrites the
file. EveryConfig treats the format as a **deployment choice**, not an architectural one, and the canonical
state is a Jackson `ObjectNode` tree that every format reads into and writes out of.

- **🌳 One tree, many formats.** The canonical state is a Jackson `ObjectNode`. A pluggable `Codec` turns text
  ⇄ tree; swap `new YamlCodec()` for `new TomlCodec()` and the rest of your code is untouched.
- **💬 Comments survive.** A format-agnostic comment overlay round-trips block, side, header and footer
  comments — through YAML, TOML and JSONC. JSON declares no comment fidelity and never pretends to.
- **🧩 Typed binding that *merges*.** Bind the tree to a POJO when you want types; on save the binding **merges
  into the tree** — unknown keys a user added by hand always survive, and the tree wins on conflict.
- **🌱 Self-healing defaults.** `getOrSetValueIfAbsent(path, def, comment)` seeds a value *and* its documentation
  only when absent, so it is safe to call on every startup.
- **🛟 Corruption-proof startup.** A malformed file is backed up to `.bak` and the config starts empty — a
  broken config never blocks boot.
- **☕ Java 8 runtime.** Bytecode targets Java 8 while the source is written in modern Java; the dependency set
  (Jackson) is Java-8-clean, so EveryConfig runs on a Java 8 JVM.

---

## Supported formats

| Format | Codec | Comment fidelity | Extensions | Notes |
|---|---|---|---|---|
| **YAML** | `YamlCodec` | **LOSSLESS** | `yml`, `yaml` | Block + side + header/footer comments round-trip. |
| **JSON** | `JsonCodec` | **NONE** | `json` | Strict RFC JSON; pretty-printed; comments are not emitted. |
| **TOML** | `TomlCodec` | **LOSSLESS** | `toml` | `[table]` sections + `#` comments. No `null` (omitted); see the gotchas. |
| **JSONC** | `JsoncCodec` | **LOSSY** | `jsonc` | JSON with `//` comments; best-effort comment positions. |

> Comment **fidelity** is a codec capability, not a global setting. Code that sets a comment is always safe to
> run on any codec — a `NONE` codec simply does not emit it, and never corrupts the data doing so.

---

## Install

EveryConfig is a **thin `java-library` jar**: Jackson is a normal transitive dependency, not relocated.

**Gradle**

```groovy
repositories {
    maven { url 'https://maven.petrus.dev/public' }
    mavenCentral()
}

dependencies {
    implementation 'br.com.finalcraft:EveryConfig:1.0.1'
}
```

**Maven**

```xml
<repository>
  <id>petrus-public</id>
  <url>https://maven.petrus.dev/public</url>
</repository>

<dependency>
  <groupId>br.com.finalcraft</groupId>
  <artifactId>EveryConfig</artifactId>
  <version>1.0.1</version>
</dependency>
```

> **Bukkit/Spigot consumers:** a plugin that bundles EveryConfig should run its own `shadowJar` and **relocate
> `com.fasterxml.jackson` and `org.yaml.snakeyaml`** in *its* shade step. Relocation policy belongs at the leaf
> artifact — the only place that knows the server's classpath — so the library itself ships thin.

---

## Quick start

```java
import br.com.finalcraft.everyconfig.config.Config;

Config cfg = Config.open("server.yml"); // codec chosen from the .yml extension (fail-fast if unknown)

cfg.setValue("server.host", "localhost");
int port = cfg.getOrSetValueIfAbsent("server.port", 25565, "the listen port"); // seeds value + comment if absent

cfg.save(); // atomic write; comments + key order preserved
```

Switching format is a one-line change — everything below `Config.open(...)` stays identical:

```java
Config cfg = Config.open("server.toml"); // or pass a codec explicitly: Config.open(path, new TomlCodec())
```

> `Config.open` accepts a `String`, `File` or `Path` and derives the codec from the file extension via the
> `CodecRegistry` — it **never guesses**, throwing a `CodecException` on a missing/unknown extension. Pass a
> codec explicitly (`Config.open(path, codec)`) to override.

Need types? Bind the tree to a POJO:

```java
DbConfig db = cfg.getValue("database", DbConfig.class); // read the subtree at a path bound to the type
db.maxPool = 25;
cfg.setValue("database", db);                           // a POJO setValue MERGES (unknown keys + comments survive)
cfg.save();
```

---

## Core concepts

| Type | Role |
|---|---|
| **`Config`** | The handle: a thin wrapper over one canonical `ObjectNode`, plus the dynamic path API, the comment API and the file lifecycle. |
| **`Codec`** | A pluggable format strategy (`JsonCodec`, `YamlCodec`, `TomlCodec`, `JsoncCodec`). Turns text ⇄ tree and declares its comment fidelity. One mapper is shared per codec across every live config. |
| **`CommentTree`** | The format-agnostic comment overlay (block/side/header/footer + blank-line layout), captured on load and reconciled on save. |
| **`EntityBinder`** | The typed view: binds the tree to a POJO and merges a POJO back into the tree. |

### The 5 design decisions (the DNA)

1. **The tree is canonical.** The dynamic API operates on the `ObjectNode`; typed binding is a derived view;
   on conflict the **tree wins** (unknown keys survive) and a binding save **merges**, never replaces.
2. **Comments are seed/override.** `@Comment` (and `getOrSetValueIfAbsent(...,comment)`) write comments in two
   explicit modes — rewrite-every-save or write-once.
3. **Comment fidelity is a codec capability** — each codec declares `LOSSLESS` / `LOSSY` / `NONE`.
4. **Save is reconciliation** against the captured (data, comments, key order): file order is preserved, new
   keys are appended.
5. **The emitter renders structure itself** and delegates only leaf-value serialization to the mapper — a
   custom mapper can restyle a value but cannot break the layout. The `Codec` (text⇄tree⇄entity) is separate
   from the I/O layer.

---

## The dynamic API

Dot-separated paths over the canonical tree; typed getters route through one coercion seam.

```java
cfg.setValue("a.b.c", 42);          // auto-vivifies intermediate objects
cfg.getInt("a.b.c");                // 42
cfg.getInt("missing", 7);           // 7 (default)
cfg.setValue("a.b.c", null);        // a Java null deletes the entry
cfg.removeValue("a.b");             // returns boolean; drops the subtree's comments too

cfg.contains("a");                  // true
cfg.getKeys("a");                   // direct children
cfg.getKeys("", true);             // deep, dotted descendant paths
cfg.getConfigSection("a.b");        // a scoped view that delegates back with the sub-path prefixed

cfg.migrateKey("old.name", "new.name"); // move a key (and its comments); returns a MigrationResult
```

> **Keys that contain a dot:** `.` separates path segments, so a key that legitimately contains one is
> **escaped** — `cfg.getInt("rates.usd\\.brl")` addresses the single key `"usd.brl"` under `rates` (and `\\`
> is a literal backslash). The escape is a no-op for an ordinary key, so normal paths are unaffected.

> **`migrateKey` is startup-safe and observable.** It returns a `MigrationResult` so a re-run that finds the
> data already moved (`ALREADY_MIGRATED`) is told apart from a typo'd source that never existed
> (`SOURCE_ABSENT`) — both no-ops, but only one is worth logging.

> **Legacy long-as-string tolerance:** the numeric getters parse a number stored as a quoted string, so a long
> once written as `"1700000000000"` still reads back via `getLong`. `getUUID` is equally tolerant — a
> malformed value yields `null`/the default rather than throwing.

> **Trichotomy:** an absent path, an explicit `null`, and a real value are distinct — `contains` tells absent
> from present, and a typed getter flattens an explicit `null` to its default.

**→ Deep dive: [The Dynamic API](https://github.com/EverNife/EveryConfig/wiki/Dynamic-API)**

---

## Default values & comments

`getOrSetValueIfAbsent` seeds on first run and lets the file win afterwards. Comments come in **two write modes**:

```java
// seeds the value AND the comment only if the path is absent — safe on every startup
int port = cfg.getOrSetValueIfAbsent("server.port", 25565, "the listen port");

cfg.setComment("server.port", "ALWAYS overwritten on save");          // authoritative
cfg.setDefaultComment("server.port", "written only if absent");        // user-edited comment wins
```

On a class, `@Comment` defaults to `OVERRIDE` (documentation in code stays current), or
`@Comment(mode = SET_IF_ABSENT)` to let a user's edit win.

**→ Deep dive: [Default Values & Comments](https://github.com/EverNife/EveryConfig/wiki/Default-Values-and-Comments)**

---

## Typed entity binding

Binding is a derived view over the tree. A binding **save merges** into the tree (the tree wins, unknown keys
survive):

```java
@Comment(value = "Database settings", mode = CommentMode.SET_IF_ABSENT)
class DbConfig {
    @Comment("The JDBC url")
    @Key(transformCase = KeyTransformCase.KEBAB_CASE)
    String jdbcUrl = "jdbc:h2:mem:test";          // -> key "jdbc-url"

    @Section("database.pool")                       // a flat field placed under a nested path
    int maxSize = 50;

    @PostLoad
    void validate() { /* runs after binding */ }
}

DbConfig db = cfg.loadAs(DbConfig.class, codec);    // lenient by default
```

- **Path-oriented binder.** `cfg.bind(type[, codec]).read(path)` / `readInto(path, target)` / `write(path, pojo)`
  (the root is the empty path `""`); `read*Result(...)` variants carry the issues. The Config façade wraps it:
  `getValue(path, type)` (typed read), `getValueInto(path, target)`, `getList(path, type)`, and a POJO
  `setValue(path, pojo)` (the annotation-aware merge).
- **Lenient bind (default):** a value that can't be coerced is recorded as a `LoadIssue` and the field keeps
  its real default; `STRICT` throws a `BindException` on the first mismatch. Use `loadAsResult(...)` (or the
  binder's `readResult(...)`) to get a `BindResult<T>` carrying the value **and** the issues together.
- **Annotations:** `@Key` (rename + case, or class-wide via `@JsonNaming(KeyCaseStrategy.Kebab.class)`),
  `@Comment` (+ `CommentMode`), `@Section` (nested placement, on top-level **or nested-POJO** fields),
  `@KeyIndex` (collection indexing). Native Jackson annotations keep working too.
- **Lifecycle hooks:** `@PreLoad`/`@PostLoad`/`@PreSave`/`@PostSave` methods (no-arg or a single `ConfigContext`)
  fire around the binder's read/write; the opt-in `ConfigLifecycle` interface offers the same four. Each gets
  a `ConfigContext` (`section()` + `issues()`).
- **Obsolete keys** (in the file, not declared by the POJO): `ObsoletePolicy.PRESERVE` (default), `REMOVE`
  (strip), or `COMMENT_OUT` (keep + stamp a deprecation comment, on comment-capable codecs).

**→ Deep dives: [Typed Entity Binding](https://github.com/EverNife/EveryConfig/wiki/Entity-Binding) ·
[Annotations](https://github.com/EverNife/EveryConfig/wiki/Annotations)**

---

## `@KeyIndex` collections

A `Collection<T>` whose element carries a `@KeyIndex` field serializes as a section keyed by that field's
value (it is omitted from the body; on read the section key is the sole authority).

```java
class Account { @KeyIndex String name; int balance; /* ... */ }

cfg.writeKeyIndexCollection("accounts", Arrays.asList(
        new Account("alice", 100), new Account("bob", 50)), codec);
// accounts:
//   alice: { balance: 100 }
//   bob:   { balance: 50 }

List<Account> back = cfg.readKeyIndexCollection("accounts", Account.class, codec);
```

`@KeyIndex` may be `String`, a boxed/primitive numeric, `boolean` or `UUID`.

**→ Deep dive: [`@KeyIndex` Collections](https://github.com/EverNife/EveryConfig/wiki/Id-Collections)**

---

## Lifecycle, reload & watching

```java
Config cfg = Config.open("app.yml");     // codec from the extension; absent -> empty; malformed -> .bak (never throws)
Config db  = Config.open(path, codec, Durability.FSYNC); // optional: force bytes to disk on each save (crash-safe)
cfg.save();                              // atomic write under a per-config lock
cfg.saveIfDirty();                       // no I/O when nothing changed
cfg.saveAsync();                         // on a shared daemon executor
cfg.reload();                            // re-read from disk
cfg.onReload(() -> ...).withAutoReload(Duration.ofSeconds(2));       // poll on a daemon thread
cfg.onReload(() -> ...).withAutoReload(Duration.ofSeconds(2), true); // also catch a same-size edit (hashes content)
cfg.close();                             // idempotent; stops the watcher
```

**In-memory & cross-format saves.** A `Config` does not need a file at all, and it can be persisted in a
different format than it was opened with:

```java
Config mem = Config.inMemory();      // full typed/POJO API, no file — save() throws (use a real codec to persist)

cfg.save(new JsonCodec());           // one-shot: dump the tree as JSON, leaving the live codec unchanged
cfg.changeCodec(new TomlCodec());    // switch the format used by every subsequent save
```

> `Config.inMemory()` carries a Jackson mapper, so `setValue(path, pojo)`, `getValue(path, type)` and the
> binding annotations all work in memory; it just has nothing to write to. (A bare `new Config()` has no
> codec at all and accepts only native values.)

> **In-memory save principle.** Outside a watcher, a `Config` lives entirely in memory: `save()` dumps the
> in-memory tree and **never reads the file first**. A hand-edit made while the app runs is overwritten on the
> next save unless the caller `reload()`s to pick it up.

**→ Deep dive: [Lifecycle, Reload & Watching](https://github.com/EverNife/EveryConfig/wiki/Lifecycle-and-Reload)**

---

## Building & running the tests

### Prerequisites

- **JDK 25** — the only JDK you need to launch the build. The wrapper is Gradle 9.5.1 (runs on JDK 25
  directly). Production sources compile on the Java 25 toolchain via [Jabel](https://github.com/bsideup/jabel)
  with `--release 8`, so the published bytecode and API floor stay at **Java 8**.
- The `-PtestJdk=N` multi-runtime runs need that JDK installed and discoverable by Gradle's toolchain
  auto-detection (e.g. under `~/.jdks`). Auto-download is off.

```bash
export JAVA_HOME=/path/to/jdk-25      # PowerShell: $env:JAVA_HOME = "C:\path\to\jdk-25"

./gradlew build                       # compile + run all tests on Java 25
./gradlew test -PtestJdk=8            # run the suite on the Java 8 runtime floor (also: 11, 17, 21)
```

> The codec-agnostic contract (`AbstractConfigTest`) runs the same body against all four codecs, so a
> behavior is validated identically on YAML, JSON, TOML and JSONC. Residual files are written under
> `build/test-residuals/` for inspection.

**→ Deep dives: [Building from Source](https://github.com/EverNife/EveryConfig/wiki/Building-from-Source) ·
[Running the Tests](https://github.com/EverNife/EveryConfig/wiki/Running-the-Tests)**

---

## Project layout

```
EveryConfig/
└── src/main/java/br/com/finalcraft/everyconfig/
    ├── config/                  # Config (dynamic API + lifecycle) + config.section (ConfigSection)
    ├── core/                    # the canonical model: core.tree (DPath), core.coerce (NodeCoercion),
    │                            #   core.comment (CommentTree), KeyOrder
    ├── codec/                   # Codec SPI, CommentFidelity, registry, mapper profiles
    │   └── jackson/             # JsonCodec, YamlCodec, TomlCodec, JsoncCodec
    ├── io/                      # file I/O: atomic write, .bak, poll watcher, async executor
    ├── binding/                 # typed binding: EntityBinder + binding.schema, binding.merge, binding.introspect
    └── annotation/              # @Key, @Comment, @Section, @KeyIndex, @PostLoad (+ KeyTransformCase, CommentMode)
```

**→ Deep dive: [Project Layout](https://github.com/EverNife/EveryConfig/wiki/Project-Layout)**

---

## Compatibility notes

- **Java 8 runtime floor.** The library is compiled with `--release 8`. Jabel lifts *syntax* but not runtime
  APIs, so the source avoids Java 9+ APIs; the `-PtestJdk=8` run is the guard. Validated green on Java 8, 11,
  17, 21 and 25.
- **Dependencies.** Jackson `databind` + `dataformat-yaml`/`-toml` are on the public `api` surface (the tree
  and codecs expose Jackson types); `jsr310` and `jdk8` (java.time / `Optional`) are runtime. The library's
  major version tracks Jackson's major (`1.x` ⟷ Jackson 2.x).
- **Serialization.** Bound entities must be Jackson-serializable (a no-arg constructor plus accessors/fields,
  or appropriate Jackson annotations).
- **No EverNifeCore, no Bukkit/Spigot API** — pure Java.

<div align="center">

**Made by [Petrus Pradella](https://petrus.dev)**

</div>
