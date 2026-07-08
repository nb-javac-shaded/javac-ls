/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package shaded.org.eclipse.jdt.core.dom;

import java.util.Set;

/**
 * Constants used by DOM classes, extracted from JDT compiler and core.
 */
public class DOMConstants {

	// From org.eclipse.jdt.internal.compiler.util.Util
	static final String EMPTY_STRING = "";
	static final int[] EMPTY_INT_ARRAY = new int[0];

	// From shaded.org.eclipse.jdt.core.compiler.CharOperation
	static final char[] NO_CHAR = new char[0];
	static final char[][] NO_CHAR_CHAR = new char[0][];
	public static final String[] NO_STRINGS = new String[0];

	// From org.eclipse.jdt.internal.compiler.lookup.TypeConstants
	static final char[][] JAVA_LANG_ASSERTIONERROR = new char[][] {"java".toCharArray(), "lang".toCharArray(), "AssertionError".toCharArray()};
	static final char[][] JAVA_LANG_BOOLEAN = new char[][] {"java".toCharArray(), "lang".toCharArray(), "Boolean".toCharArray()};
	static final char[][] JAVA_LANG_BYTE = new char[][] {"java".toCharArray(), "lang".toCharArray(), "Byte".toCharArray()};
	static final char[][] JAVA_LANG_CHARACTER = new char[][] {"java".toCharArray(), "lang".toCharArray(), "Character".toCharArray()};
	static final char[][] JAVA_LANG_DOUBLE = new char[][] {"java".toCharArray(), "lang".toCharArray(), "Double".toCharArray()};
	static final char[][] JAVA_LANG_ERROR = new char[][] {"java".toCharArray(), "lang".toCharArray(), "Error".toCharArray()};
	static final char[][] JAVA_LANG_EXCEPTION = new char[][] {"java".toCharArray(), "lang".toCharArray(), "Exception".toCharArray()};
	static final char[][] JAVA_LANG_FLOAT = new char[][] {"java".toCharArray(), "lang".toCharArray(), "Float".toCharArray()};
	static final char[][] JAVA_LANG_INTEGER = new char[][] {"java".toCharArray(), "lang".toCharArray(), "Integer".toCharArray()};
	static final char[][] JAVA_LANG_LONG = new char[][] {"java".toCharArray(), "lang".toCharArray(), "Long".toCharArray()};
	static final char[][] JAVA_LANG_RUNTIMEEXCEPTION = new char[][] {"java".toCharArray(), "lang".toCharArray(), "RuntimeException".toCharArray()};
	static final char[][] JAVA_LANG_SHORT = new char[][] {"java".toCharArray(), "lang".toCharArray(), "Short".toCharArray()};
	static final char[][] JAVA_LANG_STRINGBUFFER = new char[][] {"java".toCharArray(), "lang".toCharArray(), "StringBuffer".toCharArray()};
	static final char[][] JAVA_LANG_VOID = new char[][] {"java".toCharArray(), "lang".toCharArray(), "Void".toCharArray()};
	static final char[] PACKAGE_INFO_NAME = "package-info".toCharArray();
	static final char[] WILDCARD_NAME = "?".toCharArray();
	static final char[] WILDCARD_SUPER = "? super ".toCharArray();
	static final char[] WILDCARD_EXTENDS = "? extends ".toCharArray();
	static final char[][] JAVA_DOT_BASE = new char[][] {"java".toCharArray(), "base".toCharArray()};

	// JDK version constants from ClassFileConstants
	static final long JDK1_8 = 0x3080000L;
	static final long JDK9 = 0x3090000L;
	static final long JDK10 = 0x30A0000L;
	static final long JDK11 = 0x30B0000L;
	static final long JDK12 = 0x30C0000L;
	static final long JDK13 = 0x30D0000L;
	static final long JDK14 = 0x30E0000L;
	static final long JDK15 = 0x30F0000L;
	static final long JDK16 = 0x3100000L;
	static final long JDK17 = 0x3110000L;
	static final long JDK18 = 0x3120000L;
	static final long JDK19 = 0x3130000L;
	static final long JDK20 = 0x3140000L;
	static final long JDK21 = 0x3150000L;
	static final long JDK22 = 0x3160000L;
	static final long JDK23 = 0x3170000L;
	static final long JDK24 = 0x3180000L;
	static final long JDK25 = 0x3190000L;
	static final long JDK26 = 0x31A0000L;

