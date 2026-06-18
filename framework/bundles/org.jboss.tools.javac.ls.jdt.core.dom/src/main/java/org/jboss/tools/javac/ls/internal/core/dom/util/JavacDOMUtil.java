/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Red Hat, Inc. - initial API and implementation
 *     Derived from org.eclipse.jdt.internal.javac.JavacUtils
 *     Derived from org.eclipse.jdt.internal.codeassist.DOMCodeSelector
 *******************************************************************************/
package org.jboss.tools.javac.ls.internal.core.dom.util;

import shaded.org.eclipse.jdt.core.dom.ASTNode;
import shaded.org.eclipse.jdt.core.dom.ASTVisitor;
import shaded.org.eclipse.jdt.core.dom.MarkerAnnotation;

/**
 * Utility methods for javac-based DOM operations.
 * Contains utilities needed by JavacConverter for converting javac trees to Eclipse DOM AST.
 */
public class JavacDOMUtil {

	/**
	 * Fake identifier used by error recovery scanner.
	 * Copied from org.eclipse.jdt.internal.compiler.parser.RecoveryScanner.FAKE_IDENTIFIER
	 */
	public static final char[] FAKE_IDENTIFIER = "$missing$".toCharArray();

	/**
	 * String version of FAKE_IDENTIFIER for convenience.
	 */
	public static final String FAKE_IDENTIFIER_STRING = new String(FAKE_IDENTIFIER);

	/**
	 * Find a match for text in content, handling Unicode escapes.
	 * This method searches for the given text within the content string, taking into account
	 * Unicode escape sequences (backslash-u followed by 4 hex digits) which may be used in Java source files.
	 *
	 * Copied from org.eclipse.jdt.internal.javac.JavacUtils.findMatch()
	 *
	 * @param content the content to search in
	 * @param text the text to search for
	 * @param searchStart the starting offset in content
	 * @param searchEnd the ending offset in content
	 * @return int array [offset, length] if found, or null if not found
	 */
	public static int[] findMatch(String content, String text, int searchStart, int searchEnd) {
		for (int offset = searchStart; offset < searchEnd; offset++) {
			int cursor = offset;
			boolean matches = true;
			for (int i = 0; i < text.length(); i++) {
				int nextCursor = findCharacterMatch(content, text.charAt(i), cursor, searchEnd);
				if (nextCursor < 0) {
					matches = false;
					break;
				}
				cursor = nextCursor;
			}
			if (matches) {
				return new int[] { offset, cursor - offset };
			}
		}
		return null;
	}

	/**
	 * Find a single character match in content, handling Unicode escapes.
	 *
	 * @param content the content to search in
	 * @param expected the expected character
	 * @param cursor the current position
	 * @param searchEnd the end of search range
	 * @return the next cursor position after the match, or -1 if no match
	 */
	private static int findCharacterMatch(String content, char expected, int cursor, int searchEnd) {
		if (cursor >= searchEnd) {
			return -1;
		}
		char sourceChar = content.charAt(cursor);
		int sourceLength = 1;

		// Handle Unicode escape sequences: backslash-u followed by hex digits
		if (sourceChar == '\\' && cursor + 1 < searchEnd && content.charAt(cursor + 1) == 'u') {
			int hexOffset = cursor + 2;
			// Skip additional 'u' characters (multiple u's are valid in Java)
			while (hexOffset < searchEnd && content.charAt(hexOffset) == 'u') {
				hexOffset++;
			}
			// Need 4 hex digits
			if (hexOffset + 4 > searchEnd) {
				return -1;
			}
			int value = 0;
			for (int i = hexOffset; i < hexOffset + 4; i++) {
				int digit = Character.digit(content.charAt(i), 16);
				if (digit < 0) {
					return -1; // Invalid hex digit
				}
				value = (value << 4) + digit;
			}
			sourceChar = (char) value;
			sourceLength = hexOffset + 4 - cursor;
		}

		return sourceChar == expected ? cursor + sourceLength : -1;
	}

	/**
	 * Check if an AST node is generated code (e.g., by Lombok).
	 * This checks for the presence of annotations that indicate generated code,
	 * such as @lombok.Generated.
	 *
	 * Copied from org.eclipse.jdt.internal.codeassist.DOMCodeSelector.isGenerated()
	 *
	 * @param node the AST node to check
	 * @return true if the node is marked as generated, false otherwise
	 */
	public static boolean isGenerated(ASTNode node) {
		if (node == null) {
			return false;
		}

		boolean[] isGenerated = {false};
		node.accept(new ASTVisitor() {
			@Override
			public void endVisit(MarkerAnnotation markerAnnotation) {
				if (!isGenerated[0]) {
					// Check for lombok.Generated annotation
					// Can be extended to check for other generated code markers
					isGenerated[0] = "lombok.Generated".equals(
						markerAnnotation.getTypeName().getFullyQualifiedName()
					);
					super.endVisit(markerAnnotation);
				}
			}
		});

		return isGenerated[0];
	}
}
