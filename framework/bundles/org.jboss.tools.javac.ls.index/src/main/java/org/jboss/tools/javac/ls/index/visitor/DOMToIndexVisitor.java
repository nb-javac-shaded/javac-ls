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
package org.jboss.tools.javac.ls.index.visitor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.jboss.tools.javac.ls.index.IndexChangeEvent;
import org.jboss.tools.javac.ls.index.IndexChangeEvent.ChangeKind;
import org.jboss.tools.javac.ls.index.model.FieldDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.Location;
import org.jboss.tools.javac.ls.index.model.MethodDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.ReferenceEntry;
import org.jboss.tools.javac.ls.index.model.ReferenceEntry.ReferenceKind;
import org.jboss.tools.javac.ls.index.model.TypeDeclarationEntry;
import org.jboss.tools.javac.ls.index.model.TypeDeclarationEntry.TypeKind;
import org.jboss.tools.javac.ls.index.store.JavaIndex;

import shaded.org.eclipse.jdt.core.dom.ASTVisitor;
import shaded.org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import shaded.org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import shaded.org.eclipse.jdt.core.dom.ClassInstanceCreation;
import shaded.org.eclipse.jdt.core.dom.CompilationUnit;
import shaded.org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import shaded.org.eclipse.jdt.core.dom.EnumDeclaration;
import shaded.org.eclipse.jdt.core.dom.FieldDeclaration;
import shaded.org.eclipse.jdt.core.dom.ITypeBinding;
import shaded.org.eclipse.jdt.core.dom.ImportDeclaration;
import shaded.org.eclipse.jdt.core.dom.MarkerAnnotation;
import shaded.org.eclipse.jdt.core.dom.MethodDeclaration;
import shaded.org.eclipse.jdt.core.dom.MethodInvocation;
import shaded.org.eclipse.jdt.core.dom.Name;
import shaded.org.eclipse.jdt.core.dom.NormalAnnotation;
import shaded.org.eclipse.jdt.core.dom.PackageDeclaration;
import shaded.org.eclipse.jdt.core.dom.ParameterizedType;
import shaded.org.eclipse.jdt.core.dom.QualifiedName;
import shaded.org.eclipse.jdt.core.dom.QualifiedType;
import shaded.org.eclipse.jdt.core.dom.RecordDeclaration;
import shaded.org.eclipse.jdt.core.dom.SimpleName;
import shaded.org.eclipse.jdt.core.dom.SimpleType;
import shaded.org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import shaded.org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import shaded.org.eclipse.jdt.core.dom.Type;
import shaded.org.eclipse.jdt.core.dom.TypeDeclaration;
import shaded.org.eclipse.jdt.core.dom.VariableDeclaration;
import shaded.org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/**
 * Visits an AST to extract declarations and references into a JavaIndex.
 */
public class DOMToIndexVisitor extends ASTVisitor {

	private final JavaIndex index;
	private final Path file;
	private final Set<String> declaredTypes = new HashSet<>();

	private String packageName = "";
	private final List<AbstractTypeDeclaration> enclosingTypes = new LinkedList<>();
	private final List<String> enclosingTypeQualifiedNames = new LinkedList<>();

	public DOMToIndexVisitor(JavaIndex index, Path file) {
		super(true);
		this.index = index;
		this.file = file;
	}

	/**
	 * Call this after visiting to finalize the index update.
	 */
	public void finishIndexing() {
		index.trackFileDeclaredTypes(file, declaredTypes);
		index.fireIndexChanged(new IndexChangeEvent(file, ChangeKind.FILE_UPDATED));
	}

	private AbstractTypeDeclaration currentType() {
		if (enclosingTypes.isEmpty()) {
			return null;
		}
		return enclosingTypes.get(enclosingTypes.size() - 1);
	}

	@Override
	public boolean visit(PackageDeclaration packageDeclaration) {
		this.packageName = packageDeclaration.getName().getFullyQualifiedName();
		return false;
	}

