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
 * Represents a method declaration.
 */
public class MethodDeclarationEntry {
	private String declaringType;
	private String methodName;
	private List<String> parameterTypes;
	private String returnType;
	private int modifiers;
	private boolean isConstructor;
	private Location location;

	public MethodDeclarationEntry() {
		this.parameterTypes = Collections.emptyList();
	}

	public String getDeclaringType() {
		return declaringType;
	}

	public void setDeclaringType(String declaringType) {
		this.declaringType = declaringType;
	}

	public String getMethodName() {
		return methodName;
	}

	public void setMethodName(String methodName) {
		this.methodName = methodName;
	}

	public List<String> getParameterTypes() {
		return parameterTypes;
	}

	public void setParameterTypes(List<String> parameterTypes) {
		this.parameterTypes = parameterTypes != null ? parameterTypes : Collections.emptyList();
	}

	public String getReturnType() {
		return returnType;
	}

	public void setReturnType(String returnType) {
		this.returnType = returnType;
	}

	public int getModifiers() {
		return modifiers;
	}

	public void setModifiers(int modifiers) {
		this.modifiers = modifiers;
	}

	public boolean isConstructor() {
		return isConstructor;
	}

	public void setConstructor(boolean constructor) {
		isConstructor = constructor;
	}

	public Location getLocation() {
		return location;
	}

	public void setLocation(Location location) {
		this.location = location;
	}

	/**
	 * Returns a signature key for this method (type.name(param1,param2,...))
	 */
	public String getSignatureKey() {
		StringBuilder sb = new StringBuilder();
		sb.append(declaringType).append('.').append(methodName).append('(');
		for (int i = 0; i < parameterTypes.size(); i++) {
			if (i > 0) sb.append(',');
			sb.append(parameterTypes.get(i));
		}
		sb.append(')');
		return sb.toString();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		MethodDeclarationEntry that = (MethodDeclarationEntry) o;
		return Objects.equals(declaringType, that.declaringType) &&
				Objects.equals(methodName, that.methodName) &&
				Objects.equals(parameterTypes, that.parameterTypes);
	}

	@Override
	public int hashCode() {
		return Objects.hash(declaringType, methodName, parameterTypes);
	}

	@Override
	public String toString() {
		return getSignatureKey() + " : " + returnType;
	}
}
