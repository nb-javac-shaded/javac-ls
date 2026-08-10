# Improving jdt-ls Startup Performance on Large Maven Projects

## Problem Statement

Our VS Code extension (vscode-java) ships jdt-ls as its language server. On large multi-module Maven projects — Quarkus being our reference case — initial project import routinely exceeds one hour. During this time, code intelligence is unavailable or severely degraded. This is the single largest pain point for users working on large codebases.

## Root Cause Analysis

Investigation using thread dumps, profiling, and targeted benchmarking identified three layers of cost during project import. They are listed in order of impact.

### 1. m2e Maven Plugin Lifecycle Execution (dominant cost)

When m2e imports a Maven project, it does not simply read the POM and resolve dependencies. It discovers lifecycle-mapped Maven plugin goals and executes them via `MojoExecutionBuildParticipant`. For projects like Quarkus that include Kotlin modules, this means running the Kotlin compiler (kapt, compile, test-compile) — three invocations per module, executed sequentially. Thread dumps captured during Quarkus import showed the JDT builder thread blocked inside Kotlin compiler execution for the vast majority of wall-clock time.

This is the core problem: m2e faithfully reproduces the Maven build lifecycle inside Eclipse, including plugin goals that are irrelevant to code intelligence.

### 2. m2e Dependency Resolution (significant cost)

Even setting aside plugin execution, m2e resolves the full dependency tree for every module during import. While m2e does this in-process (avoiding the overhead of forking an external Maven process per module), it still performs artifact resolution, downloads missing artifacts, and computes the transitive closure for each module independently. On a project with hundreds of modules sharing overlapping dependency trees, this is redundant work.

### 3. JDT Source Indexing (minor cost)

ECJ's `IndexingParser` parses every Java source file to populate the search index with type declarations, method declarations, field declarations, and references (type, method, constructor). It parses method bodies to capture reference-level information. On Quarkus (~17,000 Java files), ECJ indexing takes approximately 7.6 seconds. This is not a bottleneck.

For context: an experimental Turbine-based indexer (declaration-only, skips method bodies) completed the same corpus in 3.9 seconds (1.9x speedup) but produced 269 declaration mismatches. Turbine is not a drop-in replacement for ECJ indexing, and even if it were, the savings are marginal relative to the minutes-to-hours spent in m2e.

### Summary

| Phase | Quarkus Wall-Clock | Notes |
|---|---|---|
| m2e plugin execution | ~45–60+ min | Kotlin compiler dominates; sequential per module |
| m2e dependency resolution | minutes | Per-module transitive resolution; overlapping trees |
| JDT source indexing (ECJ) | ~7.6 sec | Not a bottleneck |

The bottleneck is not in JDT. It is in m2e's faithful reproduction of the Maven build lifecycle.

## Options

### Option A: Improve m2e Upstream

Work within the existing m2e codebase to reduce the cost of project import. Potential targets include:

- Skip or defer lifecycle-mapped plugin goals that don't contribute to classpath or source generation (e.g., Kotlin compilation when we only need the classpath)
- Cache and share dependency resolution results across modules with overlapping trees
- Parallelize module import where dependencies allow
- Add a "fast import" mode that defers expensive operations until explicitly requested

**Pros:**
- Fixes the problem at the source — benefits every Eclipse and jdt-ls user, not just us
- Preserves full m2e fidelity for projects that genuinely need it (e.g., annotation processors that generate source)
- No architectural disruption to vscode-java — we continue shipping jdt-ls as-is
- Community goodwill from contributing upstream fixes

**Cons:**
- m2e is a large, complex codebase with many stakeholders — changes require consensus
- Some lifecycle-mapped goals are genuinely load-bearing (source generators, annotation processors) — distinguishing "safe to skip" from "required" is non-trivial
- Timeline is uncertain — upstream review and release cycles are outside our control
- May require deep Maven internals expertise that we'd need to build up

**Open questions:**
- Which lifecycle-mapped goals can be safely skipped or deferred without breaking code intelligence?
- Is there appetite upstream for a "fast import" mode, or would it be seen as breaking the m2e contract?
- How much of the dependency resolution cost is network (downloading artifacts) vs. computation (resolving the tree)?

---

### Option B: Replace m2e with Lightweight Classpath Discovery

Remove m2e from the jdt-ls import path entirely. Replace it with a simpler mechanism whose sole job is to determine the classpath, source roots, and output directories for each module — without executing any Maven plugin goals.

