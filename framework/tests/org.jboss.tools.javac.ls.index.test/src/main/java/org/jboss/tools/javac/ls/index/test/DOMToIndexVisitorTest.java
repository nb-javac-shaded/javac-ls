/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.jboss.tools.javac.ls.index.test;

import static org.junit.Assert.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;

import org.jboss.tools.javac.ls.index.model.FieldDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.MethodDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.ReferenceEntry;
import org.jboss.tools.javac.ls.index.model.TypeDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.TypeDeclarationEntry.TypeKind;
import org.jboss.tools.javac.ls.index.store.JavaIndex;
import org.jboss.tools.javac.ls.index.visitor.DOMToIndexVisitor;
import org.jboss.tools.javac.ls.parser.bindings.JavacDOMParser;
import org.junit.Before;
import org.junit.Test;

import shaded.org.eclipse.jdt.core.dom.AST;
import shaded.org.eclipse.jdt.core.dom.CompilationUnit;

public class DOMToIndexVisitorTest {

	private JavaIndex index;
	private JavacDOMParser parser;
	private Path testFile;

	@Before
	public void setUp() {
		index = new JavaIndex();
		parser = new JavacDOMParser();
		testFile = Paths.get("/test/Example.java");
	}

	private void indexSource(String source) {
		CompilationUnit cu = parser.parse(source, testFile.toString(), null, AST.JLS21, null, false);
		DOMToIndexVisitor visitor = new DOMToIndexVisitor(index, testFile);
		cu.accept(visitor);
		visitor.finishIndexing();
	}

	@Test
	public void testSimpleClass() {
		String source = """
			package com.example;

			public class MyClass {
				private String name;

				public String getName() {
					return name;
				}
			}
			""";

		indexSource(source);

		// Verify type was indexed
		TypeDeclarationEntry type = index.getType("com.example.MyClass");
		assertNotNull("Type should be indexed", type);
		assertEquals("Type name should match", "MyClass", type.getSimpleName());
		assertEquals("Package should match", "com.example", type.getPackageName());
		assertEquals("Should be a class", TypeKind.CLASS, type.getKind());

		// Verify field was indexed
		Collection<FieldDeclarationEntry> fields = index.findFieldsInType("com.example.MyClass");
		assertEquals("Should have 1 field", 1, fields.size());
		FieldDeclarationEntry field = fields.iterator().next();
		assertEquals("Field name should be 'name'", "name", field.getFieldName());
		assertEquals("Field type should be String", "String", field.getFieldType());

		// Verify methods were indexed (including default constructor)
		Collection<MethodDeclarationEntry> methods = index.findMethodsInType("com.example.MyClass");
		assertTrue("Should have at least 2 methods (default ctor + getName)", methods.size() >= 2);

		boolean foundConstructor = false;
		boolean foundGetName = false;
		for (MethodDeclarationEntry method : methods) {
			if (method.isConstructor()) {
				foundConstructor = true;
			} else if ("getName".equals(method.getMethodName())) {
				foundGetName = true;
				assertEquals("Return type should be String", "String", method.getReturnType());
			}
		}
		assertTrue("Should have default constructor", foundConstructor);
		assertTrue("Should have getName method", foundGetName);
	}

	@Test
	public void testInterface() {
		String source = """
			package com.example;

			public interface MyInterface {
				void doSomething();
			}
			""";

		indexSource(source);

		TypeDeclarationEntry type = index.getType("com.example.MyInterface");
		assertNotNull("Interface should be indexed", type);
		assertEquals("Should be an interface", TypeKind.INTERFACE, type.getKind());

		Collection<MethodDeclarationEntry> methods = index.findMethodsInType("com.example.MyInterface");
		assertEquals("Should have 1 method", 1, methods.size());
		assertEquals("Method should be doSomething", "doSomething", methods.iterator().next().getMethodName());
	}

