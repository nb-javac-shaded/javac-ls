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
import java.util.List;
import java.util.Objects;

import org.jboss.tools.javac.ls.parser.bindings.resolve.JavacBindingResolver;
import org.jboss.tools.javac.ls.parser.bindings.resolve.JavacBindingResolver.BindingKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import shaded.com.sun.tools.javac.code.Flags;
import shaded.com.sun.tools.javac.code.Kinds;
import shaded.com.sun.tools.javac.code.Symbol;
import shaded.com.sun.tools.javac.code.Symbol.ClassSymbol;
import shaded.com.sun.tools.javac.code.Symbol.MethodSymbol;
import shaded.com.sun.tools.javac.code.Symbol.TypeSymbol;
import shaded.com.sun.tools.javac.code.Symbol.VarSymbol;
import shaded.com.sun.tools.javac.code.Type;
import shaded.com.sun.tools.javac.code.Type.TypeVar;

import shaded.javax.lang.model.element.ElementKind;
import shaded.org.eclipse.jdt.core.dom.AST;
import shaded.org.eclipse.jdt.core.dom.ASTNode;
import shaded.org.eclipse.jdt.core.dom.ASTVisitor;
import shaded.org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import shaded.org.eclipse.jdt.core.dom.Block;
import shaded.org.eclipse.jdt.core.dom.FieldDeclaration;
import shaded.org.eclipse.jdt.core.dom.IAnnotationBinding;
import shaded.org.eclipse.jdt.core.dom.IBinding;
import shaded.org.eclipse.jdt.core.dom.IMethodBinding;
import shaded.org.eclipse.jdt.core.dom.ITypeBinding;
import shaded.org.eclipse.jdt.core.dom.IVariableBinding;
import shaded.org.eclipse.jdt.core.dom.Initializer;
import shaded.org.eclipse.jdt.core.dom.LambdaExpression;
import shaded.org.eclipse.jdt.core.dom.MethodDeclaration;
import shaded.org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import shaded.org.eclipse.jdt.core.dom.VariableDeclaration;
import shaded.org.eclipse.jdt.core.dom.VariableDeclarationExpression;
import shaded.org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import shaded.org.eclipse.jdt.core.dom.VariableDeclarationStatement;

public abstract class JavacVariableBinding implements IVariableBinding {

	private static final Logger LOGGER = LoggerFactory.getLogger(JavacVariableBinding.class);

	public final VarSymbol variableSymbol;
	private final JavacBindingResolver resolver;
	private String key;

	public JavacVariableBinding(VarSymbol sym, JavacBindingResolver resolver) {
		this.variableSymbol = sym;
		this.resolver = resolver;
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof JavacVariableBinding other
				&& Objects.equals(this.resolver, other.resolver)
				&& Objects.equals(this.variableSymbol, other.variableSymbol);
	}
	@Override
	public int hashCode() {
		return Objects.hash(this.resolver, this.variableSymbol);
	}

	@Override
	public IAnnotationBinding[] getAnnotations() {
		if (isRecordComponent()) {
			return Arrays.stream(getDeclaringClass().getDeclaredMethods())
				.filter(method -> getName().equals(method.getName()))
				.findAny()
				.map(IMethodBinding::getAnnotations)
				.orElseGet(() -> new IAnnotationBinding[0]);
		}
		if(variableSymbol == null ) {
			return new IAnnotationBinding[0];
		}
		var anns = this.variableSymbol.getAnnotationMirrors().stream();
		if (!this.resolver.isRecoveringBindings()) {
			anns = anns.filter(ann -> !ann.type.isErroneous());
		}
		return anns.map(ann -> this.resolver.bindings.getAnnotationBinding(ann, this)).toArray(IAnnotationBinding[]::new);
	}

	@Override
	public int getKind() {
		return VARIABLE;
	}

	@Override
	public int getModifiers() {
		var decl = this.resolver.findDeclaringNode(this);
		if (decl instanceof SingleVariableDeclaration singleDecl) {
			return singleDecl.getModifiers();
		}
		return JavacMethodBinding.toInt(this.variableSymbol.getModifiers());
	}

	@Override
	public boolean isDeprecated() {
		return this.variableSymbol.isDeprecated();
	}

	@Override
	public boolean isRecovered() {
		return this.variableSymbol.kind == Kinds.Kind.ERR || this.variableSymbol.type == null;
	}

	@Override
	public boolean isSynthetic() {
		return (this.variableSymbol.flags() & Flags.SYNTHETIC) != 0;
	}

	@Override
	public String getKey() {
		if (this.key == null) {
			this.key = computeKey();
		}
		return this.key;
	}

	private String computeKey() {
		try {
			return getKeyImpl();
		} catch(BindingKeyException bke) {
			return null;
		}
	}
	static class BlockBasedIndexVisitor extends ASTVisitor {
		public int numBlocks = 0;
		public boolean foundBlock = false;

		private ASTNode cursor;

		public BlockBasedIndexVisitor(ASTNode cursor) {
			this.cursor = cursor;
		}

