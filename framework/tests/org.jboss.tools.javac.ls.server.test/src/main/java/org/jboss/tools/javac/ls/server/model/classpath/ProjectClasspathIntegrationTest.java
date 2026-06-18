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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;

import org.jboss.tools.javac.ls.server.model.WorkspaceProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for classpath discovery with realistic project structures.
 * These tests create full project examples and verify the discovered classpath.
 */
public class ProjectClasspathIntegrationTest {

	private File tempDir;
	private ClasspathCache cache;
	private ProjectClasspathDiscovery discovery;

	@Before
	public void setup() throws IOException {
		tempDir = Files.createTempDirectory("ProjectClasspathIntegrationTest").toFile();
		cache = new ClasspathCache(tempDir);
		discovery = new ProjectClasspathDiscovery(cache);
	}

	@After
	public void teardown() {
		discovery.shutdown();
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
	public void testEclipseProjectWithMultipleSources() throws IOException {
		// Create a realistic Eclipse project structure
		File projectDir = new File(tempDir, "eclipse-project");
		projectDir.mkdirs();

		// Create source folders
		new File(projectDir, "src/main/java").mkdirs();
		new File(projectDir, "src/test/java").mkdirs();
		new File(projectDir, "src/main/resources").mkdirs();

		// Create lib folder with some JARs
		File libDir = new File(projectDir, "lib");
		libDir.mkdirs();
		new File(libDir, "junit-4.13.jar").createNewFile();
		new File(libDir, "commons-lang3.jar").createNewFile();

		// Create bin folder
		new File(projectDir, "bin").mkdirs();

		// Write .classpath file
		String classpathContent = """
				<?xml version="1.0" encoding="UTF-8"?>
				<classpath>
					<classpathentry kind="src" path="src/main/java"/>
					<classpathentry kind="src" path="src/test/java"/>
					<classpathentry kind="src" path="src/main/resources"/>
					<classpathentry kind="lib" path="lib/junit-4.13.jar"/>
					<classpathentry kind="lib" path="lib/commons-lang3.jar"/>
					<classpathentry kind="output" path="bin"/>
					<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
				</classpath>""";
		Files.write(new File(projectDir, ".classpath").toPath(), classpathContent.getBytes());

		WorkspaceProject proj = new WorkspaceProject("eclipse-project", projectDir.getAbsolutePath());

		// Discover classpath
		ArrayList<IJavacClasspathEntry> entries = discovery.discoverClasspath(proj);

		assertNotNull(entries);
		// Should have: 3 source folders + 2 lib JARs + 1 bin output = 6 entries
		assertEquals(6, entries.size());

		// Verify specific entries exist
		assertTrue("Should contain src/main/java",
				entries.stream().anyMatch(e -> e.getPath().contains("src/main/java")));
		assertTrue("Should contain src/test/java",
				entries.stream().anyMatch(e -> e.getPath().contains("src/test/java")));
		assertTrue("Should contain junit JAR",
				entries.stream().anyMatch(e -> e.getPath().contains("junit-4.13.jar")));
		assertTrue("Should contain commons-lang3 JAR",
				entries.stream().anyMatch(e -> e.getPath().contains("commons-lang3.jar")));

		// Verify types
		long sourceCount = entries.stream()
				.filter(e -> e.getType() == IJavacClasspathEntry.EntryType.SOURCE)
				.count();
		long libCount = entries.stream()
				.filter(e -> e.getType() == IJavacClasspathEntry.EntryType.LIBRARY)
				.count();

		assertEquals("Should have 4 source entries (3 src folders + bin)", 4, sourceCount);
		assertEquals("Should have 2 library entries", 2, libCount);
	}

	@Test
	public void testMavenProjectStructure() throws IOException {
		// Create a realistic Maven project structure
		File projectDir = new File(tempDir, "maven-project");
		projectDir.mkdirs();

		// Create Maven standard directories
		new File(projectDir, "src/main/java").mkdirs();
		new File(projectDir, "src/test/java").mkdirs();
		new File(projectDir, "target/classes").mkdirs();
		new File(projectDir, "target/test-classes").mkdirs();

		// Write minimal pom.xml
		String pomContent = """
				<?xml version="1.0" encoding="UTF-8"?>
				<project xmlns="http://maven.apache.org/POM/4.0.0">
				  <modelVersion>4.0.0</modelVersion>
				  <groupId>com.example</groupId>
				  <artifactId>test-project</artifactId>
				  <version>1.0.0</version>
				</project>""";
		Files.write(new File(projectDir, "pom.xml").toPath(), pomContent.getBytes());

		WorkspaceProject proj = new WorkspaceProject("maven-project", projectDir.getAbsolutePath());

		// Discover classpath
		ArrayList<IJavacClasspathEntry> entries = discovery.discoverClasspath(proj);

		assertNotNull(entries);

		// Should at minimum have the standard Maven source folders
		// (actual dependency JARs won't be present without running mvn)
		assertTrue("Should contain src/main/java",
				entries.stream().anyMatch(e -> e.getPath().contains("src/main/java")));
		assertTrue("Should contain src/test/java",
				entries.stream().anyMatch(e -> e.getPath().contains("src/test/java")));
		assertTrue("Should contain target/classes",
				entries.stream().anyMatch(e -> e.getPath().contains("target/classes")));
		assertTrue("Should contain target/test-classes",
				entries.stream().anyMatch(e -> e.getPath().contains("target/test-classes")));
	}

	@Test
	public void testHybridProjectPrefersMaven() throws IOException {
		// Create a project with BOTH .classpath and pom.xml
		// Maven should win (higher priority)
		File projectDir = new File(tempDir, "hybrid-project");
		projectDir.mkdirs();

		// Create Maven structure
		new File(projectDir, "src/main/java").mkdirs();
		new File(projectDir, "target/classes").mkdirs();
		String pomContent = """
				<?xml version="1.0" encoding="UTF-8"?>
				<project xmlns="http://maven.apache.org/POM/4.0.0">
				  <modelVersion>4.0.0</modelVersion>
				  <groupId>com.example</groupId>
				  <artifactId>hybrid</artifactId>
				  <version>1.0.0</version>
				</project>""";
		Files.write(new File(projectDir, "pom.xml").toPath(), pomContent.getBytes());

		// Also create Eclipse .classpath with DIFFERENT structure
		new File(projectDir, "eclipse-src").mkdirs();
		new File(projectDir, "bin").mkdirs();
		String classpathContent = """
				<?xml version="1.0" encoding="UTF-8"?>
				<classpath>
					<classpathentry kind="src" path="eclipse-src"/>
					<classpathentry kind="output" path="bin"/>
				</classpath>""";
		Files.write(new File(projectDir, ".classpath").toPath(), classpathContent.getBytes());

		WorkspaceProject proj = new WorkspaceProject("hybrid-project", projectDir.getAbsolutePath());

		// Discover classpath
		ArrayList<IJavacClasspathEntry> entries = discovery.discoverClasspath(proj);

		assertNotNull(entries);

		// Should use Maven paths, NOT Eclipse paths
		assertTrue("Should contain Maven src/main/java",
				entries.stream().anyMatch(e -> e.getPath().contains("src/main/java")));
		assertTrue("Should NOT contain Eclipse eclipse-src",
				entries.stream().noneMatch(e -> e.getPath().contains("eclipse-src")));
		assertTrue("Should NOT contain Eclipse bin",
				entries.stream().noneMatch(e -> e.getPath().contains("bin")));
	}

	@Test
	public void testCacheIsUsedOnSecondCall() throws IOException {
		File projectDir = new File(tempDir, "cached-project");
		projectDir.mkdirs();

		new File(projectDir, "src").mkdirs();
		new File(projectDir, "bin").mkdirs();

		String classpathContent = """
				<?xml version="1.0" encoding="UTF-8"?>
				<classpath>
					<classpathentry kind="src" path="src"/>
					<classpathentry kind="output" path="bin"/>
				</classpath>""";
		Files.write(new File(projectDir, ".classpath").toPath(), classpathContent.getBytes());

		WorkspaceProject proj = new WorkspaceProject("cached-project", projectDir.getAbsolutePath());

		// First call - should discover and cache
		ArrayList<IJavacClasspathEntry> entries1 = discovery.discoverClasspath(proj);
		assertNotNull(entries1);
		assertEquals(2, entries1.size());

		// Second call - should return cached result
		ArrayList<IJavacClasspathEntry> entries2 = discovery.discoverClasspath(proj);
		assertNotNull(entries2);
		assertEquals(2, entries2.size());

		// Results should be equivalent (but not necessarily same instance)
		assertEquals(entries1.size(), entries2.size());
		for (int i = 0; i < entries1.size(); i++) {
			assertEquals(entries1.get(i).getPath(), entries2.get(i).getPath());
			assertEquals(entries1.get(i).getType(), entries2.get(i).getType());
		}
	}
}