	// Additional TypeConstants
	static final char[][] CharArray_JAVA_LANG_OBJECT = new char[][] {"java".toCharArray(), "lang".toCharArray(), "Object".toCharArray()};

	// Access flags from ClassFileConstants
	static final int AccSuper = 0x0020;
	static final int AccInterface = 0x0200;
	static final int AccEnum = 0x4000;
	static final int AccAnnotation = 0x2000;

	// Major version constant
	static final int MAJOR_VERSION = 52; // Java 8

	// ExtraCompilerModifiers constants
	static final int AccJustFlag = 0x10000000;
	static final int AccUnresolved = 0x08000000;

	/**
	 * Get compliance level for Java version.
	 * Stub implementation - returns the input value.
	 */
	static long getComplianceLevelForJavaVersion(long javaVersion) {
		return javaVersion;
	}

	// Java keyword validation

	/**
	 * All Java keywords including reserved words from all versions.
	 */
	private static final java.util.Set<String> JAVA_KEYWORDS = java.util.Set.of(
		// Java 1.0 keywords (unconditional)
		"abstract", "boolean", "break", "byte", "case", "catch", "char", "class",
		"const", "continue", "default", "do", "double", "else", "extends", "final",
		"finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
		"int", "interface", "long", "native", "new", "package", "private", "protected",
		"public", "return", "short", "static", "strictfp", "super", "switch",
		"synchronized", "this", "throw", "throws", "transient", "try", "void",
		"volatile", "while",
		// Java 1.4 - assert (keyword)
		"assert",
		// Java 5 - enum (keyword)
		"enum",
		// Java 14+ - contextual keywords (yield, record, sealed, permits)
		"yield", "record", "sealed", "permits",
		// Reserved literals (not keywords but also not valid identifiers)
		"true", "false", "null",
		// Java 9 - underscore alone is invalid
		"_"
	);

	/**
	 * Java 8 and earlier keywords (excluding contextual keywords introduced in Java 9+).
	 * Used for identifier validation to allow contextual keywords like "record", "yield", etc.
	 * as valid identifiers since they are only keywords in specific contexts.
	 */
	private static final java.util.Set<String> JAVA8_KEYWORDS = java.util.Set.of(
		// Java 1.0 keywords (unconditional)
		"abstract", "boolean", "break", "byte", "case", "catch", "char", "class",
		"const", "continue", "default", "do", "double", "else", "extends", "final",
		"finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
		"int", "interface", "long", "native", "new", "package", "private", "protected",
		"public", "return", "short", "static", "strictfp", "super", "switch",
		"synchronized", "this", "throw", "throws", "transient", "try", "void",
		"volatile", "while",
		// Java 1.4 - assert (keyword)
		"assert",
		// Java 5 - enum (keyword)
		"enum",
		// Reserved literals (not keywords but also not valid identifiers)
		"true", "false", "null"
	);

	/**
	 * Validates that a string is a valid Java identifier.
	 * An identifier must start with a letter, $, or _, and continue with letters, digits, $, or _.
	 * It must not be a keyword or reserved word.
	 * Uses JAVA8_KEYWORDS to allow contextual keywords like "record", "yield" as identifiers.
	 */
	static boolean isValidJavaIdentifier(String s) {
		return isValidJavaIdentifier(s, JAVA_KEYWORDS);
	}
	static boolean isValidJavaIdentifierFirstSupportedJDK(String s) {
		return isValidJavaIdentifier(s, JAVA8_KEYWORDS);
	}
	static boolean isValidJavaIdentifier(String s, Set<String> keywords) {
		if (s == null || s.isEmpty()) {
			return false;
		}

		// Check first character
		if (!Character.isJavaIdentifierStart(s.charAt(0))) {
			return false;
		}

		// Check remaining characters
		for (int i = 1; i < s.length(); i++) {
			if (!Character.isJavaIdentifierPart(s.charAt(i))) {
				return false;
			}
		}

		// Check it's not a keyword or reserved word (using Java 8 keywords only)
		return !keywords.contains(s);
	}

	// Scanner helper methods (from org.eclipse.jdt.internal.compiler.parser.ScannerHelper)

