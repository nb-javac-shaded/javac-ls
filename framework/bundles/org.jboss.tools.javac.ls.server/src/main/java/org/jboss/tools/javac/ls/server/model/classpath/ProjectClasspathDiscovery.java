/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.model.classpath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jboss.tools.javac.ls.server.model.WorkspaceProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for discovering project classpaths.
 * Manages a prioritized list of classpath discoverers and caching.
 */
public class ProjectClasspathDiscovery {
	private static final Logger LOG = LoggerFactory.getLogger(ProjectClasspathDiscovery.class);

	private static class DiscovererWithPriority {
		final IProjectClasspathDiscoverer discoverer;
		final int priority;

		DiscovererWithPriority(IProjectClasspathDiscoverer discoverer, int priority) {
			this.discoverer = discoverer;
			this.priority = priority;
		}
	}

	private final List<DiscovererWithPriority> discoverers;
	private final ClasspathCache cache;
	private final ExecutorService executorService;
	private final Map<String, CompletableFuture<ArrayList<IJavacClasspathEntry>>> discoveryJobs;

	public ProjectClasspathDiscovery(ClasspathCache cache) {
		this.discoverers = new ArrayList<>();
		this.cache = cache;
		this.executorService = Executors.newFixedThreadPool(2); // Small pool for background discovery
		this.discoveryJobs = new HashMap<>();
		registerDefaultDiscoverers();
	}

	private void registerDefaultDiscoverers() {
		// Higher priority values are checked first
		// Eclipse has lowest priority (fallback)
		discoverers.add(new DiscovererWithPriority(new MavenProjectClasspathDiscoverer(), 100));
		discoverers.add(new DiscovererWithPriority(new EclipseProjectClasspathDiscoverer(), 1));

		// Sort by priority (highest first)
		discoverers.sort(Comparator.comparingInt((DiscovererWithPriority d) -> d.priority).reversed());
	}

	/**
	 * Gets the classpath for a project, BLOCKING until valid classpath is available.
	 * Returns cached classpath if valid, otherwise performs fresh discovery.
	 * This method will block until discovery completes if cache is invalid or missing.
	 *
	 * @param proj the workspace project
	 * @return list of classpath entries, or empty list if no discoverer accepts the project
	 */
	public ArrayList<IJavacClasspathEntry> getClasspath(WorkspaceProject proj) {
		if (proj == null) {
			return new ArrayList<>();
		}

		// Find appropriate discoverer
		for (DiscovererWithPriority dwp : discoverers) {
			if (dwp.discoverer.accepts(proj)) {
				LOG.debug("Discoverer '{}' accepts project '{}'", dwp.discoverer.getId(), proj.getName());

				// Check cache validity
				List<java.io.File> sourceFiles = dwp.discoverer.getSourceFilesForCacheValidation(proj);
				if (sourceFiles != null && cache != null && cache.isCacheValid(proj, sourceFiles)) {
					ArrayList<IJavacClasspathEntry> cached = cache.loadCache(proj.getName());
					if (cached != null) {
						LOG.info("Using valid cached classpath for project '{}'", proj.getName());
						return cached;
					}
				}

				// Cache invalid or missing - perform blocking discovery
				LOG.info("Performing blocking discovery for project '{}' using '{}'",
						proj.getName(), dwp.discoverer.getId());
				return performDiscovery(proj, dwp.discoverer);
			}
		}

		LOG.warn("No discoverer accepts project '{}'", proj.getName());
		return new ArrayList<>();
	}

	/**
	 * Gets the classpath for a project, NON-BLOCKING. Returns immediately.
	 * Returns cached classpath (even if stale), or empty list if no cache exists.
	 *
	 * @param proj the workspace project
	 * @param triggerRefresh if true, triggers background discovery job to refresh cache
	 * @return cached classpath entries (possibly stale), or empty list if no cache
	 */
	public ArrayList<IJavacClasspathEntry> getClasspathNonBlocking(WorkspaceProject proj, boolean triggerRefresh) {
		if (proj == null) {
			return new ArrayList<>();
		}

		// Check if discovery is already in progress
		boolean discoveryInProgress = isDiscoveryInProgress(proj);

		// Try to return cached data (even if stale)
		ArrayList<IJavacClasspathEntry> cached = null;
		if (cache != null) {
			cached = cache.loadCache(proj.getName());
		}

		if (cached != null) {
			LOG.debug("Returning cached classpath for project '{}' (non-blocking, discoveryInProgress={})",
					proj.getName(), discoveryInProgress);
		} else {
			LOG.debug("No cache available for project '{}' (non-blocking)", proj.getName());
		}

		// Trigger background refresh if requested and not already running
		if (triggerRefresh && !discoveryInProgress) {
			triggerBackgroundDiscovery(proj);
		}

		return cached != null ? cached : new ArrayList<>();
	}

