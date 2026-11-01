/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.uhndata.iap.search.index.internal;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.IOUtils;

/**
 * The index's documents on disk, written one entity's block at a time. A block is only ever replaced or removed
 * whole, which is what keeps a block join able to tell which descendants belong to which entity.
 *
 * <p>
 * An index is <em>sealed</em> when it is closed knowing it matches the repository: its last commit records the layout
 * it was written in. Any other commit records none, so that an index a crash interrupted, whose last changes may never
 * have reached the disk, is rebuilt rather than trusted, and so is one written by another version or configuration.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class EntityIndex implements Closeable
{
    /**
     * A search over the index.
     *
     * @param <T> what the search finds
     * @version $Id$
     * @since 0.1.0
     */
    @FunctionalInterface
    interface Search<T>
    {
        /**
         * Runs the search.
         *
         * @param searcher reads the index as it stood when the search began
         * @return what the search found
         * @throws IOException if the index cannot be read
         */
        T run(IndexSearcher searcher) throws IOException;
    }

    private static final String LAYOUT_KEY = "layout";

    private final Directory directory;

    private final IndexWriter writer;

    private final SearcherManager searchers;

    /**
     * Opens the index, creating it if the directory holds none. The index closes the directory when it is closed.
     *
     * @param directory where the index is kept
     * @throws IOException if the index cannot be opened
     */
    EntityIndex(final Directory directory) throws IOException
    {
        this.directory = directory;
        this.writer = new IndexWriter(directory, new IndexWriterConfig());
        this.searchers = new SearcherManager(this.writer, null);
    }

    /**
     * Searches the index, including what was written but not yet made durable.
     *
     * @param <T> what the search finds
     * @param search the search to run
     * @return what the search found
     * @throws IOException if the index cannot be read
     */
    <T> T search(final Search<T> search) throws IOException
    {
        this.searchers.maybeRefreshBlocking();
        final IndexSearcher searcher = this.searchers.acquire();
        try {
            return search.run(searcher);
        } finally {
            this.searchers.release(searcher);
        }
    }

    /**
     * Whether the index was sealed in a layout and has not been written to since.
     *
     * @param layout the layout the index is expected in
     * @return {@code true} if the index can be trusted to match the repository in that layout
     */
    boolean isSealed(final String layout)
    {
        return recordedLayout().filter(layout::equals).isPresent();
    }

    /**
     * Whether the index can still be written. A write that fails at the wrong moment closes it for good.
     *
     * @return {@code false} once the index is closed
     */
    boolean isOpen()
    {
        return this.writer.isOpen();
    }

    /**
     * Indexes an entity, replacing what was indexed for it before.
     *
     * @param entityPath the entity's path
     * @param block the entity's documents, as {@link EntityDocuments} lays them out
     * @throws IOException if the index cannot be written
     */
    void replace(final String entityPath, final List<Document> block) throws IOException
    {
        this.writer.updateDocuments(new Term(IndexFields.ENTITY_FIELD, entityPath), block);
    }

    /**
     * Removes the entity at a path, and every entity below it.
     *
     * @param path the path of a removed node
     * @throws IOException if the index cannot be written
     */
    void remove(final String path) throws IOException
    {
        this.writer.deleteDocuments(new TermQuery(new Term(IndexFields.ENTITY_FIELD, path)),
            new PrefixQuery(new Term(IndexFields.ENTITY_FIELD, path + "/")));
    }

    /**
     * Removes every entity, ahead of indexing them all again.
     *
     * @throws IOException if the index cannot be written
     */
    void clear() throws IOException
    {
        this.writer.deleteAll();
    }

    /**
     * Makes what was written so far durable, and unseals the index if it was sealed.
     *
     * @throws IOException if the index cannot be written
     */
    void commit() throws IOException
    {
        if (recordedLayout().isPresent()) {
            this.writer.setLiveCommitData(Map.<String, String>of().entrySet());
        }
        if (this.writer.hasUncommittedChanges()) {
            this.writer.commit();
        }
    }

    /**
     * Makes what was written durable, sealing the index in a layout. Nothing may be written after.
     *
     * @param layout the layout the index was written in
     * @throws IOException if the index cannot be written
     */
    void seal(final String layout) throws IOException
    {
        this.writer.setLiveCommitData(Map.of(LAYOUT_KEY, layout).entrySet());
        this.writer.commit();
    }

    private Optional<String> recordedLayout()
    {
        return Optional.ofNullable(this.writer.getLiveCommitData())
            .flatMap(data -> StreamSupport.stream(data.spliterator(), false)
                .filter(entry -> LAYOUT_KEY.equals(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst());
    }

    @Override
    public void close() throws IOException
    {
        // Each closed even if the one before fails, the first failure thrown
        IOUtils.close(this.searchers, this.writer, this.directory);
    }
}
