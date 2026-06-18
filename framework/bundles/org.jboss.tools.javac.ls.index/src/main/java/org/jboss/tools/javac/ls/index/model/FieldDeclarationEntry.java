/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.jboss.tools.javac.ls.index.model;

import java.util.Objects;

/**
 * Represents a field declaration.
 */
public class FieldDeclarationEntry {
	private String declaringType;
	private String fieldName;
	private String fieldType;
	private int modifiers;
	private Location location;

	public FieldDeclarationEntry() {
	}

	public String getDeclaringType() {
		return declaringType;
	}

	public void setDeclaringType(String declaringType) {
		this.declaringType = declaringType;
	}

	public String getFieldName() {
		return fieldName;
	}

	public void setFieldName(String fieldName) {
		this.fieldName = fieldName;
	}

	public String getFieldType() {
		return fieldType;
	}

	public void setFieldType(String fieldType) {
		this.fieldType = fieldType;
	}

	public int getModifiers() {
		return modifiers;
	}

	public void setModifiers(int modifiers) {
		this.modifiers = modifiers;
	}

	public Location getLocation() {
		return location;
	}

	public void setLocation(Location location) {
		this.location = location;
	}

	/**
	 * Returns a key for this field (type.fieldName)
	 */
	public String getFieldKey() {
		return declaringType + "." + fieldName;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		FieldDeclarationEntry that = (FieldDeclarationEntry) o;
		return Objects.equals(declaringType, that.declaringType) &&
				Objects.equals(fieldName, that.fieldName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(declaringType, fieldName);
	}

	@Override
	public String toString() {
		return fieldType + " " + getFieldKey();
	}
}