		@Override
		public void preVisit(ASTNode node) {
			if (node == this.cursor) {
				foundBlock = true;
			}
			super.preVisit(node);
		}

		@Override
		public boolean visit(Block block) {
			if (!foundBlock) {
				numBlocks++;
			}
			return false;
		}
	}
	private String getKeyImpl() throws BindingKeyException {
		StringBuilder builder = new StringBuilder();
		if (this.variableSymbol.owner instanceof TypeSymbol classSymbol) {
			if( classSymbol.type.asElement() == null ) {
				return null;
			}
			JavacTypeBinding.getKey(builder, classSymbol.type, false, false, true, this.resolver);
			builder.append('.');
			builder.append(this.variableSymbol.name);
			builder.append(')');
			if (this.variableSymbol.type != null) {
				JavacTypeBinding.getKey(builder, this.variableSymbol.type, false, false, true, this.resolver);
			} else {
				builder.append('V');
			}
			return builder.toString();
		} else if (this.variableSymbol.owner instanceof MethodSymbol methodSymbol) {
			Type.MethodType toUse = methodSymbol.type instanceof Type.MethodType methodType ? methodType : null;

			ASTNode variable = this.resolver.findDeclaringNode(this);

			// block based indices
			ASTNode prevVarOrBlock = variable;
			ASTNode cursor1 = variable;
			ASTNode cursor2 = variable.getParent();
			List<Integer> indices = new ArrayList<>();
			while (cursor2 != null && !(cursor2 instanceof MethodDeclaration) && !(cursor2 instanceof Initializer)) {
				if (cursor2 instanceof Block block) {
					int index = block.statements().indexOf(cursor1);
					BlockBasedIndexVisitor myASTVisitor = new BlockBasedIndexVisitor(prevVarOrBlock);
					for (int i = 0; i < index + 1; i++) {
						ASTNode child = (ASTNode)block.statements().get(i);
						child.accept(myASTVisitor);
					}
					indices.add(myASTVisitor.numBlocks);
					prevVarOrBlock = block;
				}
				cursor1 = cursor2;
				cursor2 = cursor1.getParent();
			}
			if (!indices.isEmpty()) {
				indices.remove(0);
			}

			if (cursor2 instanceof Initializer initializer) {
				// static initializer except the static keyword is missing
				JavacTypeBinding.getKey(builder, resolver.getTypes().erasure(methodSymbol.owner.type), false, false, true, resolver);
				AbstractTypeDeclaration atd = (AbstractTypeDeclaration)initializer.getParent();
				// FIXME: should it be the index only including initializers?
				int index = atd.bodyDeclarations().indexOf(initializer);
				builder.append('#');
				builder.append(index);
			} else {
				JavacMethodBinding.getKey(builder, methodSymbol, toUse, null, true, this.resolver);
			}

			for (int i = indices.size() - 1; i >= 0; i--) {
				builder.append('#');
				builder.append(indices.get(0));
			}

			builder.append("#");
			builder.append(this.variableSymbol.name);

			if (!isUnique()) {
				builder.append('#');
				builder.append(this.variableSymbol.pos);
			}

			// FIXME: is it possible for the javac AST to contain multiple definitions of the same variable?
			// If so, we will need to distinguish them (@see org.eclipse.jdt.internal.compiler.lookup.LocalVariableBinding)
			return builder.toString();
		}
		throw new UnsupportedOperationException("unhandled `Symbol` subclass " + this.variableSymbol.owner.getClass().toString());
	}

	private static Block findParentBlock(ASTNode node) {
		ASTNode current = node;
		while (current != null) {
			if (current instanceof Block) {
				return (Block) current;
			}
			current = current.getParent();
		}
		return null;
	}

	private boolean isUnique() {
		ASTNode variable = this.resolver.findDeclaringNode(this);
		Block parentBlock = findParentBlock(variable);
		if (parentBlock == null) {
			return true;
		}
		final String variableName = getName().toString();
		class UniquenessVisitor extends ASTVisitor {
			boolean isUnique = true;
			@Override
			public boolean visit(VariableDeclarationFragment node) {
				if (node != variable && variableName.equals(node.getName().toString())) {
					isUnique = false;
				}
				return super.visit(node);
			}
			@Override
			public boolean visit(SingleVariableDeclaration node) {
				if (node != variable && variableName.equals(node.getName().toString())) {
					isUnique = false;
				}
				return super.visit(node);
			}
			@Override
			public boolean visit(Block block) {
				return block == parentBlock;
			}
		}
		UniquenessVisitor uniquenessVisitor = new UniquenessVisitor();
		parentBlock.accept(uniquenessVisitor);
		return uniquenessVisitor.isUnique;
	}

	@Override
	public boolean isEqualTo(IBinding binding) {
		return binding instanceof JavacVariableBinding other && //
			Objects.equals(this.getKey(), other.getKey());
	}

	@Override
	public boolean isField() {
		return this.variableSymbol.owner instanceof ClassSymbol;
	}

