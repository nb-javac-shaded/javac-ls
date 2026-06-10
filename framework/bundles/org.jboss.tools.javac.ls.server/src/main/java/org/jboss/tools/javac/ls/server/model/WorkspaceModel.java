/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.model;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.jboss.tools.javac.ls.index.store.JavaIndex;
import org.jboss.tools.javac.ls.index.visitor.DOMToIndexVisitor;
import org.jboss.tools.javac.ls.parser.bindings.JavacDOMParser;
import org.jboss.tools.javac.ls.server.index.JavaIndexCache;
import org.jboss.tools.javac.ls.server.model.classpath.ClasspathCache;
import org.jboss.tools.javac.ls.server.model.classpath.IJavacClasspathEntry;
import org.jboss.tools.javac.ls.server.model.classpath.ProjectClasspathDiscovery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import shaded.org.eclipse.jdt.core.dom.AST;
import shaded.org.eclipse.jdt.core.dom.CompilationUnit;

/**
 * Manages the workspace model - mapping project names to filesystem paths.
 * Persisted as JSON in the workspace directory.
 */
public class WorkspaceModel {
	private static final Logger LOG = LoggerFactory.getLogger(WorkspaceModel.class);
	private static final String WORKSPACE_FILE = "workspace.json";
	private static final String INDEX_DIR = "index";

	private final File workspaceDir;
	private final File workspaceFile;
	private final Map<String, WorkspaceProject> projects;
	private final Gson gson;
	private final ClasspathCache classpathCache;
	private final ProjectClasspathDiscovery classpathDiscovery;
	private final JavaIndexCache indexCache;

	public WorkspaceModel(File workspaceDir) {
		this.workspaceDir = workspaceDir;
		this.workspaceFile = new File(workspaceDir, WORKSPACE_FILE);
		this.projects = new HashMap<>();
		this.gson = new GsonBuilder().setPrettyPrinting().create();
		this.classpathCache = new ClasspathCache(workspaceDir);
		this.classpathDiscovery = new ProjectClasspathDiscovery(classpathCache);
		this.indexCache = new JavaIndexCache(new File(workspaceDir, INDEX_DIR).toPath());
		load();
		loadIndex();
	}

	/**
	 * Add a project to the workspace.
	 *
	 * @param name the project name (must be unique)
	 * @param path the filesystem path to the project
	 * @return true if added, false if a project with this name already exists
	 */
	public synchronized boolean addProject(String name, String path) {
		if (name == null || name.trim().isEmpty()) {
			throw new IllegalArgumentException("Project name cannot be null or empty");
		}
		if (path == null || path.trim().isEmpty()) {
			throw new IllegalArgumentException("Project path cannot be null or empty");
		}

		if (projects.containsKey(name)) {
			LOG.warn("Project '{}' already exists in workspace", name);
			return false;
		}

		WorkspaceProject project = new WorkspaceProject(name, path);
		projects.put(name, project);
		save();
		LOG.info("Added project '{}' at path: {}", name, path);
		return true;
	}

	/**
	 * Remove a project from the workspace.
	 * Note: This only removes it from the workspace model, not from the filesystem.
	 *
	 * @param name the project name
	 * @return true if removed, false if not found
	 */
	public synchronized boolean removeProject(String name) {
		WorkspaceProject removed = projects.remove(name);
		if (removed != null) {
			save();
			LOG.info("Removed project '{}' from workspace", name);
			return true;
		}
		LOG.warn("Project '{}' not found in workspace", name);
		return false;
	}

	/**
	 * Get a project by name.
	 *
	 * @param name the project name
	 * @return the project, or null if not found
	 */
	public synchronized WorkspaceProject getProject(String name) {
		return projects.get(name);
	}

	/**
	 * Get the filesystem path for a project.
	 *
	 * @param name the project name
	 * @return the path, or null if project not found
	 */
	public synchronized String getProjectPath(String name) {
		WorkspaceProject project = projects.get(name);
		return project != null ? project.getPath() : null;
	}

	/**
	 * Check if a project exists in the workspace.
	 *
	 * @param name the project name
	 * @return true if exists
	 */
	public synchronized boolean hasProject(String name) {
		return projects.containsKey(name);
	}

	/**
	 * Get all projects in the workspace.
	 *
	 * @return unmodifiable list of projects
	 */
	public synchronized List<WorkspaceProject> getProjects() {
		return Collections.unmodifiableList(new ArrayList<>(projects.values()));
	}

	/**
	 * Get all project names in the workspace.
	 *
	 * @return unmodifiable list of project names
	 */
	public synchronized List<String> getProjectNames() {
		List<String> names = new ArrayList<>(projects.keySet());
		Collections.sort(names);
		return Collections.unmodifiableList(names);
	}

