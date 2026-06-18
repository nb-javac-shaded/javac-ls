/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package shaded.org.eclipse.jdt.internal.compiler.problem;

/**
 * Stub implementation of Messages for problem reporting.
 */
public class Messages {
	public static final String problem_noSourceInformation = "no source information available";
	public static final String problem_atLine = "at line {0}";

	/**
	 * Simple message binding - replaces {0}, {1}, etc. with provided arguments.
	 */
	public static String bind(String message, String... args) {
		if (message == null || args == null || args.length == 0) {
			return message;
		}
		String result = message;
		for (int i = 0; i < args.length; i++) {
			result = result.replace("{" + i + "}", args[i]);
		}
		return result;
	}
}
