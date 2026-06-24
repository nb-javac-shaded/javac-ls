# OSGi MANIFEST Dependency Guide

**⚠️ READ THIS BEFORE MODIFYING ANY META-INF/MANIFEST.MF FILES ⚠️**

## TL;DR - The "Redundancy" is Intentional

This project uses what looks like "redundant" Import-Package + Require-Bundle declarations. **This is not a mistake.** It solves OSGi "uses constraints" issues that cause runtime ClassNotFoundException errors.

## The Pattern (Verified Working - Commit 89d0b2a)

### Rule 1: Utilities Use Import-Package Only

**These never need Require-Bundle:**
```
Import-Package: com.google.gson,
 org.slf4j,
 org.osgi.framework,
 org.osgi.service.log
```

**Why:** Simple utility libraries with no complex type dependencies.

---

### Rule 2: Our Own Bundles Use Require-Bundle + Import-Package

**When you require a bundle that exports types referencing other types, you MUST also import those packages explicitly.**

#### Example 1: org.jboss.tools.javac.ls.server

```
Import-Package: com.google.gson,
 org.eclipse.lsp4j.jsonrpc,              ← Imports from lsp4j
 org.jboss.tools.javac.ls.api,           ← Imports from api
 org.jboss.tools.javac.ls.api.dao,       ← Imports from api
 shaded.org.eclipse.jdt.core.dom,        ← Imports from jdt.core.dom
 ...
Require-Bundle: org.jboss.tools.javac.ls.api;visibility:=reexport,
 org.jboss.tools.javac.ls.parser,
 org.jboss.tools.javac.ls.index,
 org.jboss.tools.javac.ls.search,
 org.eclipse.lsp4j;bundle-version="1.0.0"  ← ALSO requires lsp4j
```

**Why the "redundancy"?**
- Server requires `api` bundle which exports types like `JavacLSClient extends LanguageClient`
- `LanguageClient` references types from `org.eclipse.lsp4j.jsonrpc` packages
- OSGi uses constraints require explicit imports for transitive type visibility
- Without the Import-Package, you get: `NoClassDefFoundError: org/eclipse/lsp4j/jsonrpc/...`

#### Example 2: org.jboss.tools.javac.ls.parser

```
Import-Package: shaded.org.eclipse.jdt.core,
 shaded.org.eclipse.jdt.core.dom,
 org.jboss.tools.javac.ls.internal.compiler.impl,
 org.slf4j
Require-Bundle: org.jboss.tools.javac.ls.jdt.core.dom
```

**Why:** Parser requires jdt.core.dom bundle, but must also explicitly import the shaded JDT packages it uses to satisfy uses constraints.

---

### Rule 3: Test Bundles Follow Same Pattern

#### Example: org.jboss.tools.javac.ls.server.test

```
Import-Package: com.google.gson,
 org.eclipse.lsp4j.jsonrpc,           ← Imports from bundle it requires
 org.jboss.tools.javac.ls.api,        ← Imports from bundle it requires
 org.jboss.tools.javac.ls.api.dao,    ← Imports from bundle it requires
 org.jboss.tools.rsp.eclipse.core.runtime,  ← From RSP bundles
 ...
Require-Bundle: org.jboss.tools.javac.ls.api,
 org.jboss.tools.javac.ls.server,
 ...
 org.eclipse.lsp4j;bundle-version="1.0.0",
 org.eclipse.lsp4j.jsonrpc;bundle-version="1.0.0"
```

**Critical:** Test bundles need even MORE explicit imports because they test code that crosses bundle boundaries.

---

## What Are "Uses Constraints"?

OSGi creates implicit "uses" relationships when:
1. Bundle A exports package `org.example.api`
2. Package `org.example.api` has types that reference types from `org.other.framework`
3. Any bundle importing `org.example.api` must see the SAME `org.other.framework` packages that Bundle A sees

**Example chain:**
```
server → requires api (which extends lsp4j.LanguageClient)
api → requires lsp4j
LanguageClient → references types from org.eclipse.lsp4j.jsonrpc.*

Result: server must ALSO import org.eclipse.lsp4j.jsonrpc.* packages
```

Without explicit imports, OSGi may wire incompatible versions or fail to wire at all → NoClassDefFoundError at runtime.

---

## Common Patterns in This Project

### Pattern A: Framework Dependencies (lsp4j)

```
Import-Package: org.eclipse.lsp4j.jsonrpc,
 org.eclipse.lsp4j.jsonrpc.messages,
 org.eclipse.lsp4j.jsonrpc.services,
 ...
Require-Bundle: org.eclipse.lsp4j;bundle-version="1.0.0"
```

