/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jboss.tools.javac.ls.server.model.WorkspaceModel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for textDocument/definition LSP method.
 * Tests the full stack: parsing → indexing → definition resolution.
 */
public class TextDocumentDefinitionIntegrationTest {

	private File tempDir;
	private WorkspaceModel workspaceModel;
	private JavacTextDocumentService textDocumentService;
	private File projectDir;
	private File srcDir;

	@Before
	public void setup() throws IOException {
		// Create temp workspace
		tempDir = Files.createTempDirectory("DefinitionIntegrationTest").toFile();
		workspaceModel = new WorkspaceModel(tempDir);

		// Create mock launcher that provides workspace model
		JavacLsServerLauncher mockLauncher = new MockServerLauncher(tempDir);

		// Create server and text document service
		JavacLSServerImpl server = new JavacLSServerImpl(mockLauncher);
		textDocumentService = new JavacTextDocumentService(server);

		// Create project structure
		projectDir = new File(tempDir, "testproject");
		srcDir = new File(projectDir, "src");
		srcDir.mkdirs();

		// Create .classpath file so Eclipse discoverer accepts the project
		File classpathFile = new File(projectDir, ".classpath");
		String classpathContent = """
				<?xml version="1.0" encoding="UTF-8"?>
				<classpath>
					<classpathentry kind="src" path="src"/>
					<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
					<classpathentry kind="output" path="bin"/>
				</classpath>
				""";
		Files.write(classpathFile.toPath(), classpathContent.getBytes());

		// Add project to workspace
		workspaceModel.addProject("testproject", projectDir.getAbsolutePath());
	}

