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

import java.util.Collections;
import java.util.List;

public class JavacJdtMarkupTag {
	private final String name;
	private final List<JavacJdtMarkupTagAttribute> attributes;

	public JavacJdtMarkupTag(String name, List<JavacJdtMarkupTagAttribute> attributes) {
		if (name == null || name.isEmpty()) {
			throw new IllegalArgumentException("Tag name cannot be null or empty");
		}
		this.name = name;
		this.attributes = attributes != null ? attributes : Collections.emptyList();
	}

	public String name() {
		return name;
	}

	public List<JavacJdtMarkupTagAttribute> attributes() {
		return attributes;
	}

	@Override
	public String toString() {
		return "@" + name + " " + attributes;
	}
}
