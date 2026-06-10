/*******************************************************************************
 * Copyright (c) 2025 Red Hat, Inc. and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.jboss.tools.javac.ls.server.index;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.jboss.tools.javac.ls.index.store.JavaIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the Java source code index with disk persistence.
 * Provides thread-safe access to the index and handles save/load operations.
 *
 * Note: Callers should call {@link #markDirty()} after modifying the index
 * to ensure changes are persisted on the next save.
 */
public class JavaIndexCache {

	private static final Logger LOG = LoggerFactory.getLogger(JavaIndexCache.class);

	private final JavaIndex index;
	private final JsonIndexPersistence persistence;
	private final ReadWriteLock lock;
	private boolean dirty;

	/**
	 * Create a new index cache with the given storage directory.
	 * @param baseDirectory directory where index will be persisted
	 */
	public JavaIndexCache(Path baseDirectory) {
		this.index = new JavaIndex();
		this.persistence = new JsonIndexPersistence(baseDirectory);
		this.lock = new ReentrantReadWriteLock();
		this.dirty = false;
	}

	/**
	 * Get the index for read/write access.
	 * Callers should acquire appropriate locks before accessing the index.
	 */
	public JavaIndex getIndex() {
		return index;
	}

	/**
	 * Acquire read lock for safe concurrent reads.
	 */
	public void lockRead() {
		lock.readLock().lock();
	}

	/**
	 * Release read lock.
	 */
	public void unlockRead() {
		lock.readLock().unlock();
	}

	/**
	 * Acquire write lock for safe modifications.
	 */
	public void lockWrite() {
		lock.writeLock().lock();
	}

	/**
	 * Release write lock.
	 */
	public void unlockWrite() {
		lock.writeLock().unlock();
	}

	/**
	 * Load index from disk if it exists.
	 * @return true if loaded from disk, false if no persisted data exists
	 */
	public boolean load() {
		if (!persistence.exists()) {
			LOG.info("No persisted index found, starting with empty index");
			return false;
		}

		lockWrite();
		try {
			long start = System.currentTimeMillis();
			LOG.info("Loading index from disk...");

			index.loadFrom(persistence);
			dirty = false;

			long duration = System.currentTimeMillis() - start;
			LOG.info("Loaded index from disk in {}ms ({} types, {} methods, {} fields)",
					duration, index.getTypeCount(), index.getMethodCount(), index.getFieldCount());
			return true;
		} catch (IOException e) {
			LOG.error("Failed to load index from disk", e);
			return false;
		} finally {
			unlockWrite();
		}
	}

	/**
	 * Save index to disk.
	 * @param force if true, save even if not dirty
	 * @return true if saved successfully
	 */
	public boolean save(boolean force) {
		lockRead();
		boolean shouldSave = dirty || force;
		unlockRead();

		if (!shouldSave) {
			LOG.debug("Index not dirty, skipping save");
			return true;
		}

		lockWrite();
		try {
			long start = System.currentTimeMillis();
			LOG.info("Saving index to disk...");

			index.saveTo(persistence);
			dirty = false;

			long duration = System.currentTimeMillis() - start;
			LOG.info("Saved index to disk in {}ms ({} types, {} methods, {} fields)",
					duration, index.getTypeCount(), index.getMethodCount(), index.getFieldCount());
			return true;
		} catch (IOException e) {
			LOG.error("Failed to save index to disk", e);
			return false;
		} finally {
			unlockWrite();
		}
	}

	/**
	 * Save index to disk if dirty.
	 */
	public boolean save() {
		return save(false);
	}

	/**
	 * Clear the in-memory index by removing a file's declarations.
	 * @param file the file whose declarations should be removed
	 */
	public void removeFile(Path file) {
		lockWrite();
		try {
			LOG.debug("Removing file from index: {}", file);
			index.removeFile(file);
			dirty = true;
		} finally {
			unlockWrite();
		}
	}

	/**
	 * Clear persisted index data from disk.
	 */
	public void clearPersisted() {
		lockWrite();
		try {
			LOG.info("Clearing persisted index data");
			persistence.clear();
		} catch (IOException e) {
			LOG.error("Failed to clear persisted index", e);
		} finally {
			unlockWrite();
		}
	}

	/**
	 * Check if index has unsaved changes.
	 */
	public boolean isDirty() {
		lockRead();
		try {
			return dirty;
		} finally {
			unlockRead();
		}
	}

	/**
	 * Mark index as dirty (needing save).
	 * Should be called after any modification to the index.
	 */
	public void markDirty() {
		lockWrite();
		try {
			dirty = true;
		} finally {
			unlockWrite();
		}
	}

	/**
	 * Get timestamp of persisted index.
	 * @return timestamp in milliseconds, or 0 if no persisted data
	 */
	public long getPersistedTimestamp() {
		return persistence.getTimestamp();
	}

	/**
	 * Check if persisted index exists.
	 */
	public boolean exists() {
		return persistence.exists();
	}

	/**
	 * Get index statistics.
	 */
	public IndexStats getStats() {
		lockRead();
		try {
			return new IndexStats(
				index.getTypeCount(),
				index.getMethodCount(),
				index.getFieldCount(),
				dirty
			);
		} finally {
			unlockRead();
		}
	}

	/**
	 * Simple statistics holder.
	 */
	public static class IndexStats {
		private final int typeCount;
		private final int methodCount;
		private final int fieldCount;
		private final boolean dirty;

		public IndexStats(int typeCount, int methodCount, int fieldCount, boolean dirty) {
			this.typeCount = typeCount;
			this.methodCount = methodCount;
			this.fieldCount = fieldCount;
			this.dirty = dirty;
		}

		public int getTypeCount() {
			return typeCount;
		}

		public int getMethodCount() {
			return methodCount;
		}

		public int getFieldCount() {
			return fieldCount;
		}

		public boolean isDirty() {
			return dirty;
		}

		@Override
		public String toString() {
			return String.format("IndexStats[types=%d, methods=%d, fields=%d, dirty=%s]",
					typeCount, methodCount, fieldCount, dirty);
		}
	}
}