**Why both:** lsp4j is a framework with many interconnected types. Require-Bundle gives us the main API, Import-Package ensures uses constraints are satisfied.

### Pattern B: Shaded JDT Dependencies

```
Import-Package: shaded.org.eclipse.jdt.core.dom,
 shaded.org.eclipse.jdt.core.compiler,
 ...
Require-Bundle: org.jboss.tools.javac.ls.jdt.core.dom
```

**Why both:** jdt.core.dom exports shaded packages. Consumers require the bundle but must explicitly import the shaded packages they use.

### Pattern C: RSP Framework (No Bundles Available)

```
Import-Package: org.jboss.tools.rsp.eclipse.core.runtime,
 org.jboss.tools.rsp.eclipse.debug.core,
 org.jboss.tools.rsp.eclipse.jdt.launching,
 org.jboss.tools.rsp.eclipse.osgi.util,
 org.jboss.tools.rsp.launching.utils,
 ...
Require-Bundle: org.jboss.tools.rsp.launching,
 org.jboss.tools.rsp.launching.java
```

**Why:** Only 3 RSP bundles exist (launching, launching.java, logging), but they export many `org.jboss.tools.rsp.eclipse.*` packages. We require the bundles but also import their exported packages for uses constraints.

---

## What NOT to Do

### ❌ DON'T Remove "Redundant" Import-Package Entries

```diff
- Import-Package: org.jboss.tools.javac.ls.api,
-  org.jboss.tools.javac.ls.api.dao
  Require-Bundle: org.jboss.tools.javac.ls.api
```

**Result:** Build succeeds, but tests fail with `NoClassDefFoundError` at runtime.

### ❌ DON'T Use Pure Import-Package for Framework Dependencies

```diff
  Import-Package: org.eclipse.lsp4j,
   org.eclipse.lsp4j.jsonrpc,
   org.eclipse.lsp4j.jsonrpc.messages,
   ... (50+ more lsp4j packages)
- Require-Bundle: org.eclipse.lsp4j
```

**Result:** You'll need to enumerate 50+ lsp4j packages, and still hit uses constraint issues. Use Require-Bundle for frameworks.

### ❌ DON'T Use Pure Require-Bundle Without Import-Package

```diff
- Import-Package: shaded.org.eclipse.jdt.core.dom
  Require-Bundle: org.jboss.tools.javac.ls.jdt.core.dom
```

**Result:** Compile succeeds but uses constraints cause runtime failures.

---

## Decision Tree: What Should I Use?

```
Is it a simple utility (gson, slf4j, osgi)?
  → Import-Package ONLY

Is it a framework (lsp4j, junit, mockito)?
  → Require-Bundle + Import-Package for packages you actually use

Is it one of our own bundles?
  → Require-Bundle + Import-Package for packages you actually use

Is it the RSP framework?
  → Require-Bundle for the 3 available bundles + Import-Package for the packages
```

---

## Testing Your Changes

After modifying a MANIFEST.MF:

1. **Build:** `mvn clean verify -pl :bundle-name -am`
2. **Full build:** `mvn clean verify` (includes tests)
3. **Watch for:**
   - `NoClassDefFoundError` in test output
   - `cannot be resolved` compile errors
   - Uses constraint violations: "is not visible from class loader"

If tests that previously passed now fail with NoClassDefFoundError → you removed a necessary "redundant" import.

---

## Why Not Pure Import-Package or Pure Require-Bundle?

### Tried Pure Import-Package (Failed)
- Had to enumerate 50+ lsp4j packages
- Still hit uses constraint issues
- Too fragile, high maintenance burden

### Tried Pure Require-Bundle (Failed)  
- Utilities like gson/slf4j don't have bundles
- Missing uses constraint visibility
- Tests failed with NoClassDefFoundError

### Hybrid Approach (Works!)
- Require-Bundle for frameworks and our bundles
- Import-Package for utilities AND to satisfy uses constraints
- **The "redundancy" is the solution, not the problem**

---

## Reference: Working State

Commit: **89d0b2a** - "Add org.eclipse.lsp4j.jsonrpc to test bundle dependencies"

All 135+ tests passing. If changes break tests, compare against this commit.

---

## Runtime: Apache Felix 7.0.3

This project runs on Felix in production. While Felix supports both Import-Package and Require-Bundle, uses constraints are strictly enforced. The hybrid approach ensures proper wiring in Felix's OSGi container.

---

## Summary

**The pattern that works:**
1. ✅ Utilities → Import-Package only
2. ✅ Frameworks & our bundles → Require-Bundle + Import-Package for types used
3. ✅ "Redundant" imports solve uses constraints
4. ✅ All tests pass

**Don't try to "clean up" the redundancy unless you're prepared to debug OSGi uses constraints for several hours.**
