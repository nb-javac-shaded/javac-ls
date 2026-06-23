/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JavacWorkspaceService implements WorkspaceService {
	private static final Logger LOG = LoggerFactory.getLogger(JavacWorkspaceService.class);

	private final JavacLSServerImpl server;

	public JavacWorkspaceService(JavacLSServerImpl server) {
		this.server = server;
	}

	@Override
	public void didChangeConfiguration(DidChangeConfigurationParams params) {
		LOG.debug("didChangeConfiguration");
		// TODO: Handle configuration changes
	}

	@Override
	public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
		LOG.debug("didChangeWatchedFiles: {} changes", params.getChanges().size());
		// TODO: Handle file system changes
	}
}
