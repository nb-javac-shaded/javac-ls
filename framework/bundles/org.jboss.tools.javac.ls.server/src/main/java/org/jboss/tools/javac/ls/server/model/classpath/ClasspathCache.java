/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.model.classpath;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.jboss.tools.javac.ls.server.model.WorkspaceProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Manages caching of classpath entries for projects.
 * Cache files are stored in the workspace's classpath directory.
 */
public class ClasspathCache {
	private static final Logger LOG = LoggerFactory.getLogger(ClasspathCache.class);
	private static final String CACHE_DIR = "classpath";

	private final File cacheDir;
	private final Gson gson;

	/**
	 * Create a classpath cache manager.
	 * @param workspaceDir the workspace directory
	 */
	public ClasspathCache(File workspaceDir) {
		this.cacheDir = new File(workspaceDir, CACHE_DIR);
		this.gson = new GsonBuilder().setPrettyPrinting().create();
		ensureCacheDirectoryExists();
	}

	private void ensureCacheDirectoryExists() {
		if (!cacheDir.exists()) {
			if (!cacheDir.mkdirs()) {
				LOG.error("Failed to create classpath cache directory: {}", cacheDir.getAbsolutePath());
			} else {
				LOG.info("Created classpath cache directory: {}", cacheDir.getAbsolutePath());
			}
		}
	}

	/**
	 * Get the cache file for a project.
	 * @param projectName the project name
	 * @return the cache file
	 */
	private File getCacheFile(String projectName) {
		// Sanitize project name for filesystem
		String safeName = projectName.replaceAll("[^a-zA-Z0-9._-]", "_");
		return new File(cacheDir, safeName + "-classpath.json");
	}

	/**
	 * Check if cache exists and is valid for the project.
	 * @param proj the workspace project
	 * @param sourceFiles files to check timestamps against (e.g., pom.xml, .classpath)
	 * @return true if cache is valid
	 */
	public boolean isCacheValid(WorkspaceProject proj, List<File> sourceFiles) {
		File cacheFile = getCacheFile(proj.getName());
		if (!cacheFile.exists()) {
			return false;
		}

		long cacheTimestamp = cacheFile.lastModified();

		// Check if any source file is newer than the cache
		for (File sourceFile : sourceFiles) {
			if (sourceFile.exists() && sourceFile.lastModified() > cacheTimestamp) {
				LOG.debug("Cache for project '{}' is stale: {} is newer",
					proj.getName(), sourceFile.getName());
				return false;
			}
		}

		LOG.debug("Cache for project '{}' is valid", proj.getName());
		return true;
	}

	/**
	 * Load cached classpath entries for a project.
	 * @param projectName the project name
	 * @return list of cached classpath entries, or null if not found or error
	 */
	public ArrayList<IJavacClasspathEntry> loadCache(String projectName) {
		File cacheFile = getCacheFile(projectName);
		if (!cacheFile.exists()) {
			LOG.debug("No cache file found for project '{}'", projectName);
			return null;
		}

		try (FileReader reader = new FileReader(cacheFile)) {
			List<JavacClasspathEntry> entries = gson.fromJson(reader,
					new TypeToken<List<JavacClasspathEntry>>(){}.getType());

			if (entries != null) {
				LOG.info("Loaded {} classpath entries from cache for project '{}'",
					entries.size(), projectName);
				return new ArrayList<>(entries);
			}
		} catch (IOException e) {
			LOG.error("Error loading classpath cache for project '{}'", projectName, e);
		} catch (Exception e) {
			LOG.error("Error parsing classpath cache for project '{}'", projectName, e);
		}

		return null;
	}

	/**
	 * Save classpath entries to cache for a project.
	 * @param projectName the project name
	 * @param entries the classpath entries to cache
	 */
	public void saveCache(String projectName, ArrayList<IJavacClasspathEntry> entries) {
		ensureCacheDirectoryExists();

		File cacheFile = getCacheFile(projectName);
		try (FileWriter writer = new FileWriter(cacheFile)) {
			// Convert to concrete type for serialization
			List<JavacClasspathEntry> concreteEntries = new ArrayList<>();
			for (IJavacClasspathEntry entry : entries) {
				if (entry instanceof JavacClasspathEntry) {
					concreteEntries.add((JavacClasspathEntry) entry);
				} else {
					// Convert to concrete type
					concreteEntries.add(new JavacClasspathEntry(entry.getType(), entry.getPath()));
				}
			}

			gson.toJson(concreteEntries, writer);
			LOG.info("Saved {} classpath entries to cache for project '{}'",
				entries.size(), projectName);
		} catch (IOException e) {
			LOG.error("Error saving classpath cache for project '{}'", projectName, e);
		}
	}

	/**
	 * Clear the cache for a project.
	 * @param projectName the project name
	 * @return true if cache was deleted
	 */
	public boolean clearCache(String projectName) {
		File cacheFile = getCacheFile(projectName);
		if (cacheFile.exists()) {
			boolean deleted = cacheFile.delete();
			if (deleted) {
				LOG.info("Cleared classpath cache for project '{}'", projectName);
			}
			return deleted;
		}
		return false;
	}

	/**
	 * Get the cache directory.
	 * @return the cache directory
	 */
	public File getCacheDirectory() {
		return cacheDir;
	}
}
