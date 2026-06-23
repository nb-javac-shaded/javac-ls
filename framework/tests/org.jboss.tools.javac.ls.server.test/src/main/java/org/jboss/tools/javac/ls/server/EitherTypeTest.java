package org.jboss.tools.javac.ls.server;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.Test;

/**
 * Test to verify we can use Either type from org.eclipse.lsp4j.jsonrpc.messages.
 */
public class EitherTypeTest {

	@Test
	public void testEitherUsage() {
		// Create an Either with a list of locations
		List<Location> locations = new ArrayList<>();
		Either<List<Location>, String> either = Either.forLeft(locations);

		// Use the Either methods
		assertTrue("Should be left", either.isLeft());
		assertFalse("Should not be right", either.isRight());

		List<Location> result = either.getLeft();
		assertNotNull(result);
		assertEquals(0, result.size());
	}
}