	@Test
	public void testEnum() {
		String source = """
			package com.example;

			public enum Color {
				RED, GREEN, BLUE
			}
			""";

		indexSource(source);

		TypeDeclarationEntry type = index.getType("com.example.Color");
		assertNotNull("Enum should be indexed", type);
		assertEquals("Should be an enum", TypeKind.ENUM, type.getKind());
		assertEquals("Superclass should be Enum", "java.lang.Enum", type.getSuperclass());

		Collection<FieldDeclarationEntry> fields = index.findFieldsInType("com.example.Color");
		assertEquals("Should have 3 enum constants", 3, fields.size());
	}

	@Test
	public void testInheritance() {
		String source = """
			package com.example;

			public class Child extends Parent implements Runnable {
			}
			""";

		indexSource(source);

		TypeDeclarationEntry type = index.getType("com.example.Child");
		assertNotNull("Class should be indexed", type);
		assertEquals("Superclass should be Parent", "Parent", type.getSuperclass());
		assertEquals("Should implement 1 interface", 1, type.getInterfaces().size());
		assertTrue("Should implement Runnable", type.getInterfaces().contains("Runnable"));

		// Verify hierarchy is tracked
		Collection<String> subtypes = index.findDirectSubtypes("Parent");
		assertTrue("Should track Child as subtype of Parent", subtypes.contains("com.example.Child"));

		Collection<String> implementors = index.findDirectImplementors("Runnable");
		assertTrue("Should track Child as implementor of Runnable", implementors.contains("com.example.Child"));
	}

	@Test
	public void testConstructorReference() {
		String source = """
			package com.example;

			public class Example {
				public void test() {
					String s = new String("hello");
				}
			}
			""";

		indexSource(source);

		// Verify constructor reference was tracked
		Collection<ReferenceEntry> refs = index.findTypeUsages("String");
		assertTrue("Should have at least 1 reference to String", refs.size() >= 1);

		boolean foundConstructorRef = false;
		for (ReferenceEntry ref : refs) {
			if (ref.getKind() == ReferenceEntry.ReferenceKind.CONSTRUCTOR_INVOCATION) {
				foundConstructorRef = true;
				break;
			}
		}
		assertTrue("Should have constructor reference", foundConstructorRef);
	}

	@Test
	public void testAnnotations() {
		String source = """
			package com.example;

			@Deprecated
			public class OldClass {
				@Override
				public String toString() {
					return "old";
				}
			}
			""";

		indexSource(source);

		// Verify annotation usages were tracked
		Collection<ReferenceEntry> deprecatedRefs = index.findTypeUsages("Deprecated");
		assertTrue("Should have reference to Deprecated", deprecatedRefs.size() >= 1);

		Collection<ReferenceEntry> overrideRefs = index.findTypeUsages("Override");
		assertTrue("Should have reference to Override", overrideRefs.size() >= 1);
	}

	@Test
	public void testNameReferences() {
		String source = """
			package com.example;

			public class Example {
				private int count;

				public void increment() {
					count++;
				}
			}
			""";

		indexSource(source);

		// Verify name references were tracked
		Collection<ReferenceEntry> countRefs = index.findNameUsages("count");
		assertTrue("Should have references to 'count'", countRefs.size() >= 1);
	}

	@Test
	public void testNestedClass() {
		String source = """
			package com.example;

			public class Outer {
				public class Inner {
					private int value;
				}
			}
			""";

		indexSource(source);

		TypeDeclarationEntry outer = index.getType("com.example.Outer");
		assertNotNull("Outer class should be indexed", outer);

		TypeDeclarationEntry inner = index.getType("com.example.Outer$Inner");
		assertNotNull("Inner class should be indexed", inner);

		Collection<FieldDeclarationEntry> innerFields = index.findFieldsInType("com.example.Outer$Inner");
		assertEquals("Inner class should have 1 field", 1, innerFields.size());
	}

	@Test
	public void testRecord() {
		String source = """
			package com.example;

			public record Point(int x, int y) {
			}
			""";

		indexSource(source);

		TypeDeclarationEntry type = index.getType("com.example.Point");
		assertNotNull("Record should be indexed", type);
		assertEquals("Should be a record", TypeKind.RECORD, type.getKind());
		assertEquals("Superclass should be Record", "java.lang.Record", type.getSuperclass());
	}
}
