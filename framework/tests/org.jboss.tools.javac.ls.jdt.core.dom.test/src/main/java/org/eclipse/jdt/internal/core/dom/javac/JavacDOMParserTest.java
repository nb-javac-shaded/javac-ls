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
package org.eclipse.jdt.internal.core.dom.javac;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.jboss.tools.javac.ls.parser.bindings.JavacDOMParser;
import org.junit.Test;

import shaded.org.eclipse.jdt.core.compiler.IProblem;
import shaded.org.eclipse.jdt.core.dom.AST;
import shaded.org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import shaded.org.eclipse.jdt.core.dom.CompilationUnit;
import shaded.org.eclipse.jdt.core.dom.FieldDeclaration;
import shaded.org.eclipse.jdt.core.dom.IMethodBinding;
import shaded.org.eclipse.jdt.core.dom.ITypeBinding;
import shaded.org.eclipse.jdt.core.dom.IVariableBinding;
import shaded.org.eclipse.jdt.core.dom.MethodDeclaration;
import shaded.org.eclipse.jdt.core.dom.TypeDeclaration;
import shaded.org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import shaded.org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import shaded.org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import shaded.org.eclipse.jdt.core.dom.Javadoc;
import shaded.org.eclipse.jdt.core.dom.TagElement;

/**
 * Tests for JavacDOMParser.
 */
public class JavacDOMParserTest {

