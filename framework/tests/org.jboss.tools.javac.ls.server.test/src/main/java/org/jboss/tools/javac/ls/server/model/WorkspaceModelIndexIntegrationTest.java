/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.model;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;

import org.jboss.tools.javac.ls.index.model.Location;
import org.jboss.tools.javac.ls.index.model.TypeDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.TypeDeclarationEntry.TypeKind;
import org.jboss.tools.javac.ls.index.store.JavaIndex;
import org.jboss.tools.javac.ls.server.index.JavaIndexCache;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkspaceModelIndexIntegrationTest {

	private File tempDir;
	private WorkspaceModel model;

	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("workspace-index-test").toFile();
		model = new WorkspaceModel(tempDir);
	}

	@After
	public void tearDown() throws IOException {
		if (model != null) {
			model.shutdown();
		}
		if (tempDir != null && tempDir.exists()) {
			deleteDirectory(tempDir.toPath());
		}
	}

	private void deleteDirectory(Path path) throws IOException {
		if (Files.exists(path)) {
			Files.walk(path)
				.sorted(Comparator.reverseOrder())
				.forEach(p -> {
					try {
						Files.deleteIfExists(p);
					} catch (IOException e) {
						// Ignore
					}
				});
		}
	}

	@Test
	public void testIndexCacheAvailable() {
		JavaIndexCache indexCache = model.getIndexCache();
		assertNotNull("Index cache should be available", indexCache);
	}

	@Test
	public void testIndexCacheInitialized() {
		JavaIndexCache indexCache = model.getIndexCache();
		assertNotNull("Index cache should not be null", indexCache);
		assertFalse("Index should not be dirty initially", indexCache.isDirty());
	}

	@Test
	public void testIndexPersistsAcrossWorkspaceInstances() {
		// Add data to index
		JavaIndexCache indexCache = model.getIndexCache();
		indexCache.lockWrite();
		try {
			JavaIndex index = indexCache.getIndex();
			TypeDeclarationEntry type = new TypeDeclarationEntry();
			type.setQualifiedName("com.example.MyClass");
			type.setSimpleName("MyClass");
			type.setPackageName("com.example");
			type.setKind(TypeKind.CLASS);
			type.setSuperclass("java.lang.Object");
			type.setInterfaces(Arrays.asList("java.io.Serializable"));
			type.setLocation(new Location(Paths.get("/test/MyClass.java"), 0, 100, 1, 1));
			index.addType(type);
		} finally {
			indexCache.unlockWrite();
		}

		indexCache.markDirty();
		assertEquals("Should have 1 type", 1, indexCache.getStats().getTypeCount());

		// Shutdown (should save)
		model.shutdown();

		// Create new workspace instance (should load)
		WorkspaceModel newModel = new WorkspaceModel(tempDir);
		try {
			JavaIndexCache newIndexCache = newModel.getIndexCache();
			assertNotNull("New index cache should not be null", newIndexCache);

			newIndexCache.lockRead();
			try {
				JavaIndex loadedIndex = newIndexCache.getIndex();
				TypeDeclarationEntry loadedType = loadedIndex.getType("com.example.MyClass");
				assertNotNull("Type should be loaded", loadedType);
				assertEquals("MyClass", loadedType.getSimpleName());
				assertEquals("com.example", loadedType.getPackageName());
				assertEquals(TypeKind.CLASS, loadedType.getKind());
				assertEquals("java.lang.Object", loadedType.getSuperclass());
				assertEquals(1, loadedType.getInterfaces().size());
				assertTrue(loadedType.getInterfaces().contains("java.io.Serializable"));
			} finally {
				newIndexCache.unlockRead();
			}
		} finally {
			newModel.shutdown();
		}
	}

	@Test
	public void testMultipleProjectsWithIndex() {
		// Add projects
		assertTrue("Should add project1", model.addProject("project1", "/path/to/project1"));
		assertTrue("Should add project2", model.addProject("project2", "/path/to/project2"));

		// Add types for different projects
		JavaIndexCache indexCache = model.getIndexCache();
		indexCache.lockWrite();
		try {
			JavaIndex index = indexCache.getIndex();

			TypeDeclarationEntry type1 = new TypeDeclarationEntry();
			type1.setQualifiedName("project1.Class1");
			type1.setSimpleName("Class1");
			type1.setPackageName("project1");
			type1.setKind(TypeKind.CLASS);
			index.addType(type1);

			TypeDeclarationEntry type2 = new TypeDeclarationEntry();
			type2.setQualifiedName("project2.Class2");
			type2.setSimpleName("Class2");
			type2.setPackageName("project2");
			type2.setKind(TypeKind.CLASS);
			index.addType(type2);
		} finally {
			indexCache.unlockWrite();
		}

		indexCache.markDirty();
		assertEquals("Should have 2 types", 2, indexCache.getStats().getTypeCount());

		// Verify both types are accessible
		indexCache.lockRead();
		try {
			JavaIndex index = indexCache.getIndex();
			assertNotNull("Should find project1.Class1", index.getType("project1.Class1"));
			assertNotNull("Should find project2.Class2", index.getType("project2.Class2"));
		} finally {
			indexCache.unlockRead();
		}
	}

	@Test
	public void testIndexSavedOnShutdown() {
		JavaIndexCache indexCache = model.getIndexCache();

		// Add data
		indexCache.lockWrite();
		try {
			JavaIndex index = indexCache.getIndex();
			TypeDeclarationEntry type = new TypeDeclarationEntry();
			type.setQualifiedName("com.example.Test");
			index.addType(type);
		} finally {
			indexCache.unlockWrite();
		}

		indexCache.markDirty();
		assertTrue("Should be dirty before shutdown", indexCache.isDirty());

		// Shutdown should save
		model.shutdown();

		// Verify index was saved by checking if files exist
		File indexDir = new File(tempDir, "index");
		File typesFile = new File(indexDir, "types.json");
		assertTrue("Types file should exist after shutdown", typesFile.exists());
	}

	@Test
	public void testIndexStatsAfterWorkspaceLoad() {
		// Add data and shutdown
		JavaIndexCache indexCache = model.getIndexCache();
		indexCache.lockWrite();
		try {
			JavaIndex index = indexCache.getIndex();
			for (int i = 0; i < 5; i++) {
				TypeDeclarationEntry type = new TypeDeclarationEntry();
				type.setQualifiedName("com.example.Type" + i);
				index.addType(type);
			}
		} finally {
			indexCache.unlockWrite();
		}

		indexCache.markDirty();
		model.shutdown();

		// Load new workspace
		WorkspaceModel newModel = new WorkspaceModel(tempDir);
		try {
			JavaIndexCache newIndexCache = newModel.getIndexCache();
			assertEquals("Should have 5 types", 5, newIndexCache.getStats().getTypeCount());
			assertFalse("Should not be dirty after load", newIndexCache.getStats().isDirty());
		} finally {
			newModel.shutdown();
		}
	}
}