Possible implementation approaches:
- Invoke `mvn dependency:build-classpath` or `mvn help:effective-pom` once and parse the output
- Use the Maven Resolver API (Aether) directly to resolve dependencies without lifecycle execution
- Read the effective POM and resolve artifact coordinates against the local repository
- Some hybrid of the above, potentially with caching

This would live inside jdt-ls (or as a library consumed by jdt-ls), replacing the m2e project configurator for Maven projects.

**Pros:**
- Eliminates the dominant cost entirely — no lifecycle execution, no Kotlin compiler, no plugin goals
- Dramatically simpler codebase than m2e for the subset of functionality we actually need
- Full control over the implementation and release timeline
- Could be designed from the start for the multi-module case (shared resolution, parallelism)

**Cons:**
- Loses m2e's handling of source-generating plugins (annotation processors, protobuf, ANTLR, etc.) — projects relying on generated source would have incomplete type information until built externally
- Significant engineering effort to build, test, and handle edge cases (BOMs, dependency management, profiles, multi-module reactor ordering, exclusions, optional dependencies)
- Maven's dependency resolution has many subtle behaviors — reimplementing or wrapping it correctly is harder than it looks
- Would need a story for Gradle projects too, or this only solves the Maven case
- Risk of divergence: if classpath discovery disagrees with what Maven actually resolves, users get confusing errors

**Open questions:**
- Can we invoke Maven externally once at import time (accepting the subprocess cost) and parse its output, rather than reimplementing resolution?
- How do we handle source-generating plugins — ignore them (require external build), run them selectively, or detect and warn?
- What's the fallback when our resolver disagrees with the real Maven build?

---

### Option C: Hybrid — Fast Classpath Bootstrap with Deferred m2e

Keep m2e in jdt-ls but change the import sequence: first, run a fast classpath discovery pass (as in Option B) to get the project navigable immediately. Then, optionally, run full m2e import in the background for projects that need it (source generation, annotation processing, etc.).

**Pros:**
- Best-of-both-worlds potential: fast startup AND full fidelity when needed
- Non-disruptive to the existing architecture — m2e stays, it just runs later
- Users get code intelligence within seconds/minutes, not hours
- The background m2e pass could be opt-in per project, eliminating it entirely for projects that don't need lifecycle execution

**Cons:**
- Two import paths means two sets of bugs, two codepaths to maintain
- The "fast" classpath might disagree with m2e's classpath — users could see symbols resolve, then break (or vice versa) when m2e finishes
- Merging the fast-pass project model with m2e's project model is non-trivial — JDT's internal model assumes a single source of truth per project
- Complexity budget: this is arguably harder than either A or B alone

**Open questions:**
- Can JDT's project model be updated in-place when the background m2e pass completes, or does it require a full rebuild of the model?
- How do we communicate to the user that code intelligence is "partial" during the fast phase?
- Is the fast phase good enough that most users would never bother waiting for the full m2e pass?

---

### Option D: javac-ls with ECJ (replacing javac + nbjavac)

Take the existing javac-ls proof-of-concept and replace its javac/nbjavac-based parsing and indexing with ECJ. This would produce a lean standalone language server that uses ECJ for parsing, indexing, and potentially type resolution, without the m2e layer.

javac-ls demonstrated that a minimal language server can index a large project in seconds when it doesn't try to reproduce the build lifecycle. Replacing the shaded nb-javac jars with ECJ could give us access to ECJ's mature type resolution, error recovery, and the JDT search infrastructure — without inheriting m2e.

Alternatively, javac-ls (with or without ECJ) could serve as a **complementary tool** rather than a replacement — e.g., a fast indexer that populates results consumed by jdt-ls, or a lightweight server for specific use cases (navigation, search) while jdt-ls handles the full IDE experience.

**Pros:**
- Proven fast: javac-ls indexes Quarkus in seconds (assuming externally built project)
- ECJ would bring better error recovery, more complete type resolution, and access to JDT's proven search infrastructure
- Full control — no upstream dependencies or consensus required
- Could serve as a testing ground for ideas that later feed back into jdt-ls

**Cons:**
- A standalone language server needs much more than indexing — completions, diagnostics, refactoring, formatting, code actions, all need implementation
- ECJ's lowest level (the compiler and parser) is available as a standalone jar, but higher-level features (search, refactoring) pull in JDT model dependencies — the usable surface without JDT needs evaluation
- "Assumes externally built project" is a significant caveat — the classpath problem doesn't go away, it just moves
- Maintaining a second language server alongside jdt-ls doubles the surface area
- The proof-of-concept was valuable for learning, but productionizing it is a different scale of effort

