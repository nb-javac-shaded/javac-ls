/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.jboss.tools.javac.ls.server.index;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

import org.jboss.tools.javac.ls.index.model.Location;
import org.jboss.tools.javac.ls.index.model.TypeDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.TypeDeclarationEntry.TypeKind;
import org.jboss.tools.javac.ls.index.store.JavaIndex;
import org.jboss.tools.javac.ls.server.index.JavaIndexCache.IndexStats;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class JavaIndexCacheTest {

	private Path tempDir;
	private JavaIndexCache cache;

	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("index-cache-test");
		cache = new JavaIndexCache(tempDir);
	}

	@After
	public void tearDown() throws IOException {
		if (tempDir != null && Files.exists(tempDir)) {
			Files.walk(tempDir)
				.sorted(Comparator.reverseOrder())
				.forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException e) {
						// Ignore
					}
				});
		}
	}

	@Test
	public void testLoadFromNonExistentDirectory() {
		assertFalse("Should return false when no persisted data exists", cache.load());
		assertFalse("Cache should not exist", cache.exists());
		assertFalse("Should not be dirty initially", cache.isDirty());
	}

	@Test
	public void testSaveAndLoad() {
		// Add some data to the index
		cache.lockWrite();
		try {
			JavaIndex index = cache.getIndex();
			TypeDeclarationEntry type = new TypeDeclarationEntry();
			type.setQualifiedName("com.example.MyClass");
			type.setSimpleName("MyClass");
			type.setPackageName("com.example");
			type.setKind(TypeKind.CLASS);
			index.addType(type);
		} finally {
			cache.unlockWrite();
		}

		cache.markDirty();
		assertTrue("Should be dirty after adding data", cache.isDirty());
		assertEquals("Should have 1 type", 1, cache.getStats().getTypeCount());

		// Save the cache
		assertTrue("Save should succeed", cache.save());
		assertFalse("Should not be dirty after save", cache.isDirty());
		assertTrue("Cache should exist after save", cache.exists());

		// Create new cache instance and load
		JavaIndexCache newCache = new JavaIndexCache(tempDir);
		assertTrue("Load should succeed", newCache.load());
		assertFalse("Loaded cache should not be dirty", newCache.isDirty());

		newCache.lockRead();
		try {
			JavaIndex loadedIndex = newCache.getIndex();
			TypeDeclarationEntry loadedType = loadedIndex.getType("com.example.MyClass");
			assertNotNull("Type should be loaded", loadedType);
			assertEquals("MyClass", loadedType.getSimpleName());
			assertEquals("com.example", loadedType.getPackageName());
			assertEquals(TypeKind.CLASS, loadedType.getKind());
		} finally {
			newCache.unlockRead();
		}
	}

	@Test
	public void testManualDirtyTracking() {
		assertFalse("Should not be dirty initially", cache.isDirty());

		// Modify the index
		cache.lockWrite();
		try {
			JavaIndex index = cache.getIndex();
			TypeDeclarationEntry type = new TypeDeclarationEntry();
			type.setQualifiedName("com.example.Test");
			index.addType(type);
		} finally {
			cache.unlockWrite();
		}

		assertFalse("Should not be dirty without explicit markDirty()", cache.isDirty());

		// Mark dirty manually
		cache.markDirty();
		assertTrue("Should be dirty after markDirty()", cache.isDirty());
	}

	@Test
	public void testSaveSkipsIfNotDirty() {
		// Save empty cache
		assertTrue("First save should succeed", cache.save());
		assertFalse("Should not be dirty after save", cache.isDirty());

		// Save again without changes
		assertTrue("Second save should succeed (but skip actual save)", cache.save());
		assertFalse("Should still not be dirty", cache.isDirty());
	}

	@Test
	public void testForceSave() {
		// Save empty cache
		assertTrue("First save should succeed", cache.save());
		assertFalse("Should not be dirty after save", cache.isDirty());

		// Force save even though not dirty
		assertTrue("Force save should succeed", cache.save(true));
		assertFalse("Should not be dirty after forced save", cache.isDirty());
	}

	@Test
	public void testRemoveFile() {
		Path testFile = Paths.get("/test/Example.java");

		// Add data with file tracking
		cache.lockWrite();
		try {
			JavaIndex index = cache.getIndex();
			TypeDeclarationEntry type = new TypeDeclarationEntry();
			type.setQualifiedName("com.example.Test");
			type.setLocation(new Location(testFile, 0, 100, 1, 1));
			index.addType(type);

			Set<String> declaredTypes = new HashSet<>();
			declaredTypes.add("com.example.Test");
			index.trackFileDeclaredTypes(testFile, declaredTypes);
		} finally {
			cache.unlockWrite();
		}

		cache.markDirty();
		assertEquals("Should have 1 type", 1, cache.getStats().getTypeCount());
		assertTrue("Should be dirty", cache.isDirty());

		// Remove file
		cache.removeFile(testFile);

		assertEquals("Should have 0 types after removeFile", 0, cache.getStats().getTypeCount());
		assertTrue("Should be dirty after removeFile", cache.isDirty());
	}

	@Test
	public void testClearPersisted() throws IOException {
		// Add data and save
		cache.lockWrite();
		try {
			JavaIndex index = cache.getIndex();
			TypeDeclarationEntry type = new TypeDeclarationEntry();
			type.setQualifiedName("com.example.Test");
			index.addType(type);
		} finally {
			cache.unlockWrite();
		}

		cache.markDirty();
		cache.save();
		assertTrue("Cache should exist after save", cache.exists());

		// Clear persisted only (not in-memory)
		cache.clearPersisted();

		assertEquals("Should still have 1 type in memory", 1, cache.getStats().getTypeCount());
		assertFalse("Cache should not exist on disk after clearPersisted", cache.exists());
	}

	@Test
	public void testGetStats() {
		cache.lockWrite();
		try {
			JavaIndex index = cache.getIndex();

			TypeDeclarationEntry type1 = new TypeDeclarationEntry();
			type1.setQualifiedName("com.example.Type1");
			index.addType(type1);

			TypeDeclarationEntry type2 = new TypeDeclarationEntry();
			type2.setQualifiedName("com.example.Type2");
			index.addType(type2);
		} finally {
			cache.unlockWrite();
		}

		cache.markDirty();

		IndexStats stats = cache.getStats();
		assertNotNull("Stats should not be null", stats);
		assertEquals("Should have 2 types", 2, stats.getTypeCount());
		assertTrue("Should be dirty", stats.isDirty());

		cache.save();
		stats = cache.getStats();
		assertFalse("Should not be dirty after save", stats.isDirty());
	}

	@Test
	public void testGetPersistedTimestamp() {
		assertEquals("Timestamp should be 0 initially", 0, cache.getPersistedTimestamp());

		// Add data and save
		cache.lockWrite();
		try {
			JavaIndex index = cache.getIndex();
			TypeDeclarationEntry type = new TypeDeclarationEntry();
			type.setQualifiedName("com.example.Test");
			index.addType(type);
		} finally {
			cache.unlockWrite();
		}

		cache.markDirty();
		cache.save();

		long timestamp = cache.getPersistedTimestamp();
		assertTrue("Timestamp should be positive after save", timestamp > 0);
	}

	@Test
	public void testThreadSafety() throws InterruptedException {
		final int numThreads = 10;
		final int operationsPerThread = 100;

		Thread[] threads = new Thread[numThreads];
		for (int i = 0; i < numThreads; i++) {
			final int threadId = i;
			threads[i] = new Thread(() -> {
				for (int j = 0; j < operationsPerThread; j++) {
					cache.lockWrite();
					try {
						JavaIndex index = cache.getIndex();
						TypeDeclarationEntry type = new TypeDeclarationEntry();
						type.setQualifiedName("com.example.Type" + threadId + "_" + j);
						index.addType(type);
					} finally {
						cache.unlockWrite();
					}
				}
			});
			threads[i].start();
		}

		// Wait for all threads to complete
		for (Thread thread : threads) {
			thread.join();
		}

		cache.markDirty();

		cache.lockRead();
		try {
			int expectedTypes = numThreads * operationsPerThread;
			assertEquals("Should have all types added by threads", expectedTypes, cache.getIndex().getTypeCount());
		} finally {
			cache.unlockRead();
		}
	}

	@Test
	public void testRoundTripWithComplexData() {
		// Add complex data
		cache.lockWrite();
		try {
			JavaIndex index = cache.getIndex();

			// Add type with full details
			TypeDeclarationEntry type = new TypeDeclarationEntry();
			type.setQualifiedName("com.example.ComplexClass");
			type.setSimpleName("ComplexClass");
			type.setPackageName("com.example");
			type.setKind(TypeKind.CLASS);
			type.setSuperclass("java.lang.Object");
			type.setInterfaces(Arrays.asList("java.io.Serializable", "java.lang.Comparable"));
			type.setLocation(new Location(Paths.get("/test/Complex.java"), 100, 500, 5, 10));
			index.addType(type);
		} finally {
			cache.unlockWrite();
		}

		cache.markDirty();
		// Save
		assertTrue("Save should succeed", cache.save());

		// Load in new cache
		JavaIndexCache newCache = new JavaIndexCache(tempDir);
		assertTrue("Load should succeed", newCache.load());

		newCache.lockRead();
		try {
			JavaIndex loadedIndex = newCache.getIndex();
			TypeDeclarationEntry loadedType = loadedIndex.getType("com.example.ComplexClass");
			assertNotNull("Type should be loaded", loadedType);
			assertEquals("ComplexClass", loadedType.getSimpleName());
			assertEquals("com.example", loadedType.getPackageName());
			assertEquals(TypeKind.CLASS, loadedType.getKind());
			assertEquals("java.lang.Object", loadedType.getSuperclass());
			assertEquals(2, loadedType.getInterfaces().size());
			assertTrue(loadedType.getInterfaces().contains("java.io.Serializable"));
			assertTrue(loadedType.getInterfaces().contains("java.lang.Comparable"));
			assertNotNull("Location should be preserved", loadedType.getLocation());
		} finally {
			newCache.unlockRead();
		}
	}

	@Test
	public void testStatsToString() {
		IndexStats stats = new IndexStats(10, 50, 25, true);
		String str = stats.toString();
		assertTrue("Should contain type count", str.contains("types=10"));
		assertTrue("Should contain method count", str.contains("methods=50"));
		assertTrue("Should contain field count", str.contains("fields=25"));
		assertTrue("Should contain dirty flag", str.contains("dirty=true"));
	}

	@Test
	public void testLockingPatterns() {
		// Test read lock
		cache.lockRead();
		try {
			JavaIndex index = cache.getIndex();
			assertNotNull("Should be able to get index with read lock", index);
		} finally {
			cache.unlockRead();
		}

		// Test write lock
		cache.lockWrite();
		try {
			JavaIndex index = cache.getIndex();
			TypeDeclarationEntry type = new TypeDeclarationEntry();
			type.setQualifiedName("com.example.Test");
			index.addType(type);
		} finally {
			cache.unlockWrite();
		}

		cache.markDirty();
		assertEquals("Should have 1 type", 1, cache.getStats().getTypeCount());
	}
}
