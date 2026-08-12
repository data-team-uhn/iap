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
package io.uhndata.iap.profiles.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.ValueFactory;

import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.util.Text;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Everything this module needs to know about an account, and the only place that touches one. Kept apart from the
 * service so that the policy -- who may read and change what -- reads as policy, with none of the repository's shape
 * mixed into it.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class AccountFacts
{
    /**
     * Oak's own record of which identity provider an account came from, as {@code <id>;<provider>} with both halves
     * escaped. Read directly rather than through Oak's external authentication bundle, so that this module needs no
     * compile dependency on it; the name and format are {@code ExternalIdentityConstants.REP_EXTERNAL_ID} and
     * {@code ExternalIdentityRef}, and Oak protects the property against everything but its own synchronisation.
     */
    static final String REP_EXTERNAL_ID = "rep:externalId";

    /** Where the dynamic principals of a synchronized account are kept, since they never become local groups. */
    static final String REP_EXTERNAL_PRINCIPAL_NAMES = "rep:externalPrincipalNames";

    private AccountFacts()
    {
        // Utility class
    }

    /**
     * The identity provider an account comes from.
     *
     * @param account the account to look at
     * @return the provider name, an empty string when the record is there but says nothing, {@code null} for a local
     *         account
     * @throws RepositoryException if the account cannot be read
     */
    @Nullable
    static String externalIdp(@NotNull final Authorizable account) throws RepositoryException
    {
        final Value[] recorded = account.getProperty(REP_EXTERNAL_ID);
        if (recorded == null || recorded.length == 0) {
            return null;
        }
        final String reference = recorded[0].getString();
        final int separator = reference.indexOf(';');
        return separator < 0 ? "" : Text.unescape(reference.substring(separator + 1));
    }

    /**
     * Every principal an account holds. Both halves are read on purpose: the roles a site manager maintains are
     * ordinary groups, while the ones an identity provider asserts are dynamic and never become groups at all, and
     * anybody asking what somebody may do needs to see them as one list.
     *
     * @param account the account to look at
     * @return principal names, including the account's own
     * @throws RepositoryException if the account cannot be read
     */
    @NotNull
    static Set<String> principalNames(@NotNull final Authorizable account) throws RepositoryException
    {
        final Set<String> names = new TreeSet<>();
        names.add(account.getPrincipal().getName());
        final Iterator<Group> groups = account.memberOf();
        while (groups.hasNext()) {
            names.add(groups.next().getPrincipal().getName());
        }
        final Value[] dynamic = account.getProperty(REP_EXTERNAL_PRINCIPAL_NAMES);
        if (dynamic != null) {
            for (final Value value : dynamic) {
                names.add(value.getString());
            }
        }
        return names;
    }

    /**
     * What is recorded at one place on an account.
     *
     * @param account the account to read
     * @param where a path relative to the account's home node
     * @return the values, empty when nothing is recorded
     * @throws RepositoryException if the account cannot be read
     */
    @NotNull
    static List<String> storedValues(@NotNull final Authorizable account, @NotNull final String where)
        throws RepositoryException
    {
        final Value[] recorded = account.getProperty(where);
        if (recorded == null) {
            return List.of();
        }
        final List<String> values = new ArrayList<>();
        for (final Value value : recorded) {
            values.add(value.getString());
        }
        return values;
    }

    /**
     * Records values at one place on an account, if they are not already what is there.
     *
     * @param account the account to change
     * @param factory turns strings into storable values
     * @param where a path relative to the account's home node
     * @param wanted the values to record, empty to record nothing at all
     * @param multiple whether the field accepts more than one value, which decides whether a single value is stored on
     *            its own or as a one-element list
     * @return {@code true} if anything changed
     * @throws RepositoryException if the change is refused
     */
    static boolean record(@NotNull final Authorizable account, @NotNull final ValueFactory factory,
        @NotNull final String where, @NotNull final List<String> wanted, final boolean multiple)
        throws RepositoryException
    {
        if (storedValues(account, where).equals(wanted)) {
            return false;
        }
        if (wanted.isEmpty()) {
            account.removeProperty(where);
        } else if (multiple) {
            account.setProperty(where, wanted.stream().map(factory::createValue).toArray(Value[]::new));
        } else {
            account.setProperty(where, factory.createValue(wanted.get(0)));
        }
        return true;
    }
}
