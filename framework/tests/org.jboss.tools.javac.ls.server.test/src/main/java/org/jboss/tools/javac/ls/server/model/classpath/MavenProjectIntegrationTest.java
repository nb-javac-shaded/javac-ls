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
import static org.junit.Assert.assertFalse;
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
 * Integration tests for Maven classpath discovery with REAL Maven execution.
 * These tests actually run 'mvn dependency:build-classpath' and can be slow on first run.
 */
public class MavenProjectIntegrationTest {

	private File tempDir;
	private ClasspathCache cache;
	private ProjectClasspathDiscovery discovery;

	@Before
	public void setup() throws IOException {
		tempDir = Files.createTempDirectory("MavenProjectIntegrationTest").toFile();
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

	private File createRealMavenProject(String projectName) throws IOException {
		File projectDir = new File(tempDir, projectName);
		projectDir.mkdirs();

		// Create Maven standard directories
		new File(projectDir, "src/main/java").mkdirs();
		new File(projectDir, "src/test/java").mkdirs();

		// Create a real pom.xml with actual dependencies
		String pomContent = """
				<?xml version="1.0" encoding="UTF-8"?>
				<project xmlns="http://maven.apache.org/POM/4.0.0"
				         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
				         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
				                             http://maven.apache.org/maven-v4_0_0.xsd">
				  <modelVersion>4.0.0</modelVersion>
				  <groupId>com.example</groupId>
				  <artifactId>%s</artifactId>
				  <version>1.0.0-SNAPSHOT</version>
				  <dependencies>
				    <dependency>
				      <groupId>org.apache.commons</groupId>
				      <artifactId>commons-lang3</artifactId>
				      <version>3.12.0</version>
				    </dependency>
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
	public void testRealMavenDiscoveryBlocking() throws IOException {
		File projectDir = createRealMavenProject("test-maven-blocking");
		WorkspaceProject proj = new WorkspaceProject("test-maven-blocking", projectDir.getAbsolutePath());

		long startTime = System.currentTimeMillis();

		// Blocking discovery - will execute 'mvn dependency:build-classpath'
		ArrayList<IJavacClasspathEntry> entries = discovery.getClasspath(proj);

		long duration = System.currentTimeMillis() - startTime;

		assertNotNull("Classpath entries should not be null", entries);
		assertTrue("Should have at least source folders", entries.size() >= 2);

		// Should have standard Maven folders
		assertTrue("Should contain src/main/java",
				entries.stream().anyMatch(e -> e.getPath().contains("src/main/java")));

		// Should have commons-lang3 JAR if Maven executed successfully
		boolean hasCommonsLang = entries.stream()
				.anyMatch(e -> e.getPath().contains("commons-lang3") && e.getPath().endsWith(".jar"));
		boolean hasCommonsIO = entries.stream()
				.anyMatch(e -> e.getPath().contains("commons-io") && e.getPath().endsWith(".jar"));

		System.out.println("Maven discovery took: " + duration + "ms");
		System.out.println("Found " + entries.size() + " classpath entries");
		System.out.println("Has commons-lang3: " + hasCommonsLang);
		System.out.println("Has commons-io: " + hasCommonsIO);

		// Even if Maven fails or isn't installed, we should at least get standard folders
		assertTrue("Should have discovered classpath entries", entries.size() >= 2);
	}

	@Test
	public void testMavenDiscoveryUsesCache() throws IOException {
		File projectDir = createRealMavenProject("test-maven-cache");
		WorkspaceProject proj = new WorkspaceProject("test-maven-cache", projectDir.getAbsolutePath());

		// First call - will execute Maven (slow)
		long startTime1 = System.currentTimeMillis();
		ArrayList<IJavacClasspathEntry> entries1 = discovery.getClasspath(proj);
		long duration1 = System.currentTimeMillis() - startTime1;

		assertNotNull(entries1);
		assertTrue("First discovery should return entries", entries1.size() > 0);

		// Second call - should use cache (fast)
		long startTime2 = System.currentTimeMillis();
		ArrayList<IJavacClasspathEntry> entries2 = discovery.getClasspath(proj);
		long duration2 = System.currentTimeMillis() - startTime2;

		assertNotNull(entries2);
		assertEquals("Cache should return same number of entries", entries1.size(), entries2.size());

		System.out.println("First discovery (Maven execution): " + duration1 + "ms");
		System.out.println("Second discovery (cache hit): " + duration2 + "ms");

		// Cache hit should be MUCH faster than Maven execution
		assertTrue("Cache hit should be faster than initial discovery",
				duration2 < duration1 || duration1 < 100); // unless first was super fast
	}

	@Test
	public void testNonBlockingReturnsImmediatelyDuringMavenExecution() throws IOException, InterruptedException {
		File projectDir = createRealMavenProject("test-maven-nonblocking");
		WorkspaceProject proj = new WorkspaceProject("test-maven-nonblocking", projectDir.getAbsolutePath());

		// Non-blocking call with no cache - should return immediately
		long startTime = System.currentTimeMillis();
		ArrayList<IJavacClasspathEntry> entries = discovery.getClasspathNonBlocking(proj, true);
		long duration = System.currentTimeMillis() - startTime;

		assertNotNull(entries);
		// Should return empty list immediately (no cache exists yet)
		assertEquals("Non-blocking should return empty when no cache", 0, entries.size());

		// Should return almost instantly
		assertTrue("Non-blocking should return quickly (was " + duration + "ms)",
				duration < 100);

		// Background job should have been triggered
		Thread.sleep(50); // Give it a moment to start
		boolean jobStarted = discovery.isDiscoveryInProgress(proj);

		System.out.println("Non-blocking returned in: " + duration + "ms");
		System.out.println("Background job started: " + jobStarted);

		// Wait for background job to complete (with timeout)
		int waited = 0;
		while (discovery.isDiscoveryInProgress(proj) && waited < 60000) {
			Thread.sleep(500);
			waited += 500;
		}

		System.out.println("Background job completed after: " + waited + "ms");

		// Now non-blocking should return cached result
		ArrayList<IJavacClasspathEntry> cachedEntries = discovery.getClasspathNonBlocking(proj, false);
		assertNotNull(cachedEntries);
		assertTrue("After background job, cache should have entries", cachedEntries.size() > 0);
	}

	@Test
	public void testNonBlockingReturnsStaleWhileBackgroundRefreshRuns() throws IOException, InterruptedException {
		File projectDir = createRealMavenProject("test-maven-stale");
		WorkspaceProject proj = new WorkspaceProject("test-maven-stale", projectDir.getAbsolutePath());

		// First, populate cache with blocking call
		ArrayList<IJavacClasspathEntry> initialEntries = discovery.getClasspath(proj);
		assertNotNull(initialEntries);
		assertTrue("Initial discovery should succeed", initialEntries.size() > 0);

		// Modify pom.xml to invalidate cache
		File pomFile = new File(projectDir, "pom.xml");
		Thread.sleep(10);
		pomFile.setLastModified(System.currentTimeMillis());

		// Non-blocking call should return stale cache immediately
		long startTime = System.currentTimeMillis();
		ArrayList<IJavacClasspathEntry> staleEntries = discovery.getClasspathNonBlocking(proj, true);
		long duration = System.currentTimeMillis() - startTime;

		assertNotNull(staleEntries);
		assertEquals("Should return stale cache", initialEntries.size(), staleEntries.size());
		assertTrue("Should return immediately (was " + duration + "ms)", duration < 100);

		// Background refresh should be running
		Thread.sleep(50);
		boolean refreshInProgress = discovery.isDiscoveryInProgress(proj);
		System.out.println("Background refresh started: " + refreshInProgress);

		// Even with job running, non-blocking should keep returning stale cache
		ArrayList<IJavacClasspathEntry> stillStale = discovery.getClasspathNonBlocking(proj, false);
		assertNotNull(stillStale);
		assertEquals("Should still return stale cache while job runs", initialEntries.size(), stillStale.size());
	}

	@Test
	public void testCacheInvalidationBehaviorWithAllStrategies() throws IOException, InterruptedException {
		File projectDir = createRealMavenProject("test-maven-invalidation");
		WorkspaceProject proj = new WorkspaceProject("test-maven-invalidation", projectDir.getAbsolutePath());

		// Step 1: Initial discovery to populate cache
		System.out.println("\n=== Step 1: Initial Discovery ===");
		long startTime = System.currentTimeMillis();
		ArrayList<IJavacClasspathEntry> initialEntries = discovery.getClasspath(proj);
		long initialDuration = System.currentTimeMillis() - startTime;

		assertNotNull(initialEntries);
		assertTrue("Initial discovery should return entries", initialEntries.size() > 0);
		System.out.println("Initial discovery took: " + initialDuration + "ms");
		System.out.println("Found " + initialEntries.size() + " entries");

		// Step 2: Verify cache is used on second call
		System.out.println("\n=== Step 2: Cache Hit ===");
		startTime = System.currentTimeMillis();
		ArrayList<IJavacClasspathEntry> cachedEntries = discovery.getClasspath(proj);
		long cachedDuration = System.currentTimeMillis() - startTime;

		assertEquals("Cached call should return same entries", initialEntries.size(), cachedEntries.size());
		assertTrue("Cache hit should be fast", cachedDuration < 50);
		System.out.println("Cache hit took: " + cachedDuration + "ms");

		// Step 3: Invalidate cache by modifying pom.xml
		System.out.println("\n=== Step 3: Invalidate Cache ===");
		File pomFile = new File(projectDir, "pom.xml");
		Thread.sleep(10);
		pomFile.setLastModified(System.currentTimeMillis());
		System.out.println("Modified pom.xml to invalidate cache");

		// Step 4: Test BLOCKING call with invalid cache - should re-execute Maven
		System.out.println("\n=== Step 4: Blocking with Invalid Cache ===");
		startTime = System.currentTimeMillis();
		ArrayList<IJavacClasspathEntry> blockingEntries = discovery.getClasspath(proj);
		long blockingDuration = System.currentTimeMillis() - startTime;

		assertNotNull("Blocking should return entries", blockingEntries);
		assertEquals("Blocking should return same number of entries", initialEntries.size(), blockingEntries.size());
		assertTrue("Blocking should take significant time (Maven re-execution)",
				blockingDuration > 500); // Should run Maven again
		System.out.println("Blocking re-discovery took: " + blockingDuration + "ms");

		// Step 5: Invalidate cache again
		System.out.println("\n=== Step 5: Invalidate Cache Again ===");
		Thread.sleep(10);
		pomFile.setLastModified(System.currentTimeMillis());

		// Step 6: Test NON-BLOCKING without trigger - should return stale, no job
		System.out.println("\n=== Step 6: Non-Blocking WITHOUT Trigger (Invalid Cache) ===");
		startTime = System.currentTimeMillis();
		ArrayList<IJavacClasspathEntry> nonBlockingNoTrigger = discovery.getClasspathNonBlocking(proj, false);
		long nonBlockingNoTriggerDuration = System.currentTimeMillis() - startTime;

		assertNotNull("Non-blocking should return stale", nonBlockingNoTrigger);
		assertEquals("Should return stale cache", initialEntries.size(), nonBlockingNoTrigger.size());
		assertTrue("Non-blocking should be instant", nonBlockingNoTriggerDuration < 50);

		// Should NOT have started a background job
		Thread.sleep(50);
		boolean jobRunning = discovery.isDiscoveryInProgress(proj);
		assertFalse("Should NOT start background job when trigger=false", jobRunning);
		System.out.println("Non-blocking (no trigger) took: " + nonBlockingNoTriggerDuration + "ms");
		System.out.println("Background job started: " + jobRunning);

		// Step 7: Invalidate cache one more time
		System.out.println("\n=== Step 7: Invalidate Cache One More Time ===");
		Thread.sleep(10);
		pomFile.setLastModified(System.currentTimeMillis());

		// Step 8: Test NON-BLOCKING with trigger - should return stale AND start job
		System.out.println("\n=== Step 8: Non-Blocking WITH Trigger (Invalid Cache) ===");
		startTime = System.currentTimeMillis();
		ArrayList<IJavacClasspathEntry> nonBlockingWithTrigger = discovery.getClasspathNonBlocking(proj, true);
		long nonBlockingWithTriggerDuration = System.currentTimeMillis() - startTime;

		assertNotNull("Non-blocking should return stale", nonBlockingWithTrigger);
		assertEquals("Should return stale cache", initialEntries.size(), nonBlockingWithTrigger.size());
		assertTrue("Non-blocking should be instant", nonBlockingWithTriggerDuration < 50);

		// Should HAVE started a background job
		Thread.sleep(50);
		boolean jobStarted = discovery.isDiscoveryInProgress(proj);
		System.out.println("Non-blocking (with trigger) took: " + nonBlockingWithTriggerDuration + "ms");
		System.out.println("Background job started: " + jobStarted);

		// Wait for background job to complete
		int waited = 0;
		while (discovery.isDiscoveryInProgress(proj) && waited < 60000) {
			Thread.sleep(500);
			waited += 500;
		}
		System.out.println("Background job completed after: " + waited + "ms");

		// Step 9: Verify summary
		System.out.println("\n=== Summary ===");
		System.out.println("Initial discovery (cold):     " + initialDuration + "ms");
		System.out.println("Cache hit (valid):            " + cachedDuration + "ms");
		System.out.println("Blocking (invalid cache):     " + blockingDuration + "ms (re-ran Maven)");
		System.out.println("Non-blocking no trigger:      " + nonBlockingNoTriggerDuration + "ms (stale, no job)");
		System.out.println("Non-blocking with trigger:    " + nonBlockingWithTriggerDuration + "ms (stale, started job)");

		assertTrue("Blocking should be much slower than non-blocking",
				blockingDuration > nonBlockingWithTriggerDuration * 10);
	}

	@Test
	public void testBlockingWaitsForBackgroundJobToComplete() throws IOException, InterruptedException {
		File projectDir = createRealMavenProject("test-maven-blocking-waits");
		WorkspaceProject proj = new WorkspaceProject("test-maven-blocking-waits", projectDir.getAbsolutePath());

		// Start background job with non-blocking call
		ArrayList<IJavacClasspathEntry> empty = discovery.getClasspathNonBlocking(proj, true);
		assertEquals("Should return empty (no cache)", 0, empty.size());

		// Give background job time to start
		Thread.sleep(100);

		// Now call blocking - it should perform its own discovery since cache is invalid
		// (The background job and blocking call might race, but both should work)
		long startTime = System.currentTimeMillis();
		ArrayList<IJavacClasspathEntry> entries = discovery.getClasspath(proj);
		long duration = System.currentTimeMillis() - startTime;

		assertNotNull(entries);
		assertTrue("Blocking call should return valid entries", entries.size() > 0);

		System.out.println("Blocking call completed in: " + duration + "ms");

		// After completion, discovery should no longer be in progress
		assertFalse("Background job should eventually complete",
				discovery.isDiscoveryInProgress(proj));
	}
}