	@Override
	public boolean visit(TypeDeclaration type) {
		String typeName = type.getName().getIdentifier();
		String qualifiedName = buildQualifiedName(typeName);

		TypeDeclarationEntry entry = new TypeDeclarationEntry();
		entry.setQualifiedName(qualifiedName);
		entry.setSimpleName(typeName);
		entry.setPackageName(packageName);
		entry.setKind(type.isInterface() ? TypeKind.INTERFACE : TypeKind.CLASS);
		entry.setModifiers(type.getModifiers());
		entry.setLocation(createLocation(type));

		// Set superclass
		if (type.getSuperclassType() != null) {
			entry.setSuperclass(resolveTypeName(type.getSuperclassType()));
		}

		// Set interfaces
		List<String> interfaces = new ArrayList<>();
		for (Object obj : type.superInterfaceTypes()) {
			if (obj instanceof Type) {
				String ifaceName = resolveTypeName((Type) obj);
				if (ifaceName != null) {
					interfaces.add(ifaceName);
				}
			}
		}
		entry.setInterfaces(interfaces);

		index.addType(entry);
		declaredTypes.add(qualifiedName);

		enclosingTypes.add(type);
		enclosingTypeQualifiedNames.add(qualifiedName);

		// Add default constructor if no explicit constructors
		if (!type.isInterface()) {
			boolean hasConstructor = false;
			for (Object member : type.bodyDeclarations()) {
				if (member instanceof MethodDeclaration) {
					MethodDeclaration method = (MethodDeclaration) member;
					if (method.isConstructor()) {
						hasConstructor = true;
						break;
					}
				}
			}

			if (!hasConstructor) {
				// Add default constructor
				MethodDeclarationEntry defaultCtor = new MethodDeclarationEntry();
				defaultCtor.setDeclaringType(qualifiedName);
				defaultCtor.setMethodName(typeName);
				defaultCtor.setConstructor(true);
				defaultCtor.setModifiers(type.getModifiers());
				defaultCtor.setParameterTypes(Collections.emptyList());
				defaultCtor.setLocation(createLocation(type));
				index.addMethod(defaultCtor);
			}

			// Add constructor reference to superclass (implicit super() call)
			if (type.getSuperclassType() != null) {
				String superName = resolveTypeName(type.getSuperclassType());
				if (superName != null) {
					index.addTypeReference(superName,
							new ReferenceEntry(createLocation(type.getSuperclassType()),
									ReferenceKind.CONSTRUCTOR_INVOCATION));
				}
			}
		}

		return true;
	}

	@Override
	public void endVisit(TypeDeclaration type) {
		enclosingTypes.remove(type);
		if (!enclosingTypeQualifiedNames.isEmpty()) {
			enclosingTypeQualifiedNames.remove(enclosingTypeQualifiedNames.size() - 1);
		}
	}

	@Override
	public boolean visit(EnumDeclaration type) {
		String typeName = type.getName().getIdentifier();
		String qualifiedName = buildQualifiedName(typeName);

		TypeDeclarationEntry entry = new TypeDeclarationEntry();
		entry.setQualifiedName(qualifiedName);
		entry.setSimpleName(typeName);
		entry.setPackageName(packageName);
		entry.setKind(TypeKind.ENUM);
		entry.setModifiers(type.getModifiers());
		entry.setLocation(createLocation(type));
		entry.setSuperclass("java.lang.Enum");

		List<String> interfaces = new ArrayList<>();
		for (Object obj : type.superInterfaceTypes()) {
			if (obj instanceof Type) {
				String ifaceName = resolveTypeName((Type) obj);
				if (ifaceName != null) {
					interfaces.add(ifaceName);
				}
			}
		}
		entry.setInterfaces(interfaces);

		index.addType(entry);
		declaredTypes.add(qualifiedName);

		enclosingTypes.add(type);
		enclosingTypeQualifiedNames.add(qualifiedName);
		return true;
	}

	@Override
	public void endVisit(EnumDeclaration type) {
		enclosingTypes.remove(type);
		if (!enclosingTypeQualifiedNames.isEmpty()) {
			enclosingTypeQualifiedNames.remove(enclosingTypeQualifiedNames.size() - 1);
		}
	}

	@Override
	public boolean visit(AnnotationTypeDeclaration type) {
		String typeName = type.getName().getIdentifier();
		String qualifiedName = buildQualifiedName(typeName);

		TypeDeclarationEntry entry = new TypeDeclarationEntry();
		entry.setQualifiedName(qualifiedName);
		entry.setSimpleName(typeName);
		entry.setPackageName(packageName);
		entry.setKind(TypeKind.ANNOTATION);
		entry.setModifiers(type.getModifiers());
		entry.setLocation(createLocation(type));

		index.addType(entry);
		declaredTypes.add(qualifiedName);

		enclosingTypes.add(type);
		enclosingTypeQualifiedNames.add(qualifiedName);
		return true;
	}

