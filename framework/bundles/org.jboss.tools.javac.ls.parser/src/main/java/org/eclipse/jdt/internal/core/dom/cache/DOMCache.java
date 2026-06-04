/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.eclipse.jdt.internal.core.dom.cache;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.internal.core.dom.javac.JavacDOMParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory cache for parsed DOM trees.
 * Caches CompilationUnit instances with timestamp-based invalidation.
 */
public class DOMCache {
	private static final Logger LOG = LoggerFactory.getLogger(DOMCache.class);

	private final Map<URI, CacheEntry> cache = new ConcurrentHashMap<>();
	private final JavacDOMParser parser = new JavacDOMParser();

	/**
	 * Cache entry containing a parsed CompilationUnit and metadata.
	 */
	private static class CacheEntry {
		final CompilationUnit unit;
		final long timestamp;
		final String sourceHash;

		CacheEntry(CompilationUnit unit, long timestamp, String sourceHash) {
			this.unit = unit;
			this.timestamp = timestamp;
			this.sourceHash = sourceHash;
		}
	}

	/**
	 * Get a CompilationUnit for the given file URI.
	 * Returns cached version if valid, otherwise parses and caches.
	 *
	 * @param fileUri URI of the source file
	 * @param classpath classpath entries for compilation
	 * @param apiLevel AST API level (e.g., AST.JLS21)
	 * @param compilerOptions compiler options map
	 * @param resolveBindings whether to resolve bindings
	 * @return CompilationUnit (cached or freshly parsed)
	 */
	public CompilationUnit getCompilationUnit(
			URI fileUri,
			List<File> classpath,
			int apiLevel,
			Map<String, String> compilerOptions,
			boolean resolveBindings) {

		File sourceFile = new File(fileUri);
		if (!sourceFile.exists()) {
			LOG.warn("Source file does not exist: {}", fileUri);
			return null;
		}

		long fileTimestamp = sourceFile.lastModified();
		CacheEntry cached = cache.get(fileUri);

		// Check if cache is valid
		if (cached != null && cached.timestamp >= fileTimestamp) {
			// TODO: Also verify source content hash matches
			LOG.debug("Using cached CompilationUnit for {}", fileUri);
			return cached.unit;
		}

		// Cache miss or stale - parse the file
		LOG.debug("Parsing {} (cache {})", fileUri, cached == null ? "miss" : "stale");
		return parseAndCache(fileUri, sourceFile, classpath, apiLevel, compilerOptions, resolveBindings);
	}

	/**
	 * Parse a source file and cache the result.
	 */
	private CompilationUnit parseAndCache(
			URI fileUri,
			File sourceFile,
			List<File> classpath,
			int apiLevel,
			Map<String, String> compilerOptions,
			boolean resolveBindings) {

		try {
			// Read source content
			String sourceContent = new String(Files.readAllBytes(Paths.get(fileUri)));
			String fileName = sourceFile.getName();

			// Parse
			CompilationUnit unit = parser.parse(
				sourceContent,
				fileName,
				classpath,
				apiLevel,
				compilerOptions,
				resolveBindings
			);

			if (unit != null) {
				// Compute source hash for future validation
				String sourceHash = String.valueOf(sourceContent.hashCode());

				// Cache the result
				long timestamp = sourceFile.lastModified();
				cache.put(fileUri, new CacheEntry(unit, timestamp, sourceHash));

				LOG.debug("Cached CompilationUnit for {} ({} problems)",
					fileUri, unit.getProblems() != null ? unit.getProblems().length : 0);
			}

			return unit;

		} catch (IOException e) {
			LOG.error("Failed to read source file: {}", fileUri, e);
			return null;
		}
	}

	/**
	 * Invalidate the cached entry for a file.
	 *
	 * @param fileUri URI of the file to invalidate
	 */
	public void invalidate(URI fileUri) {
		CacheEntry removed = cache.remove(fileUri);
		if (removed != null) {
			LOG.debug("Invalidated cache entry for {}", fileUri);
		}
	}

	/**
	 * Clear all cached entries.
	 */
	public void clear() {
		int size = cache.size();
		cache.clear();
		LOG.info("Cleared DOM cache ({} entries)", size);
	}

	/**
	 * Get the number of cached entries.
	 *
	 * @return cache size
	 */
	public int size() {
		return cache.size();
	}
}
