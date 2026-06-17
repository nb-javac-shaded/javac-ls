package org.jboss.tools.javac.ls.server;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.jboss.tools.javac.ls.api.dao.InitializationState;
import org.jboss.tools.javac.ls.index.store.JavaIndex;
import org.jboss.tools.javac.ls.search.engine.SearchEngine;
import org.jboss.tools.javac.ls.search.match.SearchMatch;
import org.jboss.tools.javac.ls.search.pattern.FieldPattern;
import org.jboss.tools.javac.ls.server.model.WorkspaceModel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for JavacLsServerLauncher workspace initialization and flag overrides.
 */
public class JavacLsServerLauncherTest {

	private File tempWorkspaceDir;
	private TestServerLauncher launcher;

	@Before
	public void setUp() throws IOException {
		// Create temp workspace directory
		tempWorkspaceDir = Files.createTempDirectory("test-launcher-workspace").toFile();
		tempWorkspaceDir.deleteOnExit();
	}

	@After
	public void tearDown() {
		if (launcher != null) {
			launcher.shutdown();
		}
		if (tempWorkspaceDir != null) {
			deleteRecursively(tempWorkspaceDir);
		}
	}

	private void deleteRecursively(File file) {
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null) {
				for (File child : children) {
					deleteRecursively(child);
				}
			}
		}
		file.delete();
	}

	@Test
	public void testWorkspaceDirectoryOverrideIsRespected() throws IOException {
		// Create a custom workspace directory
		File customWorkspace = new File(tempWorkspaceDir, "custom-workspace");
		customWorkspace.mkdirs();

		// Create a project in the custom workspace
		File projectDir = new File(customWorkspace, "test-project");
		projectDir.mkdirs();
		File srcDir = new File(projectDir, "src");
		srcDir.mkdirs();

		// Write a simple .classpath file so it's recognized as Eclipse project
		File classpathFile = new File(projectDir, ".classpath");
		Files.writeString(classpathFile.toPath(), """
				<?xml version="1.0" encoding="UTF-8"?>
				<classpath>
					<classpathentry kind="src" path="src"/>
					<classpathentry kind="output" path="bin"/>
				</classpath>""");

		// Add project to workspace.json
		File workspaceJson = new File(customWorkspace, "workspace.json");
		Files.writeString(workspaceJson.toPath(), """
				[
				  {
				    "name": "test-project",
				    "path": "%s"
				  }
				]""".formatted(projectDir.getAbsolutePath().replace("\\", "\\\\")));

		// Create launcher that overrides workspace directory
		launcher = new TestServerLauncher(customWorkspace, true);
		launcher.initialize();

		// Verify workspace model was created and loaded the project
		WorkspaceModel workspace = launcher.getWorkspaceModel();
		assertNotNull("Workspace model should be created", workspace);
		assertTrue("Workspace should have loaded the project", workspace.hasProject("test-project"));
		assertEquals("Should have 1 project", 1, workspace.getProjectCount());
	}

	@Test
	public void testStartupSyncOverrideIsRespected() throws IOException {
		// Create launcher with sync=true
		launcher = new TestServerLauncher(tempWorkspaceDir, true);
		launcher.initialize();

		// Verify workspace is in READY state (synchronous indexing completed)
		WorkspaceModel workspace = launcher.getWorkspaceModel();
		assertNotNull("Workspace model should be created", workspace);
		assertTrue("Workspace should be ready after synchronous startup",
				workspace.isReady());
	}

	@Test
	public void testStartupAsyncOverrideIsRespected() throws IOException {
		// Create launcher with sync=false
		launcher = new TestServerLauncher(tempWorkspaceDir, false);
		launcher.initialize();

		// Verify workspace exists
		WorkspaceModel workspace = launcher.getWorkspaceModel();
		assertNotNull("Workspace model should be created", workspace);

		// With async startup, it might be indexing or ready
		// Just verify it's not in an invalid state
		int state = workspace.getInitializationState();
		assertTrue("Workspace should be in valid state",
				state >= InitializationState.STATE_LOADING_CACHE);
	}

	@Test
	public void testExistingWorkspaceIsLoaded() throws IOException {
		// Create a workspace with multiple projects
		File project1 = new File(tempWorkspaceDir, "project1");
		project1.mkdirs();
		File project2 = new File(tempWorkspaceDir, "project2");
		project2.mkdirs();

		// Write workspace.json with existing projects
		File workspaceJson = new File(tempWorkspaceDir, "workspace.json");
		Files.writeString(workspaceJson.toPath(), """
				[
				  {
				    "name": "project1",
				    "path": "%s"
				  },
				  {
				    "name": "project2",
				    "path": "%s"
				  }
				]""".formatted(
						project1.getAbsolutePath().replace("\\", "\\\\"),
						project2.getAbsolutePath().replace("\\", "\\\\")));

		// Create launcher
		launcher = new TestServerLauncher(tempWorkspaceDir, true);
		launcher.initialize();

		// Verify both projects were loaded
		WorkspaceModel workspace = launcher.getWorkspaceModel();
		assertNotNull("Workspace model should be created", workspace);
		assertEquals("Should have loaded 2 projects", 2, workspace.getProjectCount());
		assertTrue("Should have project1", workspace.hasProject("project1"));
		assertTrue("Should have project2", workspace.hasProject("project2"));
	}

	@Test
	public void testWorkspacePathMatchesDirectory() throws IOException {
		launcher = new TestServerLauncher(tempWorkspaceDir, true);
		launcher.initialize();

		// Verify the workspace path matches what we provided
		String returnedPath = launcher.getWorkspacePath();
		String expectedPath = tempWorkspaceDir.getAbsolutePath();

		assertEquals("Workspace path should match provided directory",
				expectedPath, returnedPath);
	}

	@Test
	public void testSyncStartupIndexingAndSearch() throws IOException {
		// Create a project with multiple Java files that use "value" in different scopes
		File projectDir = new File(tempWorkspaceDir, "search-test-project");
		projectDir.mkdirs();
		File srcDir = new File(projectDir, "src/com/example");
		srcDir.mkdirs();

		// File 1: Class with "value" as a field
		File file1 = new File(srcDir, "Container.java");
		Files.writeString(file1.toPath(), """
				package com.example;

				public class Container {
					private int value;

					public Container(int initialValue) {
						this.value = initialValue;
					}

					public int getValue() {
						return value;
					}

					public void setValue(int newValue) {
						this.value = newValue;
					}
				}
				""");

		// File 2: Class with "value" as method parameters and local variables
		File file2 = new File(srcDir, "Calculator.java");
		Files.writeString(file2.toPath(), """
				package com.example;

				public class Calculator {
					public int add(int value, int other) {
						int result = value + other;
						return result;
					}

					public int multiply(int value, int factor) {
						int result = value * factor;
						return result;
					}

					public int process(int input) {
						int value = input * 2;
						return value + 10;
					}
				}
				""");

		// File 3: Class using "value" in different contexts
		File file3 = new File(srcDir, "Processor.java");
		Files.writeString(file3.toPath(), """
				package com.example;

				public class Processor {
					private String value;

					public void process(String value) {
						this.value = value.toUpperCase();
					}

					public String transform(String input) {
						String value = input.toLowerCase();
						return value + this.value;
					}
				}
				""");

		// Create launcher with synchronous startup
		launcher = new TestServerLauncher(tempWorkspaceDir, true);
		launcher.initialize();

		// Add the project to workspace
		WorkspaceModel workspace = launcher.getWorkspaceModel();
		assertNotNull("Workspace should be created", workspace);
		workspace.addProject("search-test-project", projectDir.getAbsolutePath());

		// Index the project synchronously
		int filesIndexed = workspace.indexProject("search-test-project");
		assertTrue("Should have indexed at least 3 files", filesIndexed >= 3);

		// Verify workspace is ready
		assertTrue("Workspace should be ready after synchronous indexing",
				workspace.isReady());

		// Get the index and search engine
		JavaIndex index = workspace.getIndexCache().getIndex();
		assertNotNull("Index should be available", index);

		SearchEngine searchEngine = new SearchEngine();
		List<SearchMatch> results = new ArrayList<>();

		// Search for all occurrences of "value"
		FieldPattern pattern = new FieldPattern("value", null, null, FieldPattern.SearchFor.ALL_OCCURRENCES);
		searchEngine.search(pattern, index, path -> {
			try {
				return Files.readString(path);
			} catch (IOException e) {
				return null;
			}
		}, results::add);

		// Verify we found references across multiple files
		assertFalse("Should find references to 'value'", results.isEmpty());
		assertTrue("Should find multiple references to 'value'", results.size() >= 3);

		// Verify we found references in different files
		long fileCount = results.stream()
				.map(SearchMatch::getFile)
				.distinct()
				.count();
		assertTrue("Should find 'value' in at least 2 different files", fileCount >= 2);

		// Verify we found both declarations and references
		long declarations = results.stream()
				.filter(m -> m.getKind() == SearchMatch.MatchKind.FIELD_DECLARATION)
				.count();
		long references = results.stream()
				.filter(m -> m.getKind() == SearchMatch.MatchKind.FIELD_REFERENCE)
				.count();

		assertTrue("Should find field declarations for 'value'", declarations > 0);
		assertTrue("Should find field references for 'value'", references > 0);
	}

	/**
	 * Test subclass of JavacLsServerLauncher that overrides the flag methods
	 * to provide custom values for testing.
	 */
	private static class TestServerLauncher extends JavacLsServerLauncher {
		private final File workspaceDir;
		private final boolean startupSync;

		public TestServerLauncher(File workspaceDir, boolean startupSync) {
			// Use a random high port (won't actually launch the server in these tests)
			super(String.valueOf(50000 + new Random().nextInt(15000)));
			this.workspaceDir = workspaceDir;
			this.startupSync = startupSync;
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
			return startupSync;
		}
	}
}