	@Override
	public void endVisit(AnnotationTypeDeclaration type) {
		enclosingTypes.remove(type);
		if (!enclosingTypeQualifiedNames.isEmpty()) {
			enclosingTypeQualifiedNames.remove(enclosingTypeQualifiedNames.size() - 1);
		}
	}

	@Override
	public boolean visit(RecordDeclaration record) {
		String typeName = record.getName().getIdentifier();
		String qualifiedName = buildQualifiedName(typeName);

		TypeDeclarationEntry entry = new TypeDeclarationEntry();
		entry.setQualifiedName(qualifiedName);
		entry.setSimpleName(typeName);
		entry.setPackageName(packageName);
		entry.setKind(TypeKind.RECORD);
		entry.setModifiers(record.getModifiers());
		entry.setLocation(createLocation(record));
		entry.setSuperclass("java.lang.Record");

		List<String> interfaces = new ArrayList<>();
		for (Object obj : record.superInterfaceTypes()) {
			if (obj instanceof Type) {
				String ifaceName = resolveTypeName((Type) obj);
				if (ifaceName != null) {
					interfaces.add(ifaceName);
				}
			}
		}
		entry.setInterfaces(interfaces);

		index.addType(entry);
		declaredTypes.add(qualifiedName);

		enclosingTypes.add(record);
		enclosingTypeQualifiedNames.add(qualifiedName);
		return true;
	}

	@Override
	public void endVisit(RecordDeclaration record) {
		enclosingTypes.remove(record);
		if (!enclosingTypeQualifiedNames.isEmpty()) {
			enclosingTypeQualifiedNames.remove(enclosingTypeQualifiedNames.size() - 1);
		}
	}

	@Override
	public boolean visit(MethodDeclaration method) {
		if (enclosingTypeQualifiedNames.isEmpty()) {
			return true;
		}

		String typeName = enclosingTypeQualifiedNames.get(enclosingTypeQualifiedNames.size() - 1);

		MethodDeclarationEntry entry = new MethodDeclarationEntry();
		entry.setDeclaringType(typeName);
		entry.setMethodName(method.getName().getIdentifier());
		entry.setModifiers(method.getModifiers());
		entry.setConstructor(method.isConstructor());
		entry.setLocation(createLocation(method));

		// Parameter types
		List<String> paramTypes = new ArrayList<>();
		for (Object obj : method.parameters()) {
			if (obj instanceof SingleVariableDeclaration) {
				SingleVariableDeclaration param = (SingleVariableDeclaration) obj;
				String paramType = resolveTypeName(param.getType());
				if (paramType != null) {
					paramTypes.add(paramType);
				}
			}
		}
		entry.setParameterTypes(paramTypes);

		// Return type
		if (!method.isConstructor() && method.getReturnType2() != null) {
			entry.setReturnType(resolveTypeName(method.getReturnType2()));
		}

		index.addMethod(entry);
		return true;
	}

	@Override
	public boolean visit(FieldDeclaration field) {
		if (enclosingTypeQualifiedNames.isEmpty()) {
			return true;
		}

		String typeName = enclosingTypeQualifiedNames.get(enclosingTypeQualifiedNames.size() - 1);
		String fieldType = field.getType() != null ? resolveTypeName(field.getType()) : null;

		for (Object obj : field.fragments()) {
			if (obj instanceof VariableDeclarationFragment) {
				VariableDeclarationFragment fragment = (VariableDeclarationFragment) obj;

				FieldDeclarationEntry entry = new FieldDeclarationEntry();
				entry.setDeclaringType(typeName);
				entry.setFieldName(fragment.getName().getIdentifier());
				entry.setFieldType(fieldType);
				entry.setModifiers(field.getModifiers());
				entry.setLocation(createLocation(fragment));

				index.addField(entry);

				// Also add as a name reference so search can find files declaring this field
				ReferenceEntry nameRef = new ReferenceEntry(entry.getLocation(), ReferenceKind.NAME_REFERENCE);
				index.addNameReference(fragment.getName().getIdentifier(), nameRef);
			}
		}
		return true;
	}

