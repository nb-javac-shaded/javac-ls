package org.jboss.tools.javac.ls.search.visitor;

import org.jboss.tools.javac.ls.search.match.MatchingNodeSet;
import org.jboss.tools.javac.ls.search.match.SearchMatch.MatchKind;
import org.jboss.tools.javac.ls.search.pattern.ConstructorPattern;
import org.jboss.tools.javac.ls.search.pattern.FieldPattern;
import org.jboss.tools.javac.ls.search.pattern.MethodPattern;
import org.jboss.tools.javac.ls.search.pattern.SearchPattern;
import org.jboss.tools.javac.ls.search.pattern.TypePattern;

import shaded.org.eclipse.jdt.core.dom.ASTVisitor;
import shaded.org.eclipse.jdt.core.dom.ClassInstanceCreation;
import shaded.org.eclipse.jdt.core.dom.ConstructorInvocation;
import shaded.org.eclipse.jdt.core.dom.FieldAccess;
import shaded.org.eclipse.jdt.core.dom.FieldDeclaration;
import shaded.org.eclipse.jdt.core.dom.MethodDeclaration;
import shaded.org.eclipse.jdt.core.dom.MethodInvocation;
import shaded.org.eclipse.jdt.core.dom.SimpleName;
import shaded.org.eclipse.jdt.core.dom.SimpleType;
import shaded.org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import shaded.org.eclipse.jdt.core.dom.TypeDeclaration;
import shaded.org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/**
 * AST visitor that traverses a compilation unit and finds nodes matching a search pattern.
 *
 * Adapted from Eclipse JDT's DOMJavaSearchDelegate to work with our search pattern system.
 * This visitor adds matching nodes to a MatchingNodeSet for later conversion to SearchMatch objects.
 */
public class DOMSearchVisitor extends ASTVisitor {

    private final SearchPattern pattern;
    private final MatchingNodeSet matchingNodes;

    public DOMSearchVisitor(SearchPattern pattern, MatchingNodeSet matchingNodes) {
        this.pattern = pattern;
        this.matchingNodes = matchingNodes;
    }

    @Override
    public boolean visit(TypeDeclaration node) {
        if (pattern instanceof TypePattern) {
            TypePattern typePattern = (TypePattern) pattern;
            if (typePattern.matches(node)) {
                MatchKind kind = MatchKind.TYPE_DECLARATION;
                matchingNodes.addMatch(node.getName(), kind);
            }
        }
        return true;
    }

    @Override
    public boolean visit(SimpleType node) {
        if (pattern instanceof TypePattern) {
            TypePattern typePattern = (TypePattern) pattern;
            if (typePattern.matches(node)) {
                MatchKind kind = MatchKind.TYPE_REFERENCE;
                matchingNodes.addMatch(node, kind);
            }
        }
        return true;
    }

    @Override
    public boolean visit(MethodDeclaration node) {
        if (pattern instanceof MethodPattern) {
            MethodPattern methodPattern = (MethodPattern) pattern;
            if (methodPattern.matches(node)) {
                MatchKind kind = MatchKind.METHOD_DECLARATION;
                matchingNodes.addMatch(node.getName(), kind);
            }
        } else if (pattern instanceof ConstructorPattern) {
            ConstructorPattern constructorPattern = (ConstructorPattern) pattern;
            if (constructorPattern.matches(node)) {
                MatchKind kind = MatchKind.CONSTRUCTOR_DECLARATION;
                matchingNodes.addMatch(node.getName(), kind);
            }
        }
        return true;
    }

    @Override
    public boolean visit(MethodInvocation node) {
        if (pattern instanceof MethodPattern) {
            MethodPattern methodPattern = (MethodPattern) pattern;
            if (methodPattern.matches(node)) {
                MatchKind kind = MatchKind.METHOD_REFERENCE;
                matchingNodes.addMatch(node.getName(), kind);
            }
        }
        return true;
    }

    @Override
    public boolean visit(ClassInstanceCreation node) {
        if (pattern instanceof ConstructorPattern) {
            ConstructorPattern constructorPattern = (ConstructorPattern) pattern;
            if (constructorPattern.matches(node)) {
                MatchKind kind = MatchKind.CONSTRUCTOR_REFERENCE;
                matchingNodes.addMatch(node.getType(), kind);
            }
        }
        return true;
    }

    @Override
    public boolean visit(ConstructorInvocation node) {
        if (pattern instanceof ConstructorPattern) {
            ConstructorPattern constructorPattern = (ConstructorPattern) pattern;
            if (constructorPattern.matches(node)) {
                MatchKind kind = MatchKind.CONSTRUCTOR_REFERENCE;
                matchingNodes.addMatch(node, kind);
            }
        }
        return true;
    }

    @Override
    public boolean visit(SuperConstructorInvocation node) {
        if (pattern instanceof ConstructorPattern) {
            ConstructorPattern constructorPattern = (ConstructorPattern) pattern;
            if (constructorPattern.matches(node)) {
                MatchKind kind = MatchKind.CONSTRUCTOR_REFERENCE;
                matchingNodes.addMatch(node, kind);
            }
        }
        return true;
    }

    @Override
    public boolean visit(FieldDeclaration node) {
        if (pattern instanceof FieldPattern) {
            FieldPattern fieldPattern = (FieldPattern) pattern;
            for (Object fragment : node.fragments()) {
                if (fragment instanceof VariableDeclarationFragment) {
                    VariableDeclarationFragment vdf = (VariableDeclarationFragment) fragment;
                    if (fieldPattern.matches(vdf)) {
                        MatchKind kind = MatchKind.FIELD_DECLARATION;
                        matchingNodes.addMatch(vdf.getName(), kind);
                    }
                }
            }
        }
        return true;
    }

    @Override
    public boolean visit(FieldAccess node) {
        if (pattern instanceof FieldPattern) {
            FieldPattern fieldPattern = (FieldPattern) pattern;
            if (fieldPattern.matches(node)) {
                MatchKind kind = MatchKind.FIELD_REFERENCE;
                matchingNodes.addMatch(node.getName(), kind);
            }
        }
        return true;
    }

    @Override
    public boolean visit(SimpleName node) {
        // SimpleName can be a field reference (if not already caught by FieldAccess)
        if (pattern instanceof FieldPattern) {
            FieldPattern fieldPattern = (FieldPattern) pattern;

            // Skip if this SimpleName is the name of a field declaration
            // (it was already handled by visit(FieldDeclaration))
            if (node.getParent() instanceof VariableDeclarationFragment) {
                VariableDeclarationFragment fragment = (VariableDeclarationFragment) node.getParent();
                if (fragment.getParent() instanceof FieldDeclaration) {
                    // This is a field declaration name, already handled
                    return true;
                }
            }

            if (fieldPattern.matches(node)) {
                MatchKind kind = MatchKind.FIELD_REFERENCE;
                matchingNodes.addMatch(node, kind);
            }
        }
        return true;
    }

    /**
     * Returns the set of matching nodes found during traversal.
     *
     * @return the matching node set
     */
    public MatchingNodeSet getMatchingNodes() {
        return matchingNodes;
    }
}
