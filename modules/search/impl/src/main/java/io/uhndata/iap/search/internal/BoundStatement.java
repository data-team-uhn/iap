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
package io.uhndata.iap.search.internal;

import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.ValueFactory;
import javax.jcr.query.Query;

/**
 * A JCR-SQL2 statement and the values of the bind variables it names.
 *
 * <p>
 * A statement this module builds writes no value from the request into its text. Every value is bound. Log the
 * statement, never the bindings, which hold what the user typed. A statement the client sent whole binds nothing.
 * </p>
 *
 * @param statement the statement, which may name bind variables
 * @param bindings the value of each variable, keyed by name without the {@code $}; empty for a statement the caller
 *            sent whole
 *
 * @version $Id$
 * @since 0.1.0
 */
record BoundStatement(String statement, Map<String, String> bindings)
{
    /**
     * Creates the query, with every variable it names given its value.
     *
     * @param session the session to create the query in
     * @return a query ready to execute
     * @throws RepositoryException if the statement is invalid
     */
    Query createQuery(final Session session) throws RepositoryException
    {
        return create(session, this.statement);
    }

    /**
     * Creates the query that reports this statement's plan instead of running it. The values are bound here too:
     * the explain of a statement with an unbound variable fails, as running the statement would.
     *
     * @param session the session to plan the query in
     * @return a query whose single row holds the plan
     * @throws RepositoryException if the statement is invalid
     */
    Query explain(final Session session) throws RepositoryException
    {
        return create(session, "explain " + this.statement);
    }

    private Query create(final Session session, final String text) throws RepositoryException
    {
        final Query query = session.getWorkspace().getQueryManager().createQuery(text, Query.JCR_SQL2);
        final ValueFactory values = session.getValueFactory();
        for (final Map.Entry<String, String> binding : this.bindings.entrySet()) {
            query.bindValue(binding.getKey(), values.createValue(binding.getValue()));
        }
        return query;
    }
}
