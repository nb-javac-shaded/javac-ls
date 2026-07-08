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

## Current Status

The javac-ls framework includes:

### Core Features
- **Java Indexing**: Full workspace indexing with type declarations, method/field signatures, and references
- **File Path Interning**: Memory-efficient storage reducing index size by ~50% (1.8GB → 948MB for large codebases)
- **Batched Parsing**: High-performance batch compilation using shared javac Context
- **Incremental Updates**: Smart re-indexing with reference leak prevention
- **Diagnostic Collection**: Compilation errors and warnings collected during indexing
- **Type Hierarchy**: Subtype and implementor relationships for fast queries
- **Reference Tracking**: Type and name references for "Find Usages" functionality
- **Persistence**: JSON-based index persistence to avoid re-indexing on startup

### Architecture
- **JavaIndex**: In-memory index with FilePathRegistry for path interning
- **DOMToIndexVisitor**: AST visitor that extracts declarations and references
- **JavacDOMParser**: Wrapper around javac for parsing with/without binding resolution
- **IndexPersistence**: Pluggable persistence layer (JSON implementation included)
- **WorkspaceModel**: High-level API for managing projects and indexing

### Recent Optimizations
- File path interning: Each unique path stored once, referenced by integer ID
  - 47.3% reduction in persisted index size
  - All file-based maps use `Map<Integer, X>` instead of `Map<Path, X>`
- Batch parsing with analyze: Diagnostics collected even without binding resolution
- Reference leak fix: fileTypeReferences/fileNameReferences track all references per file for proper cleanup

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

## Index Architecture

See detailed documentation in:
- `JavaIndex.java` - Core index data structures and algorithms
- `FilePathRegistry.java` - File path interning mechanism
- `Location.java` - Source location with file path interning
- `IndexPersistence.java` - Persistence interface and design

Key concepts:
1. **File Path Interning**: Stores each unique file path once, references by integer ID
2. **Dual Reference Tracking**: References tracked both by name (for queries) and by file (for cleanup)
3. **Incremental Updates**: removeFile() cleans up all data before re-indexing
4. **Lazy Binding Resolution**: Parse without bindings for speed, analyze() for diagnostics
5. **Transient PathRegistry**: Not serialized, rehydrated after deserialization
