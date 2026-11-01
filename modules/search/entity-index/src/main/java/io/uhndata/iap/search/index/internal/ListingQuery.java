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

import java.util.List;

import javax.jcr.RepositoryException;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.join.BitSetProducer;
import org.apache.lucene.search.join.QueryBitSetProducer;
import org.apache.lucene.search.join.ScoreMode;
import org.apache.lucene.search.join.ToParentBlockJoinQuery;

import io.uhndata.iap.search.api.DescendantFilters;
import io.uhndata.iap.search.api.EntityListing;
import io.uhndata.iap.search.api.Filter;

/**
 * The queries a listing's conditions make: one over the entities, and one over the descendants of each family. Each
 * family is joined to the entities on its own, so that an entity matches only if, for every family, one of its
 * descendants meets all of that family's conditions, as the JCR query's one join per family has it.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ListingQuery
{
    /** Tells the block join which documents are entities, each ending its block. */
    private static final BitSetProducer ENTITY_DOCUMENTS = new QueryBitSetProducer(kind(IndexFields.ENTITY_KIND));

    private final EntityListing listing;

    private final FilterQueries conditions;

    /**
     * The queries of a listing.
     *
     * @param listing the entities asked for
     * @param conditions turns each condition into a query
     */
    ListingQuery(final EntityListing listing, final FilterQueries conditions)
    {
        this.listing = listing;
        this.conditions = conditions;
    }

    /**
     * The entities the listing asks for.
     *
     * @return a query over the entities' documents
     * @throws RepositoryException if a value compared against cannot be read
     */
    Query entities() throws RepositoryException
    {
        final BooleanQuery.Builder query = new BooleanQuery.Builder()
            .add(kind(IndexFields.ENTITY_KIND), Occur.FILTER)
            .add(new TermQuery(new Term(IndexFields.TYPES_FIELD, this.listing.entityType())), Occur.FILTER);
        addConditions(query, this.listing.filters());
        for (final DescendantFilters family : this.listing.descendants()) {
            query.add(new ToParentBlockJoinQuery(descendants(family), ENTITY_DOCUMENTS, ScoreMode.None), Occur.FILTER);
        }
        return query.build();
    }

    /**
     * The descendants that meet a family's conditions, whichever entity they belong to.
     *
     * @param family one of the listing's descendant families
     * @return a query over the descendants' documents
     * @throws RepositoryException if a value compared against cannot be read
     */
    Query descendants(final DescendantFilters family) throws RepositoryException
    {
        final BooleanQuery.Builder query = new BooleanQuery.Builder()
            .add(kind(IndexFields.DESCENDANT_KIND), Occur.FILTER)
            .add(new TermQuery(new Term(IndexFields.TYPES_FIELD, family.type())), Occur.FILTER);
        addConditions(query, family.filters());
        return query.build();
    }

    private void addConditions(final BooleanQuery.Builder query, final List<Filter> filters)
        throws RepositoryException
    {
        for (final List<Filter> group : Filter.groups(filters)) {
            final BooleanQuery.Builder anyOf = new BooleanQuery.Builder();
            for (final Filter filter : group) {
                anyOf.add(this.conditions.of(filter), Occur.SHOULD);
            }
            query.add(anyOf.build(), Occur.FILTER);
        }
    }

    private static Query kind(final String kind)
    {
        return new TermQuery(new Term(IndexFields.KIND_FIELD, kind));
    }
}
