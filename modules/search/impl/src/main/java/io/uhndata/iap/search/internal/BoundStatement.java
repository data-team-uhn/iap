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
 * A JCR-SQL2 statement together with the value of every bind variable it names, and the only place those values are
 * put back together with it.
 *
 * <p>
 * A statement this endpoint generates holds no value the caller sent: the text to search for is bound instead, so
 * nothing has to be escaped for a string literal it never enters, and the statement depends on the shape of the
 * request rather than its content — which is also what makes the statement safe to log while the values are not. A
 * statement the caller sent whole carries its own values already and binds nothing.
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
     * @throws RepositoryException if the statement is invalid, or names a variable with no value here
     */
    Query createQuery(final Session session) throws RepositoryException
    {
        return create(session, this.statement);
    }

    /**
     * Creates the query that reports this statement's plan instead of running it. The values are bound here too: a
     * statement whose variables have none does not run at all, so a plan asked for without them would fail for every
     * generated query and report nothing.
     *
     * @param session the session to plan the query in
     * @return a query that, executed, yields the plan
     * @throws RepositoryException if the decorated statement is invalid
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