	@Override
	public boolean visit(EnumConstantDeclaration enumConstant) {
		if (enclosingTypeQualifiedNames.isEmpty()) {
			return true;
		}

		String typeName = enclosingTypeQualifiedNames.get(enclosingTypeQualifiedNames.size() - 1);

		FieldDeclarationEntry entry = new FieldDeclarationEntry();
		entry.setDeclaringType(typeName);
		entry.setFieldName(enumConstant.getName().getIdentifier());
		entry.setFieldType(typeName);
		entry.setModifiers(enumConstant.getModifiers());
		entry.setLocation(createLocation(enumConstant));

		index.addField(entry);

		// Add constructor reference for enum constant instantiation
		index.addTypeReference(typeName,
				new ReferenceEntry(createLocation(enumConstant), ReferenceKind.CONSTRUCTOR_INVOCATION));

		return true;
	}

	// ===== References =====

	@Override
	public boolean visit(SimpleType type) {
		String typeName = resolveTypeName(type);
		if (typeName != null) {
			index.addTypeReference(typeName,
					new ReferenceEntry(createLocation(type), ReferenceKind.TYPE_REFERENCE));
		}
		return true;
	}

	@Override
	public boolean visit(QualifiedType type) {
		String typeName = resolveTypeName(type);
		if (typeName != null) {
			index.addTypeReference(typeName,
					new ReferenceEntry(createLocation(type), ReferenceKind.TYPE_REFERENCE));
		}
		return true;
	}

	@Override
	public boolean visit(ImportDeclaration importDecl) {
		if (!importDecl.isOnDemand()) {
			String typeName = importDecl.getName().getFullyQualifiedName();
			index.addTypeReference(typeName,
					new ReferenceEntry(createLocation(importDecl), ReferenceKind.TYPE_REFERENCE));
		}
		return true;
	}

	@Override
	public boolean visit(MethodInvocation methodInvocation) {
		// Note: Without full binding resolution, we can't accurately track method references
		// This would require resolving which type the method belongs to
		return true;
	}

	@Override
	public boolean visit(ClassInstanceCreation creation) {
		String typeName = resolveTypeName(creation.getType());
		if (typeName != null) {
			index.addTypeReference(typeName,
					new ReferenceEntry(createLocation(creation), ReferenceKind.CONSTRUCTOR_INVOCATION));
		}
		return true;
	}

	@Override
	public boolean visit(NormalAnnotation annotation) {
		String typeName = resolveTypeName(annotation.getTypeName());
		if (typeName != null) {
			index.addTypeReference(typeName,
					new ReferenceEntry(createLocation(annotation), ReferenceKind.ANNOTATION_USE));
		}
		return true;
	}

	@Override
	public boolean visit(MarkerAnnotation annotation) {
		String typeName = resolveTypeName(annotation.getTypeName());
		if (typeName != null) {
			index.addTypeReference(typeName,
					new ReferenceEntry(createLocation(annotation), ReferenceKind.ANNOTATION_USE));
		}
		return true;
	}

	@Override
	public boolean visit(SingleMemberAnnotation annotation) {
		String typeName = resolveTypeName(annotation.getTypeName());
		if (typeName != null) {
			index.addTypeReference(typeName,
					new ReferenceEntry(createLocation(annotation), ReferenceKind.ANNOTATION_USE));
		}
		return true;
	}

	@Override
	public boolean visit(shaded.org.eclipse.jdt.core.dom.SuperConstructorInvocation node) {
		AbstractTypeDeclaration currentType = currentType();
		if (currentType == null) {
			return true;
		}

		// Reference to superclass constructor
		if (currentType instanceof TypeDeclaration) {
			TypeDeclaration typeDecl = (TypeDeclaration) currentType;
			if (typeDecl.getSuperclassType() != null) {
				String superName = resolveTypeName(typeDecl.getSuperclassType());
				if (superName != null) {
					index.addTypeReference(superName,
							new ReferenceEntry(createLocation(node), ReferenceKind.CONSTRUCTOR_INVOCATION));
				}
			}
		}
		return true;
	}

	@Override
	public boolean visit(shaded.org.eclipse.jdt.core.dom.SuperMethodInvocation node) {
		// Track super method invocations
		index.addTypeReference("super",
				new ReferenceEntry(createLocation(node), ReferenceKind.METHOD_INVOCATION));
		return true;
	}

	@Override
	public boolean visit(shaded.org.eclipse.jdt.core.dom.ExpressionMethodReference node) {
		// Method reference like obj::method
		index.addTypeReference(node.toString(),
				new ReferenceEntry(createLocation(node), ReferenceKind.METHOD_REFERENCE));
		return true;
	}

