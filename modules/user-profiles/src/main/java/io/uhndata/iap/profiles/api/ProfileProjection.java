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
package io.uhndata.iap.profiles.api;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One account's profile as somebody asking is allowed to see it: the catalogue, the recorded values, and a verdict per
 * field. This is a projection assembled per request rather than a serialization of one node, which is why a later move
 * of where values are stored, or the arrival of a version history, need not change what a client sees.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class ProfileProjection
{
    private final String accountId;

    private final String idpName;

    private final java.util.SortedSet<String> principalNames;

    private final List<ProfileField> fields;

    private final String json;

    /**
     * Basic constructor.
     *
     * @param accountId the account being described
     * @param idpName the identity provider the account comes from, {@code null} for a local account
     * @param principalNames every principal the account holds
     * @param fields the fields, in the catalogue's display order
     * @param json the whole thing as it is served, built where the catalogue definitions were still in hand
     */
    public ProfileProjection(@NotNull final String accountId, @Nullable final String idpName,
        @NotNull final Set<String> principalNames, @NotNull final List<ProfileField> fields,
        @NotNull final String json)
    {
        this.accountId = accountId;
        this.idpName = idpName;
        this.principalNames = Collections.unmodifiableSortedSet(new TreeSet<>(principalNames));
        this.fields = List.copyOf(fields);
        this.json = json;
    }

    /**
     * The account being described.
     *
     * @return an authorizable id
     */
    @NotNull
    public String getAccountId()
    {
        return this.accountId;
    }

    /**
     * The identity provider this account comes from.
     *
     * @return the provider name, or {@code null} for a local account
     */
    @Nullable
    public String getIdpName()
    {
        return this.idpName;
    }

    /**
     * Whether this account is managed by an identity provider, which is what makes the fields it supplies read-only.
     *
     * @return {@code true} for a synchronized account
     */
    public boolean isExternal()
    {
        return this.idpName != null;
    }

    /**
     * Every principal the account holds, identity provider roles and local groups alike.
     *
     * @return principal names, sorted so that the served JSON does not change from one request to the next
     */
    @NotNull
    public Set<String> getPrincipalNames()
    {
        return this.principalNames;
    }

    /**
     * The fields, in the catalogue's display order.
     *
     * @return the projected fields
     */
    @NotNull
    public List<ProfileField> getFields()
    {
        return this.fields;
    }

    /**
     * The whole projection as it is served.
     *
     * @return a JSON document
     */
    @NotNull
    public String asJson()
    {
        return this.json;
    }
}
