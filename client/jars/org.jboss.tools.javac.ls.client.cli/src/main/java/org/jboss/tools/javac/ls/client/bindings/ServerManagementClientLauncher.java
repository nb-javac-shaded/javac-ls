/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.client.bindings;

import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;

import org.jboss.tools.javac.ls.api.JavacLSServer;
import org.jboss.tools.javac.ls.api.SocketLauncher;

public class ServerManagementClientLauncher {

	private ServerManagementClientImpl myClient;
	private SocketLauncher<JavacLSServer> launcher;
	private Socket socket;
	private String host;
	private int port;
	private boolean connectionOpen = false;
	private IClientConnectionClosedListener listener;

	public ServerManagementClientLauncher(String host, int port) {
		this.host = host;
		this.port = port;
	}

	public void launch() throws UnknownHostException, IOException {
		// create the client
		ServerManagementClientImpl client = new ServerManagementClientImpl();
		// connect to the server
		this.socket = new Socket(host, port);
		// open a JSON-RPC connection for the opened socket
		this.launcher = new SocketLauncher<>(client, JavacLSServer.class, socket);
		/*
		 * Start listening for incoming message.
		 * When the JSON-RPC connection is closed,
		 * e.g. the server is died,
		 * the client process should exit.
		 */
		launcher.startListening().thenRun(() -> clientClosed());
		// initialize with remote proxy
		client.initialize(launcher.getRemoteProxy());
		myClient = client;
		connectionOpen = true;
	}

	private void clientClosed() {
		this.myClient = null;
		connectionOpen = false;
		if (listener != null)
			listener.connectionClosed();
	}

	public void closeConnection() {
		if (launcher != null) {
			launcher.close();
		}
	}

	public ServerManagementClientImpl getClient() {
		return this.myClient;
	}

	public boolean isConnectionActive() {
		return connectionOpen;
	}

	public JavacLSServer getServerProxy() {
		if (myClient != null) {
			return myClient.getProxy();
		}
		return null;
	}

	public void setListener(IClientConnectionClosedListener listener) {
		this.listener = listener;
	}
}
