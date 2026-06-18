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
package org.jboss.tools.javac.ls.parser.bindings;

import java.util.Objects;

import org.jboss.tools.javac.ls.parser.bindings.resolve.JavacBindingResolver;

import shaded.com.sun.tools.javac.code.Symbol.PackageSymbol;
import shaded.org.eclipse.jdt.core.dom.IAnnotationBinding;
import shaded.org.eclipse.jdt.core.dom.IBinding;
import shaded.org.eclipse.jdt.core.dom.IModuleBinding;
import shaded.org.eclipse.jdt.core.dom.IPackageBinding;
import shaded.org.eclipse.jdt.core.dom.ITypeBinding;

public abstract class JavacPackageBinding implements IPackageBinding {

	private PackageSymbol packageSymbol;
	final JavacBindingResolver resolver;
	private String nameString;

	public JavacPackageBinding(PackageSymbol packge, JavacBindingResolver resolver) {
		this.setPackageSymbol(packge);
		this.nameString = packge.getQualifiedName().toString();
		this.resolver = resolver;
	}

	public JavacPackageBinding(String nameString, JavacBindingResolver resolver) {
		this.nameString = nameString;
		this.resolver = resolver;
	}

	@Override
	public IAnnotationBinding[] getAnnotations() {
		return this.getPackageSymbol() == null ?
				new IAnnotationBinding[0] :
				this.getPackageSymbol().getAnnotationMirrors().stream()
				.map(am -> this.resolver.bindings.getAnnotationBinding(am, this))
				.filter(Objects::nonNull)
				.toArray(IAnnotationBinding[]::new);
	}

	@Override
	public int getKind() {
		return PACKAGE;
	}

	@Override
	public int getModifiers() {
		return this.getPackageSymbol() == null ? 0 : JavacMethodBinding.toInt(this.getPackageSymbol().getModifiers());
	}

	@Override
	public boolean isDeprecated() {
		return this.getPackageSymbol() == null ? false : this.getPackageSymbol().isDeprecated();
	}

	@Override
	public boolean isRecovered() {
		return this.packageSymbol == null;
	}

	@Override
	public boolean isSynthetic() {
		return false;
	}
	@Override
	public IModuleBinding getModule() {
		return this.getPackageSymbol() != null ?
				this.resolver.bindings.getModuleBinding(this.getPackageSymbol().modle) :
				null;
	}

	@Override
	public String getKey() {
		if (this.isUnnamed()) {
			return "";
		}
		return getQualifiedNameInternal().replace('.', '/');
	}

	@Override
	public String getName() {
		return isUnnamed() ? "" : this.getQualifiedNameInternal(); //$NON-NLS-1$
	}

	@Override
	public boolean isUnnamed() {
		PackageSymbol ps = this.getPackageSymbol();
		return ps != null ? ps.isUnnamed() : "".equals(this.nameString);
	}

	@Override
	public String[] getNameComponents() {
		return isUnnamed()? new String[0] : getQualifiedNameInternal().split("\\."); //$NON-NLS-1$
	}

	private String getQualifiedNameInternal() {
		return this.getPackageSymbol() != null ? this.getPackageSymbol().getQualifiedName().toString() :
			this.nameString;
	}

	@Override
	public ITypeBinding findTypeBinding(String name) {
		return null;
	}

	@Override
	public String toString() {
		return "package " + getName();
	}

	public PackageSymbol getPackageSymbol() {
		return packageSymbol;
	}

	public void setPackageSymbol(PackageSymbol packageSymbol) {
		this.packageSymbol = packageSymbol;
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.resolver, this.getPackageSymbol(), this.nameString);
	}

	@Override
	public boolean isEqualTo(IBinding binding) {
		return binding instanceof IPackageBinding other && Objects.equals(getKey(), other.getKey());
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof JavacPackageBinding other
				&& Objects.equals(this.resolver, other.resolver)
				&& Objects.equals(this.getPackageSymbol(), other.getPackageSymbol())
				&& Objects.equals(this.nameString, other.nameString);
	}

}
