package org.jboss.tools.javac.ls.index.test;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.jboss.tools.javac.ls.index.store.JavaIndex;
import org.jboss.tools.javac.ls.index.visitor.DOMToIndexVisitor;
import org.jboss.tools.javac.ls.parser.bindings.JavacDOMParser;
import org.junit.Test;

import shaded.org.eclipse.jdt.core.dom.AST;
import shaded.org.eclipse.jdt.core.dom.CompilationUnit;

/**
 * Test to verify that re-indexing a file doesn't leak references.
 *
 * CURRENT BEHAVIOR: This test FAILS because references accumulate on each re-index.
 * EXPECTED BEHAVIOR: After fixing the bug, references should remain constant.
 */
public class ReferenceLeakTest {

	@Test
	public void testReferencesLeakOnReindex() throws Exception {
		// Simple Java file with known references
		String sourceCode = """
			package test;

			public class TestClass {
				private String name;

				public void doSomething() {
					String local = name;
					System.out.println(local);
				}
			}
			""";

		Path testFile = Paths.get("/tmp/test/TestClass.java");
		JavaIndex index = new JavaIndex();
		JavacDOMParser parser = new JavacDOMParser();

		// Index the file once to establish baseline
		CompilationUnit cu = parser.parse(
			sourceCode,
			testFile.toString(),
			new ArrayList<File>(),
			AST.JLS21,
			null,
			false
		);

		DOMToIndexVisitor visitor = new DOMToIndexVisitor(index, testFile);
		cu.accept(visitor);
		visitor.finishIndexing();

		Set<String> declaredTypes = new HashSet<>();
		declaredTypes.add("test.TestClass");
		index.trackFileDeclaredTypes(testFile, declaredTypes);

		// Capture baseline reference counts
		int baselineStringRefs = index.findTypeUsages("java.lang.String").size();
		int baselineSystemRefs = index.findTypeUsages("java.lang.System").size();
		int baselineNameRefs = index.findNameUsages("name").size();
		int baselineLocalRefs = index.findNameUsages("local").size();

		System.out.println("\n=== Reference Leak Test ===");
		System.out.println("Baseline (after 1 index):");
		System.out.println("  String refs: " + baselineStringRefs);
		System.out.println("  System refs: " + baselineSystemRefs);
		System.out.println("  'name' refs: " + baselineNameRefs);
		System.out.println("  'local' refs: " + baselineLocalRefs);

		// Re-index the SAME file 200 times
		// If references are properly cleaned up, counts should stay the same
		// If they leak, counts will grow
		int iterations = 200;
		for (int i = 0; i < iterations; i++) {
			// Remove old data for this file
			index.removeFile(testFile);

			// Re-index the same file
			cu = parser.parse(
				sourceCode,
				testFile.toString(),
				new ArrayList<File>(),
				AST.JLS21,
				null,
				false
			);

			visitor = new DOMToIndexVisitor(index, testFile);
			cu.accept(visitor);
			visitor.finishIndexing();

			index.trackFileDeclaredTypes(testFile, declaredTypes);
		}

		// Check final reference counts
		int finalStringRefs = index.findTypeUsages("java.lang.String").size();
		int finalSystemRefs = index.findTypeUsages("java.lang.System").size();
		int finalNameRefs = index.findNameUsages("name").size();
		int finalLocalRefs = index.findNameUsages("local").size();

		System.out.println("\nAfter " + (iterations + 1) + " total indexes (" + iterations + " re-indexes):");
		System.out.println("  String refs: " + finalStringRefs);
		System.out.println("  System refs: " + finalSystemRefs);
		System.out.println("  'name' refs: " + finalNameRefs);
		System.out.println("  'local' refs: " + finalLocalRefs);

		// Calculate leak magnitude
		int stringLeak = finalStringRefs - baselineStringRefs;
		int systemLeak = finalSystemRefs - baselineSystemRefs;
		int nameLeak = finalNameRefs - baselineNameRefs;
		int localLeak = finalLocalRefs - baselineLocalRefs;

		System.out.println("\nLeak detected:");
		System.out.println("  String refs grew by: " + stringLeak + " (" +
		                   String.format("%.1f%%", 100.0 * stringLeak / baselineStringRefs) + ")");
		System.out.println("  System refs grew by: " + systemLeak + " (" +
		                   String.format("%.1f%%", 100.0 * systemLeak / baselineSystemRefs) + ")");
		System.out.println("  'name' refs grew by: " + nameLeak + " (" +
		                   String.format("%.1f%%", 100.0 * nameLeak / baselineNameRefs) + ")");
		System.out.println("  'local' refs grew by: " + localLeak + " (" +
		                   String.format("%.1f%%", 100.0 * localLeak / baselineLocalRefs) + ")");

		// Type count should remain constant (1 type)
		assertEquals("Type count should remain 1", 1, index.getAllTypes().size());

		// This assertion WILL FAIL with current code, demonstrating the bug
		// After fixing the bug, references should not accumulate
		if (finalStringRefs != baselineStringRefs ||
		    finalSystemRefs != baselineSystemRefs ||
		    finalNameRefs != baselineNameRefs ||
		    finalLocalRefs != baselineLocalRefs) {

			fail("\n\n" +
			     "******************** REFERENCE LEAK DETECTED ********************\n" +
			     "References accumulated on re-index instead of being cleaned up!\n" +
			     "\n" +
			     "Expected references to stay constant, but they grew by:\n" +
			     "  - String: +" + stringLeak + " (should be 0)\n" +
			     "  - System: +" + systemLeak + " (should be 0)\n" +
			     "  - name: +" + nameLeak + " (should be 0)\n" +
			     "  - local: +" + localLeak + " (should be 0)\n" +
			     "\n" +
			     "Root cause: JavaIndex.removeFile() doesn't remove references\n" +
			     "because we don't track which file they came from.\n" +
			     "*****************************************************************\n");
		}

		System.out.println("\n✓ No leaks! References stayed constant across " + iterations + " re-indexes.");
	}
}
