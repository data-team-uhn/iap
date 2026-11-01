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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.ValueFactory;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.SortedNumericSortField;
import org.apache.lucene.search.SortedSetSortField;
import org.apache.lucene.search.TermQuery;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link PropertyFields}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class PropertyFieldsTest
{
    private static final String VALUE = "value";

    private final SlingContext context = TestRepository.newContext();

    private Session session;

    private ValueFactory values;

    private Node node;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.session = TestRepository.session(this.context);
        this.values = this.session.getValueFactory();
        this.node = this.session.getRootNode().addNode("answer", "test:Answer");
    }

    @Test
    void comparesAWholeNumberAsANumber() throws Exception
    {
        this.node.setProperty(VALUE, 10L);

        // As text, "10" sorts before "4"
        assertEquals(1, found(LongPoint.newRangeQuery("value|Long|number", 4, 11)));
    }

    @Test
    void keepsTheTextOfAWholeNumber() throws Exception
    {
        this.node.setProperty(VALUE, 10L);

        // A value that does not convert to a whole number, such as 1%, is compared with this
        assertEquals(1, found(term("value|Long", "10")));
        assertEquals(1, found(term("value|lowercase", "10")));
    }

    @Test
    void comparesANumberAsANumber() throws Exception
    {
        this.node.setProperty(VALUE, 2.5d);

        assertEquals(1, found(DoublePoint.newRangeQuery("value|Double|number", 2.0, 3.0)));
        assertEquals(1, found(term("value|Double", "2.5")));
    }

    @Test
    void comparesADateAsTheInstantItNames() throws Exception
    {
        final Calendar date = new GregorianCalendar(TimeZone.getTimeZone("America/Toronto"));
        date.setTimeInMillis(1_793_491_200_000L);
        this.node.setProperty(VALUE, date);

        assertEquals(1, found(LongPoint.newExactQuery("value|Date|number", 1_793_491_200_000L)));
        // As the repository writes it, in the zone it was set in
        assertEquals(1, found(term("value|Date", "2026-10-31T20:00:00.000-04:00")));
    }

    @Test
    void matchesTextWholeAndInLowerCase() throws Exception
    {
        this.node.setProperty(VALUE, "Half Day");

        assertEquals(1, found(term("value|String", "Half Day")));
        assertEquals(1, found(term("value|lowercase", "half day")));
        assertEquals(0, found(term("value|String", "Half")));
    }

    @Test
    void matchesAnyOtherValueAsItsText() throws Exception
    {
        final Node target = this.session.getRootNode().addNode("target", "test:Entity");
        this.node.setProperty(VALUE, target);

        assertEquals(1, found(term("value|Reference", target.getIdentifier())));
    }

    @Test
    void matchesYesOrNoAsItsText() throws Exception
    {
        this.node.setProperty(VALUE, true);

        assertEquals(1, found(term("value|Boolean", "true")));
    }

    @Test
    void matchesEveryValueOfAMultipleProperty() throws Exception
    {
        this.node.setProperty(VALUE, new String[] { "first", "second" });

        assertEquals(1, found(term("value|String", "first")));
        assertEquals(1, found(term("value|String", "second")));
    }

    @Test
    void recordsThatThePropertyIsSetAndInWhichType() throws Exception
    {
        this.node.setProperty(VALUE, 1L);

        assertEquals(1, found(term(IndexFields.PROPERTIES_FIELD, VALUE)));
        assertEquals(1, found(term(IndexFields.TYPED_FIELD, "value|Long")));
        assertEquals(0, found(term(IndexFields.MULTIPLE_FIELD, VALUE)));
    }

    @Test
    void recordsThatThePropertyHoldsSeveralValues() throws Exception
    {
        this.node.setProperty(VALUE, new String[] { "only" });

        assertEquals(1, found(term(IndexFields.MULTIPLE_FIELD, VALUE)));
    }

    @Test
    void indexesNothingOfABinaryButThatItIsSet() throws Exception
    {
        this.node.setProperty(VALUE, this.values.createBinary(new ByteArrayInputStream(new byte[] { 1, 2, 3 })));

        assertEquals(Set.of(IndexFields.PROPERTIES_FIELD), document(this.node).getFields().stream()
            .map(IndexableField::name).collect(Collectors.toSet()));
    }

    @Test
    void keepsTheStartOfALongTextAndSaysSo() throws Exception
    {
        this.node.setProperty(VALUE, "a".repeat(5000));

        assertEquals(1, found(term("value|String", "a".repeat(4096))));
        assertEquals(1, found(term(IndexFields.TRUNCATED_FIELD, VALUE)));
    }

    @Test
    void doesNotSayAShortTextWasCut() throws Exception
    {
        this.node.setProperty(VALUE, "short");

        assertEquals(0, found(term(IndexFields.TRUNCATED_FIELD, VALUE)));
    }

    @Test
    void ordersWholeNumbersAsNumbers() throws Exception
    {
        assertEquals(List.of("/three", "/ten"),
            ordered(Map.of("ten", this.values.createValue(10L), "three", this.values.createValue(3L)),
                new SortedNumericSortField("value|Long|number", SortField.Type.LONG)));
    }

    @Test
    void ordersNumbersAsNumbers() throws Exception
    {
        assertEquals(List.of("/negative", "/half"),
            ordered(Map.of("half", this.values.createValue(0.5d), "negative", this.values.createValue(-2.5d)),
                new SortedNumericSortField("value|Double|number", SortField.Type.DOUBLE)));
    }

    @Test
    void ordersTextAsText() throws Exception
    {
        assertEquals(List.of("/apple", "/pear"),
            ordered(Map.of("pear", this.values.createValue("pear"), "apple", this.values.createValue("apple")),
                new SortedSetSortField("value|String", false)));
    }

    private int found(final Query query) throws IOException, RepositoryException
    {
        try (SearchedIndex index = SearchedIndex.of(List.of(document(this.node)))) {
            return index.count(query);
        }
    }

    /**
     * The order a sort puts some nodes in.
     *
     * @param valued the value each node holds, by the node's name
     * @param sort how to order them
     * @return the nodes' paths, in the order sorted
     * @throws IOException if the index fails
     * @throws RepositoryException if the nodes cannot be written
     */
    private List<String> ordered(final Map<String, Value> valued, final SortField sort)
        throws IOException, RepositoryException
    {
        final List<Document> documents = new ArrayList<>();
        for (final Map.Entry<String, Value> entry : new LinkedHashMap<>(valued).entrySet()) {
            final Node holder = this.session.getRootNode().addNode(entry.getKey(), "test:Answer");
            holder.setProperty(VALUE, entry.getValue());
            final Document document = document(holder);
            document.add(new StoredField(IndexFields.PATH_FIELD, holder.getPath()));
            documents.add(document);
        }
        try (SearchedIndex index = SearchedIndex.of(documents)) {
            return index.paths(new Sort(sort));
        }
    }

    private static Document document(final Node holder) throws RepositoryException
    {
        final Document document = new Document();
        PropertyFields.add(holder.getProperty(VALUE), document);
        return document;
    }

    private static Query term(final String field, final String text)
    {
        return new TermQuery(new Term(field, text));
    }
}
