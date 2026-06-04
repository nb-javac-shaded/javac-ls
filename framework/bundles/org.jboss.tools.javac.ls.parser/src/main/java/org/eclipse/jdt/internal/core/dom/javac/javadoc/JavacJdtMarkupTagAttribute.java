/*******************************************************************************
 * Copyright (c) 2024, 2025 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.jdt.internal.core.dom.javac.javadoc;

public class JavacJdtMarkupTagAttribute {
	private final String name;
	private final String value;

	public JavacJdtMarkupTagAttribute(String name, String value) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("Attribute name cannot be null or empty");
		}
		if (value == null) {
			throw new IllegalArgumentException("Attribute value cannot be null");
		}
		this.name = name;
		this.value = value;
	}

	public String name() {
		return name;
	}

	public String value() {
		return value;
	}

	@Override
	public String toString() {
		return name + "=\"" + value + "\"";
	}
}
