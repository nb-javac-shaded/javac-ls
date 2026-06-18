/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.model.classpath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jboss.tools.javac.ls.server.model.WorkspaceProject;
import org.jboss.tools.javac.ls.server.model.classpath.IJavacClasspathEntry.EntryType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ClasspathCacheTest {

	private File tempDir;
	private ClasspathCache cache;

	@Before
	public void setup() throws IOException {
		tempDir = Files.createTempDirectory("ClasspathCacheTest").toFile();
		cache = new ClasspathCache(tempDir);
	}

	@After
	public void teardown() {
		deleteRecursively(tempDir);
	}

	private void deleteRecursively(File file) {
		if (file.isDirectory()) {
			File[] files = file.listFiles();
			if (files != null) {
				for (File f : files) {
					deleteRecursively(f);
				}
			}
		}
		file.delete();
	}

	@Test
	public void testCacheDirectoryCreated() {
		File cacheDir = cache.getCacheDirectory();
		assertNotNull(cacheDir);
		assertTrue(cacheDir.exists());
		assertTrue(cacheDir.isDirectory());
	}

	@Test
	public void testSaveAndLoadCache() {
		ArrayList<IJavacClasspathEntry> entries = new ArrayList<>();
		entries.add(new JavacClasspathEntry(EntryType.SOURCE, "/src/main/java"));
		entries.add(new JavacClasspathEntry(EntryType.LIBRARY, "/lib/foo.jar"));

		cache.saveCache("test-project", entries);

		ArrayList<IJavacClasspathEntry> loaded = cache.loadCache("test-project");
		assertNotNull(loaded);
		assertEquals(2, loaded.size());
		assertEquals(EntryType.SOURCE, loaded.get(0).getType());
		assertEquals("/src/main/java", loaded.get(0).getPath());
		assertEquals(EntryType.LIBRARY, loaded.get(1).getType());
		assertEquals("/lib/foo.jar", loaded.get(1).getPath());
	}

	@Test
	public void testLoadNonExistentCache() {
		ArrayList<IJavacClasspathEntry> loaded = cache.loadCache("nonexistent-project");
		assertNull(loaded);
	}

	@Test
	public void testClearCache() {
		ArrayList<IJavacClasspathEntry> entries = new ArrayList<>();
		entries.add(new JavacClasspathEntry(EntryType.SOURCE, "/src"));

		cache.saveCache("test-project", entries);
		assertTrue(cache.clearCache("test-project"));

		ArrayList<IJavacClasspathEntry> loaded = cache.loadCache("test-project");
		assertNull(loaded);
	}

	@Test
	public void testClearNonExistentCache() {
		assertFalse(cache.clearCache("nonexistent-project"));
	}

	@Test
	public void testCacheValidation() throws IOException, InterruptedException {
		// Create a source file
		File pomFile = new File(tempDir, "pom.xml");
		pomFile.createNewFile();

		WorkspaceProject proj = new WorkspaceProject("test", tempDir.getAbsolutePath());

		// Save cache
		ArrayList<IJavacClasspathEntry> entries = new ArrayList<>();
		entries.add(new JavacClasspathEntry(EntryType.SOURCE, "/src"));
		cache.saveCache("test", entries);

		// Cache should be valid (newer than pom.xml)
		List<File> sourceFiles = Arrays.asList(pomFile);
		assertTrue(cache.isCacheValid(proj, sourceFiles));

		// Touch pom.xml to make it newer
		Thread.sleep(10); // Ensure time difference
		pomFile.setLastModified(System.currentTimeMillis());

		// Cache should now be invalid
		assertFalse(cache.isCacheValid(proj, sourceFiles));
	}

	@Test
	public void testCacheValidationNoCache() {
		File pomFile = new File(tempDir, "pom.xml");
		WorkspaceProject proj = new WorkspaceProject("test", tempDir.getAbsolutePath());
		List<File> sourceFiles = Arrays.asList(pomFile);

		// No cache exists, should be invalid
		assertFalse(cache.isCacheValid(proj, sourceFiles));
	}

	@Test
	public void testEmptyCache() {
		ArrayList<IJavacClasspathEntry> entries = new ArrayList<>();
		cache.saveCache("empty-project", entries);

		ArrayList<IJavacClasspathEntry> loaded = cache.loadCache("empty-project");
		assertNotNull(loaded);
		assertEquals(0, loaded.size());
	}

	@Test
	public void testProjectNameSanitization() {
		// Project names with special characters should be sanitized
		ArrayList<IJavacClasspathEntry> entries = new ArrayList<>();
		entries.add(new JavacClasspathEntry(EntryType.SOURCE, "/src"));

		cache.saveCache("project:with/special\\chars", entries);

		ArrayList<IJavacClasspathEntry> loaded = cache.loadCache("project:with/special\\chars");
		assertNotNull(loaded);
		assertEquals(1, loaded.size());
	}
}
