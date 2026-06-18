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
 * Represents a classpath entry for a Java project.
 */
public interface IJavacClasspathEntry {

	/**
	 * The type of classpath entry.
	 */
	public enum EntryType {
		SOURCE,
		LIBRARY
	}

	/**
	 * Gets the type of this classpath entry.
	 * @return the entry type
	 */
	EntryType getType();

	/**
	 * Gets the path to this classpath entry.
	 * @return the path
	 */
	String getPath();
}
