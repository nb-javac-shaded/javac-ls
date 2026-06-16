/*******************************************************************************
 * Copyright (c) 2018 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerCoreActivator implements BundleActivator {
	private static final Logger LOG = LoggerFactory.getLogger(ServerCoreActivator.class);
	private static final String BUNDLE_ID = "org.jboss.tools.javac.ls.server";

	public JavacLsServerLauncher getLauncher() {
		return LauncherSingleton.getDefault().getLauncher();
	}

	@Override
	public void start(BundleContext context) throws Exception {
		LOG.debug("{} bundle started", BUNDLE_ID);
		int port = ServerFlags.getServerPort();

		JavacLsServerLauncher launcher = null;
		try {
			launcher = new JavacLsServerLauncher("" + port);
			launcher.initialize(false);

			// Note: Don't wait for READY in OSGi context - would block bundle activation
			// Workspace initializes asynchronously, clients can connect during indexing

			launcher.launch();
			// Note: Don't add shutdown hook in OSGi - stop() method handles cleanup
			LOG.info("Javac-LS server started on port {}", port);
		} catch (RuntimeException re) {
			LOG.error("Unable to launch Javac-LS server", re);
			throw re;
		}
		final JavacLsServerLauncher launcher2 = launcher;
		LauncherSingleton.getDefault().setLauncher(launcher);

		new Thread("Launch Javac-LS Server") {
			@Override
			public void run() {
				try {
					launcher2.shutdownOnInput();
				} catch (Exception e) {
					LOG.error("Unable to launch Javac-LS server", e);
				}
			}
		}.start();
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		LOG.debug("{} bundle stopped", BUNDLE_ID);
		JavacLsServerLauncher launcher = getLauncher();
		if (launcher != null) {
			launcher.shutdown();
		}
	}
}