	@Override
	public boolean isEnumConstant() {
		return this.variableSymbol.isEnum();
	}

	@Override
	public boolean isParameter() {
		return this.variableSymbol.owner instanceof MethodSymbol ownerMethod
				&& (this.variableSymbol.flags() & Flags.PARAMETER) != 0
				&& this.variableSymbol.getKind() != ElementKind.EXCEPTION_PARAMETER;
	}

	@Override
	public String getName() {
		return this.variableSymbol.getSimpleName().toString();
	}

	@Override
	public JavacTypeBinding getDeclaringClass() {
		Symbol parentSymbol = this.variableSymbol.owner;
		do {
			if (parentSymbol instanceof MethodSymbol) {
				return null;
			} else if (parentSymbol instanceof ClassSymbol clazz) {
				if( clazz.name.toString().equals("Array") && clazz.owner != null && clazz.owner.kind == Kinds.Kind.NIL) {
					return null;
				}
				if (clazz.type != null) {
					boolean isParamed = clazz.type.isParameterized();
					boolean isGeneric = isParamed;
					if( isGeneric ) {
						List<Type> types = clazz.type.allparams();
						for(int i = 0, size = types.size(); i < size && isGeneric; i++ ) {
							if( !(types.get(i) instanceof TypeVar) ) {
								isGeneric = false;
							}
						}
					}
					return this.resolver.bindings.getTypeBinding(clazz.type, null, null, isGeneric);
				}
			}
			parentSymbol = parentSymbol.owner;
		} while (parentSymbol != null);
		return null;
	}

	@Override
	public ITypeBinding getType() {
		var res = this.resolver.bindings.getTypeBinding(this.variableSymbol.type);
		if (res != null) {
			return res;
		}
		// workaround: Javac doesn't typeSymbol for the variable
		// that does match the recovered one on the type definition
		// In case the typeBinding is wrong, just lookup the declaration
		// in AST to resolve the type definition directly
		ASTNode node = this.resolver.findDeclaringNode(this);
		if (node == null) {
			return null;
		}
		shaded.org.eclipse.jdt.core.dom.Type declType = null;
		if (node instanceof SingleVariableDeclaration decl) {
			declType = decl.getType();
		} else if (node instanceof VariableDeclarationFragment fragment) {
			if (fragment.getParent() instanceof VariableDeclarationExpression expr) {
				declType = expr.getType();
			} else if (fragment.getParent() instanceof VariableDeclarationStatement expr) {
				declType = expr.getType();
			} else if (fragment.getParent() instanceof FieldDeclaration fieldDecl) {
				declType = fieldDecl.getType();
			}
		}
		return declType != null && (node.getAST().apiLevel() < AST.JLS10 || !declType.isVar()) ? declType.resolveBinding() : null;
	}

	@Override
	public int getVariableId() {
		if( this.resolver.symbolToDeclaration != null ) {
			if (this.resolver.symbolToDeclaration.get(this.variableSymbol) instanceof VariableDeclaration decl) {
				return decl.getStartPosition();
			}
		}
		// FIXME: since we are not running code generation,
		// the variable has not been assigned an offset,
		// so it's always -1.
		return variableSymbol.adr;
	}

	@Override
	public Object getConstantValue() {
		return variableSymbol.getConstantValue();
	}

	@Override
	public IMethodBinding getDeclaringMethod() {
		Symbol parentSymbol = this.variableSymbol.owner;
		if (parentSymbol instanceof ClassSymbol) {
			// a field
			return null;
		}
		do {
			if (parentSymbol instanceof MethodSymbol method) {
				if (method.type == null || method.type.asMethodType() == null) {
					return null;
				}
				if (method.getSimpleName().isEmpty()) { // initializer
					return null;
				}
				JavacMethodBinding res = this.resolver.bindings.getMethodBinding(method.type.asMethodType(), method, null, false, null);
				ASTNode declaring = this.resolver.findDeclaringNode(this);
				ASTNode parent = declaring == null ? null : declaring.getParent();
				if (parent instanceof LambdaExpression lambda) {
					return lambda.resolveMethodBinding();
				}
				return res;
			}
			parentSymbol = parentSymbol.owner;
		} while (parentSymbol != null);
		return null;
	}

	@Override
	public IVariableBinding getVariableDeclaration() {
		return this;
	}

	@Override
	public boolean isEffectivelyFinal() {
		return (this.variableSymbol.flags() & Flags.EFFECTIVELY_FINAL) != 0;
	}
	@Override
	public String toString() {
		return getType().getQualifiedName() + " " + getName();
	}

	@Override
	public boolean isRecordComponent() {
		return
			(this.variableSymbol.owner instanceof ClassSymbol ownerType	&& ownerType.isRecord())
			||
			(this.variableSymbol.owner instanceof MethodSymbol method && method.params().contains(this.variableSymbol) && method.isConstructor() && (method.flags() & Flags.RECORD) != 0);
	}
}
