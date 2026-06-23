/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.util;

import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.jboss.tools.javac.ls.api.JavacLSClient;
import org.jboss.tools.javac.ls.api.dao.InitializationState;

/**
 * Base test client implementation that provides no-op implementations
 * of all LanguageClient methods. Tests can extend this and override
 * only the methods they care about.
 */
public class TestJavacLSClient implements JavacLSClient {

	@Override
	public void initializationStateChanged(InitializationState state) {
		// No-op by default
	}

	@Override
	public void telemetryEvent(Object object) {
		// No-op by default
	}

	@Override
	public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
		// No-op by default
	}

	@Override
	public void showMessage(MessageParams messageParams) {
		// No-op by default
	}

	@Override
	public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
		// No-op by default
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public void logMessage(MessageParams message) {
		// No-op by default
	}
}
