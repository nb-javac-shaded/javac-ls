package org.jboss.tools.javac.ls.search.test;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jboss.tools.javac.ls.index.store.JavaIndex;
import org.jboss.tools.javac.ls.search.engine.SearchEngine;
import org.jboss.tools.javac.ls.search.match.SearchMatch;
import org.jboss.tools.javac.ls.search.match.SearchMatch.MatchKind;
import org.jboss.tools.javac.ls.search.pattern.ConstructorPattern;
import org.jboss.tools.javac.ls.search.pattern.FieldPattern;
import org.jboss.tools.javac.ls.search.pattern.MethodPattern;
import org.jboss.tools.javac.ls.search.pattern.TypePattern;
import org.jboss.tools.javac.ls.server.model.WorkspaceModel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for the complete search flow.
 *
 * Tests the end-to-end search pipeline:
 * 1. Create test project with Java files
 * 2. WorkspaceModel indexes the files
 * 3. SearchEngine queries index and parses files
 * 4. Results are collected and verified
 */
public class SearchIntegrationTest {

    private SearchEngine searchEngine;
    private WorkspaceModel workspace;
    private JavaIndex index;
    private List<SearchMatch> results;
    private File tempWorkspaceDir;
    private File tempProjectDir;

    @Before
    public void setUp() throws IOException {
        searchEngine = new SearchEngine();
        results = new ArrayList<>();

        // Create temp workspace directory
        tempWorkspaceDir = Files.createTempDirectory("test-workspace").toFile();
        tempWorkspaceDir.deleteOnExit();

        // Create temp project directory
        tempProjectDir = new File(tempWorkspaceDir, "test-project");
        tempProjectDir.mkdirs();
        tempProjectDir.deleteOnExit();

        // Create test Java files
        createTestFiles();

        // Create workspace model and index the project
        workspace = new WorkspaceModel(tempWorkspaceDir);
        workspace.addProject("test-project", tempProjectDir.getAbsolutePath());
        workspace.startIndexing(true); // Synchronous indexing

        // Get the index
        index = workspace.getIndexCache().getIndex();
    }

    @After
    public void tearDown() {
        // Shutdown workspace (closes executors, saves state)
        if (workspace != null) {
            workspace.shutdown();
        }

        // Clean up temp files
        if (tempWorkspaceDir != null) {
            deleteRecursively(tempWorkspaceDir);
        }
    }

    private void deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    /**
     * Creates test Java files in the temp project directory.
     */
    private void createTestFiles() throws IOException {
        // Create source directory
        File srcDir = new File(tempProjectDir, "src/com/example");
        srcDir.mkdirs();

        // File 1: Person.java
        File personFile = new File(srcDir, "Person.java");
        Files.writeString(personFile.toPath(), """
            package com.example;

            public class Person {
                private String name;
                private int age;

                public Person(String name, int age) {
                    this.name = name;
                    this.age = age;
                }

                public String getName() {
                    return name;
                }

                public void setName(String name) {
                    this.name = name;
                }
            }
            """);

        // File 2: Company.java
        File companyFile = new File(srcDir, "Company.java");
        Files.writeString(companyFile.toPath(), """
            package com.example;

            import java.util.ArrayList;
            import java.util.List;

            public class Company {
                private List<Person> employees;
                private String companyName;

                public Company(String companyName) {
                    this.companyName = companyName;
                    this.employees = new ArrayList<>();
                }

                public void addEmployee(Person person) {
                    employees.add(person);
                }

                public Person findByName(String name) {
                    for (Person p : employees) {
                        if (p.getName().equals(name)) {
                            return p;
                        }
                    }
                    return null;
                }
            }
            """);

        // File 3: Main.java
        File mainFile = new File(srcDir, "Main.java");
        Files.writeString(mainFile.toPath(), """
            package com.example;

            public class Main {
                public static void main(String[] args) {
                    Company company = new Company("Tech Corp");
                    Person alice = new Person("Alice", 30);
                    Person bob = new Person("Bob", 25);

                    company.addEmployee(alice);
                    company.addEmployee(bob);

                    Person found = company.findByName("Alice");
                    if (found != null) {
                        System.out.println(found.getName());
                    }
                }
            }
            """);

        // File 4: Product.java (in different package)
        File otherDir = new File(tempProjectDir, "src/com/other");
        otherDir.mkdirs();
        File productFile = new File(otherDir, "Product.java");
        Files.writeString(productFile.toPath(), """
            package com.other;

            public class Product {
                private String productName;
                private double price;

                public Product(String productName, double price) {
                    this.productName = productName;
                    this.price = price;
                }
            }
            """);
    }

