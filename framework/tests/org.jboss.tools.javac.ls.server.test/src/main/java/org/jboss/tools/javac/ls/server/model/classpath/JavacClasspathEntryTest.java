/*******************************************************************************
 * Copyright (c) 2026 Red Hat, Inc. Distributed under license by Red Hat, Inc.
 * All rights reserved. This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors: Red Hat, Inc.
 ******************************************************************************/
package org.jboss.tools.javac.ls.server.model.classpath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import org.jboss.tools.javac.ls.server.model.classpath.IJavacClasspathEntry.EntryType;
import org.junit.Test;

public class JavacClasspathEntryTest {

	@Test
	public void testConstructorAndGetters() {
		JavacClasspathEntry entry = new JavacClasspathEntry(EntryType.SOURCE, "/path/to/src");

		assertEquals(EntryType.SOURCE, entry.getType());
		assertEquals("/path/to/src", entry.getPath());
	}

	@Test
	public void testEquality() {
		JavacClasspathEntry entry1 = new JavacClasspathEntry(EntryType.SOURCE, "/path/to/src");
		JavacClasspathEntry entry2 = new JavacClasspathEntry(EntryType.SOURCE, "/path/to/src");
		JavacClasspathEntry entry3 = new JavacClasspathEntry(EntryType.LIBRARY, "/path/to/src");
		JavacClasspathEntry entry4 = new JavacClasspathEntry(EntryType.SOURCE, "/different/path");

		assertEquals(entry1, entry2);
		assertEquals(entry2, entry1);
		assertEquals(entry1.hashCode(), entry2.hashCode());

		assertNotEquals(entry1, entry3); // Different type
		assertNotEquals(entry1, entry4); // Different path
	}

	@Test
	public void testToString() {
		JavacClasspathEntry sourceEntry = new JavacClasspathEntry(EntryType.SOURCE, "/src/main/java");
		JavacClasspathEntry libEntry = new JavacClasspathEntry(EntryType.LIBRARY, "/lib/foo.jar");

		String sourceStr = sourceEntry.toString();
		String libStr = libEntry.toString();

		assertNotNull(sourceStr);
		assertNotNull(libStr);

		// Should contain type and path
		assertEquals("SOURCE: /src/main/java", sourceStr);
		assertEquals("LIBRARY: /lib/foo.jar", libStr);
	}

	@Test
	public void testNullPath() {
		JavacClasspathEntry entry = new JavacClasspathEntry(EntryType.SOURCE, null);

		assertEquals(EntryType.SOURCE, entry.getType());
		assertEquals(null, entry.getPath());
	}

	@Test
	public void testSetters() {
		JavacClasspathEntry entry = new JavacClasspathEntry();

		entry.setType(EntryType.LIBRARY);
		entry.setPath("/lib/test.jar");

		assertEquals(EntryType.LIBRARY, entry.getType());
		assertEquals("/lib/test.jar", entry.getPath());
	}
}
