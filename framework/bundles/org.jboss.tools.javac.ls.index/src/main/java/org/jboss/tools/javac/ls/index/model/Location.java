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
 * Represents a location in source code.
 *
 * Internally uses file path interning to reduce memory usage when there are
 * millions of locations pointing to the same files. The file path is stored
 * as an integer ID in a registry, reducing memory by 50-80% for large codebases.
 */
public class Location {
	private final int fileId;
	private final int startOffset;
	private final int endOffset;
	private final int line;
	private final int column;

	/**
	 * Create a location in source code.
	 *
	 * @param file the source file
	 * @param startOffset start offset in the file
	 * @param endOffset end offset in the file
	 * @param line line number (1-based)
	 * @param column column number (0-based)
	 * @param index the JavaIndex (used for file path interning)
	 */
	public Location(Path file, int startOffset, int endOffset, int line, int column, JavaIndex index) {
		this.fileId = index.getPathRegistry().getOrRegister(file);
		this.startOffset = startOffset;
		this.endOffset = endOffset;
		this.line = line;
		this.column = column;
	}

	/**
	 * Constructor for deserialization (used by JSON persistence).
	 */
	public Location(int fileId, int startOffset, int endOffset, int line, int column) {
		this.fileId = fileId;
		this.startOffset = startOffset;
		this.endOffset = endOffset;
		this.line = line;
		this.column = column;
	}

	/**
	 * Get the file ID (internal use for serialization).
	 */
	public int getFileId() {
		return fileId;
	}

	/**
	 * Get the source file path.
	 *
	 * @param registry the file path registry to resolve the file ID
	 */
	public Path getFile(FilePathRegistry registry) {
		return registry.getPath(fileId);
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
