/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.jboss.tools.javac.ls.api.dao.InitializationState;
import org.jboss.tools.javac.ls.index.store.JavaIndex;
import org.jboss.tools.javac.ls.index.visitor.DOMToIndexVisitor;
import org.jboss.tools.javac.ls.parser.bindings.JavacDOMParser;
import org.jboss.tools.javac.ls.parser.dom.cache.DOMCache;
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
	private final DOMCache domCache;
	private final ExecutorService backgroundExecutor;
	private final List<WorkspaceModelListener> listeners;
	private volatile int initializationState = InitializationState.STATE_NOT_STARTED;

	public WorkspaceModel(File workspaceDir) {
		this.workspaceDir = workspaceDir;
		this.workspaceFile = new File(workspaceDir, WORKSPACE_FILE);
		this.projects = new HashMap<>();
		this.gson = new GsonBuilder().setPrettyPrinting().create();
		this.classpathCache = new ClasspathCache(workspaceDir);
		this.classpathDiscovery = new ProjectClasspathDiscovery(classpathCache);
		this.indexCache = new JavaIndexCache(new File(workspaceDir, INDEX_DIR).toPath());
		this.domCache = new DOMCache();
		this.listeners = new CopyOnWriteArrayList<>();
		this.backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "WorkspaceModel-Background");
			t.setDaemon(true);
			return t;
		});

		// Load cached data
		setInitializationState(InitializationState.STATE_LOADING_CACHE);
		load();
		loadIndex();

		// Cache loaded - stay in LOADING_CACHE state
		// Will transition to INDEXING when background indexing starts,
		// or to READY if startBackgroundIndexing() is not called
	}

	/**
	 * Get the current initialization state.
	 *
	 * @return one of STATE_NOT_STARTED, STATE_LOADING_CACHE, STATE_INDEXING, STATE_READY
	 */
	public int getInitializationState() {
		return initializationState;
	}

	/**
	 * Check if cache loading has completed.
	 *
	 * @return true if state is at LOADING_CACHE or beyond
	 */
	public boolean isCacheLoaded() {
		return initializationState >= InitializationState.STATE_LOADING_CACHE;
	}

	/**
	 * Check if currently indexing.
	 *
	 * @return true if in INDEXING state
	 */
	public boolean isIndexing() {
		return initializationState == InitializationState.STATE_INDEXING;
	}

	/**
	 * Check if initialization is complete and workspace is ready.
	 *
	 * @return true if in READY state
	 */
	public boolean isReady() {
		return initializationState == InitializationState.STATE_READY;
	}

	/**
	 * Set the initialization state and notify listeners.
	 *
	 * @param state the new state
	 */
	public void setInitializationState(int state) {
		int oldState = this.initializationState;
		if (oldState != state) {
			LOG.debug("Initialization state transition: {} -> {}", oldState, state);
			this.initializationState = state;
			notifyInitializationStateChanged(oldState, state);
		}
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
		notifyProjectAdded(project);
		return true;
	}

	/**
	 * Remove a project from the workspace.
	 * Note: This only removes it from the workspace model, not from the filesystem.
	 * Also cleans up index entries for files in the removed project.
	 *
	 * @param name the project name
	 * @return true if removed, false if not found
	 */
	public synchronized boolean removeProject(String name) {
		WorkspaceProject removed = projects.remove(name);
		if (removed != null) {
			// Clean up index entries for this project
			cleanupProjectIndex(removed);

			save();
			LOG.info("Removed project '{}' from workspace", name);
			notifyProjectRemoved(removed);
			return true;
		}
		LOG.warn("Project '{}' not found in workspace", name);
		return false;
	}

	/**
	 * Clean up index entries for a removed project.
	 *
	 * @param project the project being removed
	 */
	private void cleanupProjectIndex(WorkspaceProject project) {
		File projectDir = new File(project.getPath());
		if (!projectDir.exists() || !projectDir.isDirectory()) {
			return;
		}

		// Find all .java files in the project
		List<Path> javaFiles = findJavaFiles(projectDir.toPath());
		if (javaFiles.isEmpty()) {
			return;
		}

		LOG.info("Cleaning up index entries for {} files in project '{}'", javaFiles.size(), project.getName());

		// Remove each file from the index
		indexCache.lockWrite();
		try {
			JavaIndex index = indexCache.getIndex();
			for (Path javaFile : javaFiles) {
				index.removeFile(javaFile);
			}
			indexCache.markDirty();
		} finally {
			indexCache.unlockWrite();
		}
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
	 * Get the DOM cache.
	 *
	 * @return the DOM cache
	 */
	public DOMCache getDOMCache() {
		return domCache;
	}

	/**
	 * Index all projects in the workspace.
	 * Parses all .java files and populates the index.
	 * This is a synchronous operation that acquires write lock on the index.
	 */
	public synchronized void indexAllProjects() {
		int previousState = initializationState;
		setInitializationState(InitializationState.STATE_INDEXING);

		try {
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
		} finally {
			// Restore previous state or mark as READY if this was initialization
			setInitializationState((previousState == InitializationState.STATE_INDEXING) ? InitializationState.STATE_READY : previousState);
		}
	}

	/**
	 * Start initialization that parses all files with bindings and re-indexes them.
	 * Can run synchronously (blocking) or asynchronously (background) depending on sync parameter.
	 *
	 * @param sync if true, indexing happens on calling thread (blocks); if false, runs in background
	 */
	public void startIndexing(boolean sync) {
		if (initializationState != InitializationState.STATE_LOADING_CACHE) {
			LOG.warn("Cannot start indexing - expected LOADING_CACHE state but was: {}", initializationState);
			return;
		}

		if (sync) {
			LOG.info("Starting synchronous indexing with binding resolution");
			try {
				setInitializationState(InitializationState.STATE_INDEXING);
				indexAllProjectsWithBindings();
			} catch (Exception e) {
				LOG.error("Synchronous indexing failed", e);
			} finally {
				setInitializationState(InitializationState.STATE_READY);
			}
		} else {
			LOG.info("Starting background indexing with binding resolution");
			backgroundExecutor.submit(() -> {
				try {
					setInitializationState(InitializationState.STATE_INDEXING);
					indexAllProjectsWithBindings();
				} catch (Exception e) {
					LOG.error("Background indexing failed", e);
				} finally {
					setInitializationState(InitializationState.STATE_READY);
				}
			});
		}
	}

	/**
	 * Start background initialization that parses all files with bindings and re-indexes them.
	 * This method returns immediately and the work is done asynchronously.
	 * The initialization state will be set to INDEXING while work is in progress
	 * and READY when complete.
	 */
	public void startBackgroundIndexing() {
		startIndexing(false);
	}

	/**
	 * Index all projects with full binding resolution.
	 * Parses all .java files with bindings, caches DOMs, and re-indexes.
	 * This is a synchronous operation.
	 */
	private void indexAllProjectsWithBindings() {
		LOG.info("Starting indexing with bindings for all projects");
		long startTime = System.currentTimeMillis();
		int totalFiles = 0;

		for (WorkspaceProject project : getProjects()) {
			int filesIndexed = indexProjectWithBindings(project.getName());
			totalFiles += filesIndexed;
		}

		long duration = System.currentTimeMillis() - startTime;
		LOG.info("Indexed {} files with bindings across {} projects in {}ms",
				totalFiles, projects.size(), duration);
	}

	/**
	 * Index a single project with full binding resolution.
	 * Parses all .java files with bindings, caches DOMs, and re-indexes.
	 *
	 * @param projectName the project name
	 * @return number of files indexed
	 */
	private int indexProjectWithBindings(String projectName) {
		WorkspaceProject project = projects.get(projectName);
		if (project == null) {
			LOG.warn("Cannot index unknown project: {}", projectName);
			return 0;
		}

		LOG.info("Indexing project with bindings: {}", projectName);
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

		// Get classpath for parsing (non-blocking to avoid deadlock)
		ArrayList<IJavacClasspathEntry> classpathEntries = getProjectClasspathNonBlocking(projectName, true);
		List<File> classpath = new ArrayList<>();
		for (IJavacClasspathEntry entry : classpathEntries) {
			if (entry.getPath() != null) {
				classpath.add(new File(entry.getPath()));
			}
		}

		// Parse and index files in batches (for memory efficiency)
		int filesIndexed = 0;

		indexCache.lockWrite();
		try {
			JavaIndex index = indexCache.getIndex();

			// Create package-aware batches
			PackageAwareBatcher batcher = new PackageAwareBatcher();
			List<List<Path>> batches = batcher.createBatches(javaFiles);

			LOG.info("Created {} batches for {} files", batches.size(), javaFiles.size());

			// Process each batch
			for (List<Path> batch : batches) {
				try {
					int batchIndexed = indexBatchWithBindings(batch, classpath, index);
					filesIndexed += batchIndexed;
				} catch (Exception e) {
					LOG.error("Failed to index batch of {} files, will try individually", batch.size(), e);

					// Fallback: try each file individually
					for (Path javaFile : batch) {
						try {
							indexFileWithBindings(javaFile, classpath, index);
							filesIndexed++;
						} catch (Exception e2) {
							LOG.error("Failed to index file with bindings: {}", javaFile, e2);
						}
					}
				}
			}

			// Mark index as dirty after indexing
			indexCache.markDirty();
		} finally {
			indexCache.unlockWrite();
		}

		long duration = System.currentTimeMillis() - startTime;
		LOG.info("Indexed {} files with bindings in project '{}' in {}ms",
				filesIndexed, projectName, duration);

		return filesIndexed;
	}

	/**
	 * Index a batch of Java files with full binding resolution in a single parse.
	 * This dramatically reduces memory usage by sharing symbol tables across all files.
	 *
	 * @param javaFiles list of Java files to index
	 * @param classpath the classpath for parsing
	 * @param index the index to populate
	 * @return number of files successfully indexed
	 */
	private int indexBatchWithBindings(List<Path> javaFiles, List<File> classpath, JavaIndex index) {
		if (javaFiles.isEmpty()) {
			return 0;
		}

		LOG.debug("Batch indexing {} files with shared context", javaFiles.size());

		// Read all files into memory
		Map<String, String> sourceFiles = new LinkedHashMap<>();
		for (Path javaFile : javaFiles) {
			try {
				String source = Files.readString(javaFile);
				sourceFiles.put(javaFile.toString(), source);
			} catch (IOException e) {
				LOG.error("Failed to read file: {}", javaFile, e);
			}
		}

		if (sourceFiles.isEmpty()) {
			return 0;
		}

		// Parse all files in a single batch (shared Context!)
		JavacDOMParser parser = new JavacDOMParser();
		Map<String, CompilationUnit> units = parser.parseBatch(
			sourceFiles,
			classpath,
			AST.JLS21,
			null,  // compiler options
			true   // resolve bindings
		);

		// Index each parsed compilation unit
		int indexed = 0;
		for (Map.Entry<String, CompilationUnit> entry : units.entrySet()) {
			String fileName = entry.getKey();
			CompilationUnit cu = entry.getValue();

			if (cu == null) {
				LOG.warn("Failed to parse file in batch: {}", fileName);
				continue;
			}

			try {
				Path javaFile = Path.of(fileName);

				// Remove old declarations for this file (incremental update)
				index.removeFile(javaFile);

				// Visit AST and populate index
				DOMToIndexVisitor visitor = new DOMToIndexVisitor(index, javaFile);
				cu.accept(visitor);
				visitor.finishIndexing();

				indexed++;

				LOG.debug("Indexed file from batch: {} ({} problems)",
					javaFile, cu.getProblems() != null ? cu.getProblems().length : 0);
			} catch (Exception e) {
				LOG.error("Failed to index compilation unit: {}", fileName, e);
			}
		}

		LOG.debug("Batch indexed {}/{} files successfully", indexed, javaFiles.size());
		return indexed;
	}

	/**
	 * Index a single Java file with full binding resolution.
	 * Parses with bindings, caches the DOM, and populates the index.
	 *
	 * @param javaFile the Java file to index
	 * @param classpath the classpath for parsing
	 * @param index the index to populate
	 */
	private void indexFileWithBindings(Path javaFile, List<File> classpath, JavaIndex index) {
		URI fileUri = javaFile.toUri();

		// Parse with bindings and cache the result
		CompilationUnit cu = domCache.getCompilationUnit(
				fileUri,
				classpath,
				AST.JLS21,
				null, // compiler options
				true  // resolve bindings
		);

		if (cu == null) {
			LOG.warn("Failed to parse file: {}", javaFile);
			return;
		}

		// Remove old declarations for this file (incremental update)
		index.removeFile(javaFile);

		// Visit AST and populate index
		DOMToIndexVisitor visitor = new DOMToIndexVisitor(index, javaFile);
		cu.accept(visitor);
		visitor.finishIndexing();

		LOG.debug("Indexed file with bindings: {} ({} problems)",
				javaFile, cu.getProblems() != null ? cu.getProblems().length : 0);
	}

	/**
	 * Index a single project asynchronously in the background.
	 * Changes initialization state to INDEXING while indexing,
	 * then back to READY when complete.
	 *
	 * @param projectName the project name
	 */
	public void indexProjectAsync(String projectName) {
		int previousState = initializationState;
		setInitializationState(InitializationState.STATE_INDEXING);

		backgroundExecutor.submit(() -> {
			try {
				indexProject(projectName);
			} catch (Exception e) {
				LOG.error("Background indexing failed for project: {}", projectName, e);
			} finally {
				// Restore to READY (or previous state if it wasn't READY)
				setInitializationState(
					previousState == InitializationState.STATE_READY || previousState == InitializationState.STATE_INDEXING
						? InitializationState.STATE_READY
						: previousState
				);
			}
		});
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

		// Shutdown background executor
		backgroundExecutor.shutdown();
		try {
			if (!backgroundExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
				LOG.warn("Background executor did not terminate in time, forcing shutdown");
				backgroundExecutor.shutdownNow();
			}
		} catch (InterruptedException e) {
			LOG.error("Interrupted while waiting for background executor to shut down", e);
			backgroundExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}

		// Save index before shutdown
		indexCache.save();
		classpathDiscovery.shutdown();
	}

	/**
	 * Add a listener for workspace model changes.
	 *
	 * @param listener the listener to add
	 */
	public void addListener(WorkspaceModelListener listener) {
		if (listener != null) {
			listeners.add(listener);
		}
	}

	/**
	 * Remove a listener for workspace model changes.
	 *
	 * @param listener the listener to remove
	 */
	public void removeListener(WorkspaceModelListener listener) {
		listeners.remove(listener);
	}

	/**
	 * Notify listeners of initialization state change.
	 *
	 * @param oldState the old state
	 * @param newState the new state
	 */
	private void notifyInitializationStateChanged(int oldState, int newState) {
		for (WorkspaceModelListener listener : listeners) {
			try {
				listener.initializationStateChanged(oldState, newState);
			} catch (Exception e) {
				LOG.error("Error notifying listener of initialization state change", e);
			}
		}
	}

	/**
	 * Notify listeners that a project was added.
	 *
	 * @param project the project that was added
	 */
	private void notifyProjectAdded(WorkspaceProject project) {
		for (WorkspaceModelListener listener : listeners) {
			try {
				listener.projectAdded(project);
			} catch (Exception e) {
				LOG.error("Error notifying listener of project addition", e);
			}
		}
	}

	/**
	 * Notify listeners that a project was removed.
	 *
	 * @param project the project that was removed
	 */
	private void notifyProjectRemoved(WorkspaceProject project) {
		for (WorkspaceModelListener listener : listeners) {
			try {
				listener.projectRemoved(project);
			} catch (Exception e) {
				LOG.error("Error notifying listener of project removal", e);
			}
		}
	}
}
