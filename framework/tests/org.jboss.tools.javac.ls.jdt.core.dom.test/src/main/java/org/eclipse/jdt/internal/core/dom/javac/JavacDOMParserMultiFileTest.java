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
package org.eclipse.jdt.internal.core.dom.javac;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jboss.tools.javac.ls.parser.bindings.JavacDOMParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import shaded.org.eclipse.jdt.core.dom.AST;
import shaded.org.eclipse.jdt.core.dom.CompilationUnit;
import shaded.org.eclipse.jdt.core.dom.FieldDeclaration;
import shaded.org.eclipse.jdt.core.dom.IMethodBinding;
import shaded.org.eclipse.jdt.core.dom.ITypeBinding;
import shaded.org.eclipse.jdt.core.dom.IVariableBinding;
import shaded.org.eclipse.jdt.core.dom.MethodDeclaration;
import shaded.org.eclipse.jdt.core.dom.TypeDeclaration;
import shaded.org.eclipse.jdt.core.dom.VariableDeclarationFragment;

/**
 * Tests for JavacDOMParser with multiple source files.
 * Verifies cross-file references and binding resolution.
 */
public class JavacDOMParserMultiFileTest {

	private Path tempDir;
	private List<File> classpath;

	@Before
	public void setUp() throws IOException {
		tempDir = Files.createTempDirectory("javac-multifile-test");
		classpath = new ArrayList<>();
		classpath.add(tempDir.toFile());
	}

	@After
	public void tearDown() throws IOException {
		if (tempDir != null) {
			deleteDirectory(tempDir.toFile());
		}
	}

	private void deleteDirectory(File dir) {
		if (dir.exists()) {
			File[] files = dir.listFiles();
			if (files != null) {
				for (File file : files) {
					if (file.isDirectory()) {
						deleteDirectory(file);
					} else {
						file.delete();
					}
				}
			}
			dir.delete();
		}
	}

	private void writeSourceFile(String packageName, String className, String content) throws IOException {
		Path packageDir = tempDir;
		if (packageName != null && !packageName.isEmpty()) {
			String[] parts = packageName.split("\\.");
			for (String part : parts) {
				packageDir = packageDir.resolve(part);
			}
		}
		Files.createDirectories(packageDir);
		Path sourceFile = packageDir.resolve(className + ".java");
		Files.writeString(sourceFile, content);
	}

