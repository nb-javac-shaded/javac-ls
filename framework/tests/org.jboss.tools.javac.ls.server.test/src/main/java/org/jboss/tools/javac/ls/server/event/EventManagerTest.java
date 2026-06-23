package org.jboss.tools.javac.ls.server.event;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.jboss.tools.javac.ls.api.JavacLSClient;
import org.eclipse.lsp4j.services.LanguageClient;
import org.jboss.tools.javac.ls.api.dao.InitializationState;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for EventManager broadcasting to clients.
 */
public class EventManagerTest {

	private List<LanguageClient> clients;
	private TestClient client1;
	private TestClient client2;

	@Before
	public void setUp() {
		clients = new ArrayList<>();
		client1 = new TestClient();
		client2 = new TestClient();
		clients.add(client1);
		clients.add(client2);
	}

	@Test
	public void testFireInitializationStateChangedWithState() {
		EventManager.fireInitializationStateChanged(clients, InitializationState.STATE_INDEXING);

		assertEquals("Client 1 should receive event", 1, client1.stateChanges.size());
		assertEquals("Client 2 should receive event", 1, client2.stateChanges.size());

		assertEquals("Client 1 should receive INDEXING state",
				InitializationState.STATE_INDEXING, client1.stateChanges.get(0).getState());
		assertEquals("Client 2 should receive INDEXING state",
				InitializationState.STATE_INDEXING, client2.stateChanges.get(0).getState());
	}

	@Test
	public void testFireInitializationStateChangedWithStateAndMessage() {
		EventManager.fireInitializationStateChanged(clients, InitializationState.STATE_READY, "All done!");

		assertEquals("Client 1 should receive event", 1, client1.stateChanges.size());
		assertEquals("Client 2 should receive event", 1, client2.stateChanges.size());

		InitializationState state1 = client1.stateChanges.get(0);
		assertEquals("Should have READY state", InitializationState.STATE_READY, state1.getState());
		assertEquals("Should have message", "All done!", state1.getMessage());

		InitializationState state2 = client2.stateChanges.get(0);
		assertEquals("Should have READY state", InitializationState.STATE_READY, state2.getState());
		assertEquals("Should have message", "All done!", state2.getMessage());
	}

	@Test
	public void testFireInitializationStateChangedWithDAO() {
		InitializationState state = new InitializationState(InitializationState.STATE_LOADING_CACHE, "Loading...");
		EventManager.fireInitializationStateChanged(clients, state);

		assertEquals("Client 1 should receive event", 1, client1.stateChanges.size());
		assertEquals("Client 2 should receive event", 1, client2.stateChanges.size());

		// Both clients should receive the same DAO instance
		assertSame("Client 1 should receive same DAO", state, client1.stateChanges.get(0));
		assertSame("Client 2 should receive same DAO", state, client2.stateChanges.get(0));
	}

	@Test
	public void testFireWithNullClients() {
		// Should not throw exception
		EventManager.fireInitializationStateChanged(null, InitializationState.STATE_READY);
	}

	@Test
	public void testFireWithEmptyClientList() {
		// Should not throw exception
		EventManager.fireInitializationStateChanged(new ArrayList<>(), InitializationState.STATE_READY);
	}

	@Test
	public void testClientExceptionDoesNotPreventOtherClients() {
		// Add a client that throws exceptions
		clients.add(new org.jboss.tools.javac.ls.server.util.TestJavacLSClient() {
			@Override
			public void initializationStateChanged(InitializationState state) {
				throw new RuntimeException("Simulated client error");
			}
		});

		// Fire event - should not throw
		EventManager.fireInitializationStateChanged(clients, InitializationState.STATE_INDEXING);

		// First two clients should still receive the event
		assertEquals("Client 1 should receive event", 1, client1.stateChanges.size());
		assertEquals("Client 2 should receive event", 1, client2.stateChanges.size());
	}

	/**
	 * Test client that records events.
	 */
	private static class TestClient extends org.jboss.tools.javac.ls.server.util.TestJavacLSClient {
		List<InitializationState> stateChanges = new ArrayList<>();

		@Override
		public void initializationStateChanged(InitializationState state) {
			stateChanges.add(state);
		}
	}
}
