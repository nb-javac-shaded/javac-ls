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
import org.eclipse.lsp4j.services.LanguageServer;
import org.jboss.tools.javac.ls.api.dao.DiagnosticList;
import org.jboss.tools.javac.ls.api.dao.Status;

@JsonSegment("server")
public interface JavacLSServer extends LanguageServer {

	/**
	 * The `server/ping` request is sent by the client to check if the server
	 * is alive and responsive. This is a simple stub for testing.
	 */
	@JsonRequest
	CompletableFuture<Status> ping();

	/**
	 * The `server/shutdownServer` notification is sent by the client to shut down the
	 * server itself. This is part of the custom protocol.
	 * For standard LSP, use the initialize/shutdown/exit lifecycle instead.
	 */
	@JsonNotification
	void shutdownServer();

	/**
	 * The `server/getProjectDiagnostics` request returns all compilation errors
	 * and warnings for all files in the specified project.
	 * The server will scan for changed files before returning diagnostics
	 * to ensure results are up-to-date.
	 *
	 * @param projectName the name of the project
	 * @return diagnostic list containing all errors and warnings for the project
	 */
	@JsonRequest
	CompletableFuture<DiagnosticList> getProjectDiagnostics(String projectName);

	/**
	 * The `server/getFileDiagnostics` request returns all compilation errors
	 * and warnings for a specific file.
	 * The server will scan for changed files before returning diagnostics
	 * to ensure results are up-to-date.
	 *
	 * @param filePath the absolute path to the file
	 * @return diagnostic list containing all errors and warnings for the file
	 */
	@JsonRequest
	CompletableFuture<DiagnosticList> getFileDiagnostics(String filePath);

}
