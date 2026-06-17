/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.model;

/**
 * Listener for workspace model changes.
 */
public interface WorkspaceModelListener {

	/**
	 * Called when the initialization state changes.
	 *
	 * @param oldState the previous state
	 * @param newState the new state
	 */
	void initializationStateChanged(int oldState, int newState);

	/**
	 * Called when a project is added to the workspace.
	 *
	 * @param project the project that was added
	 */
	void projectAdded(WorkspaceProject project);

	/**
	 * Called when a project is removed from the workspace.
	 *
	 * @param project the project that was removed
	 */
	void projectRemoved(WorkspaceProject project);
}
