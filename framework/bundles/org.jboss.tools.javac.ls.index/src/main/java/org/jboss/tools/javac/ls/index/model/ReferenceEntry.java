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

import java.util.Objects;

/**
 * Represents a reference to a type, method, or field in source code.
 */
public class ReferenceEntry {
	private final Location location;
	private final ReferenceKind kind;

	public enum ReferenceKind {
		TYPE_REFERENCE,
		METHOD_INVOCATION,
		METHOD_REFERENCE,
		CONSTRUCTOR_INVOCATION,
		FIELD_READ,
		FIELD_WRITE,
		ANNOTATION_USE,
		NAME_REFERENCE
	}

	public ReferenceEntry(Location location, ReferenceKind kind) {
		this.location = location;
		this.kind = kind;
	}

	public Location getLocation() {
		return location;
	}

	public ReferenceKind getKind() {
		return kind;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		ReferenceEntry that = (ReferenceEntry) o;
		return Objects.equals(location, that.location) && kind == that.kind;
	}

	@Override
	public int hashCode() {
		return Objects.hash(location, kind);
	}

	@Override
	public String toString() {
		return kind + " at " + location;
	}
}