	/**
	 * Check if a character is whitespace according to Java specifications.
	 */
	public static boolean isWhitespace(char c) {
		return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
	}

	/**
	 * Check if a character is a digit (0-9).
	 */
	static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	/**
	 * Get the numeric value of a character.
	 * For digits 0-9, returns the numeric value.
	 * For hex digits a-f, A-F, returns 10-15.
	 */
	static int getNumericValue(char c) {
		if (c >= '0' && c <= '9') {
			return c - '0';
		}
		if (c >= 'a' && c <= 'f') {
			return c - 'a' + 10;
		}
		if (c >= 'A' && c <= 'F') {
			return c - 'A' + 10;
		}
		return -1;
	}

	// Character literal validation and parsing

	/**
	 * Validates that a string is a valid character literal.
	 * Character literals must be in the form 'x' where x is a character or escape sequence.
	 */
	static boolean isValidCharacterLiteral(String s) {
		if (s == null || s.length() < 3) return false;
		if (s.charAt(0) != '\'' || s.charAt(s.length() - 1) != '\'') return false;
		// Simple validation - check it's well-formed
		int i = 1;
		if (s.charAt(i) == '\\') {
			// Escape sequence
			i++;
			if (i >= s.length() - 1) return false;
			char escapeChar = s.charAt(i);
			if (escapeChar == 'b' || escapeChar == 't' || escapeChar == 'n' ||
				escapeChar == 'f' || escapeChar == 'r' || escapeChar == '"' ||
				escapeChar == '\'' || escapeChar == '\\') {
				return i + 1 == s.length() - 1;
			}
			// Unicode escape \\uXXXX
			if (escapeChar == 'u') {
				i++;
				for (int j = 0; j < 4; j++) {
					if (i >= s.length() - 1) return false;
					char hexChar = s.charAt(i);
					if (!((hexChar >= '0' && hexChar <= '9') ||
						  (hexChar >= 'a' && hexChar <= 'f') ||
						  (hexChar >= 'A' && hexChar <= 'F'))) {
						return false;
					}
					i++;
				}
				return i == s.length() - 1;
			}
			// Octal escape
			if (escapeChar >= '0' && escapeChar <= '7') {
				i++;
				while (i < s.length() - 1 && s.charAt(i) >= '0' && s.charAt(i) <= '7' && i < 4) {
					i++;
				}
				return i == s.length() - 1;
			}
			return false;
		} else {
			// Single character
			return s.length() == 3;
		}
	}

	/**
	 * Validates that a string is a valid string literal.
	 * String literals must be in the form "..." with properly escaped content.
	 */
	static boolean isValidStringLiteral(String s) {
		if (s == null || s.length() < 2) return false;
		if (s.charAt(0) != '\"' || s.charAt(s.length() - 1) != '\"') return false;

		// Check for proper escape sequences
		int i = 1;
		while (i < s.length() - 1) {
			char c = s.charAt(i);
			if (c == '\\') {
				i++;
				if (i >= s.length() - 1) return false;
				char escapeChar = s.charAt(i);
				// Check for valid escape sequences
				if (escapeChar == 'b' || escapeChar == 't' || escapeChar == 'n' ||
					escapeChar == 'f' || escapeChar == 'r' || escapeChar == '"' ||
					escapeChar == '\'' || escapeChar == '\\') {
					i++;
					continue;
				}
				// Unicode escape \\uXXXX
				if (escapeChar == 'u') {
					i++;
					for (int j = 0; j < 4; j++) {
						if (i >= s.length() - 1) return false;
						char hexChar = s.charAt(i);
						if (!((hexChar >= '0' && hexChar <= '9') ||
							  (hexChar >= 'a' && hexChar <= 'f') ||
							  (hexChar >= 'A' && hexChar <= 'F'))) {
							return false;
						}
						i++;
					}
					continue;
				}
				// Octal escape
				if (escapeChar >= '0' && escapeChar <= '7') {
					i++;
					int octCount = 1;
					while (i < s.length() - 1 && octCount < 3 && s.charAt(i) >= '0' && s.charAt(i) <= '7') {
						i++;
						octCount++;
					}
					continue;
				}
				return false; // Invalid escape
			} else if (c == '"') {
				// Unescaped quote in middle of string
				return false;
			}
			i++;
		}
		return true;
	}

