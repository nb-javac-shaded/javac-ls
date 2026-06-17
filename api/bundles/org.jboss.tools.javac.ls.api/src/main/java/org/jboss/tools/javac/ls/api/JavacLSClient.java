/*******************************************************************************
 * Copyright (c) 2018 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.api;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment;
import org.jboss.tools.javac.ls.api.dao.InitializationState;
import org.jboss.tools.javac.ls.api.dao.ProjectInfo;

@JsonSegment("client")
public interface JavacLSClient {

	/**
	 * The `client/initializationStateChanged` notification is sent by the server
	 * to all clients when the workspace initialization state changes.
	 *
	 * @param state the new initialization state
	 */
	@JsonNotification
	void initializationStateChanged(InitializationState state);

	/**
	 * The `client/projectAdded` notification is sent by the server
	 * to all clients when a project is added to the workspace.
	 *
	 * @param project the project that was added
	 */
	@JsonNotification
	void projectAdded(ProjectInfo project);

	/**
	 * The `client/projectRemoved` notification is sent by the server
	 * to all clients when a project is removed from the workspace.
	 *
	 * @param project the project that was removed
	 */
	@JsonNotification
	void projectRemoved(ProjectInfo project);

}
