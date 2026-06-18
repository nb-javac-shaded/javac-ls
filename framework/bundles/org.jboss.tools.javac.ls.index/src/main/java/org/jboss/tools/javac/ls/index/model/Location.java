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

/**
 * Represents a location in source code.
 */
public class Location {
	private final Path file;
	private final int startOffset;
	private final int endOffset;
	private final int line;
	private final int column;

	public Location(Path file, int startOffset, int endOffset, int line, int column) {
		this.file = file;
		this.startOffset = startOffset;
		this.endOffset = endOffset;
		this.line = line;
		this.column = column;
	}

	public Path getFile() {
		return file;
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
		return startOffset == location.startOffset &&
				endOffset == location.endOffset &&
				line == location.line &&
				column == location.column &&
				Objects.equals(file, location.file);
	}

	@Override
	public int hashCode() {
		return Objects.hash(file, startOffset, endOffset, line, column);
	}

	@Override
	public String toString() {
		return file + ":" + line + ":" + column;
	}
}
