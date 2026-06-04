/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.eclipse.jdt.internal.core.dom.cache;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for DOMCache in-memory caching.
 */
public class DOMCacheTest {

	private DOMCache cache;
	private Path tempDir;
	private Map<String, String> compilerOptions;

	@Before
	public void setUp() throws IOException {
		cache = new DOMCache();
		tempDir = Files.createTempDirectory("dom-cache-test");

		compilerOptions = new HashMap<>();
		compilerOptions.put(JavaCore.COMPILER_SOURCE, "17");
		compilerOptions.put(JavaCore.COMPILER_COMPLIANCE, "17");
		compilerOptions.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "17");
	}

	@After
	public void tearDown() throws IOException {
		if (tempDir != null && Files.exists(tempDir)) {
			Files.walk(tempDir)
				.sorted((a, b) -> b.compareTo(a)) // Delete files before directories
				.forEach(path -> {
					try {
						Files.delete(path);
					} catch (IOException e) {
						// Ignore
					}
				});
		}
	}

	@Test
	public void testCacheMissOnFirstAccess() throws IOException {
		// Create a test file
		Path sourceFile = createTestFile("TestClass.java",
			"public class TestClass {\n" +
			"    public void testMethod() {}\n" +
			"}\n");

		URI fileUri = sourceFile.toUri();

		// First access - cache miss
		assertEquals(0, cache.size());
		CompilationUnit unit = cache.getCompilationUnit(fileUri, null, AST.JLS21, compilerOptions, false);

		assertNotNull(unit);
		assertEquals(1, cache.size());
		assertFalse("Should have at least one type", unit.types().isEmpty());
	}

	@Test
	public void testCacheHitOnSecondAccess() throws IOException {
		Path sourceFile = createTestFile("TestClass.java",
			"public class TestClass {}\n");

		URI fileUri = sourceFile.toUri();

		// First access
		CompilationUnit unit1 = cache.getCompilationUnit(fileUri, null, AST.JLS21, compilerOptions, false);
		assertNotNull(unit1);

		// Second access - should return cached instance
		CompilationUnit unit2 = cache.getCompilationUnit(fileUri, null, AST.JLS21, compilerOptions, false);
		assertNotNull(unit2);

		// Should be the same instance from cache
		assertSame(unit1, unit2);
		assertEquals(1, cache.size());
	}

	@Test
	public void testCacheInvalidationOnFileModification() throws IOException, InterruptedException {
		Path sourceFile = createTestFile("TestClass.java",
			"public class TestClass {}\n");

		URI fileUri = sourceFile.toUri();

		// First access
		CompilationUnit unit1 = cache.getCompilationUnit(fileUri, null, AST.JLS21, compilerOptions, false);
		assertNotNull(unit1);

		// Wait to ensure timestamp changes
		Thread.sleep(100);

		// Modify the file
		Files.write(sourceFile,
			"public class TestClass { public void newMethod() {} }\n".getBytes());

		// Access again - should detect file change and reparse
		CompilationUnit unit2 = cache.getCompilationUnit(fileUri, null, AST.JLS21, compilerOptions, false);
		assertNotNull(unit2);

		// Should be different instance (reparsed)
		assertNotSame(unit1, unit2);
		assertEquals(1, cache.size());
	}

	@Test
	public void testExplicitInvalidation() throws IOException {
		Path sourceFile = createTestFile("TestClass.java",
			"public class TestClass {}\n");

		URI fileUri = sourceFile.toUri();

		// First access
		CompilationUnit unit1 = cache.getCompilationUnit(fileUri, null, AST.JLS21, compilerOptions, false);
		assertNotNull(unit1);
		assertEquals(1, cache.size());

		// Explicitly invalidate
		cache.invalidate(fileUri);
		assertEquals(0, cache.size());

		// Access again - should reparse
		CompilationUnit unit2 = cache.getCompilationUnit(fileUri, null, AST.JLS21, compilerOptions, false);
		assertNotNull(unit2);
		assertNotSame(unit1, unit2);
		assertEquals(1, cache.size());
	}

	@Test
	public void testClearCache() throws IOException {
		// Create multiple test files
		Path file1 = createTestFile("Class1.java", "public class Class1 {}\n");
		Path file2 = createTestFile("Class2.java", "public class Class2 {}\n");

		// Cache both
		cache.getCompilationUnit(file1.toUri(), null, AST.JLS21, compilerOptions, false);
		cache.getCompilationUnit(file2.toUri(), null, AST.JLS21, compilerOptions, false);

		assertEquals(2, cache.size());

		// Clear all
		cache.clear();
		assertEquals(0, cache.size());
	}

	@Test
	public void testNonExistentFile() {
		URI fakeUri = new File(tempDir.toFile(), "DoesNotExist.java").toURI();

		CompilationUnit unit = cache.getCompilationUnit(fakeUri, null, AST.JLS21, compilerOptions, false);

		assertNull(unit);
		assertEquals(0, cache.size());
	}

	@Test
	public void testProblemsAreCached() throws IOException {
		// Create a file with syntax errors
		Path sourceFile = createTestFile("ErrorClass.java",
			"public class ErrorClass {\n" +
			"    public void method() {\n" +
			"        undefinedVariable = 5;\n" +
			"    }\n" +
			"}\n");

		URI fileUri = sourceFile.toUri();

		CompilationUnit unit = cache.getCompilationUnit(fileUri, null, AST.JLS21, compilerOptions, false);

		assertNotNull(unit);
		assertNotNull(unit.getProblems());
		assertTrue("Expected problems in unit", unit.getProblems().length > 0);
	}

	/**
	 * Helper to create a test file with given content.
	 */
	private Path createTestFile(String name, String content) throws IOException {
		Path file = tempDir.resolve(name);
		Files.write(file, content.getBytes());
		return file;
	}
}