	/**
	 * Checks if classpath discovery is currently in progress for the given project.
	 *
	 * @param proj the workspace project
	 * @return true if discovery job is running, false otherwise
	 */
	public boolean isDiscoveryInProgress(WorkspaceProject proj) {
		if (proj == null) {
			return false;
		}

		synchronized (discoveryJobs) {
			CompletableFuture<ArrayList<IJavacClasspathEntry>> job = discoveryJobs.get(proj.getName());
			return job != null && !job.isDone();
		}
	}

	/**
	 * Triggers background discovery for a project if not already running.
	 *
	 * @param proj the workspace project
	 */
	private void triggerBackgroundDiscovery(WorkspaceProject proj) {
		synchronized (discoveryJobs) {
			// Check if job already running
			CompletableFuture<ArrayList<IJavacClasspathEntry>> existingJob = discoveryJobs.get(proj.getName());
			if (existingJob != null && !existingJob.isDone()) {
				LOG.debug("Background discovery already in progress for project '{}'", proj.getName());
				return;
			}

			// Find discoverer
			IProjectClasspathDiscoverer discoverer = findDiscoverer(proj);
			if (discoverer == null) {
				LOG.warn("No discoverer accepts project '{}'", proj.getName());
				return;
			}

			// Start background job
			LOG.info("Starting background discovery for project '{}' using '{}'",
					proj.getName(), discoverer.getId());

			CompletableFuture<ArrayList<IJavacClasspathEntry>> job = CompletableFuture.supplyAsync(() -> {
				return performDiscovery(proj, discoverer);
			}, executorService);

			// Clean up job from map when complete
			job.whenComplete((result, error) -> {
				synchronized (discoveryJobs) {
					discoveryJobs.remove(proj.getName());
				}
				if (error != null) {
					LOG.error("Background discovery failed for project '{}'", proj.getName(), error);
				} else {
					LOG.info("Background discovery completed for project '{}'", proj.getName());
				}
			});

			discoveryJobs.put(proj.getName(), job);
		}
	}

	/**
	 * Performs actual classpath discovery and saves to cache.
	 * This is the shared implementation used by both blocking and background discovery.
	 */
	private ArrayList<IJavacClasspathEntry> performDiscovery(WorkspaceProject proj, IProjectClasspathDiscoverer discoverer) {
		ArrayList<IJavacClasspathEntry> entries = discoverer.discoverClasspath(proj);

		// Save to cache
		if (cache != null && entries != null) {
			cache.saveCache(proj.getName(), entries);
		}

		return entries;
	}

	/**
	 * Finds the appropriate discoverer for a project.
	 */
	private IProjectClasspathDiscoverer findDiscoverer(WorkspaceProject proj) {
		for (DiscovererWithPriority dwp : discoverers) {
			if (dwp.discoverer.accepts(proj)) {
				return dwp.discoverer;
			}
		}
		return null;
	}

	/**
	 * Legacy method for backward compatibility.
	 * Delegates to {@link #getClasspath(WorkspaceProject)}.
	 *
	 * @deprecated Use {@link #getClasspath(WorkspaceProject)} or {@link #getClasspathNonBlocking(WorkspaceProject, boolean)}
	 */
	@Deprecated
	public ArrayList<IJavacClasspathEntry> discoverClasspath(WorkspaceProject proj) {
		return getClasspath(proj);
	}

	/**
	 * Gets the list of registered discoverers in priority order.
	 * @return list of discoverers
	 */
	public List<IProjectClasspathDiscoverer> getDiscoverers() {
		List<IProjectClasspathDiscoverer> result = new ArrayList<>();
		for (DiscovererWithPriority dwp : discoverers) {
			result.add(dwp.discoverer);
		}
		return result;
	}

	/**
	 * Get the classpath cache.
	 * @return the cache
	 */
	public ClasspathCache getCache() {
		return cache;
	}

	/**
	 * Shuts down the background discovery executor service.
	 * Should be called when the discovery service is no longer needed.
	 */
	public void shutdown() {
		LOG.info("Shutting down classpath discovery background executor");
		executorService.shutdown();
	}
}
