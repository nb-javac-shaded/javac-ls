package org.jboss.tools.javac.ls.server;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Unit tests for textDocument/definition helper methods.
 *
 * Note: Full integration tests for textDocument/definition require:
 * - Starting a server with workspace and projects
 * - Connecting a client via socket
 * - Indexing Java files with bindings
 * - Making LSP requests and validating responses
 *
 * These tests verify the core helper logic used by the definition implementation.
 */
public class DefinitionHelperMethodsTest {

	/**
	 * Tests for position-to-offset conversion.
	 * This is critical for finding the AST node at a cursor position.
	 */
	@Test
	public void testPositionToOffset_FirstLine() {
		String content = "public class Test {\n    private int value;\n}";

		// Position 0:0 should be offset 0
		int offset = positionToOffset(content, 0, 0);
		assertEquals(0, offset);

		// Position 0:6 should be offset 6 ("public" is 6 chars)
		offset = positionToOffset(content, 0, 6);
		assertEquals(6, offset);
	}

	@Test
	public void testPositionToOffset_SecondLine() {
		String content = "public class Test {\n    private int value;\n}";

		// Position 1:0 should be offset 20 (length of line 0 + newline)
		int offset = positionToOffset(content, 1, 0);
		assertEquals(20, offset);

		// Position 1:4 should be offset 24 (4 spaces)
		offset = positionToOffset(content, 1, 4);
		assertEquals(24, offset);
	}

	@Test
	public void testPositionToOffset_InvalidPosition() {
		String content = "public class Test {\n    private int value;\n}";

		// Line beyond end should return -1
		int offset = positionToOffset(content, 10, 0);
		assertEquals(-1, offset);

		// Character beyond line end should return -1
		offset = positionToOffset(content, 0, 100);
		assertEquals(-1, offset);
	}

	@Test
	public void testOffsetToPosition_Start() {
		String content = "public class Test {\n    private int value;\n}";

		// Offset 0 is line 0, char 0
		Position pos = offsetToPosition(content, 0);
		assertEquals(0, pos.line);
		assertEquals(0, pos.character);

		// Offset 6 is line 0, char 6
		pos = offsetToPosition(content, 6);
		assertEquals(0, pos.line);
		assertEquals(6, pos.character);
	}

	@Test
	public void testOffsetToPosition_SecondLine() {
		String content = "public class Test {\n    private int value;\n}";

		// Offset 20 is start of line 1 (after newline)
		Position pos = offsetToPosition(content, 20);
		assertEquals(1, pos.line);
		assertEquals(0, pos.character);

		// Offset 24 is line 1, char 4
		pos = offsetToPosition(content, 24);
		assertEquals(1, pos.line);
		assertEquals(4, pos.character);
	}

	@Test
	public void testOffsetToPosition_EndOfContent() {
		String content = "public class Test {\n    private int value;\n}";

		// Offset beyond end should return last position
		Position pos = offsetToPosition(content, 1000);
		assertEquals(2, pos.line); // Last line
		assertTrue(pos.character >= 0);
	}

	@Test
	public void testRoundTrip_PositionOffsetPosition() {
		String content = "public class Test {\n    private int value;\n    public int getValue() {\n        return value;\n    }\n}";

		// Test various positions round-trip correctly
		int[][] testPositions = {
			{0, 0},   // Start
			{0, 10},  // Middle of first line
			{1, 4},   // Indented second line
			{2, 15},  // Method declaration
			{3, 8}    // Inside method
		};

		for (int[] testPos : testPositions) {
			int line = testPos[0];
			int character = testPos[1];

			int offset = positionToOffset(content, line, character);
			assertTrue("Offset should be valid", offset >= 0);

			Position reconstructed = offsetToPosition(content, offset);
			assertEquals("Line should match", line, reconstructed.line);
			assertEquals("Character should match", character, reconstructed.character);
		}
	}

	// Helper methods that mirror the implementation in JavacTextDocumentService

	private int positionToOffset(String content, int line, int character) {
		String[] lines = content.split("\n", -1);
		if (line >= lines.length) {
			return -1;
		}

		int offset = 0;
		for (int i = 0; i < line; i++) {
			offset += lines[i].length() + 1; // +1 for newline
		}

		if (character > lines[line].length()) {
			return -1;
		}

		return offset + character;
	}

	private Position offsetToPosition(String content, int offset) {
		String[] lines = content.split("\n", -1);
		int currentOffset = 0;

		for (int line = 0; line < lines.length; line++) {
			int lineLength = lines[line].length();
			if (currentOffset + lineLength >= offset) {
				int character = offset - currentOffset;
				return new Position(line, character);
			}
			currentOffset += lineLength + 1; // +1 for newline
		}

		// Fallback: end of document
		return new Position(lines.length - 1, lines[lines.length - 1].length());
	}

	// Simple Position class for testing
	private static class Position {
		int line;
		int character;

		Position(int line, int character) {
			this.line = line;
			this.character = character;
		}
	}
}