	/**
	 * Get the number of projects in the workspace.
	 *
	 * @return project count
	 */
	public synchronized int getProjectCount() {
		return projects.size();
	}

	/**
	 * Load the workspace from disk.
	 */
	private void load() {
		if (!workspaceFile.exists()) {
			LOG.info("Workspace file does not exist, starting with empty workspace: {}", workspaceFile.getAbsolutePath());
			return;
		}

		try (FileReader reader = new FileReader(workspaceFile)) {
			List<WorkspaceProject> loadedProjects = gson.fromJson(reader,
					new TypeToken<List<WorkspaceProject>>(){}.getType());

			if (loadedProjects != null) {
				projects.clear();
				for (WorkspaceProject project : loadedProjects) {
					projects.put(project.getName(), project);
				}
				LOG.info("Loaded {} projects from workspace file", projects.size());
			}
		} catch (IOException e) {
			LOG.error("Error loading workspace from {}", workspaceFile.getAbsolutePath(), e);
		} catch (Exception e) {
			LOG.error("Error parsing workspace file {}", workspaceFile.getAbsolutePath(), e);
		}
	}

	/**
	 * Load the index from disk.
	 */
	private void loadIndex() {
		indexCache.load();
	}

	/**
	 * Save the workspace to disk.
	 */
	private void save() {
		// Ensure workspace directory exists
		if (!workspaceDir.exists()) {
			if (!workspaceDir.mkdirs()) {
				LOG.error("Failed to create workspace directory: {}", workspaceDir.getAbsolutePath());
				return;
			}
		}

		try (FileWriter writer = new FileWriter(workspaceFile)) {
			List<WorkspaceProject> projectList = new ArrayList<>(projects.values());
			// Sort by name for consistent output
			projectList.sort((p1, p2) -> p1.getName().compareTo(p2.getName()));
			gson.toJson(projectList, writer);
			LOG.debug("Saved {} projects to workspace file", projects.size());
		} catch (IOException e) {
			LOG.error("Error saving workspace to {}", workspaceFile.getAbsolutePath(), e);
		}
	}

	/**
	 * Get the workspace directory.
	 *
	 * @return the workspace directory
	 */
	public File getWorkspaceDirectory() {
		return workspaceDir;
	}

	/**
	 * Get the workspace file path.
	 *
	 * @return the workspace file
	 */
	public File getWorkspaceFile() {
		return workspaceFile;
	}

	/**
	 * Get the classpath cache.
	 *
	 * @return the classpath cache
	 */
	public ClasspathCache getClasspathCache() {
		return classpathCache;
	}

	/**
	 * Get the classpath discovery service.
	 *
	 * @return the classpath discovery service
	 */
	public ProjectClasspathDiscovery getClasspathDiscovery() {
		return classpathDiscovery;
	}

	/**
	 * Get the classpath for a project (BLOCKING).
	 * Returns cached classpath if valid, otherwise performs fresh discovery.
	 * This method will block until discovery completes if cache is invalid or missing.
	 *
	 * @param projectName the project name
	 * @return list of classpath entries, or empty list if project not found or no discoverer accepts it
	 */
	public synchronized ArrayList<IJavacClasspathEntry> getProjectClasspath(String projectName) {
		WorkspaceProject project = projects.get(projectName);
		if (project == null) {
			LOG.warn("Cannot get classpath for unknown project: {}", projectName);
			return new ArrayList<>();
		}
		return classpathDiscovery.getClasspath(project);
	}

	/**
	 * Get the classpath for a project (NON-BLOCKING).
	 * Returns cached classpath (even if stale), or empty list if no cache exists.
	 * Returns immediately without blocking.
	 *
	 * @param projectName the project name
	 * @param triggerRefresh if true, triggers background discovery job to refresh cache
	 * @return cached classpath entries (possibly stale), or empty list if no cache or project not found
	 */
	public synchronized ArrayList<IJavacClasspathEntry> getProjectClasspathNonBlocking(String projectName, boolean triggerRefresh) {
		WorkspaceProject project = projects.get(projectName);
		if (project == null) {
			LOG.warn("Cannot get classpath for unknown project: {}", projectName);
			return new ArrayList<>();
		}
		return classpathDiscovery.getClasspathNonBlocking(project, triggerRefresh);
	}

	/**
	 * Check if classpath discovery is currently in progress for a project.
	 *
	 * @param projectName the project name
	 * @return true if discovery job is running, false otherwise or if project not found
	 */
	public synchronized boolean isClasspathDiscoveryInProgress(String projectName) {
		WorkspaceProject project = projects.get(projectName);
		if (project == null) {
			return false;
		}
		return classpathDiscovery.isDiscoveryInProgress(project);
	}

	/**
	 * Get the index cache.
	 *
	 * @return the index cache
	 */
	public JavaIndexCache getIndexCache() {
		return indexCache;
	}

