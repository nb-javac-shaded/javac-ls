package org.jboss.tools.javac.ls.search.pattern;

import shaded.org.eclipse.jdt.core.dom.ASTNode;
import shaded.org.eclipse.jdt.core.dom.ITypeBinding;
import shaded.org.eclipse.jdt.core.dom.SimpleName;
import shaded.org.eclipse.jdt.core.dom.SimpleType;
import shaded.org.eclipse.jdt.core.dom.TypeDeclaration;

/**
 * Search pattern for finding type references or declarations.
 */
public class TypePattern extends SearchPattern {

    public enum SearchFor {
        REFERENCES,
        DECLARATIONS,
        ALL_OCCURRENCES
    }

    private final SearchFor searchFor;

    public TypePattern(String typeName, MatchRule matchRule, SearchFor searchFor) {
        super(typeName, matchRule);
        this.searchFor = searchFor != null ? searchFor : SearchFor.ALL_OCCURRENCES;
    }

    public TypePattern(String typeName) {
        this(typeName, MatchRule.EXACT_MATCH, SearchFor.ALL_OCCURRENCES);
    }

    @Override
    public boolean matches(ASTNode node) {
        if (node == null) {
            return false;
        }

        switch (searchFor) {
            case REFERENCES:
                return matchesReference(node);
            case DECLARATIONS:
                return matchesDeclaration(node);
            case ALL_OCCURRENCES:
                return matchesReference(node) || matchesDeclaration(node);
            default:
                return false;
        }
    }

    private boolean matchesReference(ASTNode node) {
        if (node instanceof SimpleType) {
            SimpleType simpleType = (SimpleType) node;
            String typeName = simpleType.getName().getFullyQualifiedName();

            // Try to get qualified name from binding for better matching
            ITypeBinding binding = simpleType.resolveBinding();
            if (binding != null && binding.getQualifiedName() != null) {
                // Match against both simple and qualified names
                if (matchesName(binding.getQualifiedName())) {
                    return true;
                }
            }

            return matchesName(typeName);
        }

        if (node instanceof SimpleName) {
            SimpleName name = (SimpleName) node;
            ITypeBinding binding = getTypeBinding(name);
            if (binding != null) {
                // Use qualified name for precise matching when available
                String qualifiedName = binding.getQualifiedName();
                if (qualifiedName != null && matchesName(qualifiedName)) {
                    return true;
                }
                // Fall back to simple name
                return matchesName(binding.getName());
            }
            return matchesName(name.getIdentifier());
        }

        return false;
    }

    private boolean matchesDeclaration(ASTNode node) {
        if (node instanceof TypeDeclaration) {
            TypeDeclaration typeDecl = (TypeDeclaration) node;

            // Try to get qualified name from binding for better matching
            ITypeBinding binding = typeDecl.resolveBinding();
            if (binding != null && binding.getQualifiedName() != null) {
                // Match against both simple and qualified names
                if (matchesName(binding.getQualifiedName())) {
                    return true;
                }
            }

            // Fall back to simple name
            return matchesName(typeDecl.getName().getIdentifier());
        }

        return false;
    }

    private ITypeBinding getTypeBinding(SimpleName name) {
        if (name.resolveBinding() instanceof ITypeBinding) {
            return (ITypeBinding) name.resolveBinding();
        }
        return null;
    }

    public SearchFor getSearchFor() {
        return searchFor;
    }

    @Override
    public String toString() {
        return String.format("TypePattern[searchString=%s, matchRule=%s, searchFor=%s]",
                searchString, matchRule, searchFor);
    }
}
