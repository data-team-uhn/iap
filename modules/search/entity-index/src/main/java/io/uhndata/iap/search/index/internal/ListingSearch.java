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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFactory;

import org.apache.commons.lang3.StringUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.search.api.DescendantFilters;
import io.uhndata.iap.search.api.EntityListing;
import io.uhndata.iap.search.api.Filter;

/**
 * Answers listings of an index's entities as a JCR query would, or declines those it cannot answer exactly.
 *
 * <p>
 * The index holds everything the indexing service user reads. What it finds is narrowed to what the person asking
 * reads: an entity they cannot read is left out, and so is one matched only through descendants they cannot read,
 * since their own query would not have joined those.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ListingSearch
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ListingSearch.class);

    private final EntityIndex index;

    private final String homepage;

    private final String entityType;

    private final Set<String> descendantTypes;

    /**
     * Answers listings of the entities an index holds.
     *
     * @param index the index
     * @param homepage the path the entities are filed under
     * @param entityType the node type of the entities
     * @param descendantTypes the node types of the descendants indexed with them
     */
    ListingSearch(final EntityIndex index, final String homepage, final String entityType,
        final Set<String> descendantTypes)
    {
        this.index = index;
        this.homepage = homepage;
        this.entityType = entityType;
        this.descendantTypes = Set.copyOf(descendantTypes);
    }

    /**
     * Whether the index can answer a listing exactly as the repository would.
     *
     * @param listing the entities asked for
     * @return {@code false} for a listing of other entities, a full text search, conditions on descendants not
     *         indexed, a condition on a value the index holds only in part, or an order it cannot give, and when the
     *         index cannot be read
     */
    boolean canServe(final EntityListing listing)
    {
        try {
            return covers(listing) && this.index.search(searcher -> answersExactly(listing, searcher));
        } catch (final IOException e) {
            // The repository answers instead, only more slowly
            LOGGER.warn("Failed to read the index of {}: {}", this.homepage, e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(ListingSearch.class, "readIndex").about(this.homepage));
            return false;
        }
    }

    /**
     * The entities a listing asks for, that the person asking can read, in order.
     *
     * @param listing the entities asked for, one the index {@link #canServe can serve}
     * @param resolver the resolver of the person asking
     * @return the paths of the entities, checked as they are read
     * @throws IOException if the index cannot be read, or can no longer give the order asked for
     */
    Iterator<String> find(final EntityListing listing, final ResourceResolver resolver) throws IOException
    {
        final ValueFactory values = valuesOf(resolver);
        final Matches matches = this.index.search(searcher -> matches(listing, searcher, values));
        return matches.entities().stream().filter(entity -> matches.readableBy(resolver, entity)).iterator();
    }

    /**
     * Converts the values a listing compares against, by the rules of the repository the person asking reads.
     *
     * @param resolver the resolver of the person asking
     * @return its repository's value factory
     * @throws IOException if the resolver has none
     */
    private static ValueFactory valuesOf(final ResourceResolver resolver) throws IOException
    {
        try {
            return Objects.requireNonNull(resolver.adaptTo(Session.class), "A listing is read through a JCR session")
                .getValueFactory();
        } catch (final RepositoryException e) {
            throw new IOException("Values cannot be converted by the repository's rules", e);
        }
    }

    private boolean covers(final EntityListing listing)
    {
        return this.homepage.equals(listing.scopePath()) && this.entityType.equals(listing.entityType())
            && StringUtils.isBlank(listing.fullText())
            && listing.descendants().stream().map(DescendantFilters::type).allMatch(this.descendantTypes::contains);
    }

    private static boolean answersExactly(final EntityListing listing, final IndexSearcher searcher)
        throws IOException
    {
        final StoredTypes stored = StoredTypes.of(searcher.getIndexReader());
        for (final String property : filteredProperties(listing)) {
            // Only the start of a long text is indexed, and a decimal only as text
            if (searcher.getIndexReader().docFreq(new Term(IndexFields.TRUNCATED_FIELD, property)) > 0
                || stored.of(property).contains(PropertyType.DECIMAL)) {
                return false;
            }
        }
        return ListingSort.of(listing.sortBy(), listing.descending(), searcher, stored).isPresent();
    }

    private static List<String> filteredProperties(final EntityListing listing)
    {
        return Stream.concat(Stream.of(listing.filters()),
            listing.descendants().stream().map(DescendantFilters::filters))
            .flatMap(List::stream)
            .map(Filter::getName)
            .distinct()
            .toList();
    }

    private static Matches matches(final EntityListing listing, final IndexSearcher searcher,
        final ValueFactory values) throws IOException
    {
        final StoredTypes stored = StoredTypes.of(searcher.getIndexReader());
        final Sort sort = ListingSort.of(listing.sortBy(), listing.descending(), searcher, stored)
            .orElseThrow(() -> new IOException("The index can no longer order the entities by " + listing.sortBy()));
        try {
            final ListingQuery query = new ListingQuery(listing, new FilterQueries(stored, values));
            final List<String> entities = found(searcher, query.entities(), sort).stream()
                .map(entity -> entity.get(IndexFields.PATH_FIELD))
                .toList();
            final List<Map<String, List<String>>> families = new ArrayList<>();
            for (final DescendantFilters family : listing.descendants()) {
                families.add(byEntity(found(searcher, query.descendants(family), Sort.INDEXORDER)));
            }
            return new Matches(entities, families);
        } catch (final RepositoryException e) {
            throw new IOException("A value compared against could not be read", e);
        }
    }

    private static Map<String, List<String>> byEntity(final List<Document> descendants)
    {
        final Map<String, List<String>> byEntity = new HashMap<>();
        for (final Document descendant : descendants) {
            byEntity.computeIfAbsent(descendant.get(IndexFields.ENTITY_FIELD), entity -> new ArrayList<>())
                .add(descendant.get(IndexFields.PATH_FIELD));
        }
        return byEntity;
    }

    private static List<Document> found(final IndexSearcher searcher, final Query query, final Sort sort)
        throws IOException
    {
        final int count = searcher.count(query);
        final List<Document> documents = new ArrayList<>(count);
        if (count > 0) {
            for (final ScoreDoc hit : searcher.search(query, count, sort).scoreDocs) {
                documents.add(searcher.storedFields().document(hit.doc));
            }
        }
        return documents;
    }

    /**
     * What a listing matched in the index, before narrowing it to what the person asking reads.
     *
     * @param entities the paths of the matching entities, in order
     * @param families for each descendant family, the paths of its matching descendants by their entity's path
     * @version $Id$
     * @since 0.1.0
     */
    private record Matches(List<String> entities, List<Map<String, List<String>>> families)
    {
        boolean readableBy(final ResourceResolver resolver, final String entity)
        {
            return resolver.getResource(entity) != null && this.families.stream()
                .allMatch(family -> family.getOrDefault(entity, List.of()).stream()
                    .anyMatch(descendant -> resolver.getResource(descendant) != null));
        }
    }
}
