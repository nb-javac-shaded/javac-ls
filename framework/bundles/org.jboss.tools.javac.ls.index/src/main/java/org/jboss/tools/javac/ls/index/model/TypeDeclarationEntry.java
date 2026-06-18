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

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a type declaration (class, interface, enum, annotation, record).
 */
public class TypeDeclarationEntry {
	private String qualifiedName;
	private String simpleName;
	private String packageName;
	private TypeKind kind;
	private int modifiers;
	private String superclass;
	private List<String> interfaces;
	private Location location;

	public enum TypeKind {
		CLASS, INTERFACE, ENUM, ANNOTATION, RECORD
	}

	public TypeDeclarationEntry() {
		this.interfaces = Collections.emptyList();
	}

	public String getQualifiedName() {
		return qualifiedName;
	}

	public void setQualifiedName(String qualifiedName) {
		this.qualifiedName = qualifiedName;
	}

	public String getSimpleName() {
		return simpleName;
	}

	public void setSimpleName(String simpleName) {
		this.simpleName = simpleName;
	}

	public String getPackageName() {
		return packageName;
	}

	public void setPackageName(String packageName) {
		this.packageName = packageName;
	}

	public TypeKind getKind() {
		return kind;
	}

	public void setKind(TypeKind kind) {
		this.kind = kind;
	}

	public int getModifiers() {
		return modifiers;
	}

	public void setModifiers(int modifiers) {
		this.modifiers = modifiers;
	}

	public String getSuperclass() {
		return superclass;
	}

	public void setSuperclass(String superclass) {
		this.superclass = superclass;
	}

	public List<String> getInterfaces() {
		return interfaces;
	}

	public void setInterfaces(List<String> interfaces) {
		this.interfaces = interfaces != null ? interfaces : Collections.emptyList();
	}

	public Location getLocation() {
		return location;
	}

	public void setLocation(Location location) {
		this.location = location;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		TypeDeclarationEntry that = (TypeDeclarationEntry) o;
		return Objects.equals(qualifiedName, that.qualifiedName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(qualifiedName);
	}

	@Override
	public String toString() {
		return kind + " " + qualifiedName;
	}
}
