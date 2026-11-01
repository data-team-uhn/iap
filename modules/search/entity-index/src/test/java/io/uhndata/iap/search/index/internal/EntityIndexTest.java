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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link EntityIndex}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class EntityIndexTest
{
    private static final String LAYOUT = "1;test:Request;test:Answer";

    @TempDir
    private Path directory;

    @Test
    void replacesAnEntitysWholeBlock() throws IOException
    {
        try (EntityIndex index = open()) {
            index.replace("/h/a", List.of(document("/h/a", "/h/a/one"), document("/h/a", "/h/a/two"),
                document("/h/a", "/h/a")));
            index.replace("/h/a", List.of(document("/h/a", "/h/a")));
            index.commit();
        }

        assertEquals(List.of("/h/a"), indexedPaths());
    }

    @Test
    void removesAnEntityAndTheEntitiesBelowIt() throws IOException
    {
        try (EntityIndex index = open()) {
            for (final String entity : List.of("/h/b1/e1", "/h/b1/e2", "/h/b10/e3", "/h/b2/e4")) {
                index.replace(entity, List.of(document(entity, entity)));
            }
            index.remove("/h/b1");
            index.remove("/h/b2/e4");
            index.commit();
        }

        assertEquals(List.of("/h/b10/e3"), indexedPaths());
    }

    @Test
    void isNotSealedUntilSealed() throws IOException
    {
        try (EntityIndex index = open()) {
            assertFalse(index.isSealed(LAYOUT));
            index.seal(LAYOUT);
        }

        try (EntityIndex index = open()) {
            assertTrue(index.isSealed(LAYOUT));
            assertFalse(index.isSealed("2;test:Request;test:Answer"));
        }
    }

    @Test
    void isUnsealedByTheNextCommit() throws IOException
    {
        sealed();
        try (EntityIndex index = open()) {
            index.replace("/h/b", List.of(document("/h/b", "/h/b")));
            index.commit();
        }

        try (EntityIndex index = open()) {
            assertFalse(index.isSealed(LAYOUT));
        }
    }

    @Test
    void isUnsealedByACommitWithNothingToWrite() throws IOException
    {
        sealed();
        try (EntityIndex index = open()) {
            index.commit();
        }

        try (EntityIndex index = open()) {
            assertFalse(index.isSealed(LAYOUT));
        }
    }

    @Test
    void forgetsEverythingWhenCleared() throws IOException
    {
        try (EntityIndex index = open()) {
            index.replace("/h/a", List.of(document("/h/a", "/h/a")));
            index.commit();
            index.clear();
            index.replace("/h/b", List.of(document("/h/b", "/h/b")));
            index.commit();
        }

        assertEquals(List.of("/h/b"), indexedPaths());
    }

    @Test
    void commitsOnlyWhatChanged() throws IOException
    {
        try (EntityIndex index = open()) {
            index.replace("/h/a", List.of(document("/h/a", "/h/a")));
            index.commit();
            index.commit();
        }

        try (FSDirectory reopened = FSDirectory.open(this.directory)) {
            assertEquals(1, DirectoryReader.listCommits(reopened).size());
        }
    }

    @Test
    void isOpenUntilClosed() throws IOException
    {
        final EntityIndex index = open();
        assertTrue(index.isOpen());

        index.close();

        assertFalse(index.isOpen());
    }

    private void sealed() throws IOException
    {
        try (EntityIndex index = open()) {
            index.replace("/h/a", List.of(document("/h/a", "/h/a")));
            index.seal(LAYOUT);
        }
    }

    private EntityIndex open() throws IOException
    {
        return new EntityIndex(FSDirectory.open(this.directory));
    }

    private List<String> indexedPaths() throws IOException
    {
        try (SearchedIndex index = SearchedIndex.at(this.directory)) {
            assertEquals(index.count(new MatchAllDocsQuery()),
                index.count(new TermQuery(new Term(IndexFields.KIND_FIELD, IndexFields.ENTITY_KIND))));
            return index.paths(Sort.INDEXORDER);
        }
    }

    private static Document document(final String entity, final String path)
    {
        final Document document = new Document();
        document.add(new StringField(IndexFields.ENTITY_FIELD, entity, Store.NO));
        document.add(new StringField(IndexFields.PATH_FIELD, path, Store.YES));
        document.add(new StringField(IndexFields.KIND_FIELD,
            entity.equals(path) ? IndexFields.ENTITY_KIND : IndexFields.DESCENDANT_KIND, Store.NO));
        return document;
    }
}