	@Test
	public void testSimpleInheritanceAcrossFiles() throws IOException {
		// Write base class
		String baseSource = """
			package com.example;

			public class Animal {
				private String name;

				public String getName() {
					return name;
				}

				public void setName(String name) {
					this.name = name;
				}

				public void makeSound() {
					System.out.println("Some sound");
				}
			}
			""";
		writeSourceFile("com.example", "Animal", baseSource);

		// Write derived class
		String dogSource = """
			package com.example;

			public class Dog extends Animal {
				private String breed;

				public String getBreed() {
					return breed;
				}

				@Override
				public void makeSound() {
					System.out.println("Woof!");
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(dogSource, "Dog.java", classpath, AST.JLS21, null, true);

		assertNotNull("CompilationUnit should not be null", cu);
		assertEquals("Should have 1 type", 1, cu.types().size());

		TypeDeclaration dogType = (TypeDeclaration) cu.types().get(0);
		assertEquals("Type name should be Dog", "Dog", dogType.getName().getIdentifier());

		// Test type binding
		ITypeBinding dogBinding = dogType.resolveBinding();
		assertNotNull("Dog binding should not be null", dogBinding);
		assertEquals("Type name should be Dog", "Dog", dogBinding.getName());
		assertEquals("Qualified name should be com.example.Dog", "com.example.Dog", dogBinding.getQualifiedName());

		// Test superclass binding
		ITypeBinding superclass = dogBinding.getSuperclass();
		assertNotNull("Superclass binding should not be null", superclass);
		assertEquals("Superclass should be Animal", "Animal", superclass.getName());
		assertEquals("Superclass qualified name should be com.example.Animal", "com.example.Animal", superclass.getQualifiedName());

		// Verify makeSound method overrides Animal's method
		MethodDeclaration[] methods = dogType.getMethods();
		boolean foundMakeSound = false;
		for (MethodDeclaration method : methods) {
			if ("makeSound".equals(method.getName().getIdentifier())) {
				foundMakeSound = true;
				IMethodBinding methodBinding = method.resolveBinding();
				assertNotNull("makeSound binding should not be null", methodBinding);

				// Check if it overrides a method from superclass
				IMethodBinding[] overriddenMethods = methodBinding.getDeclaringClass().getSuperclass().getDeclaredMethods();
				boolean overridesAnimalMethod = false;
				for (IMethodBinding superMethod : overriddenMethods) {
					if ("makeSound".equals(superMethod.getName())) {
						overridesAnimalMethod = true;
						break;
					}
				}
				assertTrue("makeSound should override Animal's method", overridesAnimalMethod);
			}
		}
		assertTrue("Should find makeSound method", foundMakeSound);
	}

	@Test
	public void testInterfaceImplementationAcrossFiles() throws IOException {
		// Write interface
		String interfaceSource = """
			package com.example;

			public interface Flyable {
				void fly();
				double getAltitude();
			}
			""";
		writeSourceFile("com.example", "Flyable", interfaceSource);

		// Write implementation
		String birdSource = """
			package com.example;

			public class Bird implements Flyable {
				private double altitude;

				@Override
				public void fly() {
					altitude = 100.0;
				}

				@Override
				public double getAltitude() {
					return altitude;
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(birdSource, "Bird.java", classpath, AST.JLS21, null, true);

		assertNotNull("CompilationUnit should not be null", cu);
		TypeDeclaration birdType = (TypeDeclaration) cu.types().get(0);

		// Test type binding
		ITypeBinding birdBinding = birdType.resolveBinding();
		assertNotNull("Bird binding should not be null", birdBinding);

		// Test interface implementation
		ITypeBinding[] interfaces = birdBinding.getInterfaces();
		assertEquals("Should implement 1 interface", 1, interfaces.length);
		assertEquals("Should implement Flyable", "Flyable", interfaces[0].getName());
		assertEquals("Interface qualified name should be com.example.Flyable", "com.example.Flyable", interfaces[0].getQualifiedName());

		// Verify interface has the expected methods
		IMethodBinding[] interfaceMethods = interfaces[0].getDeclaredMethods();
		assertEquals("Flyable should have 2 methods", 2, interfaceMethods.length);
	}

	@Test
	public void testFieldTypeFromAnotherFile() throws IOException {
		// Write model class
		String personSource = """
			package com.example.model;

			public class Person {
				private String firstName;
				private String lastName;

				public String getFirstName() {
					return firstName;
				}

				public String getLastName() {
					return lastName;
				}
			}
			""";
		writeSourceFile("com.example.model", "Person", personSource);

		// Write service class that uses Person
		String serviceSource = """
			package com.example.service;

			import com.example.model.Person;

			public class UserService {
				private Person currentUser;

				public Person getCurrentUser() {
					return currentUser;
				}

				public void setCurrentUser(Person user) {
					this.currentUser = user;
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(serviceSource, "UserService.java", classpath, AST.JLS21, null, true);

		assertNotNull("CompilationUnit should not be null", cu);
		TypeDeclaration serviceType = (TypeDeclaration) cu.types().get(0);

		// Test field binding
		FieldDeclaration[] fields = serviceType.getFields();
		assertEquals("Should have 1 field", 1, fields.length);

		VariableDeclarationFragment fieldFragment = (VariableDeclarationFragment) fields[0].fragments().get(0);
		IVariableBinding fieldBinding = fieldFragment.resolveBinding();
		assertNotNull("Field binding should not be null", fieldBinding);
		assertEquals("Field name should be currentUser", "currentUser", fieldBinding.getName());

		// Test field type binding (cross-file reference)
		ITypeBinding fieldType = fieldBinding.getType();
		assertNotNull("Field type binding should not be null", fieldType);
		assertEquals("Field type should be Person", "Person", fieldType.getName());
		assertEquals("Field type qualified name should be com.example.model.Person", "com.example.model.Person", fieldType.getQualifiedName());

		// Test method return type binding
		MethodDeclaration[] methods = serviceType.getMethods();
		assertTrue("Should have at least 2 methods", methods.length >= 2);

		MethodDeclaration getterMethod = null;
		for (MethodDeclaration method : methods) {
			if ("getCurrentUser".equals(method.getName().getIdentifier())) {
				getterMethod = method;
				break;
			}
		}
		assertNotNull("getCurrentUser method should be found", getterMethod);

		IMethodBinding methodBinding = getterMethod.resolveBinding();
		assertNotNull("Method binding should not be null", methodBinding);

		ITypeBinding returnType = methodBinding.getReturnType();
		assertNotNull("Return type binding should not be null", returnType);
		assertEquals("Return type should be Person", "Person", returnType.getName());
		assertEquals("Return type qualified name should be com.example.model.Person", "com.example.model.Person", returnType.getQualifiedName());
	}

	@Test
	public void testThreeFileInheritanceChain() throws IOException {
		// Write base class
		String vehicleSource = """
			package com.example;

			public abstract class Vehicle {
				private String manufacturer;

				public String getManufacturer() {
					return manufacturer;
				}

				public abstract void start();
			}
			""";
		writeSourceFile("com.example", "Vehicle", vehicleSource);

		// Write middle class
		String carSource = """
			package com.example;

			public class Car extends Vehicle {
				private int doors;

				public int getDoors() {
					return doors;
				}

				@Override
				public void start() {
					System.out.println("Car starting");
				}
			}
			""";
		writeSourceFile("com.example", "Car", carSource);

		// Write final derived class
		String electricCarSource = """
			package com.example;

			public class ElectricCar extends Car {
				private int batteryLevel;

				public int getBatteryLevel() {
					return batteryLevel;
				}

				@Override
				public void start() {
					if (batteryLevel > 0) {
						System.out.println("Electric car starting");
					}
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(electricCarSource, "ElectricCar.java", classpath, AST.JLS21, null, true);

		assertNotNull("CompilationUnit should not be null", cu);
		TypeDeclaration electricCarType = (TypeDeclaration) cu.types().get(0);

		// Test type binding
		ITypeBinding electricCarBinding = electricCarType.resolveBinding();
		assertNotNull("ElectricCar binding should not be null", electricCarBinding);

		// Test direct superclass (Car)
		ITypeBinding carBinding = electricCarBinding.getSuperclass();
		assertNotNull("Car binding should not be null", carBinding);
		assertEquals("Direct superclass should be Car", "Car", carBinding.getName());

		// Test superclass of superclass (Vehicle)
		ITypeBinding vehicleBinding = carBinding.getSuperclass();
		assertNotNull("Vehicle binding should not be null", vehicleBinding);
		assertEquals("Superclass of Car should be Vehicle", "Vehicle", vehicleBinding.getName());

		// Verify full hierarchy: ElectricCar -> Car -> Vehicle -> Object
		ITypeBinding current = electricCarBinding;
		int hierarchyDepth = 0;
		while (current != null && hierarchyDepth < 10) {
			current = current.getSuperclass();
			hierarchyDepth++;
		}
		assertTrue("Should have reasonable hierarchy depth", hierarchyDepth >= 3);
	}

	@Test
	public void testMultipleFilesWithComplexReferences() throws IOException {
		// Write a data class
		String dataSource = """
			package com.example.data;

			public class UserData {
				public String username;
				public int age;
			}
			""";
		writeSourceFile("com.example.data", "UserData", dataSource);

		// Write a processor interface
		String processorSource = """
			package com.example.processor;

			import com.example.data.UserData;

			public interface DataProcessor {
				void process(UserData data);
				boolean validate(UserData data);
			}
			""";
		writeSourceFile("com.example.processor", "DataProcessor", processorSource);

		// Write an implementation that uses both
		String implSource = """
			package com.example.impl;

			import com.example.data.UserData;
			import com.example.processor.DataProcessor;

			public class UserDataProcessor implements DataProcessor {
				@Override
				public void process(UserData data) {
					if (validate(data)) {
						System.out.println("Processing: " + data.username);
					}
				}

				@Override
				public boolean validate(UserData data) {
					return data != null && data.username != null && data.age > 0;
				}
			}
			""";

		JavacDOMParser parser = new JavacDOMParser();
		CompilationUnit cu = parser.parse(implSource, "UserDataProcessor.java", classpath, AST.JLS21, null, true);

		assertNotNull("CompilationUnit should not be null", cu);
		TypeDeclaration implType = (TypeDeclaration) cu.types().get(0);

		// Test type binding
		ITypeBinding implBinding = implType.resolveBinding();
		assertNotNull("UserDataProcessor binding should not be null", implBinding);

		// Test interface implementation
		ITypeBinding[] interfaces = implBinding.getInterfaces();
		assertEquals("Should implement 1 interface", 1, interfaces.length);
		assertEquals("Should implement DataProcessor", "DataProcessor", interfaces[0].getName());

		// Test method parameter types reference UserData
		MethodDeclaration[] methods = implType.getMethods();
		for (MethodDeclaration method : methods) {
			IMethodBinding methodBinding = method.resolveBinding();
			assertNotNull("Method binding should not be null for " + method.getName(), methodBinding);

			ITypeBinding[] paramTypes = methodBinding.getParameterTypes();
			if (paramTypes.length > 0) {
				assertEquals("Parameter should be UserData", "UserData", paramTypes[0].getName());
				assertEquals("Parameter qualified name should be com.example.data.UserData",
						"com.example.data.UserData", paramTypes[0].getQualifiedName());
			}
		}
	}

	@Test
	public void testParsingMultipleFilesIndependently() throws IOException {
		// Write first class
		String class1Source = """
			package com.example;

			public class ClassOne {
				private String data;

				public String getData() {
					return data;
				}
			}
			""";
		writeSourceFile("com.example", "ClassOne", class1Source);

		// Write second class
		String class2Source = """
			package com.example;

			public class ClassTwo {
				private int value;

				public int getValue() {
					return value;
				}
			}
			""";
		writeSourceFile("com.example", "ClassTwo", class2Source);

		JavacDOMParser parser = new JavacDOMParser();

		// Parse first file
		CompilationUnit cu1 = parser.parse(class1Source, "ClassOne.java", classpath, AST.JLS21, null, true);
		assertNotNull("First CompilationUnit should not be null", cu1);
		TypeDeclaration type1 = (TypeDeclaration) cu1.types().get(0);
		assertEquals("First type should be ClassOne", "ClassOne", type1.getName().getIdentifier());

		ITypeBinding binding1 = type1.resolveBinding();
		assertNotNull("ClassOne binding should not be null", binding1);
		assertEquals("ClassOne qualified name", "com.example.ClassOne", binding1.getQualifiedName());

		// Parse second file
		CompilationUnit cu2 = parser.parse(class2Source, "ClassTwo.java", classpath, AST.JLS21, null, true);
		assertNotNull("Second CompilationUnit should not be null", cu2);
		TypeDeclaration type2 = (TypeDeclaration) cu2.types().get(0);
		assertEquals("Second type should be ClassTwo", "ClassTwo", type2.getName().getIdentifier());

		ITypeBinding binding2 = type2.resolveBinding();
		assertNotNull("ClassTwo binding should not be null", binding2);
		assertEquals("ClassTwo qualified name", "com.example.ClassTwo", binding2.getQualifiedName());

		// Both should have resolved bindings even when parsed separately
		assertNotNull("ClassOne should have valid binding", binding1);
		assertNotNull("ClassTwo should have valid binding", binding2);
	}
}
