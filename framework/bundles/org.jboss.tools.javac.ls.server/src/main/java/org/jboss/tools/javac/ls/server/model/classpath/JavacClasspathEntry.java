/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.model.classpath;

/**
 * Implementation of a classpath entry.
 */
public class JavacClasspathEntry implements IJavacClasspathEntry {

	private EntryType type;
	private String path;

	// Default constructor for JSON deserialization
	public JavacClasspathEntry() {
	}

	public JavacClasspathEntry(EntryType type, String path) {
		this.type = type;
		this.path = path;
	}

	@Override
	public EntryType getType() {
		return type;
	}

	public void setType(EntryType type) {
		this.type = type;
	}

	@Override
	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	@Override
	public String toString() {
		return type + ": " + path;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null || getClass() != obj.getClass())
			return false;
		JavacClasspathEntry other = (JavacClasspathEntry) obj;
		if (type != other.type)
			return false;
		if (path == null) {
			if (other.path != null)
				return false;
		} else if (!path.equals(other.path)) {
			return false;
		}
		return true;
	}

	@Override
	public int hashCode() {
		int result = type != null ? type.hashCode() : 0;
		result = 31 * result + (path != null ? path.hashCode() : 0);
		return result;
	}
}