	@After
	public void teardown() {
		if (workspaceModel != null) {
			workspaceModel.shutdown();
		}
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

	/**
	 * Test: Jump to class definition when cursor is on a type reference.
	 */
	@Test
	public void testDefinition_TypeReference() throws Exception {
		// Create Person.java
		File personFile = new File(srcDir, "Person.java");
		String personContent = """
				public class Person {
				    private String name;
				    private int age;

				    public Person(String name, int age) {
				        this.name = name;
				        this.age = age;
				    }
				}
				""";
		Files.write(personFile.toPath(), personContent.getBytes());

		// Create Main.java that references Person
		File mainFile = new File(srcDir, "Main.java");
		String mainContent = """
				public class Main {
				    public static void main(String[] args) {
				        Person p = new Person("Alice", 30);
				        System.out.println(p);
				    }
				}
				""";
		Files.write(mainFile.toPath(), mainContent.getBytes());

		// Index the files
		workspaceModel.reparseFiles("testproject", List.of(personFile.toPath(), mainFile.toPath()));

		// Request definition for "Person" on line 2, character 8 (the "Person p" declaration)
		DefinitionParams params = new DefinitionParams(
			new TextDocumentIdentifier(mainFile.toURI().toString()),
			new Position(2, 8) // Line 2: "        Person p = ..."
		);

		Either<List<? extends Location>, List<? extends LocationLink>> result =
			textDocumentService.definition(params).get();

		// Verify we got a Location result
		assertNotNull("Definition result should not be null", result);
		assertTrue("Should return List<Location>", result.isLeft());

		List<? extends Location> locations = result.getLeft();
		assertFalse("Should find at least one location", locations.isEmpty());

		// Verify the location points to Person.java
		Location location = locations.get(0);
		assertTrue("Should point to Person.java",
			location.getUri().endsWith("Person.java"));

		// Should point to "public class Person" line
		assertEquals("Should point to line 0 (class declaration)", 0, location.getRange().getStart().getLine());
	}

	/**
	 * Test: Jump to method definition when cursor is on a method call.
	 */
	@Test
	public void testDefinition_MethodCall() throws Exception {
		// Create Calculator.java
		File calcFile = new File(srcDir, "Calculator.java");
		String calcContent = """
				public class Calculator {
				    public int add(int a, int b) {
				        return a + b;
				    }

				    public int multiply(int a, int b) {
				        return a * b;
				    }
				}
				""";
		Files.write(calcFile.toPath(), calcContent.getBytes());

		// Create Test.java that calls Calculator methods
		File testFile = new File(srcDir, "Test.java");
		String testContent = """
				public class Test {
				    public void run() {
				        Calculator calc = new Calculator();
				        int result = calc.add(5, 3);
				    }
				}
				""";
		Files.write(testFile.toPath(), testContent.getBytes());

		// Index the files
		workspaceModel.reparseFiles("testproject", List.of(calcFile.toPath(), testFile.toPath()));

		// Request definition for "add" method call on line 3
		DefinitionParams params = new DefinitionParams(
			new TextDocumentIdentifier(testFile.toURI().toString()),
			new Position(3, 26) // Line 3: "        int result = calc.add(5, 3);"
		);

		Either<List<? extends Location>, List<? extends LocationLink>> result =
			textDocumentService.definition(params).get();

		// Verify result
		assertNotNull(result);
		assertTrue(result.isLeft());

		List<? extends Location> locations = result.getLeft();
		assertFalse("Should find method definition", locations.isEmpty());

		Location location = locations.get(0);
		assertTrue("Should point to Calculator.java",
			location.getUri().endsWith("Calculator.java"));

		// Should point to "public int add(...)" line
		assertEquals("Should point to add method declaration", 1, location.getRange().getStart().getLine());
	}

	/**
	 * Test: Jump to field definition when cursor is on a field reference.
	 */
	@Test
	public void testDefinition_FieldReference() throws Exception {
		// Create Config.java with fields
		File configFile = new File(srcDir, "Config.java");
		String configContent = """
				public class Config {
				    public static final String API_URL = "https://api.example.com";
				    public static final int TIMEOUT = 5000;

				    public void printConfig() {
				        System.out.println(API_URL);
				        System.out.println(TIMEOUT);
				    }
				}
				""";
		Files.write(configFile.toPath(), configContent.getBytes());

		// Index the file
		workspaceModel.reparseFiles("testproject", List.of(configFile.toPath()));

		// Request definition for "API_URL" field reference on line 5
		DefinitionParams params = new DefinitionParams(
			new TextDocumentIdentifier(configFile.toURI().toString()),
			new Position(5, 28) // Line 5: "        System.out.println(API_URL);"
		);

		Either<List<? extends Location>, List<? extends LocationLink>> result =
			textDocumentService.definition(params).get();

		// Verify result
		assertNotNull(result);
		assertTrue(result.isLeft());

		List<? extends Location> locations = result.getLeft();
		assertFalse("Should find field definition", locations.isEmpty());

		Location location = locations.get(0);
		assertTrue("Should point to Config.java",
			location.getUri().endsWith("Config.java"));

		// Should point to field declaration line
		assertEquals("Should point to API_URL field declaration", 1, location.getRange().getStart().getLine());
	}

	/**
	 * Test: Jump to local variable definition when cursor is on variable usage.
	 */
	@Test
	public void testDefinition_LocalVariable() throws Exception {
		// Create Method.java with local variables
		File methodFile = new File(srcDir, "Method.java");
		String methodContent = """
				public class Method {
				    public void process() {
				        int count = 0;
				        String message = "Hello";

				        for (int i = 0; i < 10; i++) {
				            count++;
				        }

				        System.out.println(message);
				        System.out.println(count);
				    }
				}
				""";
		Files.write(methodFile.toPath(), methodContent.getBytes());

		// Index the file
		workspaceModel.reparseFiles("testproject", List.of(methodFile.toPath()));

		// Request definition for "count" variable on line 10 (usage)
		DefinitionParams params = new DefinitionParams(
			new TextDocumentIdentifier(methodFile.toURI().toString()),
			new Position(10, 31) // Line 10: "        System.out.println(count);"
		);

		Either<List<? extends Location>, List<? extends LocationLink>> result =
			textDocumentService.definition(params).get();

		// Verify result
		assertNotNull(result);
		assertTrue(result.isLeft());

		List<? extends Location> locations = result.getLeft();
		assertFalse("Should find variable definition", locations.isEmpty());

		Location location = locations.get(0);
		assertTrue("Should point to Method.java",
			location.getUri().endsWith("Method.java"));

		// Should point to "int count = 0;" line
		assertEquals("Should point to count variable declaration", 2, location.getRange().getStart().getLine());
	}

	/**
	 * Test: Return empty list when no definition is found.
	 */
	@Test
	public void testDefinition_NoDefinitionFound() throws Exception {
		// Create file with undefined reference
		File testFile = new File(srcDir, "NoDefTest.java");
		String testContent = """
				public class NoDefTest {
				    // Comment with SomeUndefinedClass reference
				    public void test() {
				    }
				}
				""";
		Files.write(testFile.toPath(), testContent.getBytes());

		// Index the file
		workspaceModel.reparseFiles("testproject", List.of(testFile.toPath()));

		// Request definition on a comment (no semantic meaning)
		DefinitionParams params = new DefinitionParams(
			new TextDocumentIdentifier(testFile.toURI().toString()),
			new Position(1, 10) // In the comment
		);

		Either<List<? extends Location>, List<? extends LocationLink>> result =
			textDocumentService.definition(params).get();

		// Verify empty result
		assertNotNull(result);
		assertTrue(result.isLeft());

		List<? extends Location> locations = result.getLeft();
		assertTrue("Should return empty list when no definition found", locations.isEmpty());
	}

	/**
	 * Mock launcher that provides WorkspaceModel without starting a server.
	 */
	private class MockServerLauncher extends JavacLsServerLauncher {
		private final File workspaceDir;

		MockServerLauncher(File workspaceDir) {
			super(null); // No port needed for this test
			this.workspaceDir = workspaceDir;
		}

		@Override
		protected String getWorkspacePath() {
			return workspaceDir.getAbsolutePath();
		}

		@Override
		protected File getWorkspaceDirectory() {
			return workspaceDir;
		}

		@Override
		protected boolean isStartupSync() {
			return true;
		}

		@Override
		public WorkspaceModel getWorkspaceModel() {
			// Return the workspace model created in setup()
			return workspaceModel;
		}
	}
}
