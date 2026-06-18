/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.model.classpath;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.jboss.tools.javac.ls.server.model.WorkspaceProject;

/**
 * Interface for discovering classpath entries for a workspace project.
 */
public interface IProjectClasspathDiscoverer {

	/**
	 * Gets the unique identifier for this discoverer.
	 * @return the discoverer ID
	 */
	String getId();

	/**
	 * Checks if this discoverer can handle the given project.
	 * @param proj the workspace project
	 * @return true if this discoverer can discover the classpath for the project
	 */
	boolean accepts(WorkspaceProject proj);

	/**
	 * Discovers the classpath for the given project.
	 * @param proj the workspace project
	 * @return list of classpath entries
	 */
	ArrayList<IJavacClasspathEntry> discoverClasspath(WorkspaceProject proj);

	/**
	 * Gets the source files to check for cache validation.
	 * The cache is considered invalid if any of these files have a newer
	 * timestamp than the cache file.
	 * @param proj the workspace project
	 * @return list of files to check, or null if cache validation not supported
	 */
	List<File> getSourceFilesForCacheValidation(WorkspaceProject proj);
}
