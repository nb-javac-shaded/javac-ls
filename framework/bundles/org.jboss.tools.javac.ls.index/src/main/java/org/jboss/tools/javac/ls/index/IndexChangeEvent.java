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
package org.jboss.tools.javac.ls.index;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Event indicating that the index has changed.
 */
public class IndexChangeEvent {
	private final Path file;
	private final ChangeKind kind;
	private final long timestamp;

	public enum ChangeKind {
		FILE_ADDED,
		FILE_UPDATED,
		FILE_REMOVED,
		BULK_UPDATE
	}

	public IndexChangeEvent(Path file, ChangeKind kind) {
		this.file = file;
		this.kind = kind;
		this.timestamp = System.currentTimeMillis();
	}

	public Path getFile() {
		return file;
	}

	public ChangeKind getKind() {
		return kind;
	}

	public long getTimestamp() {
		return timestamp;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		IndexChangeEvent that = (IndexChangeEvent) o;
		return timestamp == that.timestamp &&
				Objects.equals(file, that.file) &&
				kind == that.kind;
	}

	@Override
	public int hashCode() {
		return Objects.hash(file, kind, timestamp);
	}

	@Override
	public String toString() {
		return kind + " " + file + " at " + timestamp;
	}
}
