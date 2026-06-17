/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.api.dao;

/**
 * Represents project information for client notifications.
 */
public class ProjectInfo {

	/**
	 * The project name (unique identifier).
	 */
	private String name;

	/**
	 * The filesystem path to the project.
	 */
	private String path;

	/**
	 * No-arg constructor for JSON serialization.
	 */
	public ProjectInfo() {
	}

	/**
	 * Create project info.
	 *
	 * @param name the project name
	 * @param path the filesystem path
	 */
	public ProjectInfo(String name, String path) {
		this.name = name;
		this.path = path;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	@Override
	public String toString() {
		return "ProjectInfo[name=" + name + ", path=" + path + "]";
	}
}
