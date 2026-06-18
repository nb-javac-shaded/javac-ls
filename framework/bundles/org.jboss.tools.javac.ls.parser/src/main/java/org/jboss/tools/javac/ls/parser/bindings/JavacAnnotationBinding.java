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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.jboss.tools.javac.ls.parser.bindings.resolve.JavacBindingResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import shaded.com.sun.tools.javac.code.Attribute.Compound;
import shaded.org.eclipse.jdt.core.dom.IAnnotationBinding;
import shaded.org.eclipse.jdt.core.dom.IBinding;
import shaded.org.eclipse.jdt.core.dom.IMemberValuePairBinding;
import shaded.org.eclipse.jdt.core.dom.IMethodBinding;
import shaded.org.eclipse.jdt.core.dom.ITypeBinding;

public abstract class JavacAnnotationBinding implements IAnnotationBinding {

	private static final Logger LOGGER = LoggerFactory.getLogger(JavacAnnotationBinding.class);

	private final JavacBindingResolver resolver;
	private final Compound annotation;

	private final IBinding recipient;

	public JavacAnnotationBinding(Compound ann, JavacBindingResolver resolver, IBinding recipient) {
		this.resolver = resolver;
		this.annotation = ann;
		this.recipient = recipient;
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof JavacAnnotationBinding other
				&& Objects.equals(this.resolver, other.resolver)
				&& Objects.equals(this.annotation, other.annotation)
				&& Objects.equals(this.getRecipient(), other.getRecipient());
	}
	@Override
	public int hashCode() {
		return Objects.hash(this.resolver, this.annotation, this.getRecipient());
	}

	@Override
	public IAnnotationBinding[] getAnnotations() {
		return new IAnnotationBinding[0];
	}

	@Override
	public int getKind() {
		return ANNOTATION;
	}

	@Override
	public int getModifiers() {
		return getAnnotationTypeOptional().map(ITypeBinding::getModifiers).orElse(0);
	}

	@Override
	public boolean isDeprecated() {
		return getAnnotationTypeOptional().map(ITypeBinding::isDeprecated).orElse(false);
	}

	@Override
	public boolean isRecovered() {
		return getAnnotationTypeOptional().map(ITypeBinding::isRecovered).orElse(false);
	}

	@Override
	public boolean isSynthetic() {
		return getAnnotationTypeOptional().map(ITypeBinding::isSynthetic).orElse(false);
	}

	@Override
	public String getKey() {
		StringBuilder builder = new StringBuilder();
		if (this.getRecipient() != null) {
			builder.append(this.getRecipient().getKey());
		}
		builder.append('@');
		ITypeBinding annotationType = this.getAnnotationType();
		if (annotationType != null) {
			builder.append(this.getAnnotationType().getKey());
		} else {
			LOGGER.error("missing annotation type");
		}
		return builder.toString();
	}

	@Override
	public boolean isEqualTo(IBinding binding) {
		return binding instanceof IAnnotationBinding other && Objects.equals(getKey(), other.getKey());
	}

	@Override
	public IMemberValuePairBinding[] getAllMemberValuePairs() {
		IMemberValuePairBinding[] declared = getDeclaredMemberValuePairs();
		List<IMethodBinding> implicitDefaultMethods = new ArrayList<>(Arrays.asList(getAnnotationType().getDeclaredMethods()));
		Collections.sort(implicitDefaultMethods, new Comparator<IMethodBinding>() {
			@Override
			public int compare(IMethodBinding o1, IMethodBinding o2) {
				String s1 = o1.getName();
				String s2 = o2.getName();
				return Comparator.nullsFirst(String::compareTo).compare(s1, s2);
			}
		});
		var explicitedMethods = Arrays.stream(declared).map(IMemberValuePairBinding::getMethodBinding).toList();
		implicitDefaultMethods.removeAll(explicitedMethods);
		if (implicitDefaultMethods.isEmpty()) {
			return declared;
		}
		var defaults = implicitDefaultMethods.stream().map(this.resolver.bindings::getDefaultMemberValuePairBinding).filter(Objects::nonNull).toArray();
		var res = Arrays.copyOf(declared, declared.length + defaults.length);
		System.arraycopy(defaults, 0, res, declared.length, defaults.length);
		return res;
	}

	@Override
	public ITypeBinding getAnnotationType() {
		return this.resolver.bindings.getTypeBinding(this.annotation.type);
	}

	@Override
	public IMemberValuePairBinding[] getDeclaredMemberValuePairs() {
		return this.annotation.getElementValues().entrySet().stream()
				.map(entry -> this.resolver.bindings.getMemberValuePairBinding(entry.getKey(), entry.getValue()))
				.filter(Objects::nonNull)
				.toArray(IMemberValuePairBinding[]::new);
	}

	@Override
	public String getName() {
		return getAnnotationTypeOptional().map(ITypeBinding::getName).orElse(null);
	}

	@Override
	public String toString() {
		return '@' + getName() + '(' +
				Arrays.stream(getAllMemberValuePairs()).map(IMemberValuePairBinding::toString).collect(Collectors.joining(","))
			+ ')';
	}

	public IBinding getRecipient() {
		return recipient;
	}

	private Optional<ITypeBinding> getAnnotationTypeOptional() {
		return Optional.ofNullable(getAnnotationType());
	}
}
