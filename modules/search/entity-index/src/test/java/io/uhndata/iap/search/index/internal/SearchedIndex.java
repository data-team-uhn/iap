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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * Asks an index what it finds, the way a search would.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SearchedIndex implements Closeable
{
    private final Directory directory;

    private final DirectoryReader reader;

    private SearchedIndex(final Directory directory) throws IOException
    {
        this.directory = directory;
        this.reader = DirectoryReader.open(directory);
    }

    /**
     * An index of some documents, kept in memory.
     *
     * @param documents the documents to index
     * @return the index, to close once read
     * @throws IOException if the documents cannot be indexed
     */
    static SearchedIndex of(final List<Document> documents) throws IOException
    {
        final Directory directory = new ByteBuffersDirectory();
        try (IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig())) {
            writer.addDocuments(documents);
        }
        return new SearchedIndex(directory);
    }

    /**
     * The index committed in a directory.
     *
     * @param path the directory
     * @return the index, to close once read
     * @throws IOException if there is no index there
     */
    static SearchedIndex at(final Path path) throws IOException
    {
        return new SearchedIndex(FSDirectory.open(path));
    }

    /**
     * How many documents a query finds.
     *
     * @param query the query
     * @return the number of documents
     * @throws IOException if the index cannot be read
     */
    int count(final Query query) throws IOException
    {
        return new IndexSearcher(this.reader).count(query);
    }

    /**
     * The nodes the documents a query finds describe.
     *
     * @param query the query
     * @return the paths stored with the documents found, in index order
     * @throws IOException if the index cannot be read
     */
    List<String> paths(final Query query) throws IOException
    {
        return paths(query, Sort.INDEXORDER);
    }

    /**
     * The nodes every document describes, in an order.
     *
     * @param sort the order
     * @return the paths stored with the documents
     * @throws IOException if the index cannot be read
     */
    List<String> paths(final Sort sort) throws IOException
    {
        return paths(new MatchAllDocsQuery(), sort);
    }

    /**
     * The nodes the documents a query finds describe, in an order.
     *
     * @param query the query
     * @param sort the order
     * @return the paths stored with the documents found
     * @throws IOException if the index cannot be read
     */
    List<String> paths(final Query query, final Sort sort) throws IOException
    {
        final IndexSearcher searcher = new IndexSearcher(this.reader);
        final List<String> paths = new ArrayList<>();
        for (final ScoreDoc hit : searcher.search(query, Math.max(1, this.reader.maxDoc()), sort).scoreDocs) {
            paths.add(searcher.storedFields().document(hit.doc).get(IndexFields.PATH_FIELD));
        }
        return paths;
    }

    /**
     * Reads the index directly.
     *
     * @return the index's reader, closed with this
     */
    IndexReader reader()
    {
        return this.reader;
    }

    @Override
    public void close() throws IOException
    {
        try {
            this.reader.close();
        } finally {
            this.directory.close();
        }
    }
}
