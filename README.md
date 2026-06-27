# FinalConfig (v2)

A standalone Java configuration library built on **Jackson**. It keeps the ergonomics of the original
FinalConfig — a dynamic path API (`setValue("a.b", x)` / `getValue` / `getOrSetDefaultValue`) and
comment-aware files — while replacing the engine: the canonical state is a Jackson `ObjectNode` tree, a
pluggable `Codec` turns text into that tree (and back), a format-agnostic comment overlay preserves and
writes comments, and typed entity binding is a derived view that merges into the tree rather than
replacing it.

- **Group / artifact:** `br.com.finalcraft:FinalConfig`  ·  **Version:** `1.0.1`
- **Root package:** `br.com.finalcraft.finalconfig`
- Pure Java — no Bukkit/Spigot API, no EverNifeCore.

## Layers

| Package | Role |
|---|---|
| `config`, `config.section` | `Config` / `ConfigSection` — the handle, the dynamic path API, and the file lifecycle |
| `core.tree`, `core.coerce`, `core.comment` | path utilities, value coercion, the comment overlay |
| `codec`, `codec.jackson` | the `Codec` SPI + the JSON / YAML / TOML / JSONC codecs (one shared `ObjectMapper` per codec) |
| `backend` | atomic file I/O, `.bak` rescue, reload, the poll watcher |
| `binding`, `binding.introspect`, `annotation` | typed entity binding, `@Key`/`@Comment`/`@Section`/`@Id`/`@PostInject`, smart merge |

## Quick start — the dynamic API

```java
Config cfg = Config.open(Paths.get("server.yml"), new YamlCodec());

cfg.setValue("server.host", "localhost");
int port = cfg.getOrSetDefaultValue("server.port", 8080, "the listen port"); // seeds value + comment

cfg.save(); // atomic write; comments + key order preserved
```

`getOrSetDefaultValue(path, def, comment)` writes the default (and its comment) only if the path is
absent, so it is safe to call on every startup. Unknown keys a user added by hand always survive a save.

## Comments — two write modes

A comment can be **rewritten on every save** (so documentation in code stays current) or **written only
once** (so a user-edited comment wins). This applies to both the annotation and the fluent API:

```java
class DbConfig {
    @Comment("JDBC url")                                   // OVERRIDE (default): code doc wins, stays current
    @Key(transformCase = KeyTransformCase.KEBAB_CASE)
    String jdbcUrl = "jdbc:h2:mem:test";                   // -> key "jdbc-url"

    @Comment(value = "tune me", mode = CommentMode.SET_IF_ABSENT) // user edit wins after first write
    int maxPool = 10;
}
```

```java
cfg.setComment("a.b", "always overwritten");
cfg.setDefaultComment("a.b", "written only if a.b has no comment yet");
```

> A **class-level** `@Comment` in OVERRIDE mode overwrites the file header — use `SET_IF_ABSENT` on the
> class to preserve a header the user wrote.

## Entity binding (typed view)

Binding is a derived view over the tree; a binding **save merges** into the tree (the tree wins on
conflict, unknown keys survive):

```java
DbConfig db = cfg.loadAs(DbConfig.class, codec); // bind + @PostInject; lenient by default
db.maxPool = 25;
cfg.mergeFrom(db, codec);                         // merge POJO -> tree (+ seed @Comment), then cfg.save()
```

- **Lenient bind (default):** a value that cannot be coerced is recorded as a `LoadIssue` and the field
  keeps its real default; `STRICT` throws on the first mismatch.
- **Annotations:** `@Key` (rename + case), `@Section("a.b")` (place a flat field under a nested path),
  `@Id` (a `Collection<T>` becomes a section keyed by the id), `@PostInject` (run after binding). Native
  Jackson annotations (`@JsonProperty`, `@JsonIgnore`, `@JsonAlias`, …) keep working.

## Lifecycle

`open` / `save` / `saveIfDirty` / `saveAsync` / `reload` / `close`, plus `withAutoReload(Duration)`. A
malformed file is backed up to `.bak` and the config starts empty (a corrupt file never blocks startup).

> **In-memory save principle.** Outside a watcher, a `Config` lives entirely in memory: `save()` dumps the
> in-memory tree and **never reads the file first**. If the user edits the file by hand while the app is
> running, those edits are overwritten on the next save unless the caller `reload()`s (or `open()`s) to
> pick them up. Editing a running app's config without a reload is an anti-pattern; enable a watcher if you
> need the app to react to external edits.

## Build & test

Written in modern Java syntax (via Jabel) and compiled to a **Java 8 bytecode floor** (`options.release = 8`).
A single Java 25 toolchain runs the build; the Jabel fork (`br.com.finalcraft:jabel`, from `mavenLocal()`)
lets the Java 25 compiler emit Java 8 bytecode.

```powershell
$env:JAVA_HOME = "<JDK_25_HOME>"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew build
.\gradlew test -PtestJdk=8      # validate the Java 8 runtime floor (also: 11, 17, 21, 25)
```

### Shading on Bukkit/Spigot

This is a **thin** `java-library` jar: Jackson is a normal transitive dependency, not relocated. A plugin
that bundles FinalConfig should run its own `shadowJar` and relocate `com.fasterxml.jackson` and
`org.yaml.snakeyaml` on its side — relocation policy belongs at the leaf artifact, which is the only place
that knows the server's classpath.

## Memory

The original library held a heavy per-file SnakeYAML engine, which is what motivated Simple-YAML issue
#80. v2 has no such cost: the heavy `ObjectMapper` is shared per codec across every live `Config`, so a
config's footprint is just its tree. There is no engine pool, no `ThreadLocal`, and no smart-cache
eviction.
