/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;

import org.jboss.tools.javac.ls.api.JavacLSClient;
import org.jboss.tools.javac.ls.server.util.ClientLauncher;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class JavacLSStartupShutdownTest {
	private JavacLsServerLauncher serverInstance;
	private ClientLauncher clientInstance;

	private static String ORIGINAL_WORKSPACE_LOC = null;

	@BeforeClass
	public static void beforeClass() {
		ORIGINAL_WORKSPACE_LOC = System.getProperty(ServerFlags.SYSPROP_WORKSPACE_PATH);
		try {
			File tmp = Files.createTempDirectory("JavacLSStartupShutdownTest").toFile();
			System.setProperty(ServerFlags.SYSPROP_WORKSPACE_PATH, tmp.getAbsolutePath());
		} catch(IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}

	@AfterClass
	public static void afterClass() {
		if( ORIGINAL_WORKSPACE_LOC == null )
			System.clearProperty(ServerFlags.SYSPROP_WORKSPACE_PATH);
		else
			System.setProperty(ServerFlags.SYSPROP_WORKSPACE_PATH, ORIGINAL_WORKSPACE_LOC);
	}

	@Before
	public void setup() {
		serverInstance = null;
		clientInstance = null;
	}

	@After
	public void teardown() {
		cleanup(serverInstance, clientInstance);
	}

	@Test
	public void testStart() {
		System.out.println("Testing testStart");
		initNew();
		assertNotNull(serverInstance);
		assertNotNull(clientInstance);
		assertTrue(clientInstance.isConnectionActive());
		cleanup(serverInstance, clientInstance);
	}

	@Test
	public void testShutdown() {
		System.out.println("Testing testShutdown");
		initNew();

		assertNotNull(clientInstance.getServerProxy());
		List<JavacLSClient> clients = serverInstance.getClients();
		assertNotNull(clients);
		assertEquals(1, clients.size());

		clientInstance.getServerProxy().shutdown();

		// Give shutdown time to complete
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
		}

		cleanup(serverInstance, clientInstance);
	}


	@Test
	public void testClientClosed() {
		System.out.println("Testing testClientClosed");
		initNew();

		assertNotNull(clientInstance.getServerProxy());
		assertNotNull(serverInstance.getClients());
		assertEquals(1, serverInstance.getClients().size());

		clientInstance.closeConnection();

		// Give time for client removal
		try {
			Thread.sleep(500);
		} catch (InterruptedException e) {
		}

		assertEquals(0, serverInstance.getClients().size());
		cleanup(serverInstance, clientInstance);
	}

	protected JavacLsServerLauncher defaultLauncher(int port) {
		return new JavacLsServerLauncher(String.valueOf(port));
	}

	private void initNew() {
		int port = new Random().nextInt(1000) + 10000;
		serverInstance = defaultLauncher(port);
		try {
			serverInstance.launch(port);
		} catch(Exception e) {
			e.printStackTrace();
			cleanup(serverInstance, null);
			fail("Failed to launch server: " + e.getMessage());
		}

		clientInstance = null;
		try {
			clientInstance = new ClientLauncher("localhost", port);
			clientInstance.launch();

			assertTrue(clientInstance.isConnectionActive());

			// Give time for server to register the client
			Thread.sleep(500);
		} catch(Exception e) {
			e.printStackTrace();
			cleanup(serverInstance, clientInstance);
			fail("Failed to launch client: " + e.getMessage());
		}
	}

	private void cleanup(JavacLsServerLauncher serverInstance, ClientLauncher clientInstance) {
		if( serverInstance != null ) {
			serverInstance.shutdown();
			this.serverInstance = null;
		}
		if( clientInstance != null ) {
			clientInstance.closeConnection();
			this.clientInstance = null;
		}
	}
}
