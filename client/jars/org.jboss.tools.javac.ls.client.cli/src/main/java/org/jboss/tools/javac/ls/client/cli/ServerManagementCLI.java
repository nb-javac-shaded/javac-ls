/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.client.cli;

import java.util.concurrent.ExecutionException;

import org.jboss.tools.javac.ls.api.dao.Status;
import org.jboss.tools.javac.ls.client.bindings.IClientConnectionClosedListener;
import org.jboss.tools.javac.ls.client.bindings.ServerManagementClientLauncher;

public class ServerManagementCLI implements IClientConnectionClosedListener {

	public static void main(String[] args) {
		if (args.length < 2) {
			System.out.println("Usage: ServerManagementCLI <host> <port>");
			System.exit(1);
		}

		ServerManagementCLI cli = new ServerManagementCLI();
		try {
			cli.connect(args[0], args[1]);
		} catch (Exception e) {
			e.printStackTrace();
			return;
		}

		System.out.println("Connected to: " + args[0] + ":" + args[1]);
		cli.waitForConnection();
	}

	private ServerManagementClientLauncher launcher;
	private boolean isComplete = false;

	private void connect(String host, String port) throws Exception {
		this.launcher = new ServerManagementClientLauncher(host, Integer.parseInt(port));
		launcher.setListener(this);
		launcher.launch();

		// Call ping to test the connection
		Status pingResult = launcher.getServerProxy().ping().get();
		System.out.println("Ping result: " + pingResult.toString());
	}

	private synchronized boolean isComplete() {
		return isComplete;
	}

	private synchronized void setComplete(boolean val) {
		this.isComplete = val;
	}

	private void waitForConnection() {
		while (!isComplete()) {
			if (!launcher.isConnectionActive()) {
				setComplete(true);
			} else {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					setComplete(true);
				}
			}
		}
		close();
	}

	@Override
	public void connectionClosed() {
		setComplete(true);
	}

	private void close() {
		System.out.println("Connection with remote server has terminated.");
		System.exit(0);
	}
}
