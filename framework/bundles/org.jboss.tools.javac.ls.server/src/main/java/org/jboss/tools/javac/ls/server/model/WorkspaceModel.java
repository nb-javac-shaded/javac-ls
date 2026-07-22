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
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jboss.tools.javac.ls.api.dao.Diagnostic;
import org.jboss.tools.javac.ls.api.dao.DiagnosticList;
import org.jboss.tools.javac.ls.api.dao.InitializationState;
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
import shaded.org.eclipse.jdt.core.compiler.IProblem;
import shaded.org.eclipse.jdt.internal.compiler.problem.ProblemSeverities;

/**
 * Manages the workspace model - mapping project names to filesystem paths.
 * Projects are managed in-memory and initialized via LSP workspaceFolders.
 */
public class WorkspaceModel {
	private static final Logger LOG = LoggerFactory.getLogger(WorkspaceModel.class);
	private static final String INDEX_DIR = "index";

	private final File workspaceDir;
	private final Map<String, WorkspaceProject> projects;
	private final Gson gson;
	private final ClasspathCache classpathCache;
	private final ProjectClasspathDiscovery classpathDiscovery;
	private final JavaIndexCache indexCache;
	private final ExecutorService backgroundExecutor;
	private final ScheduledExecutorService periodicScanExecutor;
	private final Set<String> projectsCurrentlyScanning;
	private final List<WorkspaceModelListener> listeners;
	private volatile int initializationState = InitializationState.STATE_NOT_STARTED;
	private volatile boolean periodicScannerStarted = false;

	// Batch tracking (in-memory only, rebuilt on startup)
	private final Map<Path, Integer> fileToOriginalBatch = new ConcurrentHashMap<>();
	private final Map<Integer, BatchInfo> batches = new ConcurrentHashMap<>();
	private final AtomicInteger nextBatchId = new AtomicInteger(0);

	// Batch size constants
	private static final int TARGET_BATCH_SIZE = 200;  // Target during initial indexing
	private static final int MAX_BATCH_SIZE = 250;     // Split threshold

