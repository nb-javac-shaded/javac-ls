/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.jboss.tools.javac.ls.index.model;

import java.nio.file.Path;
import java.util.Objects;

import org.jboss.tools.javac.ls.index.store.FilePathRegistry;
import org.jboss.tools.javac.ls.index.store.JavaIndex;

/**
 * Represents a location in source code (file, line, column, offset range).
 *
 * <h2>File Path Interning for Memory Efficiency</h2>
 * A large codebase may have millions of Location objects pointing to thousands of files.
 * Storing the full file path in each Location would waste enormous amounts of memory.
 *
 * Instead, Location stores an integer fileId and a reference to a FilePathRegistry:
 * - fileId: Integer ID representing the file path (4 bytes, persisted)
 * - pathRegistry: Reference to the registry that maps IDs to Paths (transient, not persisted)
 *
 * <h2>Serialization Strategy</h2>
 * When serialized to JSON:
 * - fileId is saved (e.g., {"fileId": 42, "startOffset": 100, ...})
 * - pathRegistry is NOT saved (marked transient)
 *
 * This avoids circular references: Location → pathRegistry → Map containing Locations.
 *
 * When deserialized from JSON:
 * - fileId is restored from JSON
 * - pathRegistry is null initially
 * - JavaIndex.rehydrateLocations() walks all Location objects and calls setPathRegistry()
 *
 * <h2>Memory Impact</h2>
 * Example: 1 million references from 1000 files
 * - Before (Path per Location): ~50 bytes × 1M = ~50MB
 * - After (int fileId per Location): 4 bytes × 1M = ~4MB
 * - Savings: ~92% reduction in file path storage
 *
 * <h2>Usage</h2>
 * Creating a location:
 *   Location loc = new Location(filePath, start, end, line, col, index);
 *
 * Getting the file path:
 *   Path path = loc.getFile();  // Resolves fileId → Path via pathRegistry
 *
 * After deserialization:
 *   JavaIndex.rehydrateLocations() sets pathRegistry on all Location objects
 *
 * @see FilePathRegistry for the file path interning mechanism
 * @see JavaIndex#rehydrateLocations() for the rehydration process after deserialization
 */
public class Location {
	/** Integer ID of the file path (persisted to JSON).
	 *  Maps to a Path via FilePathRegistry. */
	private final int fileId;

	/** Reference to the path registry for resolving fileId → Path.
	 *  Marked transient to avoid circular serialization.
	 *  Set to null during deserialization, restored by rehydrateLocations(). */
	private transient FilePathRegistry pathRegistry;

	/** Character offset where this location starts in the file */
	private final int startOffset;

	/** Character offset where this location ends in the file */
	private final int endOffset;

	/** Line number (1-based) */
	private final int line;

	/** Column number (0-based) */
	private final int column;

	/**
	 * Create a location in source code.
	 *
	 * This constructor is used during indexing when creating new Location objects.
	 * It registers the file path in the registry (if not already registered) and
	 * stores the resulting ID.
	 *
	 * @param file the source file
	 * @param startOffset start offset in the file
	 * @param endOffset end offset in the file
	 * @param line line number (1-based)
	 * @param column column number (0-based)
	 * @param index the JavaIndex (used to access the file path registry)
	 */
	public Location(Path file, int startOffset, int endOffset, int line, int column, JavaIndex index) {
		this.pathRegistry = index.getPathRegistry();
		this.fileId = pathRegistry.getOrRegister(file);  // Register path and get ID
		this.startOffset = startOffset;
		this.endOffset = endOffset;
		this.line = line;
		this.column = column;
	}

	/**
	 * Constructor for deserialization (used by JSON persistence).
	 *
	 * When Gson deserializes a Location from JSON, it calls this constructor.
	 * The fileId comes from the JSON, but pathRegistry is null (it's transient).
	 *
	 * After all data is loaded, JavaIndex.rehydrateLocations() walks all Location
	 * objects and calls setPathRegistry() to restore the registry reference.
	 *
	 * @param fileId the file ID (from JSON)
	 * @param startOffset start offset (from JSON)
	 * @param endOffset end offset (from JSON)
	 * @param line line number (from JSON)
	 * @param column column number (from JSON)
	 */
	public Location(int fileId, int startOffset, int endOffset, int line, int column) {
		this.fileId = fileId;
		this.pathRegistry = null; // Will be set during rehydration
		this.startOffset = startOffset;
		this.endOffset = endOffset;
		this.line = line;
		this.column = column;
	}

	/**
	 * Rehydrate this location with a path registry after deserialization.
	 *
	 * Called by JavaIndex.rehydrateLocations() after loading the index from disk.
	 * This restores the transient pathRegistry reference so getFile() works.
	 *
	 * @param registry the file path registry from JavaIndex
	 */
	public void setPathRegistry(FilePathRegistry registry) {
		this.pathRegistry = registry;
	}

	/**
	 * Get the file ID (internal use for serialization and debugging).
	 *
	 * @return the integer file ID
	 */
	public int getFileId() {
		return fileId;
	}

	/**
	 * Get the source file path by resolving the file ID via the path registry.
	 *
	 * @return the file path, or null if pathRegistry is not set (shouldn't happen after rehydration)
	 */
	public Path getFile() {
		return pathRegistry.getPath(fileId);
	}

	public int getStartOffset() {
		return startOffset;
	}

	public int getEndOffset() {
		return endOffset;
	}

	public int getLine() {
		return line;
	}

	public int getColumn() {
		return column;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		Location location = (Location) o;
		return fileId == location.fileId &&
				startOffset == location.startOffset &&
				endOffset == location.endOffset &&
				line == location.line &&
				column == location.column;
	}

	@Override
	public int hashCode() {
		return Objects.hash(fileId, startOffset, endOffset, line, column);
	}

	@Override
	public String toString() {
		return "fileId=" + fileId + ":" + line + ":" + column;
	}
}
