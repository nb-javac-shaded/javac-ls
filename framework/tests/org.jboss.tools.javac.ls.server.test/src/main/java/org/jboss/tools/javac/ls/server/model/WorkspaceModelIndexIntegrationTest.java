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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;

import org.jboss.tools.javac.ls.index.model.FieldDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.Location;
import org.jboss.tools.javac.ls.index.model.MethodDeclarationEntry;
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

	@Test
	public void testIndexSingleProject() throws IOException {
		// Create a test project directory
		File projectDir = new File(tempDir, "test-project");
		assertTrue("Should create project dir", projectDir.mkdirs());

		// Create Java source files
		File srcDir = new File(projectDir, "src");
		assertTrue("Should create src dir", srcDir.mkdirs());

		String javaSource1 = "package com.example;\n\n" +
				"public class MyClass {\n" +
				"	private String name;\n\n" +
				"	public String getName() {\n" +
				"		return name;\n" +
				"	}\n" +
				"}\n";

		String javaSource2 = "package com.example;\n\n" +
				"public interface MyInterface {\n" +
				"	void doSomething();\n" +
				"}\n";

		File javaFile1 = new File(srcDir, "MyClass.java");
		Files.write(javaFile1.toPath(), javaSource1.getBytes(StandardCharsets.UTF_8));

		File javaFile2 = new File(srcDir, "MyInterface.java");
		Files.write(javaFile2.toPath(), javaSource2.getBytes(StandardCharsets.UTF_8));

		// Add project to workspace
		assertTrue("Should add project", model.addProject("test-project", projectDir.getAbsolutePath()));

		// Index the project
		int filesIndexed = model.indexProject("test-project");
		assertEquals("Should have indexed 2 files", 2, filesIndexed);

		// Verify types were indexed
		JavaIndexCache indexCache = model.getIndexCache();
		indexCache.lockRead();
		try {
			JavaIndex index = indexCache.getIndex();

			TypeDeclarationEntry myClass = index.getType("com.example.MyClass");
			assertNotNull("MyClass should be indexed", myClass);
			assertEquals("MyClass", myClass.getSimpleName());
			assertEquals(TypeKind.CLASS, myClass.getKind());

			TypeDeclarationEntry myInterface = index.getType("com.example.MyInterface");
			assertNotNull("MyInterface should be indexed", myInterface);
			assertEquals("MyInterface", myInterface.getSimpleName());
			assertEquals(TypeKind.INTERFACE, myInterface.getKind());

			// Verify fields were indexed
			Collection<FieldDeclarationEntry> fields = index.findFieldsInType("com.example.MyClass");
			assertEquals("Should have 1 field", 1, fields.size());
			assertEquals("name", fields.iterator().next().getFieldName());

			// Verify methods were indexed
			Collection<MethodDeclarationEntry> methods = index.findMethodsInType("com.example.MyClass");
			assertTrue("Should have methods", methods.size() >= 1);
		} finally {
			indexCache.unlockRead();
		}

		assertTrue("Index should be dirty after indexing", indexCache.isDirty());
	}

	@Test
	public void testIndexAllProjects() throws IOException {
		// Create two test projects
		File project1Dir = new File(tempDir, "project1");
		File project2Dir = new File(tempDir, "project2");
		assertTrue("Should create project1 dir", project1Dir.mkdirs());
		assertTrue("Should create project2 dir", project2Dir.mkdirs());

		// Create Java files in project1
		File src1Dir = new File(project1Dir, "src");
		assertTrue("Should create src dir", src1Dir.mkdirs());
		String source1 = "package p1; public class Class1 {}";
		Files.write(new File(src1Dir, "Class1.java").toPath(), source1.getBytes(StandardCharsets.UTF_8));

		// Create Java files in project2
		File src2Dir = new File(project2Dir, "src");
		assertTrue("Should create src dir", src2Dir.mkdirs());
		String source2 = "package p2; public class Class2 {}";
		Files.write(new File(src2Dir, "Class2.java").toPath(), source2.getBytes(StandardCharsets.UTF_8));

		// Add projects to workspace
		assertTrue("Should add project1", model.addProject("project1", project1Dir.getAbsolutePath()));
		assertTrue("Should add project2", model.addProject("project2", project2Dir.getAbsolutePath()));

		// Index all projects
		model.indexAllProjects();

		// Verify both classes were indexed
		JavaIndexCache indexCache = model.getIndexCache();
		indexCache.lockRead();
		try {
			JavaIndex index = indexCache.getIndex();
			assertNotNull("Class1 should be indexed", index.getType("p1.Class1"));
			assertNotNull("Class2 should be indexed", index.getType("p2.Class2"));
			assertEquals("Should have 2 types", 2, index.getTypeCount());
		} finally {
			indexCache.unlockRead();
		}
	}

	@Test
	public void testIndexNonExistentProject() {
		int filesIndexed = model.indexProject("non-existent");
		assertEquals("Should index 0 files for non-existent project", 0, filesIndexed);
	}

	@Test
	public void testReindexingRemovesOldDeclarations() throws IOException {
		// Create project with a Java file
		File projectDir = new File(tempDir, "test-project");
		File srcDir = new File(projectDir, "src");
		assertTrue("Should create dirs", srcDir.mkdirs());

		File javaFile = new File(srcDir, "Test.java");
		String originalSource = "package test; public class Test { private int field1; }";
		Files.write(javaFile.toPath(), originalSource.getBytes(StandardCharsets.UTF_8));

		model.addProject("test-project", projectDir.getAbsolutePath());
		model.indexProject("test-project");

		// Verify original field
		JavaIndexCache indexCache = model.getIndexCache();
		indexCache.lockRead();
		try {
			JavaIndex index = indexCache.getIndex();
			Collection<FieldDeclarationEntry> fields = index.findFieldsInType("test.Test");
			assertEquals("Should have 1 field", 1, fields.size());
			assertEquals("field1", fields.iterator().next().getFieldName());
		} finally {
			indexCache.unlockRead();
		}

		// Modify the file (change field)
		String modifiedSource = "package test; public class Test { private String field2; }";
		Files.write(javaFile.toPath(), modifiedSource.getBytes(StandardCharsets.UTF_8));

		// Re-index
		model.indexProject("test-project");

		// Verify old field is gone and new field exists
		indexCache.lockRead();
		try {
			JavaIndex index = indexCache.getIndex();
			Collection<FieldDeclarationEntry> fields = index.findFieldsInType("test.Test");
			assertEquals("Should still have 1 field", 1, fields.size());
			assertEquals("field2", fields.iterator().next().getFieldName());
		} finally {
			indexCache.unlockRead();
		}
	}
}
