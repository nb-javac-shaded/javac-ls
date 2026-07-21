# Javac Language Server

## Summary

This is a lightweight Java language server based on javac, designed for AI-centric development workflows. It provides a minimal API for testing and will be extended with javac-based language features.

The protocol is based on LSP4J, providing a simplified interface for language server communication.

The base protocol of LSP can be found [here](https://microsoft.github.io/language-server-protocol/specification).

## Install

This client / server code can be found at: https://github.com/nb-javac-shaded/javac-ls

## Get the code

The easiest way to get started with the code is to [create your own fork](http://help.github.com/forking/),
and then clone your fork:

    $ git clone git@github.com:<you>/javac-ls
    $ cd javac-ls
    $ git remote add upstream https://github.com/nb-javac-shaded/javac-ls

At any time, you can pull changes from the upstream and merge them onto your main branch:

    $ git checkout main                 # switches to the 'main' branch
    $ git pull upstream main            # fetches all 'upstream' changes and merges 'upstream/main' onto your 'main' branch
    $ git push origin                   # pushes all the updates to your fork, which should be in-sync with 'upstream'

The general idea is to keep your 'main' branch in-sync with the
'upstream/main'.

## Building The client and server

This command will run the build:

    $ ./build.sh

Or with tests:

    $ ./build-with-tests.sh

If you prefer to use Maven directly:

    $ MAVEN_OPTS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0" mvn clean verify

To skip tests:

    $ ./build.sh -DskipTests=true

But *do not* push changes without having the new and existing unit tests pass!

## Running the Server

After building, a distribution ZIP is created at `distribution/distribution/target/org.jboss.tools.javac.ls.distribution-0.0.1-SNAPSHOT.zip`

Extract and run:

    $ unzip distribution/distribution/target/org.jboss.tools.javac.ls.distribution-0.0.1-SNAPSHOT.zip
    $ cd rsp-distribution
    $ java -jar bin/felix.jar

### Configuration

The server can be configured via system properties:

- **`javacls.workspace.path`** - Path to the workspace directory where server data is stored
  - Default: `~/.javacls/workspace`
  - Example: `-Djavacls.workspace.path=/path/to/my/workspace`

- **`javacls.server.port`** - Port number for the language server
  - Default: `27511`
  - Example: `-Djavacls.server.port=8080`

Example with custom workspace:

    $ java -Djavacls.workspace.path=/my/project/workspace -jar bin/felix.jar

## Dependencies & Shading Strategy

Javac-ls needs to use its own Java compiler (javac) and its own AST model (Eclipse JDT DOM) without conflicting with the JDK's built-in compiler or any Eclipse JDT installation on the host system. The solution is **package shading** -- relocating all classes under a `shaded.*` prefix so they coexist safely.

There are three shaded dependency trees:

### 1. nb-javac-shaded (The Compiler)

[nb-javac](https://github.com/oracle/nb-javac) is Oracle's fork of javac that exposes internal compiler APIs for IDE use. The [nb-javac-shaded](https://github.com/nb-javac-shaded/nb-javac-shaded) project takes the official `nb-javac` artifact from Maven Central and relocates its packages:

- `com.sun.tools.*` → `shaded.com.sun.tools.*`
- `com.sun.source.*` → `shaded.com.sun.source.*`
- `javax.tools` → `shaded.javax.tools`
- `javax.lang.*` → `shaded.javax.lang.*`
- `javax.annotation.processing` → `shaded.javax.annotation.processing`

**This project contains zero custom Java code.** It is purely a Maven build that applies `maven-shade-plugin` plus two shell scripts for multi-release JAR relocation and source JAR restructuring. Updating to a new JDK version of nb-javac means changing one version property in the POM and rebuilding.

### 2. nnb-doclint-shaded (DocLint Checker)

[nnb-doclint-shaded](https://github.com/nb-javac-shaded/nnb-doclint-shaded) provides JavaDoc comment validation (the `-Xdoclint` feature). It's 7 Java files extracted from OpenJDK JDK 26 with only two categories of changes:

- **Java 17 downgrade patches** -- mechanical replacements of pattern-matching `instanceof` with traditional casts
- **Package relocation** -- import statements rewritten to reference `shaded.*` packages

No functional or algorithmic modifications were made to the upstream code.

### 3. Shaded Eclipse JDT Core DOM (AST Model)

The `org.jboss.tools.javac.ls.jdt.core.dom` bundle contains a shaded copy of the Eclipse JDT Core DOM model under `shaded.org.eclipse.jdt.*`. This provides the AST node type hierarchy (`CompilationUnit`, `MethodDeclaration`, `TypeDeclaration`, etc.), compiler problem definitions, and binding abstractions that the rest of the project uses as its AST representation.

### Maintainability

The shading layer has a **low maintenance burden**. The nb-javac-shaded project has no Java code at all, and nnb-doclint-shaded has only mechanical patches. Updating to a new JDK version is a version bump + rebuild for both.

Known risks:
- **Shell script fragility** -- the post-processing scripts in nb-javac-shaded assume specific JAR internal structure. If upstream nb-javac reorganizes, they'll silently break.
- **No automated verification** -- there are no tests in the shading projects that validate the shaded JARs actually work.
- **Two-project coordination** -- nb-javac-shaded excludes `com.sun.tools.doclint.**` because nnb-doclint-shaded handles it separately. Consumers need both.

## Project Modules

The project is an OSGi application built with Tycho (Eclipse/Maven build system), running on Apache Felix. The modules form a layered dependency stack:

```
org.jboss.tools.javac.ls.api          LSP protocol contract (interfaces, DAOs)
        |
        |   org.jboss.tools.javac.ls
        |    .jdt.core.dom             Shaded Eclipse JDT AST model
        |           |                  (CompilationUnit, ASTNode, IBinding, etc.)
        |           |
        |   org.jboss.tools.javac.ls
        |    .parser                   Javac-to-JDT bridge
        |           |                  Parses Java source with shaded nb-javac,
        |           |                  produces JDT DOM nodes. Embeds the three
        |           |                  shaded JARs. Binding resolution, diagnostics,
        |           |                  javadoc processing.
        |           |
        |   org.jboss.tools.javac.ls
        |    .index                    Symbol index
        |           |                  JavaIndex, FilePathRegistry, persistence.
        |           |                  Extracts declarations and references from AST.
        |           |
        |   org.jboss.tools.javac.ls
        |    .search                   Search engine over the index
        |           |                  Pattern matching, scopes, find references.
        |           |
        +-----+-----+
              |
  org.jboss.tools.javac.ls
   .server                            LSP server implementation
              |                        WorkspaceModel, classpath discovery,
              |                        LSP protocol handlers, diagnostics.
              |
  org.jboss.tools.javac.ls
   .foundation.core                   OSGi lifecycle, launchers, transport
              |
        [Distribution]                 Apache Felix 7.0.3 + all bundles
```

Supporting modules:
- **api** (`org.jboss.tools.javac.ls.api`) -- defines the LSP protocol interfaces and data types, built on LSP4J
- **foundation.core** -- OSGi lifecycle management, launcher integration, task scheduling
- **distribution** -- packages everything as a standalone Felix application with logging and launcher scripts
- **client CLI** -- a command-line client for connecting to the running server

## Key Design Decisions

- **File path interning**: Each unique file path is stored once in `FilePathRegistry` and referenced by integer ID throughout the index. Reduces persisted index size by 47.3% (1.8GB → 948MB on the Quarkus benchmark with 23,604 files). All file-based maps use `Map<Integer, X>` instead of `Map<Path, X>`.

- **Dual reference tracking**: Every reference is added to two maps -- one keyed by name (for "Find References" queries) and one keyed by source file ID (for cleanup when re-indexing). This prevents reference leaks when files change.

- **Batch parsing with lazy binding resolution**: Files are parsed in batches of 100 sharing a single javac Context for performance. `task.analyze()` is always called to collect diagnostics, but `IBinding` objects are only created on demand. This gives full error reporting without the cost of binding resolution during indexing.

- **JSON persistence**: The index is persisted as separate JSON files (types, methods, references, etc.) to avoid re-indexing on startup. The file path registry is saved first and loaded first, then all `Location` objects are rehydrated with their `pathRegistry` reference after deserialization.

## Building

### Standard Build (No Tests)

```bash
cd framework
mvn clean compile -DskipTests
```

### Full Build With Tests

```bash
cd framework
mvn clean verify
```

Or use the provided script:

```bash
./build-with-tests.sh
```

### Build Options

Skip tests:
```bash
mvn clean compile -DskipTests
```

Run specific test:
```bash
mvn test -Dtest=DiagnosticsWithoutBindingsTest -pl tests/org.jboss.tools.javac.ls.server.test
```

Run integration tests:
```bash
mvn integration-test -Dtest=WorkspaceModelIndexIntegrationTest -pl tests/org.jboss.tools.javac.ls.server.test
```

### Build Requirements
- Java 17 or later
- Maven 3.6+
- Environment variables for XML entity limits (set automatically by build scripts)

## Benchmarking

The framework includes benchmarking tools to measure indexing performance and memory usage.

### Benchmark 1: Spring PetClinic

A small-to-medium sized Spring Boot application for quick benchmarking.

**Setup:**
```bash
# Clone and build Spring PetClinic
cd ~/apps/claude/benchmarks
git clone https://github.com/spring-projects/spring-petclinic.git
cd spring-petclinic
mvn clean compile -DskipTests
```

**Run Benchmark:**
```bash
cd ~/apps/claude/javac-ls/framework
mvn exec:java -pl tests/org.jboss.tools.javac.ls.server.test \
  -Dexec.mainClass="org.jboss.tools.javac.ls.server.benchmark.IndexBenchmark" \
  -Dexec.args="/home/rob/apps/claude/benchmarks/spring-petclinic"
```

**Expected Results:**
- Files: ~100 Java files
- Index time: A few seconds
- Memory: Low MB range
- Index size: Small (< 10MB)

### Benchmark 2: Quarkus

A large, complex codebase with 23,604 Java files for stress testing.

**Setup:**
```bash
# Clone and build Quarkus
cd ~/apps/claude/benchmarks
git clone https://github.com/quarkusio/quarkus.git
cd quarkus
mvn clean compile -DskipTests -Dquickly
```

**Run Benchmark:**
```bash
cd ~/apps/claude/javac-ls/framework
mvn exec:java -pl tests/org.jboss.tools.javac.ls.server.test \
  -Dexec.mainClass="org.jboss.tools.javac.ls.server.benchmark.IndexBenchmark" \
  -Dexec.args="/home/rob/apps/claude/benchmarks/quarkus"
```

**Expected Results (with file path interning):**
- Files: 23,604 Java files
- Index time: ~30-60 seconds
- Peak memory: ~478MB in-memory
- Persisted index: ~948MB (was 1.8GB before interning)
- Breakdown:
  - file_to_types.json: 2.5MB
  - file_timestamps.json: 497KB
  - file_type_references.json: 106MB
  - file_name_references.json: 320MB
  - file_path_registry.json: 3.8MB
  - types.json, methods.json, fields.json, etc.

### Benchmark Cache

The benchmarks create an index cache at:
```
~/apps/claude/benchmarks/<project-name>/javac-ls-index/
```

To get accurate "cold start" measurements, delete the cache first:
```bash
rm -rf ~/apps/claude/benchmarks/quarkus/javac-ls-index/
```

### Benchmark Output

The benchmark reports:
- **File counts**: Total Java files indexed
- **Timing**: Parse time, index time, total time
- **Memory**: Peak memory usage during indexing
- **Index size**: Total persisted index size on disk
- **Per-file breakdown**: Size of each JSON file in the index
- **Statistics**: Type count, method count, reference count, etc.

## Further Reading

Detailed Javadoc on the index architecture can be found in:
- `JavaIndex.java` - Core index data structures, persistence, and incremental update algorithm
- `FilePathRegistry.java` - File path interning mechanism with memory savings analysis
- `Location.java` - Source location with file path interning and rehydration
- `IndexPersistence.java` - Persistence interface and JSON file layout
