/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package shaded.org.eclipse.jdt.core;

/**
 * Minimal Signature implementation providing only methods needed by parser.
 * This is a simplified implementation that extracts type names from signatures.
 */
public class Signature {

	private Signature() {
		// No instances
	}

	/**
	 * Returns the simple name portion of a type signature.
	 * Simplified implementation that extracts the type name after the last package separator.
	 *
	 * For example:
	 * - "Ljava.util.Map$Entry;" -> "Map$Entry"
	 * - "Qjava.util.List;" -> "List"
	 * - "Map" -> "Map"
	 *
	 * @param typeSignature the type signature
	 * @return the simple name
	 */
	public static String getSignatureSimpleName(String typeSignature) {
		if (typeSignature == null || typeSignature.isEmpty()) {
			return "";
		}

		// Remove leading type indicators (L, Q, +Q, -Q, etc.)
		String working = typeSignature;
		if (working.startsWith("L") || working.startsWith("Q")) {
			working = working.substring(1);
		}

		// Remove trailing semicolon if present
		if (working.endsWith(";")) {
			working = working.substring(0, working.length() - 1);
		}

		// Find the last dot before any generic markers or inner class markers
		int lastDot = -1;
		for (int i = 0; i < working.length(); i++) {
			char c = working.charAt(i);
			if (c == '.') {
				lastDot = i;
			} else if (c == '<' || c == '$') {
				// Stop looking for dots after we hit generics or inner classes
				break;
			}
		}

		// Return everything after the last dot, or the whole string if no dot found
		if (lastDot >= 0 && lastDot < working.length() - 1) {
			return working.substring(lastDot + 1);
		}

		return working;
	}
}