	/**
	 * Parses an escaped string literal and returns the unescaped value.
	 * The input should be a complete string literal including quotes.
	 */
	static String parseStringLiteral(String escapedValue) {
		if (escapedValue == null || escapedValue.length() < 2) {
			throw new IllegalArgumentException("Invalid string literal");
		}
		if (escapedValue.charAt(0) != '\"' || escapedValue.charAt(escapedValue.length() - 1) != '\"') {
			throw new IllegalArgumentException("String literal must start and end with quotes");
		}

		StringBuilder result = new StringBuilder();
		int i = 1; // Skip opening quote
		int len = escapedValue.length() - 1; // Skip closing quote

		while (i < len) {
			char c = escapedValue.charAt(i);
			if (c == '\\') {
				i++;
				if (i >= len) {
					throw new IllegalArgumentException("Invalid escape at end of string");
				}
				char escapeChar = escapedValue.charAt(i);
				switch (escapeChar) {
					case 'b':
						result.append('\b');
						break;
					case 't':
						result.append('\t');
						break;
					case 'n':
						result.append('\n');
						break;
					case 'f':
						result.append('\f');
						break;
					case 'r':
						result.append('\r');
						break;
					case '\"':
						result.append('\"');
						break;
					case '\'':
						result.append('\'');
						break;
					case '\\':
						result.append('\\');
						break;
					case 'u':
						// Unicode escape \\uXXXX
						i++;
						if (i + 3 >= len) {
							throw new IllegalArgumentException("Invalid unicode escape");
						}
						String hexStr = escapedValue.substring(i, i + 4);
						try {
							int codePoint = Integer.parseInt(hexStr, 16);
							result.append((char) codePoint);
						} catch (NumberFormatException e) {
							throw new IllegalArgumentException("Invalid unicode escape: \\u" + hexStr);
						}
						i += 3; // Will be incremented by 1 more at end of loop
						break;
					default:
						// Octal escape
						if (escapeChar >= '0' && escapeChar <= '7') {
							int octalValue = escapeChar - '0';
							int count = 1;
							while (i + 1 < len && count < 3) {
								char nextChar = escapedValue.charAt(i + 1);
								if (nextChar >= '0' && nextChar <= '7') {
									octalValue = octalValue * 8 + (nextChar - '0');
									i++;
									count++;
								} else {
									break;
								}
							}
							result.append((char) octalValue);
						} else {
							throw new IllegalArgumentException("Invalid escape sequence: \\" + escapeChar);
						}
				}
			} else {
				result.append(c);
			}
			i++;
		}

		return result.toString();
	}

	/**
	 * Validates that a string is a valid number literal.
	 */
	static boolean isValidNumberLiteral(String s) {
		if (s == null || s.isEmpty()) return false;
		// Simple regex-based validation for common number formats
		// Handles: integers, longs, floats, doubles, hex, binary, octal
		return s.matches("[-+]?[0-9][0-9_]*(\\.[0-9_]+)?([eE][-+]?[0-9]+)?[lLfFdD]?") ||  // decimal
			   s.matches("[-+]?0[xX][0-9a-fA-F][0-9a-fA-F_]*[lL]?") ||  // hex
			   s.matches("[-+]?0[bB][01][01_]*[lL]?") ||  // binary
			   s.matches("[-+]?0[0-7][0-7_]*[lL]?") ||  // octal
			   s.matches("[-+]?\\.[0-9][0-9_]*([eE][-+]?[0-9]+)?[fFdD]?");  // decimal starting with .
	}

	/**
	 * Validates that a string is a valid text block.
	 * Text blocks start with three double quotes, have content (possibly multi-line),
	 * and end with three double quotes.
	 */
	static boolean isValidTextBlock(String s) {
		if (s == null || s.length() < 7) return false; // Minimum: """...""" (7 chars)

		// Must start with """
		if (!s.startsWith("\"\"\"")) return false;

		// Must end with """
		if (!s.endsWith("\"\"\"")) return false;

		// After opening """, must have at least one line terminator before content
		// This is a simplified check - text blocks require a newline after opening """
		int i = 3;
		boolean foundNewline = false;
		while (i < s.length() - 3) {
			char c = s.charAt(i);
			if (c == '\n' || c == '\r') {
				foundNewline = true;
				break;
			}
			if (!isWhitespace(c)) {
				// Non-whitespace before newline is invalid
				return false;
			}
			i++;
		}

		return foundNewline || s.length() == 7; // Empty text block """ \n""" or just """"""
	}

