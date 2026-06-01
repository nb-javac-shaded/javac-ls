/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.eclipse.jdt.internal.compiler.problem;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.eclipse.jdt.core.compiler.IProblem;

/**
 * Simple implementation of DefaultProblemFactory for message localization.
 */
public class DefaultProblemFactory {

	private ResourceBundle bundle;

	public DefaultProblemFactory(Locale locale) {
		try {
			this.bundle = ResourceBundle.getBundle("org.eclipse.jdt.internal.compiler.problem.messages", locale);
		} catch (MissingResourceException e) {
			// Fallback - bundle not available
			this.bundle = null;
		}
	}

	public String getLocalizedMessage(int problemId, String[] arguments) {
		String message = getMessage(problemId);
		if (message == null) {
			// Fallback to generic message
			return "Problem ID: " + problemId;
		}

		// Simple argument substitution
		if (arguments != null && arguments.length > 0) {
			for (int i = 0; i < arguments.length; i++) {
				String placeholder = "{" + i + "}";
				if (message.contains(placeholder) && arguments[i] != null) {
					message = message.replace(placeholder, arguments[i]);
				}
			}
		}

		return message;
	}

	private String getMessage(int problemId) {
		if (bundle != null) {
			try {
				return bundle.getString(String.valueOf(problemId));
			} catch (MissingResourceException e) {
				// Fall through to hardcoded messages
			}
		}

		// Hardcoded messages for common problem IDs
		switch (problemId) {
			case IProblem.UndefinedType:
				return "{0} cannot be resolved to a type";
			case IProblem.UninitializedBlankFinalField:
				return "The blank final field {0} may not have been initialized";
			case IProblem.EnumConstantMustImplementAbstractMethod:
				return "The enum constant {0} must implement the abstract method {1}";
			case IProblem.EnumAbstractMethodMustBeImplemented:
				return "The enum {0} can only define the abstract method {1} if it also defines enum constants with corresponding implementations";
			case IProblem.AbstractMethodMustBeImplemented:
				return "The type {0} must implement the inherited abstract method {1}";
			default:
				return "Problem ID: " + problemId;
		}
	}
}