    /**
     * Helper to read file contents for SearchEngine.
     */
    private String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            return null;
        }
    }

    // ========== Type Search Tests ==========

    @Test
    public void testFindTypeDeclaration() {
        TypePattern pattern = new TypePattern("Person", null, TypePattern.SearchFor.DECLARATIONS);

        searchEngine.search(pattern, index, this::readFile, results::add);

        assertEquals(1, results.size());
        SearchMatch match = results.get(0);
        assertTrue(match.getFile().toString().endsWith("Person.java"));
        assertEquals(MatchKind.TYPE_DECLARATION, match.getKind());
        assertTrue(match.getElementName().contains("Person"));
    }

    @Test
    public void testFindTypeReferences() {
        TypePattern pattern = new TypePattern("Person", null, TypePattern.SearchFor.REFERENCES);

        searchEngine.search(pattern, index, this::readFile, results::add);

        // Should find Person references in Company.java and Main.java
        assertTrue(results.size() >= 2);

        // Verify we found references in both files
        boolean foundInCompany = results.stream()
            .anyMatch(m -> m.getFile().toString().endsWith("Company.java"));
        boolean foundInMain = results.stream()
            .anyMatch(m -> m.getFile().toString().endsWith("Main.java"));

        assertTrue("Should find Person reference in Company.java", foundInCompany);
        assertTrue("Should find Person reference in Main.java", foundInMain);

        // All matches should be type references
        for (SearchMatch match : results) {
            assertEquals(MatchKind.TYPE_REFERENCE, match.getKind());
        }
    }

    @Test
    public void testFindTypeAllOccurrences() {
        TypePattern pattern = new TypePattern("Person", null, TypePattern.SearchFor.ALL_OCCURRENCES);

        searchEngine.search(pattern, index, this::readFile, results::add);

        // Should find declaration + all references
        assertTrue(results.size() >= 3);

        // Should have at least one declaration
        long declarations = results.stream()
            .filter(m -> m.getKind() == MatchKind.TYPE_DECLARATION)
            .count();
        assertTrue(declarations >= 1);

        // Should have multiple references
        long references = results.stream()
            .filter(m -> m.getKind() == MatchKind.TYPE_REFERENCE)
            .count();
        assertTrue(references >= 2);
    }

    // ========== Method Search Tests ==========

    @Test
    public void testFindMethodDeclaration() {
        MethodPattern pattern = new MethodPattern("getName", null, null, MethodPattern.SearchFor.DECLARATIONS);

        searchEngine.search(pattern, index, this::readFile, results::add);

        assertEquals(1, results.size());
        SearchMatch match = results.get(0);
        assertTrue(match.getFile().toString().endsWith("Person.java"));
        assertEquals(MatchKind.METHOD_DECLARATION, match.getKind());
    }

    @Test
    public void testFindMethodReferences() {
        MethodPattern pattern = new MethodPattern("getName", null, null, MethodPattern.SearchFor.REFERENCES);

        searchEngine.search(pattern, index, this::readFile, results::add);

        // Should find getName() calls in Company.java and Main.java
        assertTrue(results.size() >= 2);

        for (SearchMatch match : results) {
            assertEquals(MatchKind.METHOD_REFERENCE, match.getKind());
        }
    }

    @Test
    public void testFindMethodAllOccurrences() {
        MethodPattern pattern = new MethodPattern("addEmployee", null, null, MethodPattern.SearchFor.ALL_OCCURRENCES);

        searchEngine.search(pattern, index, this::readFile, results::add);

        // Should find declaration in Company.java and calls in Main.java
        assertTrue(results.size() >= 2);

        long declarations = results.stream()
            .filter(m -> m.getKind() == MatchKind.METHOD_DECLARATION)
            .count();
        long references = results.stream()
            .filter(m -> m.getKind() == MatchKind.METHOD_REFERENCE)
            .count();

        assertTrue(declarations >= 1);
        assertTrue(references >= 1);
    }

    // ========== Field Search Tests ==========

    @Test
    public void testFindFieldDeclaration() {
        FieldPattern pattern = new FieldPattern("name", null, null, FieldPattern.SearchFor.DECLARATIONS);

        searchEngine.search(pattern, index, this::readFile, results::add);

        // Should find 'name' field declaration in Person.java
        assertEquals(1, results.size());
        SearchMatch match = results.get(0);
        assertTrue(match.getFile().toString().endsWith("Person.java"));
        assertEquals(MatchKind.FIELD_DECLARATION, match.getKind());
    }

    @Test
    public void testFindFieldReferences() {
        FieldPattern pattern = new FieldPattern("name", null, null, FieldPattern.SearchFor.REFERENCES);

        searchEngine.search(pattern, index, this::readFile, results::add);

        // Should find references to 'name' field in Person.java
        assertTrue(results.size() >= 1);

        for (SearchMatch match : results) {
            assertEquals(MatchKind.FIELD_REFERENCE, match.getKind());
        }
    }

    @Test
    public void testFindFieldAllOccurrences() {
        FieldPattern pattern = new FieldPattern("employees", null, null, FieldPattern.SearchFor.ALL_OCCURRENCES);

        searchEngine.search(pattern, index, this::readFile, results::add);

        // Should find declaration and references
        assertTrue(results.size() >= 2);

        long declarations = results.stream()
            .filter(m -> m.getKind() == MatchKind.FIELD_DECLARATION)
            .count();
        long references = results.stream()
            .filter(m -> m.getKind() == MatchKind.FIELD_REFERENCE)
            .count();

        assertEquals(1, declarations);
        assertTrue(references >= 1);
    }

    // ========== Constructor Search Tests ==========

    @Test
    public void testFindConstructorDeclaration() {
        ConstructorPattern pattern = new ConstructorPattern("Person", null, ConstructorPattern.SearchFor.DECLARATIONS);

        searchEngine.search(pattern, index, this::readFile, results::add);

        assertEquals(1, results.size());
        SearchMatch match = results.get(0);
        assertTrue(match.getFile().toString().endsWith("Person.java"));
        assertEquals(MatchKind.CONSTRUCTOR_DECLARATION, match.getKind());
    }

    @Test
    public void testFindConstructorReferences() {
        ConstructorPattern pattern = new ConstructorPattern("Person", null, ConstructorPattern.SearchFor.REFERENCES);

        searchEngine.search(pattern, index, this::readFile, results::add);

        // Should find 'new Person(...)' calls in Main.java
        assertTrue(results.size() >= 2);

        for (SearchMatch match : results) {
            assertEquals(MatchKind.CONSTRUCTOR_REFERENCE, match.getKind());
        }
    }

    @Test
    public void testFindConstructorAllOccurrences() {
        ConstructorPattern pattern = new ConstructorPattern("Company", null, ConstructorPattern.SearchFor.ALL_OCCURRENCES);

        searchEngine.search(pattern, index, this::readFile, results::add);

        // Should find declaration in Company.java and call in Main.java
        assertTrue(results.size() >= 2);

        long declarations = results.stream()
            .filter(m -> m.getKind() == MatchKind.CONSTRUCTOR_DECLARATION)
            .count();
        long references = results.stream()
            .filter(m -> m.getKind() == MatchKind.CONSTRUCTOR_REFERENCE)
            .count();

        assertEquals(1, declarations);
        assertEquals(1, references);
    }

    // ========== Multi-file and Edge Case Tests ==========

    @Test
    public void testSearchAcrossMultipleFiles() {
        TypePattern pattern = new TypePattern("Person", null, TypePattern.SearchFor.ALL_OCCURRENCES);

        searchEngine.search(pattern, index, this::readFile, results::add);

        // Should find matches in Person.java, Company.java, and Main.java
        long filesWithMatches = results.stream()
            .map(SearchMatch::getFile)
            .distinct()
            .count();

        assertTrue(filesWithMatches >= 3);
    }

    @Test
    public void testNoMatchesForNonexistentType() {
        TypePattern pattern = new TypePattern("NonexistentClass", null, TypePattern.SearchFor.ALL_OCCURRENCES);

        searchEngine.search(pattern, index, this::readFile, results::add);

        assertEquals(0, results.size());
    }

    @Test
    public void testSearchWithEmptyPattern() {
        TypePattern pattern = new TypePattern("", null, TypePattern.SearchFor.ALL_OCCURRENCES);

        searchEngine.search(pattern, index, this::readFile, results::add);

        // Empty pattern should return no results
        assertEquals(0, results.size());
    }

    @Test
    public void testMatchPositionInformation() {
        TypePattern pattern = new TypePattern("Person", null, TypePattern.SearchFor.DECLARATIONS);

        searchEngine.search(pattern, index, this::readFile, results::add);

        assertEquals(1, results.size());
        SearchMatch match = results.get(0);

        // Verify position information is present
        assertTrue(match.getOffset() >= 0);
        assertTrue(match.getLength() > 0);
        assertNotNull(match.getElementName());
        assertTrue(match.getElementName().length() > 0);
    }
}
