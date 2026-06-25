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
import java.util.Random;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.jboss.tools.javac.ls.server.model.WorkspaceModel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for textDocument/references LSP method.
 * Tests the full stack: parsing → indexing → reference finding.
 */
public class TextDocumentReferencesIntegrationTest {

	private File tempDir;
	private WorkspaceModel workspaceModel;
	private JavacTextDocumentService textDocumentService;
	private File projectDir;
	private File srcDir;

	@Before
	public void setup() throws IOException {
		// Create temp workspace
		tempDir = Files.createTempDirectory("ReferencesIntegrationTest").toFile();
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
	 * Test: Find all references to a method.
	 */
	@Test
	public void testReferences_MethodCall() throws Exception {
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
				        int result2 = calc.add(10, 20);
				    }
				}
				""";
		Files.write(testFile.toPath(), testContent.getBytes());

		// Create Main.java that also calls add
		File mainFile = new File(srcDir, "Main.java");
		String mainContent = """
				public class Main {
				    public static void main(String[] args) {
				        Calculator c = new Calculator();
				        System.out.println(c.add(1, 2));
				    }
				}
				""";
		Files.write(mainFile.toPath(), mainContent.getBytes());

		// Index the files
		workspaceModel.reparseFiles("testproject", List.of(calcFile.toPath(), testFile.toPath(), mainFile.toPath()));

		// Request references for "add" method declaration in Calculator.java
		ReferenceParams params = new ReferenceParams(
			new TextDocumentIdentifier(calcFile.toURI().toString()),
			new Position(1, 15), // Line 1: "    public int add(int a, int b) {"
			new ReferenceContext(false) // Don't include declaration
		);

		List<? extends Location> references = textDocumentService.references(params).get();

		// Verify we found references
		assertNotNull("References result should not be null", references);
		assertFalse("Should find references to 'add' method", references.isEmpty());

		// We expect at least 3 references (2 in Test.java, 1 in Main.java)
		assertTrue("Should find at least 3 references to 'add' method", references.size() >= 3);

		// Verify references are in different files
		long fileCount = references.stream()
			.map(Location::getUri)
			.distinct()
			.count();
		assertTrue("References should be in at least 2 different files", fileCount >= 2);
	}

	/**
	 * Test: Find all references to a field, including declaration.
	 */
	@Test
	public void testReferences_FieldWithDeclaration() throws Exception {
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

		// Create App.java that uses API_URL
		File appFile = new File(srcDir, "App.java");
		String appContent = """
				public class App {
				    public void connect() {
				        String url = Config.API_URL;
				        System.out.println("Connecting to: " + Config.API_URL);
				    }
				}
				""";
		Files.write(appFile.toPath(), appContent.getBytes());

		// Index the files
		workspaceModel.reparseFiles("testproject", List.of(configFile.toPath(), appFile.toPath()));

		// Request references for "API_URL" field with declaration included
		// Line 1: "    public static final String API_URL = ..."
		// Position 32 should be on "API_URL" (after "String ")
		ReferenceParams params = new ReferenceParams(
			new TextDocumentIdentifier(configFile.toURI().toString()),
			new Position(1, 35), // Line 1: field name "API_URL"
			new ReferenceContext(true) // Include declaration
		);

		List<? extends Location> references = textDocumentService.references(params).get();

		// Verify we found references including declaration
		assertNotNull(references);
		assertFalse("Should find references to 'API_URL'", references.isEmpty());

		// We expect at least the declaration and some references
		assertTrue("Should find declaration and references", references.size() >= 2);

		// Verify we found the declaration (should be in Config.java at line 1)
		long declarationsInConfig = references.stream()
			.filter(loc -> loc.getUri().endsWith("Config.java"))
			.filter(loc -> loc.getRange().getStart().getLine() == 1)
			.count();
		assertTrue("Should include the field declaration", declarationsInConfig >= 1);
	}

	/**
	 * Test: Find references to local variable.
	 */
	@Test
	public void testReferences_LocalVariable() throws Exception {
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
				        int result = count * 2;
				    }
				}
				""";
		Files.write(methodFile.toPath(), methodContent.getBytes());

		// Index the file
		workspaceModel.reparseFiles("testproject", List.of(methodFile.toPath()));

		// Request references for "count" variable (at declaration)
		ReferenceParams params = new ReferenceParams(
			new TextDocumentIdentifier(methodFile.toURI().toString()),
			new Position(2, 12), // Line 2: "        int count = 0;"
			new ReferenceContext(false) // Don't include declaration
		);

		List<? extends Location> references = textDocumentService.references(params).get();

		// Verify we found references
		assertNotNull(references);
		assertFalse("Should find references to 'count'", references.isEmpty());

		// We expect 3 references: line 6 (count++), line 10 (println), line 11 (count * 2)
		// Declaration on line 2 should not be included
		assertEquals("Should find 3 references to 'count' without declaration", 3, references.size());

		// All references should be in the same file
		assertTrue("All references should be in Method.java",
			references.stream().allMatch(loc -> loc.getUri().endsWith("Method.java")));
	}

	/**
	 * Test: Find references to a type.
	 */
	@Test
	public void testReferences_TypeReference() throws Exception {
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
				        Person p2 = new Person("Bob", 25);
				        System.out.println(p);
				    }
				}
				""";
		Files.write(mainFile.toPath(), mainContent.getBytes());

		// Index the files
		workspaceModel.reparseFiles("testproject", List.of(personFile.toPath(), mainFile.toPath()));

		// Request references for "Person" class (at declaration)
		ReferenceParams params = new ReferenceParams(
			new TextDocumentIdentifier(personFile.toURI().toString()),
			new Position(0, 13), // Line 0: "public class Person {"
			new ReferenceContext(false) // Don't include declaration
		);

		List<? extends Location> references = textDocumentService.references(params).get();

		// Verify we found references
		assertNotNull(references);
		assertFalse("Should find references to 'Person' type", references.isEmpty());

		// We expect references in Main.java: 2 type references + 2 constructor calls
		assertTrue("Should find multiple references to 'Person'", references.size() >= 4);

		// Should find references in Main.java
		long mainReferences = references.stream()
			.filter(loc -> loc.getUri().endsWith("Main.java"))
			.count();
		assertTrue("Should find references in Main.java", mainReferences >= 4);
	}

	/**
	 * Test: Return empty list when no references are found.
	 */
	@Test
	public void testReferences_NoReferencesFound() throws Exception {
		// Create file with unused variable
		File testFile = new File(srcDir, "NoRefTest.java");
		String testContent = """
				public class NoRefTest {
				    public void test() {
				        int unusedVar = 42;
				    }
				}
				""";
		Files.write(testFile.toPath(), testContent.getBytes());

		// Index the file
		workspaceModel.reparseFiles("testproject", List.of(testFile.toPath()));

		// Request references for unusedVar
		ReferenceParams params = new ReferenceParams(
			new TextDocumentIdentifier(testFile.toURI().toString()),
			new Position(2, 12), // Line 2: "        int unusedVar = 42;"
			new ReferenceContext(false)
		);

		List<? extends Location> references = textDocumentService.references(params).get();

		// Verify empty result (no references, declaration not included)
		assertNotNull(references);
		assertTrue("Should return empty list when no references found", references.isEmpty());
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