	/**
	 * Index all projects in the workspace.
	 * Parses all .java files and populates the index.
	 * This is a synchronous operation that acquires write lock on the index.
	 */
	public synchronized void indexAllProjects() {
		LOG.info("Starting indexing of all projects in workspace");
		long startTime = System.currentTimeMillis();
		int totalFiles = 0;

		for (WorkspaceProject project : getProjects()) {
			int filesIndexed = indexProject(project.getName());
			totalFiles += filesIndexed;
		}

		long duration = System.currentTimeMillis() - startTime;
		LOG.info("Indexed {} files across {} projects in {}ms",
				totalFiles, projects.size(), duration);
	}

	/**
	 * Index a single project.
	 * Parses all .java files in the project and populates the index.
	 * This is a synchronous operation that acquires write lock on the index.
	 *
	 * @param projectName the project name
	 * @return number of files indexed
	 */
	public synchronized int indexProject(String projectName) {
		WorkspaceProject project = projects.get(projectName);
		if (project == null) {
			LOG.warn("Cannot index unknown project: {}", projectName);
			return 0;
		}

		LOG.info("Indexing project: {}", projectName);
		long startTime = System.currentTimeMillis();

		File projectDir = new File(project.getPath());
		if (!projectDir.exists() || !projectDir.isDirectory()) {
			LOG.warn("Project directory does not exist: {}", project.getPath());
			return 0;
		}

		// Find all .java files in the project
		List<Path> javaFiles = findJavaFiles(projectDir.toPath());
		if (javaFiles.isEmpty()) {
			LOG.info("No Java files found in project: {}", projectName);
			return 0;
		}

		// Get classpath for parsing
		ArrayList<IJavacClasspathEntry> classpathEntries = getProjectClasspathNonBlocking(projectName, false);
		List<File> classpath = new ArrayList<>();
		for (IJavacClasspathEntry entry : classpathEntries) {
			if (entry.getPath() != null) {
				classpath.add(new File(entry.getPath()));
			}
		}

		// Parse and index each file
		JavacDOMParser parser = new JavacDOMParser();
		int filesIndexed = 0;

		indexCache.lockWrite();
		try {
			JavaIndex index = indexCache.getIndex();

			for (Path javaFile : javaFiles) {
				try {
					indexFile(javaFile, parser, classpath, index);
					filesIndexed++;
				} catch (Exception e) {
					LOG.error("Failed to index file: {}", javaFile, e);
				}
			}

			// Mark index as dirty after indexing
			indexCache.markDirty();
		} finally {
			indexCache.unlockWrite();
		}

		long duration = System.currentTimeMillis() - startTime;
		LOG.info("Indexed {} files in project '{}' in {}ms", filesIndexed, projectName, duration);

		return filesIndexed;
	}

	/**
	 * Index a single Java file.
	 *
	 * @param javaFile the Java file to index
	 * @param parser the parser to use
	 * @param classpath the classpath for parsing
	 * @param index the index to populate
	 * @throws IOException if reading file fails
	 */
	private void indexFile(Path javaFile, JavacDOMParser parser, List<File> classpath, JavaIndex index)
			throws IOException {
		// Read file content
		String sourceContent = new String(Files.readAllBytes(javaFile));

		// Parse to CompilationUnit (without resolving bindings for performance)
		CompilationUnit cu = parser.parse(
				sourceContent,
				javaFile.getFileName().toString(),
				classpath,
				AST.JLS21,
				null,
				false // Don't resolve bindings for initial indexing
		);

		// Remove old declarations for this file (incremental update)
		index.removeFile(javaFile);

		// Visit AST and populate index
		DOMToIndexVisitor visitor = new DOMToIndexVisitor(index, javaFile);
		cu.accept(visitor);
		visitor.finishIndexing();

		LOG.debug("Indexed file: {}", javaFile);
	}

	/**
	 * Find all .java files in a directory recursively.
	 *
	 * @param rootDir the root directory to search
	 * @return list of Java file paths
	 */
	private List<Path> findJavaFiles(Path rootDir) {
		List<Path> javaFiles = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(rootDir)) {
			paths.filter(Files::isRegularFile)
				.filter(p -> p.toString().endsWith(".java"))
				.forEach(javaFiles::add);
		} catch (IOException e) {
			LOG.error("Error finding Java files in directory: {}", rootDir, e);
		}
		return javaFiles;
	}

	/**
	 * Shutdown the workspace model and release resources.
	 * This includes shutting down the background classpath discovery executor
	 * and saving the index to disk.
	 */
	public void shutdown() {
		LOG.info("Shutting down workspace model");
		// Save index before shutdown
		indexCache.save();
		classpathDiscovery.shutdown();
	}
}
