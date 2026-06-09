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
import java.util.*;

import org.jboss.tools.javac.ls.index.model.FieldDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.Location;
import org.jboss.tools.javac.ls.index.model.MethodDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.ReferenceEntry;
import org.jboss.tools.javac.ls.index.model.TypeDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.TypeDeclarationEntry.TypeKind;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class JsonIndexPersistenceTest {

	private Path tempDir;
	private JsonIndexPersistence persistence;

	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("json-index-test");
		persistence = new JsonIndexPersistence(tempDir);
	}

	@After
	public void tearDown() throws IOException {
		// Clean up temp directory
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
	public void testSaveAndLoadTypes() throws IOException {
		Map<String, TypeDeclarationEntry> types = new HashMap<>();

		TypeDeclarationEntry type1 = new TypeDeclarationEntry();
		type1.setQualifiedName("com.example.MyClass");
		type1.setSimpleName("MyClass");
		type1.setPackageName("com.example");
		type1.setKind(TypeKind.CLASS);
		type1.setSuperclass("java.lang.Object");
		type1.setInterfaces(Arrays.asList("java.io.Serializable"));
		types.put("com.example.MyClass", type1);

		TypeDeclarationEntry type2 = new TypeDeclarationEntry();
		type2.setQualifiedName("com.example.MyInterface");
		type2.setSimpleName("MyInterface");
		type2.setPackageName("com.example");
		type2.setKind(TypeKind.INTERFACE);
		types.put("com.example.MyInterface", type2);

		persistence.saveTypes(types);

		Map<String, TypeDeclarationEntry> loaded = persistence.loadTypes();
		assertEquals("Should have 2 types", 2, loaded.size());

		TypeDeclarationEntry loadedType1 = loaded.get("com.example.MyClass");
		assertNotNull("MyClass should be loaded", loadedType1);
		assertEquals("MyClass", loadedType1.getSimpleName());
		assertEquals("com.example", loadedType1.getPackageName());
		assertEquals(TypeKind.CLASS, loadedType1.getKind());
		assertEquals("java.lang.Object", loadedType1.getSuperclass());
		assertEquals(1, loadedType1.getInterfaces().size());
		assertTrue(loadedType1.getInterfaces().contains("java.io.Serializable"));

		TypeDeclarationEntry loadedType2 = loaded.get("com.example.MyInterface");
		assertNotNull("MyInterface should be loaded", loadedType2);
		assertEquals(TypeKind.INTERFACE, loadedType2.getKind());
	}

	@Test
	public void testLoadTypesFromNonExistentFile() throws IOException {
		Map<String, TypeDeclarationEntry> loaded = persistence.loadTypes();
		assertNotNull("Should return empty map", loaded);
		assertEquals("Map should be empty", 0, loaded.size());
	}

	@Test
	public void testSaveAndLoadSubtypes() throws IOException {
		Map<String, Set<String>> subtypes = new HashMap<>();
		Set<String> animalSubtypes = new HashSet<>(Arrays.asList("com.example.Dog", "com.example.Cat"));
		subtypes.put("com.example.Animal", animalSubtypes);

		persistence.saveSubtypes(subtypes);

		Map<String, Set<String>> loaded = persistence.loadSubtypes();
		assertEquals("Should have 1 entry", 1, loaded.size());

		Set<String> loadedSubtypes = loaded.get("com.example.Animal");
		assertNotNull("Should have subtypes for Animal", loadedSubtypes);
		assertEquals("Should have 2 subtypes", 2, loadedSubtypes.size());
		assertTrue("Should contain Dog", loadedSubtypes.contains("com.example.Dog"));
		assertTrue("Should contain Cat", loadedSubtypes.contains("com.example.Cat"));
	}

	@Test
	public void testSaveAndLoadImplementors() throws IOException {
		Map<String, Set<String>> implementors = new HashMap<>();
		Set<String> runnableImpls = new HashSet<>(Arrays.asList("com.example.MyTask"));
		implementors.put("java.lang.Runnable", runnableImpls);

		persistence.saveImplementors(implementors);

		Map<String, Set<String>> loaded = persistence.loadImplementors();
		assertEquals("Should have 1 entry", 1, loaded.size());

		Set<String> loadedImpls = loaded.get("java.lang.Runnable");
		assertNotNull("Should have implementors for Runnable", loadedImpls);
		assertEquals("Should have 1 implementor", 1, loadedImpls.size());
		assertTrue("Should contain MyTask", loadedImpls.contains("com.example.MyTask"));
	}

	@Test
	public void testSaveAndLoadTypeReferences() throws IOException {
		Map<String, List<ReferenceEntry>> typeReferences = new HashMap<>();

		Path file = Paths.get("/test/Example.java");
		List<ReferenceEntry> refs = new ArrayList<>();
		refs.add(new ReferenceEntry(
			new Location(file, 10, 20, 1, 5),
			ReferenceEntry.ReferenceKind.TYPE_REFERENCE
		));
		refs.add(new ReferenceEntry(
			new Location(file, 30, 40, 2, 10),
			ReferenceEntry.ReferenceKind.CONSTRUCTOR_INVOCATION
		));

		typeReferences.put("com.example.MyClass", refs);

		persistence.saveTypeReferences(typeReferences);

		Map<String, List<ReferenceEntry>> loaded = persistence.loadTypeReferences();
		assertEquals("Should have 1 entry", 1, loaded.size());

		List<ReferenceEntry> loadedRefs = loaded.get("com.example.MyClass");
		assertNotNull("Should have references for MyClass", loadedRefs);
		assertEquals("Should have 2 references", 2, loadedRefs.size());

		ReferenceEntry ref1 = loadedRefs.get(0);
		assertEquals(ReferenceEntry.ReferenceKind.TYPE_REFERENCE, ref1.getKind());
		assertEquals(10, ref1.getLocation().getStartOffset());
		assertEquals(20, ref1.getLocation().getEndOffset());

		ReferenceEntry ref2 = loadedRefs.get(1);
		assertEquals(ReferenceEntry.ReferenceKind.CONSTRUCTOR_INVOCATION, ref2.getKind());
	}

	@Test
	public void testSaveAndLoadNameReferences() throws IOException {
		Map<String, List<ReferenceEntry>> nameReferences = new HashMap<>();

		Path file = Paths.get("/test/Example.java");
		List<ReferenceEntry> refs = new ArrayList<>();
		refs.add(new ReferenceEntry(
			new Location(file, 15, 25, 1, 8),
			ReferenceEntry.ReferenceKind.NAME_REFERENCE
		));

		nameReferences.put("myVariable", refs);

		persistence.saveNameReferences(nameReferences);

		Map<String, List<ReferenceEntry>> loaded = persistence.loadNameReferences();
		assertEquals("Should have 1 entry", 1, loaded.size());

		List<ReferenceEntry> loadedRefs = loaded.get("myVariable");
		assertNotNull("Should have references for myVariable", loadedRefs);
		assertEquals("Should have 1 reference", 1, loadedRefs.size());
		assertEquals(ReferenceEntry.ReferenceKind.NAME_REFERENCE, loadedRefs.get(0).getKind());
	}

	@Test
	public void testSaveAndLoadMethods() throws IOException {
		Map<String, MethodDeclarationEntry> methods = new HashMap<>();

		MethodDeclarationEntry method = new MethodDeclarationEntry();
		method.setDeclaringType("com.example.MyClass");
		method.setMethodName("doSomething");
		method.setParameterTypes(Arrays.asList("java.lang.String", "int"));
		method.setReturnType("void");
		method.setConstructor(false);
		methods.put(method.getSignatureKey(), method);

		persistence.saveMethods(methods);

		Map<String, MethodDeclarationEntry> loaded = persistence.loadMethods();
		assertEquals("Should have 1 method", 1, loaded.size());

		MethodDeclarationEntry loadedMethod = loaded.values().iterator().next();
		assertEquals("doSomething", loadedMethod.getMethodName());
		assertEquals("com.example.MyClass", loadedMethod.getDeclaringType());
		assertEquals(2, loadedMethod.getParameterTypes().size());
		assertEquals("void", loadedMethod.getReturnType());
		assertFalse(loadedMethod.isConstructor());
	}

	@Test
	public void testSaveAndLoadFields() throws IOException {
		Map<String, FieldDeclarationEntry> fields = new HashMap<>();

		FieldDeclarationEntry field = new FieldDeclarationEntry();
		field.setDeclaringType("com.example.MyClass");
		field.setFieldName("myField");
		field.setFieldType("java.lang.String");
		fields.put(field.getFieldKey(), field);

		persistence.saveFields(fields);

		Map<String, FieldDeclarationEntry> loaded = persistence.loadFields();
		assertEquals("Should have 1 field", 1, loaded.size());

		FieldDeclarationEntry loadedField = loaded.values().iterator().next();
		assertEquals("myField", loadedField.getFieldName());
		assertEquals("com.example.MyClass", loadedField.getDeclaringType());
		assertEquals("java.lang.String", loadedField.getFieldType());
	}

	@Test
	public void testSaveAndLoadFileToDeclaredTypes() throws IOException {
		Map<Path, Set<String>> fileToDeclaredTypes = new HashMap<>();

		Path file1 = Paths.get("/project/src/Example.java");
		Set<String> types1 = new HashSet<>(Arrays.asList("com.example.Example", "com.example.Example$Inner"));
		fileToDeclaredTypes.put(file1, types1);

		Path file2 = Paths.get("/project/src/Other.java");
		Set<String> types2 = new HashSet<>(Arrays.asList("com.example.Other"));
		fileToDeclaredTypes.put(file2, types2);

		persistence.saveFileToDeclaredTypes(fileToDeclaredTypes);

		Map<Path, Set<String>> loaded = persistence.loadFileToDeclaredTypes();
		assertEquals("Should have 2 entries", 2, loaded.size());

		Set<String> loadedTypes1 = loaded.get(file1);
		assertNotNull("Should have types for file1", loadedTypes1);
		assertEquals("Should have 2 types", 2, loadedTypes1.size());
		assertTrue("Should contain Example", loadedTypes1.contains("com.example.Example"));
		assertTrue("Should contain Inner", loadedTypes1.contains("com.example.Example$Inner"));

		Set<String> loadedTypes2 = loaded.get(file2);
		assertNotNull("Should have types for file2", loadedTypes2);
		assertEquals("Should have 1 type", 1, loadedTypes2.size());
		assertTrue("Should contain Other", loadedTypes2.contains("com.example.Other"));
	}

	@Test
	public void testClear() throws IOException {
		// Save some data
		Map<String, TypeDeclarationEntry> types = new HashMap<>();
		TypeDeclarationEntry type = new TypeDeclarationEntry();
		type.setQualifiedName("com.example.Test");
		types.put("com.example.Test", type);
		persistence.saveTypes(types);

		Map<String, Set<String>> subtypes = new HashMap<>();
		subtypes.put("Parent", new HashSet<>(Arrays.asList("Child")));
		persistence.saveSubtypes(subtypes);

		// Verify files exist
		assertTrue("Types file should exist", Files.exists(tempDir.resolve("types.json")));
		assertTrue("Subtypes file should exist", Files.exists(tempDir.resolve("subtypes.json")));

		// Clear
		persistence.clear();

		// Verify files are deleted
		assertFalse("Types file should be deleted", Files.exists(tempDir.resolve("types.json")));
		assertFalse("Subtypes file should be deleted", Files.exists(tempDir.resolve("subtypes.json")));
	}

	@Test
	public void testRoundTripWithComplexData() throws IOException {
		// Create complex type entry with all fields populated
		TypeDeclarationEntry type = new TypeDeclarationEntry();
		type.setQualifiedName("com.example.Complex");
		type.setSimpleName("Complex");
		type.setPackageName("com.example");
		type.setKind(TypeKind.CLASS);
		type.setSuperclass("com.example.Base");
		type.setInterfaces(Arrays.asList("java.io.Serializable", "java.lang.Comparable"));
		type.setModifiers(0x0001 | 0x0400); // PUBLIC | ABSTRACT
		type.setLocation(new Location(Paths.get("/test/Complex.java"), 100, 500, 5, 25));

		Map<String, TypeDeclarationEntry> types = new HashMap<>();
		types.put(type.getQualifiedName(), type);

		persistence.saveTypes(types);
		Map<String, TypeDeclarationEntry> loaded = persistence.loadTypes();

		TypeDeclarationEntry loadedType = loaded.get("com.example.Complex");
		assertNotNull("Type should be loaded", loadedType);
		assertEquals("Complex", loadedType.getSimpleName());
		assertEquals("com.example", loadedType.getPackageName());
		assertEquals(TypeKind.CLASS, loadedType.getKind());
		assertEquals("com.example.Base", loadedType.getSuperclass());
		assertEquals(2, loadedType.getInterfaces().size());
		assertTrue(loadedType.getInterfaces().contains("java.io.Serializable"));
		assertTrue(loadedType.getInterfaces().contains("java.lang.Comparable"));
		assertEquals(0x0001 | 0x0400, loadedType.getModifiers());
		assertNotNull("Location should be preserved", loadedType.getLocation());
		assertEquals(100, loadedType.getLocation().getStartOffset());
		assertEquals(500, loadedType.getLocation().getEndOffset());
	}

	@Test
	public void testExists() throws IOException {
		assertFalse("Should not exist initially", persistence.exists());

		Map<String, TypeDeclarationEntry> types = new HashMap<>();
		TypeDeclarationEntry type = new TypeDeclarationEntry();
		type.setQualifiedName("com.example.Test");
		types.put("com.example.Test", type);
		persistence.saveTypes(types);

		assertTrue("Should exist after saving", persistence.exists());
	}

	@Test
	public void testGetTimestamp() throws IOException {
		assertEquals("Timestamp should be 0 initially", 0, persistence.getTimestamp());

		Map<String, TypeDeclarationEntry> types = new HashMap<>();
		TypeDeclarationEntry type = new TypeDeclarationEntry();
		type.setQualifiedName("com.example.Test");
		types.put("com.example.Test", type);
		persistence.saveTypes(types);

		long timestamp = persistence.getTimestamp();
		assertTrue("Timestamp should be positive after saving", timestamp > 0);
	}
}
