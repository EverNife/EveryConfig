# FinalConfig (v2)

A standalone Java configuration library built on **Jackson**. It keeps the ergonomics of the original
FinalConfig — a dynamic path API (`setValue("a.b", x)` / `getValue` / `getOrSetDefaultValue`) and
comment-aware files — while replacing the engine: the canonical state is a Jackson `ObjectNode` tree, a
pluggable `Codec` turns text into that tree (and back), a format-agnostic comment overlay preserves and
seeds comments, and typed entity binding is a derived view that merges into the tree rather than
replacing it.

- **Group / artifact:** `br.com.finalcraft:FinalConfig`
- **Version:** `2.0.0-SNAPSHOT`
- **Root package:** `br.com.finalcraft.finalconfig`

## Layers

| Package | Role |
|---|---|
| `config`, `config.section` | `Config` / `ConfigSection` — the handle and the dynamic path API |
| `core.tree`, `core.coerce`, `core.comment` | path utilities, value coercion, the comment overlay |
| `codec`, `codec.jackson` | the `Codec` SPI + the JSON and YAML codecs (one shared `ObjectMapper` per codec) |
| `backend` | atomic file I/O, `.bak` rescue, reload, the poll watcher |
| `binding`, `binding.introspect`, `annotation` | typed entity binding, `@Key`/`@Comment`/`@Id`/`@PostInject`, smart merge |

## Build

Written in Java 17 syntax (via Jabel) and compiled to a **Java 8 bytecode floor** (`options.release = 8`).
A Java 17 toolchain runs the build; the production compile is pinned to a Java 17 compiler because Jabel
rides javac internals that changed after JDK 17.

```powershell
$env:JAVA_HOME = "<JDK_25_HOME>"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
.\gradlew build
```

The suite runs on Java 17 by default. To validate the Java 8 runtime floor (or any other JDK), pass the
target version — the test task runs on that JDK via a Gradle toolchain:

```powershell
.\gradlew test -PtestJdk=8     # also: 11, 17, 21, 25
```

Toolchain locations are listed in `gradle.properties` (`org.gradle.java.installations.paths`); auto-download
is off, so only locally installed JDKs are used.

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