**Open questions:**
- ECJ's compiler/parser jar is standalone, but how much of its type resolution and search infrastructure can we use without pulling in the full JDT project model?
- What's the minimum viable feature set for a language server that users would accept as a replacement (or complement) for jdt-ls?
- If the answer is "complement," what's the integration surface between javac-ls and jdt-ls?

---

### Option E: javac-ls As-Is (javac + nbjavac)

Continue the current javac-ls direction using javac and the shaded nb-javac libraries for parsing and indexing.

**Pros:**
- Already built and functional for its current scope
- javac produces the most accurate parse trees (it is the reference compiler)
- nb-javac provides the partial/error-tolerant parsing that IDEs need
- No new dependencies to evaluate

**Cons:**
- javac-ls does not use nb-javac directly — it uses **shaded** nb-javac jars, which adds a layer of packaging and maintenance cost on top of nb-javac itself
- Additionally, javac-ls depends on **nnb-doclint**, a separate project structured after nb-javac but targeting the doclint codebase — this adds further complexity and yet another shaded dependency to track across JDK releases
- nb-javac is a patched fork of javac maintained by the NetBeans team — its future is tied to NetBeans's priorities, and javac's internals are not a stable API
- The shaded jar approach means every JDK release potentially requires re-shading both nb-javac and nnb-doclint, compounding the maintenance burden
- Same "needs much more than indexing" gap as Option D
- Same "assumes externally built project" caveat
- The proof-of-concept has served its learning purpose — continuing it as a product effort competes for resources with options that fix the actual shipped product (vscode-java / jdt-ls)

**Open questions:**
- Is there a specific capability that javac/nb-javac provides that ECJ does not, which would justify the maintenance cost of the shaded jars and nnb-doclint?
- What would "done" look like for this option — a full language server, or something more focused?

## Appendix: Benchmarks and Findings

### ECJ vs. Turbine Indexing (Quarkus, ~17,000 files)

Results from Angelo Zerr's `TurbineIndexComparator` run on the Quarkus source tree (we reviewed his comparator code and results but did not run it ourselves):

| Indexer | Time | Failures/Errors | Scope |
|---|---|---|---|
| ECJ (IndexingParser) | 7,576 ms | 0 | Declarations + references (parses method bodies) |
| Turbine | 3,904 ms | 269 declaration mismatches | Declarations only (skips method bodies) |

Speedup: 1.9x. However, Turbine's 269 failures and lack of reference indexing make it unsuitable as a drop-in ECJ replacement.

### What ECJ Indexes

ECJ's `IndexingParser` produces index entries for:
- **Declarations**: typeDecl, methodDecl, methodDeclPlus, constructorDecl, fieldDecl, moduleDecl
- **References**: typeRef, methodRef, constructorRef, superRef, memberTypeRef

It does NOT index:
- Local variable declarations or references (local variable search uses `LocalVariablePattern`, which bypasses the index entirely and searches only the containing file)

### m2e Import: Where Time Goes

Thread dumps captured during Quarkus import in jdt-ls showed:

1. **`MavenBuilder.build()`** → iterates project modules, calling `MojoExecutionBuildParticipant.build()` for each lifecycle-mapped goal
2. **Kotlin modules** trigger `kapt`, `compile`, and `test-compile` goals — three sequential Kotlin compiler invocations per module
3. **Dependency resolution** runs per-module via the Maven Resolver (Aether), computing the transitive closure independently for each module even when dependency trees overlap substantially
4. JDT indexing runs concurrently with m2e import but completes in seconds — it is never the bottleneck

### javac-ls Indexing Performance

javac-ls indexes a pre-built Quarkus checkout in single-digit seconds. This is possible because it:
- Reads the classpath from the already-built project (no lifecycle execution)
- Uses javac/nb-javac for parsing (fast, single-pass)
- Indexes declarations and references without compiling

This demonstrates that the indexing problem is solved — the bottleneck is classpath and dependency discovery, not source parsing.

### Index Storage Size

One caveat with javac-ls: the index for Quarkus is large. javac-ls currently stores its index in a text-based format, which is likely wasteful compared to JDT's binary index format. While we reduced the index size significantly during development, a production-quality index would need a more compact storage format. This is a solvable engineering problem but worth noting as additional work for Options D and E.
