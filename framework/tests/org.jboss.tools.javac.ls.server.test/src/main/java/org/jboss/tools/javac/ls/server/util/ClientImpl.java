/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.util;

import org.jboss.tools.javac.ls.api.JavacLSServer;
import org.jboss.tools.javac.ls.api.dao.InitializationState;

public class ClientImpl extends TestJavacLSClient {

	private JavacLSServer server;

	public void initialize(JavacLSServer server) throws Exception {
		this.server = server;
	}

	public JavacLSServer getProxy() {
		return server;
	}

	@Override
	public void initializationStateChanged(InitializationState state) {
		System.out.println("Initialization state changed: " + state);
	}
}
