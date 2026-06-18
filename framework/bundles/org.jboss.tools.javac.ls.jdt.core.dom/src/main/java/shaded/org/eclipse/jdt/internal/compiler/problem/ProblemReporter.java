/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package shaded.org.eclipse.jdt.internal.compiler.problem;

import shaded.org.eclipse.jdt.core.compiler.CategorizedProblem;
import shaded.org.eclipse.jdt.core.compiler.IProblem;

/**
 * Stub implementation of ProblemReporter for categorizing problems.
 */
public class ProblemReporter {

	/**
	 * Returns the category ID for a given problem based on its severity and ID.
	 * This is a simplified stub implementation that returns a basic categorization.
	 */
	public static int getProblemCategory(int severity, int problemID) {
		// Basic categorization based on problem ID ranges
		// This is a simplified version - the real implementation has detailed mappings

		if (problemID == IProblem.Task) {
			return CategorizedProblem.CAT_UNSPECIFIED;
		}

		// Syntax errors (general range)
		if (problemID >= IProblem.ParsingError && problemID <= IProblem.ParsingErrorInsertToCompletePhrase) {
			return CategorizedProblem.CAT_SYNTAX;
		}

		// Import problems
		if (problemID >= IProblem.ImportNotFound && problemID <= IProblem.DuplicateImport) {
			return CategorizedProblem.CAT_IMPORT;
		}

		// Type problems
		if (problemID >= IProblem.UndefinedType && problemID <= IProblem.UndefinedTypeVariable) {
			return CategorizedProblem.CAT_TYPE;
		}

		// Default to unspecified
		return CategorizedProblem.CAT_UNSPECIFIED;
	}
}
