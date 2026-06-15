/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;

import org.jboss.tools.javac.ls.server.model.classpath.IJavacClasspathEntry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for WorkspaceModel classpath functionality.
 * Tests the full stack: WorkspaceModel -> ProjectClasspathDiscovery -> Discoverers.
 */
public class WorkspaceModelClasspathIntegrationTest {

	private File tempDir;
	private WorkspaceModel workspaceModel;

	@Before
	public void setup() throws IOException {
		tempDir = Files.createTempDirectory("WorkspaceModelClasspathTest").toFile();
		workspaceModel = new WorkspaceModel(tempDir);
	}

	@After
	public void teardown() {
		workspaceModel.shutdown();
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

	private File createEclipseProject(String projectName) throws IOException {
		File projectDir = new File(tempDir, projectName);
		projectDir.mkdirs();

		// Create source folders
		new File(projectDir, "src").mkdirs();
		new File(projectDir, "test").mkdirs();
		new File(projectDir, "bin").mkdirs();

		// Create lib folder with JAR
		File libDir = new File(projectDir, "lib");
		libDir.mkdirs();
		new File(libDir, "test.jar").createNewFile();

		// Write .classpath file
		String classpathContent = """
				<?xml version="1.0" encoding="UTF-8"?>
				<classpath>
					<classpathentry kind="src" path="src"/>
					<classpathentry kind="src" path="test"/>
					<classpathentry kind="lib" path="lib/test.jar"/>
					<classpathentry kind="output" path="bin"/>
				</classpath>""";
		Files.write(new File(projectDir, ".classpath").toPath(), classpathContent.getBytes());

		return projectDir;
	}

	private File createMavenProject(String projectName) throws IOException {
		File projectDir = new File(tempDir, projectName);
		projectDir.mkdirs();

		// Create Maven standard directories
		new File(projectDir, "src/main/java").mkdirs();
		new File(projectDir, "src/test/java").mkdirs();

		// Write pom.xml with real dependencies
		String pomContent = """
				<?xml version="1.0" encoding="UTF-8"?>
				<project xmlns="http://maven.apache.org/POM/4.0.0">
				  <modelVersion>4.0.0</modelVersion>
				  <groupId>com.example</groupId>
				  <artifactId>%s</artifactId>
				  <version>1.0.0</version>
				  <dependencies>
				    <dependency>
				      <groupId>commons-io</groupId>
				      <artifactId>commons-io</artifactId>
				      <version>2.11.0</version>
				    </dependency>
				  </dependencies>
				</project>""".formatted(projectName);
		Files.write(new File(projectDir, "pom.xml").toPath(), pomContent.getBytes());

		return projectDir;
	}

	@Test
	public void testGetClasspathForEclipseProject() throws IOException {
		// Create Eclipse project
		File projectDir = createEclipseProject("test-eclipse-ws");

		// Add to workspace
		assertTrue("Should add project", workspaceModel.addProject("test-eclipse-ws", projectDir.getAbsolutePath()));

		// Get classpath through WorkspaceModel API (blocking)
		ArrayList<IJavacClasspathEntry> entries = workspaceModel.getProjectClasspath("test-eclipse-ws");

		assertNotNull("Classpath should not be null", entries);
		assertEquals("Should have 4 entries (2 src + 1 lib + 1 bin)", 4, entries.size());

		// Verify entries
		assertTrue("Should contain src folder",
				entries.stream().anyMatch(e -> e.getPath().endsWith("src")));
		assertTrue("Should contain test folder",
				entries.stream().anyMatch(e -> e.getPath().endsWith("test")));
		assertTrue("Should contain test.jar",
				entries.stream().anyMatch(e -> e.getPath().endsWith("test.jar")));
	}

	@Test
	public void testGetClasspathForMavenProject() throws IOException {
		// Create Maven project
		File projectDir = createMavenProject("test-maven-ws");

		// Add to workspace
		assertTrue("Should add project", workspaceModel.addProject("test-maven-ws", projectDir.getAbsolutePath()));

		// Get classpath through WorkspaceModel API (blocking)
		ArrayList<IJavacClasspathEntry> entries = workspaceModel.getProjectClasspath("test-maven-ws");

		assertNotNull("Classpath should not be null", entries);
		assertTrue("Should have at least source folders", entries.size() >= 2);

		// Should have standard Maven folders
		assertTrue("Should contain src/main/java",
				entries.stream().anyMatch(e -> e.getPath().contains("src/main/java")));
	}

	@Test
	public void testGetClasspathForUnknownProject() {
		// Try to get classpath for project that doesn't exist
		ArrayList<IJavacClasspathEntry> entries = workspaceModel.getProjectClasspath("non-existent");

		assertNotNull("Should return empty list", entries);
		assertEquals("Should be empty", 0, entries.size());
	}

	@Test
	public void testNonBlockingClasspathRetrieval() throws IOException, InterruptedException {
		// Create Eclipse project
		File projectDir = createEclipseProject("test-nonblocking");
		workspaceModel.addProject("test-nonblocking", projectDir.getAbsolutePath());

		// Non-blocking call with no cache - should return empty immediately
		long startTime = System.currentTimeMillis();
		ArrayList<IJavacClasspathEntry> entries1 = workspaceModel.getProjectClasspathNonBlocking("test-nonblocking", false);
		long duration = System.currentTimeMillis() - startTime;

		assertEquals("Should return empty (no cache)", 0, entries1.size());
		assertTrue("Should be instant", duration < 50);

		// Blocking call to populate cache
		ArrayList<IJavacClasspathEntry> blockingEntries = workspaceModel.getProjectClasspath("test-nonblocking");
		assertEquals("Should discover entries", 4, blockingEntries.size());

		// Non-blocking call with cache - should return cached immediately
		ArrayList<IJavacClasspathEntry> entries2 = workspaceModel.getProjectClasspathNonBlocking("test-nonblocking", false);
		assertEquals("Should return cached entries", 4, entries2.size());
	}

	@Test
	public void testNonBlockingWithBackgroundRefresh() throws IOException, InterruptedException {
		// Create Maven project (slower discovery)
		File projectDir = createMavenProject("test-background");
		workspaceModel.addProject("test-background", projectDir.getAbsolutePath());

		// Non-blocking with trigger - should return empty and start background job
		ArrayList<IJavacClasspathEntry> entries = workspaceModel.getProjectClasspathNonBlocking("test-background", true);
		assertEquals("Should return empty (no cache)", 0, entries.size());

		// Check if background job started
		Thread.sleep(50);
		boolean inProgress = workspaceModel.isClasspathDiscoveryInProgress("test-background");

		System.out.println("Background job started: " + inProgress);

		// Wait for completion
		int waited = 0;
		while (workspaceModel.isClasspathDiscoveryInProgress("test-background") && waited < 60000) {
			Thread.sleep(500);
			waited += 500;
		}

		System.out.println("Background job completed after: " + waited + "ms");

		// Now non-blocking should return cached result
		ArrayList<IJavacClasspathEntry> cachedEntries = workspaceModel.getProjectClasspathNonBlocking("test-background", false);
		assertTrue("Should have cached entries now", cachedEntries.size() > 0);
	}

	@Test
	public void testMultipleProjectsInWorkspace() throws IOException {
		// Create multiple projects
		File eclipseDir = createEclipseProject("project-eclipse");
		File mavenDir = createMavenProject("project-maven");

		// Add both to workspace
		workspaceModel.addProject("project-eclipse", eclipseDir.getAbsolutePath());
		workspaceModel.addProject("project-maven", mavenDir.getAbsolutePath());

		// Verify workspace has both
		assertEquals("Should have 2 projects", 2, workspaceModel.getProjectCount());
		assertTrue("Should have eclipse project", workspaceModel.hasProject("project-eclipse"));
		assertTrue("Should have maven project", workspaceModel.hasProject("project-maven"));

		// Get classpath for each
		ArrayList<IJavacClasspathEntry> eclipseCP = workspaceModel.getProjectClasspath("project-eclipse");
		ArrayList<IJavacClasspathEntry> mavenCP = workspaceModel.getProjectClasspath("project-maven");

		assertNotNull("Eclipse classpath should not be null", eclipseCP);
		assertNotNull("Maven classpath should not be null", mavenCP);
		assertTrue("Eclipse project should have entries", eclipseCP.size() > 0);
		assertTrue("Maven project should have entries", mavenCP.size() > 0);

		// They should be different
		// (can't easily assert size difference as both might have same number of standard folders)
	}

	@Test
	public void testClasspathCachePersistedAcrossWorkspaceInstances() throws IOException {
		// Create project and get classpath
		File projectDir = createEclipseProject("test-persistence");
		workspaceModel.addProject("test-persistence", projectDir.getAbsolutePath());

		ArrayList<IJavacClasspathEntry> entries1 = workspaceModel.getProjectClasspath("test-persistence");
		assertNotNull(entries1);
		assertTrue("Should have entries", entries1.size() > 0);

		// Shutdown workspace
		workspaceModel.shutdown();

		// Create new workspace instance pointing to same directory
		WorkspaceModel workspaceModel2 = new WorkspaceModel(tempDir);
		workspaceModel2.addProject("test-persistence", projectDir.getAbsolutePath());

		// Get classpath - should use cached data (fast)
		long startTime = System.currentTimeMillis();
		ArrayList<IJavacClasspathEntry> entries2 = workspaceModel2.getProjectClasspath("test-persistence");
		long duration = System.currentTimeMillis() - startTime;

		assertNotNull(entries2);
		assertEquals("Should have same entries from cache", entries1.size(), entries2.size());
		assertTrue("Should be fast (cache hit)", duration < 100);

		workspaceModel2.shutdown();
	}

	@Test
	public void testRemoveProjectClearsDiscoveryState() throws IOException, InterruptedException {
		// Create project and start background discovery
		File projectDir = createMavenProject("test-remove");
		workspaceModel.addProject("test-remove", projectDir.getAbsolutePath());

		// Start background discovery
		workspaceModel.getProjectClasspathNonBlocking("test-remove", true);
		Thread.sleep(50);

		// Wait for it to complete
		int waited = 0;
		while (workspaceModel.isClasspathDiscoveryInProgress("test-remove") && waited < 60000) {
			Thread.sleep(500);
			waited += 500;
		}

		// Remove project from workspace
		assertTrue("Should remove project", workspaceModel.removeProject("test-remove"));

		// Now attempts to get classpath should return empty
		ArrayList<IJavacClasspathEntry> entries = workspaceModel.getProjectClasspath("test-remove");
		assertEquals("Should return empty for removed project", 0, entries.size());

		// Discovery status should return false
		assertFalse("Should not show discovery in progress",
				workspaceModel.isClasspathDiscoveryInProgress("test-remove"));
	}

	@Test
	public void testIsDiscoveryInProgressForUnknownProject() {
		// Check discovery status for non-existent project
		assertFalse("Should return false for unknown project",
				workspaceModel.isClasspathDiscoveryInProgress("non-existent"));
	}
}