	@Override
	public boolean visit(shaded.org.eclipse.jdt.core.dom.TypeMethodReference node) {
		// Method reference like Type::method
		String typeName = resolveTypeName(node.getType());
		if (typeName != null) {
			index.addTypeReference(typeName,
					new ReferenceEntry(createLocation(node), ReferenceKind.METHOD_REFERENCE));
		}
		return true;
	}

	@Override
	public boolean visit(shaded.org.eclipse.jdt.core.dom.SuperMethodReference node) {
		// Method reference like super::method
		index.addTypeReference("super",
				new ReferenceEntry(createLocation(node), ReferenceKind.METHOD_REFERENCE));
		return true;
	}

	@Override
	public boolean visit(shaded.org.eclipse.jdt.core.dom.CreationReference node) {
		// Constructor reference like Type::new
		String typeName = resolveTypeName(node.getType());
		if (typeName != null) {
			index.addTypeReference(typeName,
					new ReferenceEntry(createLocation(node), ReferenceKind.CONSTRUCTOR_INVOCATION));
		}
		return true;
	}

	@Override
	public boolean visit(shaded.org.eclipse.jdt.core.dom.LambdaExpression node) {
		// Index lambda as a pseudo-method declaration
		// Lambdas don't have explicit names, but we can track them for completeness
		if (!enclosingTypeQualifiedNames.isEmpty()) {
			String typeName = enclosingTypeQualifiedNames.get(enclosingTypeQualifiedNames.size() - 1);

			MethodDeclarationEntry lambdaEntry = new MethodDeclarationEntry();
			lambdaEntry.setDeclaringType(typeName);
			lambdaEntry.setMethodName("<lambda>");
			lambdaEntry.setModifiers(0);
			lambdaEntry.setConstructor(false);
			lambdaEntry.setLocation(createLocation(node));

			// Try to extract parameter types from lambda parameters
			List<String> paramTypes = new ArrayList<>();
			for (Object param : node.parameters()) {
				if (param instanceof SingleVariableDeclaration) {
					SingleVariableDeclaration svd = (SingleVariableDeclaration) param;
					String paramType = resolveTypeName(svd.getType());
					if (paramType != null) {
						paramTypes.add(paramType);
					}
				}
			}
			lambdaEntry.setParameterTypes(paramTypes);

			index.addMethod(lambdaEntry);
		}
		return true;
	}

	@Override
	public boolean visit(SimpleName name) {
		// Index all simple name references for find usages, rename refactoring, etc.
		// This is essential for tracking field access, variable usage, etc.
		String identifier = name.getIdentifier();
		index.addNameReference(identifier, new ReferenceEntry(createLocation(name), ReferenceKind.NAME_REFERENCE));
		return true;
	}

	// ===== Helper Methods =====

	private String buildQualifiedName(String simpleName) {
		StringBuilder sb = new StringBuilder();

		// Start with package name
		if (!packageName.isEmpty()) {
			sb.append(packageName).append(".");
		}

		// Add all enclosing types
		for (AbstractTypeDeclaration enclosing : enclosingTypes) {
			sb.append(enclosing.getName().getIdentifier()).append("$");
		}

		// Add this type's simple name
		sb.append(simpleName);

		return sb.toString();
	}

	private String resolveTypeName(Type type) {
		if (type instanceof SimpleType) {
			return resolveTypeName(((SimpleType) type).getName());
		} else if (type instanceof QualifiedType) {
			return resolveTypeName(((QualifiedType) type).getName());
		} else if (type instanceof ParameterizedType) {
			return resolveTypeName(((ParameterizedType) type).getType());
		}
		// For primitive types, array types, etc., use toString
		return type.toString();
	}

	private String resolveTypeName(Name name) {
		if (name instanceof SimpleName) {
			// Without full resolution, we can only use the simple name
			// This is a limitation - ideally we'd resolve to qualified name
			return name.getFullyQualifiedName();
		} else if (name instanceof QualifiedName) {
			return name.getFullyQualifiedName();
		}
		return name.toString();
	}

	private Location createLocation(shaded.org.eclipse.jdt.core.dom.ASTNode node) {
		CompilationUnit cu = (CompilationUnit) node.getRoot();
		int startOffset = node.getStartPosition();
		int endOffset = startOffset + node.getLength();
		int line = cu.getLineNumber(startOffset);
		int column = cu.getColumnNumber(startOffset);

		return new Location(file, startOffset, endOffset, line, column);
	}
}
