/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.api;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.jsonrpc.services.JsonSegment;
import org.jboss.tools.javac.ls.api.dao.ProjectInfo;
import org.jboss.tools.javac.ls.api.dao.Status;

@JsonSegment("server")
public interface JavacLSServer {

	/**
	 * The `server/ping` request is sent by the client to check if the server
	 * is alive and responsive. This is a simple stub for testing.
	 */
	@JsonRequest
	CompletableFuture<Status> ping();

	/**
	 * The `server/shutdown` notification is sent by the client to shut down the
	 * server itself.
	 */
	@JsonNotification
	void shutdown();

	/**
	 * The `server/addProject` request adds a project to the workspace and begins indexing it.
	 * The workspace initialization state will change to INDEXING while the project is being indexed,
	 * then return to READY when complete.
	 *
	 * @param project the project to add (name and path)
	 * @return status indicating success or failure
	 */
	@JsonRequest
	CompletableFuture<Status> addProject(ProjectInfo project);

	/**
	 * The `server/removeProject` request removes a project from the workspace
	 * and cleans up its index entries.
	 *
	 * @param projectName the name of the project to remove
	 * @return status indicating success or failure
	 */
	@JsonRequest
	CompletableFuture<Status> removeProject(String projectName);

}