	public WorkspaceModel(File workspaceDir) {
		this.workspaceDir = workspaceDir;
		this.projects = new HashMap<>();
		this.gson = new GsonBuilder().setPrettyPrinting().create();
		this.classpathCache = new ClasspathCache(workspaceDir);
		this.classpathDiscovery = new ProjectClasspathDiscovery(classpathCache);
		this.indexCache = new JavaIndexCache(new File(workspaceDir, INDEX_DIR).toPath());
		this.listeners = new CopyOnWriteArrayList<>();
		this.backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "WorkspaceModel-Background");
			t.setDaemon(true);
			return t;
		});
		this.periodicScanExecutor = Executors.newScheduledThreadPool(1, r -> {
			Thread t = new Thread(r, "WorkspaceModel-PeriodicScan");
			t.setDaemon(true);
			return t;
		});
		this.projectsCurrentlyScanning = Collections.synchronizedSet(new HashSet<>());

		// Load cached data
		setInitializationState(InitializationState.STATE_LOADING_CACHE);
		loadIndex();

		// Periodic file change scanner will be started after indexing completes
		// (see setInitializationState when transitioning to STATE_READY)

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

			// Start periodic file scanner after initial indexing completes
			if (state == InitializationState.STATE_READY && oldState == InitializationState.STATE_INDEXING) {
				startPeriodicFileChangeScanner();
			}

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
	 * Holder for separated classpath and sourcepath entries.
	 */
	private static class ClasspathAndSourcepath {
		final List<File> classpath;
		final List<String> sourcepath;

		ClasspathAndSourcepath(List<File> classpath, List<String> sourcepath) {
			this.classpath = classpath;
			this.sourcepath = sourcepath;
		}
	}

	/**
	 * Get classpath and sourcepath for a project, properly separated.
	 * SOURCE entries go to sourcepath, LIBRARY entries go to classpath.
	 * This method performs blocking classpath discovery if needed.
	 *
	 * @param projectName the project name
	 * @return separated classpath and sourcepath
	 */
	private ClasspathAndSourcepath getClasspathAndSourcepath(String projectName) {
		List<File> classpath = new ArrayList<>();
		List<String> sourcepath = new ArrayList<>();

		// Use blocking classpath discovery to ensure we have valid results
		ArrayList<IJavacClasspathEntry> classpathEntries = getProjectClasspath(projectName);
		if (classpathEntries != null) {
			for (IJavacClasspathEntry entry : classpathEntries) {
				if (entry.getPath() != null) {
					if (entry.getType() == IJavacClasspathEntry.EntryType.SOURCE) {
						sourcepath.add(entry.getPath());
					} else {
						classpath.add(new File(entry.getPath()));
					}
				}
			}
		}

		return new ClasspathAndSourcepath(classpath, sourcepath);
	}

	/**
	 * Build compiler options map with sourcepath configured.
	 *
	 * @param sourcepath the source paths
	 * @return compiler options map
	 */
	private Map<String, String> buildCompilerOptions(List<String> sourcepath) {
		Map<String, String> options = new HashMap<>();
		if (sourcepath != null && !sourcepath.isEmpty()) {
			options.put("javac.sourcepath", String.join(File.pathSeparator, sourcepath));
		}
		return options;
	}

	/**
	 * Load the index from disk.
	 */
	private void loadIndex() {
		indexCache.load();
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
	 * Parse a file on-demand with bindings resolved.
	 * Does not cache the result - parses fresh every time.
	 *
	 * @param fileUri URI of the file to parse
	 * @param classpath classpath for resolving bindings
	 * @param compilerOptions compiler options (can be null)
	 * @return parsed CompilationUnit with bindings, or null if parsing fails
	 */
	public CompilationUnit parseFile(URI fileUri, List<File> classpath, Map<String, String> compilerOptions) {
		File sourceFile = new File(fileUri);
		if (!sourceFile.exists()) {
			LOG.warn("Source file does not exist: {}", fileUri);
			return null;
		}

		try {
			String sourceContent = new String(Files.readAllBytes(Paths.get(fileUri)));
			String fileName = sourceFile.getAbsolutePath();

			JavacDOMParser parser = new JavacDOMParser();
			return parser.parse(
				sourceContent,
				fileName,
				classpath,
				AST.JLS21,
				compilerOptions,
				true // resolve bindings
			);
		} catch (IOException e) {
			LOG.error("Failed to read source file: {}", fileUri, e);
			return null;
		}
	}

	/**
	 * Get diagnostics (errors, warnings) for all files in a project.
	 * Scans for changed files before collecting diagnostics to ensure results are up-to-date.
	 *
	 * @param projectName the project name
	 * @return list of diagnostics for all files in the project
	 */
	public DiagnosticList getProjectDiagnostics(String projectName) {
		WorkspaceProject project = projects.get(projectName);
		if (project == null) {
			LOG.warn("Cannot get diagnostics for unknown project: {}", projectName);
			return new DiagnosticList(projectName);
		}

		// Scan for changed files first to ensure diagnostics are up-to-date
		scanAndReparseChangedFiles(projectName);

		DiagnosticList result = new DiagnosticList(projectName);

		// Get index
		JavaIndex index = indexCache.getIndex();

		// Find all Java files in the project
		List<Path> javaFiles = findJavaFiles(Paths.get(project.getPath()));

		// Collect diagnostics from index for each file
		for (Path file : javaFiles) {
			List<String> diagnosticJsonList = index.getDiagnostics(file);

			// Deserialize diagnostics from JSON
			for (String json : diagnosticJsonList) {
				try {
					Diagnostic diag = gson.fromJson(json, Diagnostic.class);
					result.addDiagnostic(diag);
				} catch (Exception e) {
					LOG.error("Failed to deserialize diagnostic from index for file {}: {}", file, json, e);
				}
			}
		}

		LOG.debug("Retrieved {} diagnostics for project {} from index", result.getDiagnostics().size(), projectName);
		return result;
	}

	/**
	 * Get diagnostics (errors, warnings) for a specific file.
	 * Scans for changed files before collecting diagnostics to ensure results are up-to-date.
	 *
	 * @param filePath the absolute path to the file
	 * @return list of diagnostics for the file
	 */
	public DiagnosticList getFileDiagnostics(String filePath) {
		Path file = Paths.get(filePath);

		// Find which project this file belongs to
		String projectName = null;
		for (WorkspaceProject project : projects.values()) {
			Path projectPath = Paths.get(project.getPath());
			if (file.startsWith(projectPath)) {
				projectName = project.getName();
				break;
			}
		}

		if (projectName == null) {
			LOG.warn("Cannot find project for file: {}", filePath);
			return new DiagnosticList(null, filePath);
		}

		// Scan for changed files first to ensure diagnostics are up-to-date
		scanAndReparseChangedFiles(projectName);

		DiagnosticList result = new DiagnosticList(projectName, filePath);

		// Get diagnostics from index (already extracted during indexing)
		JavaIndex index = indexCache.getIndex();
		List<String> diagnosticJsonList = index.getDiagnostics(file);

		// Deserialize diagnostics from JSON
		for (String json : diagnosticJsonList) {
			try {
				Diagnostic diag = gson.fromJson(json, Diagnostic.class);
				result.addDiagnostic(diag);
			} catch (Exception e) {
				LOG.error("Failed to deserialize diagnostic from index: {}", json, e);
			}
		}

		LOG.debug("Retrieved {} diagnostics for file {} from index", result.getDiagnostics().size(), filePath);
		return result;
	}

	/**
	 * Convert JDT IProblem to Diagnostic DAO.
	 */
	private Diagnostic convertProblemToDiagnostic(IProblem problem, String filePath) {
		Diagnostic diag = new Diagnostic();
		diag.setFilePath(filePath);
		diag.setMessage(problem.getMessage());
		diag.setLineNumber(problem.getSourceLineNumber());
		diag.setStartPosition(problem.getSourceStart());
		diag.setEndPosition(problem.getSourceEnd());

		// Convert severity
		if (problem.isError()) {
			diag.setSeverity(Diagnostic.ERROR);
		} else if (problem.isWarning()) {
			diag.setSeverity(Diagnostic.WARNING);
		} else {
			diag.setSeverity(Diagnostic.INFO);
		}

		// Set column number (approximate from start position and line start)
		// IProblem doesn't directly provide column, so we estimate
		diag.setColumnNumber(0); // TODO: calculate column from source

		return diag;
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
			LOG.info("Starting synchronous two-phase indexing");
			try {
				setInitializationState(InitializationState.STATE_INDEXING);
				// Phase 1: fast index (parse-only, no classpath needed)
				indexAllProjectsFast();
			} catch (Exception e) {
				LOG.error("Synchronous indexing failed", e);
			} finally {
				setInitializationState(InitializationState.STATE_READY);
			}
			// Phase 2: re-index with bindings (blocking classpath discovery + analyze)
			try {
				indexAllProjectsWithBindings();
			} catch (Exception e) {
				LOG.error("Binding resolution pass failed", e);
			}
		} else {
			LOG.info("Starting background two-phase indexing");
			backgroundExecutor.submit(() -> {
				try {
					setInitializationState(InitializationState.STATE_INDEXING);
					// Phase 1: fast index (parse-only, no classpath needed)
					indexAllProjectsFast();
				} catch (Exception e) {
					LOG.error("Background indexing failed", e);
				} finally {
					setInitializationState(InitializationState.STATE_READY);
				}
				// Phase 2: re-index with bindings (blocking classpath discovery + analyze)
				// Server is already READY — diagnostics trickle in via publishDiagnostics
				try {
					indexAllProjectsWithBindings();
				} catch (Exception e) {
					LOG.error("Background binding resolution pass failed", e);
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
	 * Index all projects without analyze() for fast startup.
	 * Builds the symbol index only — diagnostics are collected separately in Phase 2.
	 */
	private void indexAllProjectsFast() {
		LOG.info("Phase 1: fast indexing (no analyze) for all projects");
		long startTime = System.currentTimeMillis();
		int totalFiles = 0;

		for (WorkspaceProject project : getProjects()) {
			String projectName = project.getName();
			WorkspaceProject p = projects.get(projectName);
			if (p == null) continue;

			File projectDir = new File(p.getPath());
			if (!projectDir.exists() || !projectDir.isDirectory()) continue;

			List<Path> javaFiles = findJavaFiles(projectDir.toPath());
			if (javaFiles.isEmpty()) continue;

			ArrayList<IJavacClasspathEntry> classpathEntries = getProjectClasspathNonBlocking(projectName, false);
			List<File> classpath = new ArrayList<>();
			for (IJavacClasspathEntry entry : classpathEntries) {
				if (entry.getPath() != null) {
					classpath.add(new File(entry.getPath()));
				}
			}

			int filesIndexed = 0;
			indexCache.lockWrite();
			try {
				JavaIndex index = indexCache.getIndex();
				int batchSize = 100;
				for (int i = 0; i < javaFiles.size(); i += batchSize) {
					int endIndex = Math.min(i + batchSize, javaFiles.size());
					List<Path> batch = javaFiles.subList(i, endIndex);
					try {
						filesIndexed += indexBatch(batch, classpath, index, false);
					} catch (Exception e) {
						LOG.error("Failed to index batch {}-{}", i, endIndex, e);
					}
				}
				indexCache.markDirty();
			} finally {
				indexCache.unlockWrite();
			}

			totalFiles += filesIndexed;
			LOG.info("Fast-indexed {} files in project '{}'", filesIndexed, projectName);
		}

		long duration = System.currentTimeMillis() - startTime;
		LOG.info("Phase 1 complete: indexed {} files across {} projects in {}ms",
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

		// Get classpath and sourcepath for parsing (blocking — ensures real classpath)
		ClasspathAndSourcepath paths = getClasspathAndSourcepath(projectName);
		if (paths.classpath.isEmpty()) {
			LOG.warn("No classpath discovered for project '{}' — skipping binding resolution pass", projectName);
			return 0;
		}
		Map<String, String> compilerOptions = buildCompilerOptions(paths.sourcepath);

		// Parse and index files in batches (for memory efficiency)
		int filesIndexed = 0;

		indexCache.lockWrite();
		try {
			JavaIndex index = indexCache.getIndex();

			// Create package-aware batches
			PackageAwareBatcher batcher = new PackageAwareBatcher();
			List<List<Path>> batchGroups = batcher.createBatches(javaFiles);

			LOG.info("Created {} batches for {} files", batchGroups.size(), javaFiles.size());

			// Process each batch and track it
			for (List<Path> batchFiles : batchGroups) {
				int batchId = nextBatchId.getAndIncrement();
				BatchInfo batchInfo = new BatchInfo(batchId);
				batchInfo.files.addAll(batchFiles);
				this.batches.put(batchId, batchInfo);

				// Map each file to its batch
				for (Path file : batchFiles) {
					fileToOriginalBatch.put(file, batchId);
				}

				try {
					int batchIndexed = indexBatchWithBindings(batchFiles, paths.classpath, index);
					filesIndexed += batchIndexed;
				} catch (Exception e) {
					LOG.error("Failed to index batch {} of {} files, will try individually", batchId, batchFiles.size(), e);

					// Fallback: try each file individually
					for (Path javaFile : batchFiles) {
						try {
							indexFileWithBindings(projectName, javaFile, paths.classpath, compilerOptions, index);
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

				// Extract and store diagnostics
				storeDiagnosticsForFile(index, javaFile, cu);

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
	 * @param projectName the project name
	 * @param javaFile the Java file to index
	 * @param classpath the classpath for parsing
	 * @param compilerOptions compiler options (including sourcepath)
	 * @param index the index to populate
	 */
	private void indexFileWithBindings(String projectName, Path javaFile, List<File> classpath,
			Map<String, String> compilerOptions, JavaIndex index) {
		URI fileUri = javaFile.toUri();

		// Parse with bindings to index the file
		CompilationUnit cu = parseFile(fileUri, classpath, compilerOptions);

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

		// Extract and store diagnostics
		storeDiagnosticsForFile(index, javaFile, cu);

		LOG.debug("Indexed file with bindings: {} ({} problems)",
				javaFile, cu.getProblems() != null ? cu.getProblems().length : 0);
	}

	/**
	 * Extract diagnostics from a CompilationUnit and store in the index.
	 *
	 * @param index the index to store diagnostics in
	 * @param file the source file
	 * @param cu the compilation unit with problems
	 */
	private void storeDiagnosticsForFile(JavaIndex index, Path file, CompilationUnit cu) {
		IProblem[] problems = cu.getProblems();
		if (problems == null || problems.length == 0) {
			index.storeDiagnostics(file, Collections.emptyList());
			return;
		}

		// Convert problems to JSON strings for storage
		List<String> diagnosticStrings = new ArrayList<>();
		for (IProblem problem : problems) {
			Diagnostic diag = convertProblemToDiagnostic(problem, file.toString());
			String json = gson.toJson(diag);
			diagnosticStrings.add(json);
		}

		index.storeDiagnostics(file, diagnosticStrings);
	}

	/**
	 * Index a single project asynchronously in the background using two-phase indexing.
	 * Phase 1: fast parse-only index (no analyze, no bindings) → fires READY when done.
	 * Phase 2: re-index with binding resolution in background (diagnostics trickle in).
	 *
	 * @param projectName the project name
	 */
	public void indexProjectAsync(String projectName) {
		setInitializationState(InitializationState.STATE_INDEXING);

		backgroundExecutor.submit(() -> {
			try {
				indexProject(projectName);
			} catch (Exception e) {
				LOG.error("Background indexing Phase 1 failed for project: {}", projectName, e);
			} finally {
				setInitializationState(InitializationState.STATE_READY);
			}
			// Phase 2: re-index with bindings (blocking classpath discovery + analyze)
			try {
				indexProjectWithBindings(projectName);
			} catch (Exception e) {
				LOG.error("Background indexing Phase 2 failed for project: {}", projectName, e);
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

		int filesIndexed = 0;
		long totalBatchTime = 0;
		int batchCount = 0;
		indexCache.lockWrite();
		try {
			JavaIndex index = indexCache.getIndex();

			// Parse all files in batches (without binding resolution for performance)
			// Batching shares the JavacFileManager/Context overhead across files
			int batchSize = 100; // Parse 100 files per batch
			for (int i = 0; i < javaFiles.size(); i += batchSize) {
				int endIndex = Math.min(i + batchSize, javaFiles.size());
				List<Path> batch = javaFiles.subList(i, endIndex);

				try {
					long batchStart = System.currentTimeMillis();
					int indexed = indexBatch(batch, classpath, index, false);
					long batchTime = System.currentTimeMillis() - batchStart;
					totalBatchTime += batchTime;
					batchCount++;

					filesIndexed += indexed;
					LOG.debug("Indexed batch {}/{}: {} files in {}ms (avg {}/file)",
							(i / batchSize) + 1,
							(javaFiles.size() + batchSize - 1) / batchSize,
							indexed, batchTime,
							String.format("%.1fms", (double)batchTime / indexed));
				} catch (Exception e) {
					LOG.error("Failed to index batch {}-{}, falling back to individual parsing", i, endIndex, e);
					// Fallback: parse individually
					JavacDOMParser parser = new JavacDOMParser();
					for (Path javaFile : batch) {
						try {
							indexFile(javaFile, parser, classpath, index);
							filesIndexed++;
						} catch (Exception e2) {
							LOG.error("Failed to index file: {}", javaFile, e2);
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
		LOG.info("Indexed {} files in project '{}' in {}ms", filesIndexed, projectName, duration);
		if (batchCount > 0) {
			long avgBatchTime = totalBatchTime / batchCount;
			long overhead = duration - totalBatchTime;
			LOG.info("Batch statistics: {} batches, avg {}ms/batch, overhead={}ms ({}%)",
					batchCount, avgBatchTime, overhead,
					String.format("%.1f", 100.0 * overhead / duration));
		}

		return filesIndexed;
	}

	/**
	 * Rebatch dirty batches to consolidate memory.
	 * Re-parses entire batches that contain individually-parsed files,
	 * allowing the old individual parse Contexts to be GC'd while maintaining
	 * package coherence.
	 *
	 * @param projectName the project name
	 */
	private void rebatchDirtyBatches(String projectName) {
		WorkspaceProject project = projects.get(projectName);
		if (project == null) {
			LOG.warn("Cannot rebatch for unknown project: {}", projectName);
			return;
		}

		ClasspathAndSourcepath paths = getClasspathAndSourcepath(projectName);
		JavaIndex index = indexCache.getIndex();

		indexCache.lockWrite();
		try {
			// Collect dirty batches
			List<BatchInfo> dirtyBatches = batches.values().stream()
				.filter(b -> b.isDirty)
				.collect(Collectors.toList());

			if (dirtyBatches.isEmpty()) {
				LOG.debug("No dirty batches to rebatch");
			} else {
				LOG.info("Rebatching {} dirty batches", dirtyBatches.size());

				for (BatchInfo dirtyBatch : dirtyBatches) {
					if (dirtyBatch.needsSplit) {
						splitBatch(dirtyBatch, paths, index);
					} else {
						rebatchSingleBatch(dirtyBatch, paths, index);
					}
				}
			}

			// Clear individually-parsed tracking regardless of whether we rebatched
			// (we hit the threshold, so we should reset the counter)
			indexCache.clearIndividuallyParsedFiles();

		} finally {
			indexCache.unlockWrite();
		}
	}

	/**
	 * Re-parse a single batch together.
	 */
	private void rebatchSingleBatch(BatchInfo batch, ClasspathAndSourcepath paths, JavaIndex index) {
		LOG.info("Rebatching batch {} ({} files)", batch.id, batch.files.size());

		// Re-parse entire batch together
		indexBatchWithBindings(new ArrayList<>(batch.files), paths.classpath, index);

		batch.isDirty = false;
		batch.needsSplit = false;
	}

	/**
	 * Split a batch that exceeds MAX_BATCH_SIZE using package-aware batching.
	 */
	private void splitBatch(BatchInfo batch, ClasspathAndSourcepath paths, JavaIndex index) {
		LOG.info("Splitting batch {} ({} files exceeds MAX_BATCH_SIZE {})",
			batch.id, batch.files.size(), MAX_BATCH_SIZE);

		// Use PackageAwareBatcher to split
		PackageAwareBatcher batcher = new PackageAwareBatcher();
		List<List<Path>> newBatches = batcher.createBatches(new ArrayList<>(batch.files));

		LOG.info("Split batch {} into {} new batches", batch.id, newBatches.size());

		// Remove old batch
		batches.remove(batch.id);

		// Create new batches
		for (List<Path> newBatchFiles : newBatches) {
			int newBatchId = nextBatchId.getAndIncrement();
			BatchInfo newBatch = new BatchInfo(newBatchId);
			newBatch.files.addAll(newBatchFiles);
			batches.put(newBatchId, newBatch);

			// Update file → batch mappings
			for (Path file : newBatchFiles) {
				fileToOriginalBatch.put(file, newBatchId);
			}

			// Parse new batch
			indexBatchWithBindings(newBatchFiles, paths.classpath, index);
		}
	}

	/**
	 * Find a suitable batch for a new file based on package.
	 * Returns null if no suitable batch found.
	 */
	private Integer findBatchForPackage(Path file) {
		String packageName = extractPackageName(file);

		// Find batch with same package that has room
		for (BatchInfo batch : batches.values()) {
			if (batch.files.size() < MAX_BATCH_SIZE) {
				// Check if any file in batch shares package
				for (Path batchFile : batch.files) {
					if (extractPackageName(batchFile).equals(packageName)) {
						return batch.id;
					}
				}
			}
		}

		return null;
	}

	/**
	 * Extract package name from file path.
	 * Delegates to PackageAwareBatcher's logic.
	 */
	private String extractPackageName(Path file) {
		Path parent = file.getParent();
		if (parent == null) {
			return "";
		}

		// Convert path to string and look for common patterns
		String pathStr = parent.toString();

		// Look for common source roots
		int srcIndex = findSourceRoot(pathStr);
		if (srcIndex >= 0) {
			// Extract everything after source root
			String afterSrc = pathStr.substring(srcIndex);
			// Convert path separators to dots
			return afterSrc.replace('/', '.').replace('\\', '.');
		}

		// Fallback: use last 2-3 path components as package
		int componentCount = 0;
		StringBuilder pkg = new StringBuilder();
		for (int i = parent.getNameCount() - 1; i >= 0 && componentCount < 3; i--) {
			String component = parent.getName(i).toString();
			if (pkg.length() > 0) {
				pkg.insert(0, '.');
			}
			pkg.insert(0, component);
			componentCount++;
		}

		return pkg.toString();
	}

	/**
	 * Find the index of source root in path string.
	 */
	private int findSourceRoot(String pathStr) {
		String[] patterns = {
			"/src/main/java/",
			"/src/test/java/",
			"\\src\\main\\java\\",
			"\\src\\test\\java\\",
			"/src/",
			"\\src\\",
			"/source/",
			"\\source\\"
		};

		for (String pattern : patterns) {
			int index = pathStr.indexOf(pattern);
			if (index >= 0) {
				return index + pattern.length();
			}
		}

		return -1;
	}

	/**
	 * Try to merge a batch that has become small due to file deletions.
	 */
	private void tryMergeBatch(BatchInfo smallBatch) {
		if (smallBatch.files.isEmpty()) {
			batches.remove(smallBatch.id);
			return;
		}

		// Find a merge candidate: same package, combined size < MAX_BATCH_SIZE
		String packageName = extractPackageName(smallBatch.files.iterator().next());

		for (BatchInfo candidate : batches.values()) {
			if (candidate.id == smallBatch.id) {
				continue;
			}

			// Check if candidate is from same package
			if (!candidate.files.isEmpty()) {
				String candidatePackage = extractPackageName(candidate.files.iterator().next());
				if (candidatePackage.equals(packageName)) {
					int combinedSize = smallBatch.files.size() + candidate.files.size();
					if (combinedSize <= MAX_BATCH_SIZE) {
						// Merge!
						LOG.info("Merging batch {} ({} files) into batch {} ({} files)",
							smallBatch.id, smallBatch.files.size(),
							candidate.id, candidate.files.size());

						candidate.files.addAll(smallBatch.files);
						candidate.isDirty = true;

						// Update mappings
						for (Path file : smallBatch.files) {
							fileToOriginalBatch.put(file, candidate.id);
						}

						// Remove small batch
						batches.remove(smallBatch.id);
						return;
					}
				}
			}
		}

		// No suitable merge candidate found, keep as-is
	}

	/**
	 * Handle new files that weren't previously tracked.
	 * Adds them to appropriate batches based on package.
	 */
	private void handleNewFiles(String projectName, Collection<Path> newFiles) {
		ClasspathAndSourcepath paths = getClasspathAndSourcepath(projectName);
		Map<String, String> compilerOptions = buildCompilerOptions(paths.sourcepath);
		JavaIndex index = indexCache.getIndex();

		indexCache.lockWrite();
		try {
			for (Path newFile : newFiles) {
				// Parse individually first (for quick response)
				indexFileWithBindings(projectName, newFile, paths.classpath, compilerOptions, index);
				indexCache.trackIndividuallyParsedFile(newFile);

				// Find or create batch for this file
				Integer batchId = findBatchForPackage(newFile);

				if (batchId == null) {
					// No suitable batch found, create new single-file batch
					batchId = nextBatchId.getAndIncrement();
					BatchInfo newBatch = new BatchInfo(batchId);
					batches.put(batchId, newBatch);
					LOG.debug("Created new batch {} for file {}", batchId, newFile);
				}

				BatchInfo batch = batches.get(batchId);
				batch.files.add(newFile);
				fileToOriginalBatch.put(newFile, batchId);
				batch.isDirty = true;

				// Check if batch needs splitting
				if (batch.files.size() > MAX_BATCH_SIZE) {
					LOG.debug("Batch {} exceeded MAX_BATCH_SIZE, marking for split", batchId);
					batch.needsSplit = true;
				}

				// Notify listeners
				String filePath = newFile.toString();
				DiagnosticList diagnostics = getFileDiagnostics(filePath);
				notifyFileDiagnosticsChanged(filePath, diagnostics);
			}

			indexCache.markDirty();
		} finally {
			indexCache.unlockWrite();
		}

		// Check if we should rebatch
		if (indexCache.shouldRebatch()) {
			LOG.info("Rebatch threshold reached after adding new files");
			rebatchDirtyBatches(projectName);
		}
	}

	/**
	 * Handle deleted files by removing them from batches and index.
	 */
	private void handleDeletedFiles(Collection<Path> deletedFiles) {
		indexCache.lockWrite();
		try {
			JavaIndex index = indexCache.getIndex();

			for (Path file : deletedFiles) {
				// Remove from batch tracking
				Integer batchId = fileToOriginalBatch.remove(file);
				if (batchId != null) {
					BatchInfo batch = batches.get(batchId);
					if (batch != null) {
						batch.files.remove(file);
						batch.isDirty = true;

						// Consider merging if batch is now small
						if (batch.files.size() < TARGET_BATCH_SIZE / 2) {
							tryMergeBatch(batch);
						}
					}
				}

				// Remove from index
				index.removeFile(file);

				// Notify listeners (empty diagnostics for deleted file)
				notifyFileDiagnosticsChanged(file.toString(), new DiagnosticList());
			}

			indexCache.markDirty();
		} finally {
			indexCache.unlockWrite();
		}
	}

	/**
	 * Reparse a collection of files and update the index.
	 * Files are first removed from the index, then re-parsed with bindings,
	 * and added back to the index. Uses the rebatching strategy to manage memory.
	 *
	 * @param projectName the project name
	 * @param files the files to reparse
	 */
	public void reparseFiles(String projectName, Collection<Path> files) {
		if (files == null || files.isEmpty()) {
			return;
		}

		WorkspaceProject project = projects.get(projectName);
		if (project == null) {
			LOG.warn("Cannot reparse files for unknown project: {}", projectName);
			return;
		}

		LOG.debug("Reparsing {} files in project {}", files.size(), projectName);

		// Get classpath and sourcepath for parsing
		ClasspathAndSourcepath paths = getClasspathAndSourcepath(projectName);
		Map<String, String> compilerOptions = buildCompilerOptions(paths.sourcepath);

		// Remove files from index and reparse them
		indexCache.lockWrite();
		try {
			JavaIndex index = indexCache.getIndex();

			// Remove old declarations
			for (Path file : files) {
				index.removeFile(file);
			}

			// Reparse files individually (for fast response)
			for (Path file : files) {
				indexFileWithBindings(projectName, file, paths.classpath, compilerOptions, index);
				indexCache.trackIndividuallyParsedFile(file);

				// Notify listeners about diagnostics change after reparsing
				String filePath = file.toString();
				DiagnosticList diagnostics = getFileDiagnostics(filePath);
				notifyFileDiagnosticsChanged(filePath, diagnostics);
			}

			// Mark affected batches as dirty
			for (Path file : files) {
				Integer batchId = fileToOriginalBatch.get(file);
				if (batchId != null) {
					BatchInfo batch = batches.get(batchId);
					if (batch != null) {
						batch.isDirty = true;
					}
				}
			}

			// Mark index as dirty
			indexCache.markDirty();
		} finally {
			indexCache.unlockWrite();
		}

		// Check if we should rebatch to consolidate memory
		if (indexCache.shouldRebatch()) {
			LOG.info("Rebatch threshold reached ({} files), rebatching dirty batches",
					indexCache.getIndividuallyParsedCount());
			rebatchDirtyBatches(projectName);
		}
	}

	/**
	 * Scan for files with changed timestamps and reparse them.
	 * This method is non-blocking: if the project is already being scanned,
	 * it returns immediately without waiting or blocking.
	 *
	 * IMPORTANT: This should be called before handling requests that depend on
	 * up-to-date index data:
	 * - Find references requests
	 * - Diagnostics requests
	 * - Code completion requests
	 * - Hover/documentation requests
	 *
	 * @param projectName the project name
	 * @return true if scan was performed, false if skipped (already scanning)
	 */
	public boolean scanAndReparseChangedFiles(String projectName) {
		// Non-blocking check: skip if already scanning this project
		if (!projectsCurrentlyScanning.add(projectName)) {
			LOG.debug("Project {} is already being scanned, skipping", projectName);
			return false;
		}

		try {
			WorkspaceProject project = projects.get(projectName);
			if (project == null) {
				LOG.debug("Cannot scan unknown project: {}", projectName);
				return false;
			}

			LOG.debug("Scanning project {} for changed files", projectName);

			// Find all Java files in the project
			List<Path> allJavaFiles = findJavaFiles(Paths.get(project.getPath()));
			if (allJavaFiles.isEmpty()) {
				return true;
			}

			// Check which files have changed since they were indexed
			List<Path> changedFiles = new ArrayList<>();
			List<Path> newFiles = new ArrayList<>();
			Set<Path> allJavaFilesSet = new HashSet<>(allJavaFiles);

			indexCache.lockRead();
			try {
				JavaIndex index = indexCache.getIndex();
				for (Path file : allJavaFiles) {
					long fileTimestamp = file.toFile().lastModified();
					long indexedTimestamp = index.getFileTimestamp(file);

					// If file is newer than indexed version, it has changed
					if (fileTimestamp > indexedTimestamp) {
						// Check if it's truly new or just modified
						if (!fileToOriginalBatch.containsKey(file)) {
							newFiles.add(file);
						} else {
							changedFiles.add(file);
						}
					}
				}
			} finally {
				indexCache.unlockRead();
			}

			// Handle deleted files (files in batch tracking but not on disk)
			List<Path> deletedFiles = new ArrayList<>();
			for (Path trackedFile : fileToOriginalBatch.keySet()) {
				if (!allJavaFilesSet.contains(trackedFile) && !trackedFile.toFile().exists()) {
					deletedFiles.add(trackedFile);
				}
			}

			// Process new files
			if (!newFiles.isEmpty()) {
				LOG.info("Found {} new files in project {}", newFiles.size(), projectName);
				handleNewFiles(projectName, newFiles);
			}

			// Process changed files
			if (!changedFiles.isEmpty()) {
				LOG.info("Found {} changed files in project {}", changedFiles.size(), projectName);
				reparseFiles(projectName, changedFiles);
			}

			// Process deleted files
			if (!deletedFiles.isEmpty()) {
				LOG.info("Found {} deleted files in project {}", deletedFiles.size(), projectName);
				handleDeletedFiles(deletedFiles);
			}

			if (newFiles.isEmpty() && changedFiles.isEmpty() && deletedFiles.isEmpty()) {
				LOG.debug("No file changes found in project {}", projectName);
			}

			return true;
		} finally {
			// Always remove from scanning set when done
			projectsCurrentlyScanning.remove(projectName);
		}
	}

	/**
	 * Start the periodic file change scanner.
	 * Scans all projects every 30 seconds for file changes.
	 * Idempotent - safe to call multiple times.
	 */
	private synchronized void startPeriodicFileChangeScanner() {
		if (periodicScannerStarted) {
			LOG.debug("Periodic file scanner already started, skipping");
			return;
		}

		periodicScanExecutor.scheduleWithFixedDelay(() -> {
			try {
				// Scan all projects for changed files
				for (String projectName : projects.keySet()) {
					scanAndReparseChangedFiles(projectName);
				}
			} catch (Exception e) {
				LOG.error("Error during periodic file change scan", e);
			}
		}, 30, 30, TimeUnit.SECONDS); // Initial delay 30s, then every 30s

		periodicScannerStarted = true;
		LOG.info("Started periodic file change scanner (every 30 seconds)");
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

		CompilationUnit cu = parser.parse(
				sourceContent,
				javaFile.toString(),
				classpath,
				AST.JLS21,
				null,
				false  // no binding resolution
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
	 * Index a batch of Java files without binding resolution.
	 * Uses shared javac Context for better performance.
	 *
	 * @param javaFiles list of Java files to index
	 * @param classpath the classpath for parsing
	 * @param index the index to populate
	 * @return number of files successfully indexed
	 */
	private int indexBatch(List<Path> javaFiles, List<File> classpath, JavaIndex index) throws IOException {
		return indexBatch(javaFiles, classpath, index, true);
	}

	private int indexBatch(List<Path> javaFiles, List<File> classpath, JavaIndex index, boolean analyze) throws IOException {
		if (javaFiles.isEmpty()) {
			return 0;
		}

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

		long parseStart = System.currentTimeMillis();
		JavacDOMParser parser = new JavacDOMParser();
		Map<String, CompilationUnit> units = parser.parseBatch(
			sourceFiles,
			classpath,
			AST.JLS21,
			null,  // compiler options
			false, // no binding resolution
			analyze
		);
		long parseTime = System.currentTimeMillis() - parseStart;

		long indexStart = System.currentTimeMillis();
		// Index each parsed compilation unit in parallel
		//
		// Parallelism strategy: We use parallelStream() which defaults to ForkJoinPool.commonPool()
		// with parallelism = availableProcessors - 1 (typically 15 on a 16-core machine).
		//
		// MEMORY CONSIDERATIONS:
		// We could calculate optimal parallelism based on available memory, classpath size, etc.,
		// but analysis shows this is unnecessary:
		//
		// 1. During indexing (resolveBindings=false), javac does NOT load classpath jars.
		//    The classpath parameter is passed but unused - javac only parses syntax.
		//
		// 2. Memory per concurrent batch is modest (~30-40 MB):
		//    - AST for 100 files: ~10-15 MB
		//    - Javac Context overhead: ~20-30 MB
		//    - No classpath jar loading: 0 MB
		//
		// 3. Real-world validation (Quarkus: 23,603 files, 2,426 jars, 15 concurrent batches):
		//    - Total memory delta: 1,934 MB (mostly index data, not parsing overhead)
		//    - 15 batches × 40 MB = 600 MB peak for parsing (the rest is index storage)
		//    - No memory issues even with large projects
		//
		// 4. CPU is the bottleneck, not memory:
		//    - Even with 10,000 jars and 4GB memory, CPU remains the constraint
		//    - Only with 2GB memory does memory become limiting
		//
		// 5. Batches are short-lived (~777ms average) creating a rolling wave effect:
		//    - Batches overlap but don't all peak simultaneously
		//    - Memory is released as each batch completes
		//
		// CONCLUSION:
		// Use all available cores via default ForkJoinPool for maximum throughput.
		// If memory pressure becomes an issue in the future (2GB deployments, exotic
		// configurations), we can add adaptive parallelism based on Runtime.maxMemory()
		// and classpath size. For now, the default behavior is optimal.
		//
		AtomicInteger indexed = new AtomicInteger(0);
		units.entrySet().parallelStream().forEach(entry -> {
			String fileName = entry.getKey();
			CompilationUnit cu = entry.getValue();

			if (cu == null) {
				LOG.warn("Failed to parse file in batch: {}", fileName);
				return;
			}

			try {
				Path javaFile = Path.of(fileName);

				// Remove old declarations for this file (incremental update)
				index.removeFile(javaFile);

				// Visit AST and populate index
				DOMToIndexVisitor visitor = new DOMToIndexVisitor(index, javaFile);
				cu.accept(visitor);
				visitor.finishIndexing();

				if (analyze) {
					storeDiagnosticsForFile(index, javaFile, cu);
				}

				indexed.incrementAndGet();
			} catch (Exception e) {
				LOG.error("Failed to index compilation unit: {}", fileName, e);
			}
		});
		long indexTime = System.currentTimeMillis() - indexStart;
		long totalTime = parseTime + indexTime;

		LOG.info("Batch timing for {} files: parse={}ms index={}ms total={}ms (parse={} index={})",
				indexed.get(), parseTime, indexTime, totalTime,
				String.format("%.1f%%", 100.0 * parseTime / totalTime),
				String.format("%.1f%%", 100.0 * indexTime / totalTime));

		return indexed.get();
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
				.filter(p -> !p.toString().endsWith(".qute.java"))
				.filter(p -> !p.toString().contains("/target/"))
				.filter(p -> !p.toString().contains("/build/"))
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

		// Shutdown periodic scan executor
		periodicScanExecutor.shutdown();
		try {
			if (!periodicScanExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
				LOG.warn("Periodic scan executor did not terminate in time, forcing shutdown");
				periodicScanExecutor.shutdownNow();
			}
		} catch (InterruptedException e) {
			LOG.error("Interrupted while waiting for periodic scan executor to shut down", e);
			periodicScanExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}

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

	/**
	 * Notify listeners that file diagnostics have changed.
	 *
	 * @param filePath absolute path to the file
	 * @param diagnostics the current diagnostics for the file
	 */
	private void notifyFileDiagnosticsChanged(String filePath, DiagnosticList diagnostics) {
		for (WorkspaceModelListener listener : listeners) {
			try {
				listener.fileDiagnosticsChanged(filePath, diagnostics);
			} catch (Exception e) {
				LOG.error("Error notifying listener of file diagnostics change", e);
			}
		}
	}

	/**
	 * Tracks information about a batch of files parsed together.
	 * Files in the same batch share a javac Context and symbol tables.
	 */
	private static class BatchInfo {
		final int id;
		final Set<Path> files = ConcurrentHashMap.newKeySet();
		volatile boolean isDirty = false;
		volatile boolean needsSplit = false;

		BatchInfo(int id) {
			this.id = id;
		}
	}
}
