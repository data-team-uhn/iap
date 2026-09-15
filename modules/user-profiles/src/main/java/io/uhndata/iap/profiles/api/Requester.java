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

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

/**
 * Who is asking, reduced to the two things any profile decision needs: which account they are signed in as, and which
 * principals they hold.
 *
 * <p>
 * The principals are the whole point of taking them as a set of names rather than looking up group membership.
 * Identity provider roles arrive as dynamic principals and never become local group nodes, while the roles a site
 * manager maintains are ordinary Oak groups; a permission decision that reads principal names cannot tell the two
 * apart, and must not, since that is what lets an institution's roles and this platform's roles be used
 * interchangeably.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class Requester
{
    private final String id;

    private final Set<String> principalNames;

    /**
     * Basic constructor.
     *
     * @param id the account the request is made as
     * @param principalNames every principal that account holds, including its own
     */
    public Requester(@NotNull final String id, @NotNull final Collection<String> principalNames)
    {
        this.id = id;
        this.principalNames = Set.copyOf(principalNames);
    }

    /**
     * Convenience constructor for a request made with no group principals worth speaking of.
     *
     * @param id the account the request is made as
     */
    public Requester(@NotNull final String id)
    {
        this(id, List.of(id));
    }

    /**
     * The account the request is made as.
     *
     * @return an authorizable id
     */
    @NotNull
    public String getId()
    {
        return this.id;
    }

    /**
     * Every principal this account holds.
     *
     * @return principal names, never empty in practice since an account always holds its own
     */
    @NotNull
    public Set<String> getPrincipalNames()
    {
        return this.principalNames;
    }

    /**
     * Whether this account holds any of the given principals.
     *
     * @param candidates the principal names to look for
     * @return {@code true} if at least one of them is held
     */
    public boolean holdsAnyOf(@NotNull final Collection<String> candidates)
    {
        return candidates.stream().anyMatch(this.principalNames::contains);
    }

    /**
     * Whether this is the account being looked at.
     *
     * @param accountId the account being looked at
     * @return {@code true} when somebody is looking at their own profile
     */
    public boolean is(@NotNull final String accountId)
    {
        return this.id.equals(accountId);
    }
}
