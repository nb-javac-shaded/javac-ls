/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.model.classpath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.jboss.tools.javac.ls.server.model.WorkspaceProject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class EclipseProjectClasspathDiscovererTest {

	private EclipseProjectClasspathDiscoverer discoverer;
	private File tempDir;

	@Before
	public void setup() throws IOException {
		discoverer = new EclipseProjectClasspathDiscoverer();
		tempDir = Files.createTempDirectory("EclipseProjectTest").toFile();
	}

	@After
	public void teardown() {
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
	public void testGetId() {
		assertEquals("eclipse", discoverer.getId());
	}

	@Test
	public void testAcceptsEclipseProject() throws IOException {
		File classpathFile = new File(tempDir, ".classpath");
		classpathFile.createNewFile();

		WorkspaceProject proj = new WorkspaceProject("test-eclipse", tempDir.getAbsolutePath());
		assertTrue(discoverer.accepts(proj));
	}

	@Test
	public void testRejectsNonEclipseProject() {
		WorkspaceProject proj = new WorkspaceProject("test-non-eclipse", tempDir.getAbsolutePath());
		assertFalse(discoverer.accepts(proj));
	}

	@Test
	public void testRejectsNullProject() {
		assertFalse(discoverer.accepts(null));
	}

	@Test
	public void testRejectsProjectWithNullPath() {
		WorkspaceProject proj = new WorkspaceProject("test", null);
		assertFalse(discoverer.accepts(proj));
	}

	@Test
	public void testGetSourceFilesForCacheValidation() throws IOException {
		File classpathFile = new File(tempDir, ".classpath");
		classpathFile.createNewFile();

		WorkspaceProject proj = new WorkspaceProject("test-eclipse", tempDir.getAbsolutePath());

		List<File> sourceFiles = discoverer.getSourceFilesForCacheValidation(proj);
		assertNotNull(sourceFiles);
		assertEquals(1, sourceFiles.size());
		assertEquals(classpathFile, sourceFiles.get(0));
	}

	@Test
	public void testDiscoverClasspath() throws IOException {
		File classpathFile = new File(tempDir, ".classpath");

		// Create actual source and lib directories
		File srcDir = new File(tempDir, "src");
		File libDir = new File(tempDir, "lib");
		File binDir = new File(tempDir, "bin");
		srcDir.mkdirs();
		libDir.mkdirs();
		binDir.mkdirs();

		// Create a dummy JAR file
		File jarFile = new File(libDir, "test.jar");
		jarFile.createNewFile();

		// Write a valid .classpath XML
		String classpathContent = """
				<?xml version="1.0" encoding="UTF-8"?>
				<classpath>
					<classpathentry kind="src" path="src"/>
					<classpathentry kind="lib" path="lib/test.jar"/>
					<classpathentry kind="output" path="bin"/>
					<classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
				</classpath>""";

		java.nio.file.Files.write(classpathFile.toPath(), classpathContent.getBytes());

		WorkspaceProject proj = new WorkspaceProject("test-eclipse", tempDir.getAbsolutePath());

		java.util.ArrayList<IJavacClasspathEntry> entries = discoverer.discoverClasspath(proj);
		assertNotNull(entries);

		// Should have 3 entries: src folder, lib JAR, and bin output folder
		// (con entries are skipped)
		assertEquals(3, entries.size());

		// Verify we have the expected entries
		boolean foundSrc = false;
		boolean foundLib = false;
		boolean foundBin = false;

		for (IJavacClasspathEntry entry : entries) {
			if (entry.getPath().endsWith("src")) {
				foundSrc = true;
				assertEquals(IJavacClasspathEntry.EntryType.SOURCE, entry.getType());
			} else if (entry.getPath().endsWith("test.jar")) {
				foundLib = true;
				assertEquals(IJavacClasspathEntry.EntryType.LIBRARY, entry.getType());
			} else if (entry.getPath().endsWith("bin")) {
				foundBin = true;
				assertEquals(IJavacClasspathEntry.EntryType.SOURCE, entry.getType());
			}
		}

		assertTrue("src folder not found", foundSrc);
		assertTrue("lib JAR not found", foundLib);
		assertTrue("bin output folder not found", foundBin);
	}
}