	@Test
	public void testBasicParsing() {
		String source = """
			package com.example;

			public class HelloWorld {
				public static void main(String[] args) {
					System.out.println("Hello, World!");
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "HelloWorld.java", null, AST.JLS21, null, false);

		assertNotNull("CompilationUnit should not be null", cu);
		assertEquals("Should have package declaration", "com.example", cu.getPackage().getName().getFullyQualifiedName());
		assertEquals("Should have 1 type", 1, cu.types().size());

		TypeDeclaration type = (TypeDeclaration) cu.types().get(0);
		assertEquals("Type name should be HelloWorld", "HelloWorld", type.getName().getIdentifier());
		assertTrue("Type should be public", shaded.org.eclipse.jdt.core.dom.Modifier.isPublic(type.getModifiers()));
	}

	@Test
	public void testMethodParsing() {
		String source = """
			public class Test {
				private int value;

				public int getValue() {
					return value;
				}

				public void setValue(int value) {
					this.value = value;
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "Test.java", null, AST.JLS21, null, false);

		assertNotNull(cu);
		TypeDeclaration type = (TypeDeclaration) cu.types().get(0);

		MethodDeclaration[] methods = type.getMethods();
		assertEquals("Should have 2 methods", 2, methods.length);

		assertEquals("First method should be getValue", "getValue", methods[0].getName().getIdentifier());
		assertEquals("Second method should be setValue", "setValue", methods[1].getName().getIdentifier());
	}

	@Test
	public void testJavadocParsing() {
		String source = """
			/**
			 * A simple test class.
			 *
			 * @author Test Author
			 */
			public class DocTest {
				/**
				 * Gets a value.
				 *
				 * @return the value
				 */
				public int getValue() {
					return 42;
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "DocTest.java", null, AST.JLS21, null, false);

		assertNotNull(cu);
		TypeDeclaration type = (TypeDeclaration) cu.types().get(0);

		assertNotNull("Type should have javadoc", type.getJavadoc());

		MethodDeclaration method = type.getMethods()[0];
		assertNotNull("Method should have javadoc", method.getJavadoc());
	}

	@Test
	public void testCommentsParsing() {
		String source = """
			// Line comment
			public class CommentTest {
				/* Block comment */
				int field;

				/**
				 * Javadoc comment
				 */
				void method() {
					// Another line comment
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "CommentTest.java", null, AST.JLS21, null, false);

		assertNotNull(cu);

		List<?> comments = cu.getCommentList();
		assertNotNull("Should have comments", comments);
		assertTrue("Should have multiple comments", comments.size() >= 3);
	}

	@Test
	public void testSourcePositions() {
		String source = """
			public class PositionTest {
				int field = 42;
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "PositionTest.java", null, AST.JLS21, null, false);

		assertNotNull(cu);
		assertTrue("CompilationUnit should have valid position", cu.getStartPosition() >= 0);
		assertTrue("CompilationUnit should have valid length", cu.getLength() > 0);

		TypeDeclaration type = (TypeDeclaration) cu.types().get(0);
		assertTrue("Type should have valid position", type.getStartPosition() >= 0);
		assertTrue("Type should have valid length", type.getLength() > 0);
	}

	@Test
	public void testNoPackageDeclaration() {
		String source = """
			public class NoPackage {
				void test() {}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "NoPackage.java", null, AST.JLS21, null, false);

		assertNotNull(cu);
		assertEquals("Should have no package", null, cu.getPackage());
		assertEquals("Should have 1 type", 1, cu.types().size());
	}

	@Test
	public void testMultipleTypes() {
		String source = """
			class First {}
			class Second {}
			class Third {}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "Multiple.java", null, AST.JLS21, null, false);

		assertNotNull(cu);
		assertEquals("Should have 3 types", 3, cu.types().size());
	}

	@Test
	public void testGenericsParsing() {
		String source = """
			import java.util.List;

			public class GenericTest<T extends Number> {
				private List<T> items;

				public <E> E getFirst(List<E> list) {
					return list.get(0);
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "GenericTest.java", null, AST.JLS21, null, false);

		assertNotNull(cu);
		TypeDeclaration type = (TypeDeclaration) cu.types().get(0);

		assertNotNull("Type should have type parameters", type.typeParameters());
		assertEquals("Should have 1 type parameter", 1, type.typeParameters().size());
	}

	@Test
	public void testAnnotationsParsing() {
		String source = """
			@Deprecated
			public class AnnotatedClass {
				@Override
				public String toString() {
					return "test";
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "AnnotatedClass.java", null, AST.JLS21, null, false);

		assertNotNull(cu);
		TypeDeclaration type = (TypeDeclaration) cu.types().get(0);

		assertNotNull("Type should have modifiers", type.modifiers());
		assertTrue("Should have at least 2 modifiers (public + @Deprecated)", type.modifiers().size() >= 2);
	}

	@Test
	public void testProblemsReporting() {
		// Code with obvious syntax error
		String source = """
			public class ErrorTest {
				this is not valid java syntax at all!
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "ErrorTest.java", null, AST.JLS21, null, false);

		assertNotNull("CompilationUnit should not be null", cu);

		IProblem[] problems = cu.getProblems();
		assertNotNull("Problems array should not be null", problems);
		assertTrue("Should have at least one problem, got: " + problems.length, problems.length > 0);

		// Verify problem details
		for (IProblem problem : problems) {
			assertNotNull("Problem should not be null", problem);
			assertTrue("Problem should have a message", problem.getMessage() != null && !problem.getMessage().isEmpty());
			assertTrue("Problem should be an error or warning", problem.isError() || problem.isWarning());
		}
	}

	@Test
	public void testBindingResolution() {
		String source = """
			package com.example;

			public class BindingTest {
				private String name;
				private int count;

				public String getName() {
					return name;
				}

				public void setName(String name) {
					this.name = name;
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "BindingTest.java", null, AST.JLS21, null, true);

		assertNotNull("CompilationUnit should not be null", cu);
		assertEquals("Should have 1 type", 1, cu.types().size());

		TypeDeclaration type = (TypeDeclaration) cu.types().get(0);
		assertNotNull("Type should not be null", type);

		// Test type binding
		ITypeBinding typeBinding = type.resolveBinding();
		assertNotNull("Type binding should not be null", typeBinding);
		assertEquals("Type name should be BindingTest", "BindingTest", typeBinding.getName());
		assertEquals("Qualified name should be com.example.BindingTest", "com.example.BindingTest", typeBinding.getQualifiedName());
		assertTrue("Type should be a class", typeBinding.isClass());

		// Test field bindings
		FieldDeclaration[] fields = type.getFields();
		assertEquals("Should have 2 fields", 2, fields.length);

		VariableDeclarationFragment nameField = (VariableDeclarationFragment) fields[0].fragments().get(0);
		IVariableBinding nameBinding = nameField.resolveBinding();
		assertNotNull("Field binding should not be null", nameBinding);
		assertEquals("Field name should be 'name'", "name", nameBinding.getName());
		assertTrue("Field should be a field", nameBinding.isField());

		ITypeBinding fieldType = nameBinding.getType();
		assertNotNull("Field type binding should not be null", fieldType);
		assertEquals("Field type should be String", "String", fieldType.getName());

		// Test method bindings
		MethodDeclaration[] methods = type.getMethods();
		assertEquals("Should have 2 methods", 2, methods.length);

		MethodDeclaration getNameMethod = methods[0];
		IMethodBinding getNameBinding = getNameMethod.resolveBinding();
		assertNotNull("Method binding should not be null", getNameBinding);
		assertEquals("Method name should be 'getName'", "getName", getNameBinding.getName());

		ITypeBinding returnType = getNameBinding.getReturnType();
		assertNotNull("Return type binding should not be null", returnType);
		assertEquals("Return type should be String", "String", returnType.getName());
	}

	@Test
	public void testMethodParameterBindings() {
		String source = """
			package com.example;

			public class ParamTest {
				public void process(String name, int count, Object data) {
					System.out.println(name + count);
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "ParamTest.java", null, AST.JLS21, null, true);

		assertNotNull("CompilationUnit should not be null", cu);
		TypeDeclaration type = (TypeDeclaration) cu.types().get(0);
		MethodDeclaration method = type.getMethods()[0];

		// Test method binding
		IMethodBinding methodBinding = method.resolveBinding();
		assertNotNull("Method binding should not be null", methodBinding);
		assertEquals("Method should have 3 parameters", 3, methodBinding.getParameterTypes().length);

		// Test first parameter (String name)
		SingleVariableDeclaration param0Decl = (SingleVariableDeclaration) method.parameters().get(0);
		IVariableBinding param0 = param0Decl.resolveBinding();
		assertNotNull("Parameter 0 binding should not be null", param0);
		assertEquals("Parameter 0 name should be 'name'", "name", param0.getName());
		assertTrue("Parameter 0 should be a parameter", param0.isParameter());
		assertEquals("Parameter 0 type should be String", "String", param0.getType().getName());

		// Test second parameter (int count)
		SingleVariableDeclaration param1Decl = (SingleVariableDeclaration) method.parameters().get(1);
		IVariableBinding param1 = param1Decl.resolveBinding();
		assertNotNull("Parameter 1 binding should not be null", param1);
		assertEquals("Parameter 1 name should be 'count'", "count", param1.getName());
		assertTrue("Parameter 1 should be a parameter", param1.isParameter());
		assertEquals("Parameter 1 type should be int", "int", param1.getType().getName());

		// Test third parameter (Object data)
		SingleVariableDeclaration param2Decl = (SingleVariableDeclaration) method.parameters().get(2);
		IVariableBinding param2 = param2Decl.resolveBinding();
		assertNotNull("Parameter 2 binding should not be null", param2);
		assertEquals("Parameter 2 name should be 'data'", "data", param2.getName());
		assertTrue("Parameter 2 should be a parameter", param2.isParameter());
		assertEquals("Parameter 2 type should be Object", "Object", param2.getType().getName());
	}

	@Test
	public void testLocalVariableBindings() {
		String source = """
			package com.example;

			public class LocalVarTest {
				public void method() {
					String localVar = "test";
					int count = 5;
					Object obj = null;
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "LocalVarTest.java", null, AST.JLS21, null, true);

		assertNotNull("CompilationUnit should not be null", cu);
		TypeDeclaration type = (TypeDeclaration) cu.types().get(0);
		MethodDeclaration method = type.getMethods()[0];

		// Get variable declaration statements from method body
		List<?> statements = method.getBody().statements();
		assertEquals("Should have 3 variable declarations", 3, statements.size());

		// Test first local variable (String localVar)
		VariableDeclarationStatement stmt0 =
			(VariableDeclarationStatement) statements.get(0);
		VariableDeclarationFragment frag0 =
			(VariableDeclarationFragment) stmt0.fragments().get(0);
		IVariableBinding var0 = frag0.resolveBinding();
		assertNotNull("Local variable 0 binding should not be null", var0);
		assertEquals("Variable 0 name should be 'localVar'", "localVar", var0.getName());
		assertTrue("Variable 0 should not be a field", !var0.isField());
		assertTrue("Variable 0 should not be a parameter", !var0.isParameter());
		assertEquals("Variable 0 type should be String", "String", var0.getType().getName());

		// Test second local variable (int count)
		VariableDeclarationStatement stmt1 =
			(VariableDeclarationStatement) statements.get(1);
		VariableDeclarationFragment frag1 =
			(VariableDeclarationFragment) stmt1.fragments().get(0);
		IVariableBinding var1 = frag1.resolveBinding();
		assertNotNull("Local variable 1 binding should not be null", var1);
		assertEquals("Variable 1 name should be 'count'", "count", var1.getName());
		assertTrue("Variable 1 should not be a field", !var1.isField());
		assertTrue("Variable 1 should not be a parameter", !var1.isParameter());
		assertEquals("Variable 1 type should be int", "int", var1.getType().getName());

		// Test third local variable (Object obj)
		VariableDeclarationStatement stmt2 =
			(VariableDeclarationStatement) statements.get(2);
		VariableDeclarationFragment frag2 =
			(VariableDeclarationFragment) stmt2.fragments().get(0);
		IVariableBinding var2 = frag2.resolveBinding();
		assertNotNull("Local variable 2 binding should not be null", var2);
		assertEquals("Variable 2 name should be 'obj'", "obj", var2.getName());
		assertTrue("Variable 2 should not be a field", !var2.isField());
		assertTrue("Variable 2 should not be a parameter", !var2.isParameter());
		assertEquals("Variable 2 type should be Object", "Object", var2.getType().getName());
	}

	@Test
	public void testSuperTypeBindings() {
		String source = """
			package com.example;

			import java.util.ArrayList;

			public class MyList extends ArrayList<String> implements Comparable<MyList> {
				@Override
				public int compareTo(MyList other) {
					return 0;
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "MyList.java", null, AST.JLS21, null, true);

		assertNotNull("CompilationUnit should not be null", cu);
		TypeDeclaration type = (TypeDeclaration) cu.types().get(0);
		ITypeBinding typeBinding = type.resolveBinding();

		assertNotNull("Type binding should not be null", typeBinding);
		assertEquals("Type name should be MyList", "MyList", typeBinding.getName());

		// Test superclass binding
		ITypeBinding superclass = typeBinding.getSuperclass();
		assertNotNull("Superclass binding should not be null", superclass);
		assertTrue("Superclass should be parameterized", superclass.isParameterizedType());
		assertEquals("Superclass should be ArrayList", "ArrayList", superclass.getErasure().getName());

		// Test superclass type arguments
		ITypeBinding[] typeArgs = superclass.getTypeArguments();
		assertEquals("Should have 1 type argument", 1, typeArgs.length);
		assertEquals("Type argument should be String", "String", typeArgs[0].getName());

		// Test interfaces
		ITypeBinding[] interfaces = typeBinding.getInterfaces();
		assertEquals("Should implement 1 interface", 1, interfaces.length);
		assertTrue("Interface should be parameterized", interfaces[0].isParameterizedType());
		assertEquals("Interface should be Comparable", "Comparable", interfaces[0].getErasure().getName());
	}

	@Test
	public void testAnnotationBindings() {
		String source = """
			package com.example;

			@Deprecated
			@SuppressWarnings("unused")
			public class AnnotatedTest {
				@Override
				public String toString() {
					return "test";
				}

				@Deprecated
				public void oldMethod() {
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "AnnotatedTest.java", null, AST.JLS21, null, true);

		assertNotNull("CompilationUnit should not be null", cu);
		TypeDeclaration type = (TypeDeclaration) cu.types().get(0);
		ITypeBinding typeBinding = type.resolveBinding();

		assertNotNull("Type binding should not be null", typeBinding);

		// Test type annotations
		shaded.org.eclipse.jdt.core.dom.IAnnotationBinding[] typeAnnotations = typeBinding.getAnnotations();
		assertNotNull("Type annotations should not be null", typeAnnotations);
		assertTrue("Should have at least 1 annotation", typeAnnotations.length >= 1);

		boolean foundDeprecated = false;
		for (shaded.org.eclipse.jdt.core.dom.IAnnotationBinding annotation : typeAnnotations) {
			if ("Deprecated".equals(annotation.getAnnotationType().getName())) {
				foundDeprecated = true;
				break;
			}
		}
		assertTrue("Should have @Deprecated annotation on type", foundDeprecated);

		// Test method annotations
		MethodDeclaration[] methods = type.getMethods();
		assertTrue("Should have at least 2 methods", methods.length >= 2);

		// Find toString method and check @Override
		MethodDeclaration toStringMethod = null;
		for (MethodDeclaration method : methods) {
			if ("toString".equals(method.getName().getIdentifier())) {
				toStringMethod = method;
				break;
			}
		}
		assertNotNull("toString method should be found", toStringMethod);

		IMethodBinding toStringBinding = toStringMethod.resolveBinding();
		assertNotNull("toString binding should not be null", toStringBinding);

		shaded.org.eclipse.jdt.core.dom.IAnnotationBinding[] methodAnnotations = toStringBinding.getAnnotations();
		assertNotNull("Method annotations should not be null", methodAnnotations);
		assertTrue("Should have at least 1 annotation", methodAnnotations.length >= 1);

		boolean foundOverride = false;
		for (shaded.org.eclipse.jdt.core.dom.IAnnotationBinding annotation : methodAnnotations) {
			if ("Override".equals(annotation.getAnnotationType().getName())) {
				foundOverride = true;
				break;
			}
		}
		assertTrue("Should have @Override annotation on toString method", foundOverride);

		// Find oldMethod and check @Deprecated
		MethodDeclaration oldMethod = null;
		for (MethodDeclaration method : methods) {
			if ("oldMethod".equals(method.getName().getIdentifier())) {
				oldMethod = method;
				break;
			}
		}
		assertNotNull("oldMethod should be found", oldMethod);

		IMethodBinding oldMethodBinding = oldMethod.resolveBinding();
		assertNotNull("oldMethod binding should not be null", oldMethodBinding);

		shaded.org.eclipse.jdt.core.dom.IAnnotationBinding[] oldMethodAnnotations = oldMethodBinding.getAnnotations();
		assertNotNull("oldMethod annotations should not be null", oldMethodAnnotations);
		assertTrue("Should have at least 1 annotation", oldMethodAnnotations.length >= 1);

		boolean foundDeprecatedOnMethod = false;
		for (shaded.org.eclipse.jdt.core.dom.IAnnotationBinding annotation : oldMethodAnnotations) {
			if ("Deprecated".equals(annotation.getAnnotationType().getName())) {
				foundDeprecatedOnMethod = true;
				break;
			}
		}
		assertTrue("Should have @Deprecated annotation on oldMethod", foundDeprecatedOnMethod);
	}

	@Test
	public void testJavadocStructureAndContent() {
		String source = """
			package com.example;

			/**
			 * A comprehensive test class for javadoc parsing.
			 * This class demonstrates various javadoc features.
			 *
			 * @author Test Author
			 * @version 1.0
			 * @since 2025
			 */
			public class JavadocTest {
				/**
				 * The name field stores the user's name.
				 * It should never be null.
				 */
				private String name;

				/**
				 * Processes data with the given parameters.
				 * This method performs complex operations.
				 *
				 * @param input the input string to process
				 * @param count the number of iterations
				 * @param flags optional flags for processing
				 * @return the processed result as a string
				 * @throws IllegalArgumentException if input is null
				 * @throws IllegalStateException if system is not initialized
				 * @see #helper(String)
				 * @deprecated Use {@link #processV2(String, int)} instead
				 */
				@Deprecated
				public String process(String input, int count, Object... flags) throws IllegalArgumentException, IllegalStateException {
					return helper(input);
				}

				/**
				 * Helper method for internal use.
				 *
				 * @param data the data to process
				 * @return processed data
				 */
				private String helper(String data) {
					return data;
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(source, "JavadocTest.java", null, AST.JLS21, null, true);

		assertNotNull("CompilationUnit should not be null", cu);
		TypeDeclaration type = (TypeDeclaration) cu.types().get(0);

		// Test class javadoc
		Javadoc classDoc = type.getJavadoc();
		assertNotNull("Class should have javadoc", classDoc);
		List<?> classTags = classDoc.tags();
		assertNotNull("Class javadoc should have tags", classTags);
		assertTrue("Class javadoc should have multiple tags", classTags.size() >= 3);

		// Verify specific tags in class javadoc
		boolean foundAuthor = false;
		boolean foundVersion = false;
		boolean foundSince = false;
		for (Object tagObj : classTags) {
			TagElement tag = (TagElement) tagObj;
			String tagName = tag.getTagName();
			if ("@author".equals(tagName)) {
				foundAuthor = true;
			} else if ("@version".equals(tagName)) {
				foundVersion = true;
			} else if ("@since".equals(tagName)) {
				foundSince = true;
			}
		}
		assertTrue("Should have @author tag", foundAuthor);
		assertTrue("Should have @version tag", foundVersion);
		assertTrue("Should have @since tag", foundSince);

		// Test field javadoc
		FieldDeclaration[] fields = type.getFields();
		assertEquals("Should have 1 field", 1, fields.length);
		Javadoc fieldDoc = fields[0].getJavadoc();
		assertNotNull("Field should have javadoc", fieldDoc);

		// Test method javadoc with comprehensive tags
		MethodDeclaration[] methods = type.getMethods();
		assertTrue("Should have at least 2 methods", methods.length >= 2);

		// Find the 'process' method
		MethodDeclaration processMethod = null;
		for (MethodDeclaration method : methods) {
			if ("process".equals(method.getName().getIdentifier())) {
				processMethod = method;
				break;
			}
		}
		assertNotNull("process method should be found", processMethod);

		Javadoc methodDoc = processMethod.getJavadoc();
		assertNotNull("Method should have javadoc", methodDoc);
		List<?> methodTags = methodDoc.tags();
		assertNotNull("Method javadoc should have tags", methodTags);
		assertTrue("Method javadoc should have multiple tags", methodTags.size() >= 5);

		// Count specific tag types
		int paramCount = 0;
		int throwsCount = 0;
		boolean foundReturn = false;
		boolean foundSee = false;
		boolean foundDeprecated = false;

		for (Object tagObj : methodTags) {
			TagElement tag = (TagElement) tagObj;
			String tagName = tag.getTagName();
			if ("@param".equals(tagName)) {
				paramCount++;
			} else if ("@return".equals(tagName)) {
				foundReturn = true;
			} else if ("@throws".equals(tagName)) {
				throwsCount++;
			} else if ("@see".equals(tagName)) {
				foundSee = true;
			} else if ("@deprecated".equals(tagName)) {
				foundDeprecated = true;
			}
		}

		assertEquals("Should have 3 @param tags", 3, paramCount);
		assertTrue("Should have @return tag", foundReturn);
		assertEquals("Should have 2 @throws tags", 2, throwsCount);
		assertTrue("Should have @see tag", foundSee);
		assertTrue("Should have @deprecated tag", foundDeprecated);

		// Test helper method javadoc
		MethodDeclaration helperMethod = null;
		for (MethodDeclaration method : methods) {
			if ("helper".equals(method.getName().getIdentifier())) {
				helperMethod = method;
				break;
			}
		}
		assertNotNull("helper method should be found", helperMethod);

		Javadoc helperDoc = helperMethod.getJavadoc();
		assertNotNull("Helper method should have javadoc", helperDoc);
	}
}