	/**
	 * Validates that a string is a valid Javadoc comment.
	 * Javadoc comments must start with slash star star, and end with star slash,
	 * and contain only valid content between the delimiters.
	 */
	static boolean isValidJavadoc(String s) {
		if (s == null || s.length() < 5) return false; // Minimum: "/** */" (7 chars) but allow "/**/" (5 chars)

		// Must start with /**
		if (!s.startsWith("/**")) return false;

		// Must end with */
		if (!s.endsWith("*/")) return false;

		// Check for embedded */ that would terminate the comment early
		int endMarkerIndex = s.indexOf("*/");
		if (endMarkerIndex != s.length() - 2) {
			// Found */ before the end
			return false;
		}

		return true;
	}

	/**
	 * Validates that a string is a valid Java comment.
	 * Comments can be:
	 * - Block comments: slash star ... star slash
	 * - Javadoc comments: slash star star ... star slash
	 * - Line comments: slash slash ... (with optional line terminator)
	 */
	static boolean isValidComment(String s) {
		if (s == null || s.length() < 2) return false;

		// Check for block or Javadoc comment
		if (s.startsWith("/*")) {
			// Must end with */
			if (!s.endsWith("*/")) return false;

			// Check for embedded */ that would terminate the comment early
			int endMarkerIndex = s.indexOf("*/");
			if (endMarkerIndex != s.length() - 2) {
				// Found */ before the end
				return false;
			}
			return true;
		}

		// Check for line comment
		if (s.startsWith("//")) {
			// Line comments should not contain embedded newlines (except at the end)
			int firstNewline = -1;
			for (int i = 2; i < s.length(); i++) {
				char c = s.charAt(i);
				if (c == '\n' || c == '\r') {
					if (firstNewline == -1) {
						firstNewline = i;
					} else {
						// Multiple newlines - check if there's any non-whitespace after first newline
						if (i > firstNewline + 1 && s.charAt(firstNewline + 1) != '\n' && s.charAt(firstNewline + 1) != '\r') {
							return false; // Content after newline
						}
					}
				} else if (firstNewline != -1 && !isWhitespace(c)) {
					// Non-whitespace content after newline
					return false;
				}
			}
			return true;
		}

		return false;
	}

	/**
	 * Copy a subarray from source array.
	 * Replacement for CharOperation.subarray().
	 */
	static char[] subarray(char[] array, int start, int end) {
		if (start > end) {
			return new char[0];
		}
		int length = end - start;
		char[] result = new char[length];
		System.arraycopy(array, start, result, 0, length);
		return result;
	}

	/**
	 * Append an escaped version of a character to a StringBuilder.
	 * Used for character and string literal escaping.
	 */
	static void appendEscapedChar(StringBuilder buffer, char c, boolean stringLiteral) {
		switch (c) {
			case '\b':
				buffer.append("\\b");
				break;
			case '\t':
				buffer.append("\\t");
				break;
			case '\n':
				buffer.append("\\n");
				break;
			case '\f':
				buffer.append("\\f");
				break;
			case '\r':
				buffer.append("\\r");
				break;
			case '\"':
				if (stringLiteral) {
					buffer.append("\\\"");
				} else {
					buffer.append('\"');
				}
				break;
			case '\'':
				if (!stringLiteral) {
					buffer.append("\\'");
				} else {
					buffer.append('\'');
				}
				break;
			case '\\':
				buffer.append("\\\\");
				break;
			default:
				if (c >= 0x20 && c < 0x7F) {
					buffer.append(c);
				} else {
					// Unicode escape
					buffer.append("\\u");
					String hex = Integer.toHexString(c);
					for (int i = hex.length(); i < 4; i++) {
						buffer.append('0');
					}
					buffer.append(hex);
				}
		}
	}

	/**
	 * Simple character reader for parsing character literals.
	 */
	static class CharReader {
		private final char[] source;
		private int position;

		CharReader(char[] source) {
			this.source = source;
			this.position = 0;
		}

		int getNextChar() {
			if (position >= source.length) {
				return -1;
			}
			return source[position++];
		}

		void reset() {
			position = 0;
		}
	}

	private DOMConstants() {
		// No instances
	}
}
