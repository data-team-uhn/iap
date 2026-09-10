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

import java.util.Map;
import java.util.Optional;

import org.apache.jackrabbit.api.security.user.Authorizable;
import org.jetbrains.annotations.NotNull;

/**
 * Reads and changes what is recorded about a person. Everything goes through here rather than through the repository
 * directly, for two reasons: the per-field read and write rules live in the catalogue and have to be applied
 * somewhere, and user homes are no longer readable by everyone, so reaching anybody else's account needs the
 * platform's own credentials rather than the caller's.
 *
 * <p>
 * The {@link Authorizable}-shaped methods exist for callers that already hold an account and want one value, such as
 * resolving a person's preferred language; the id-shaped ones look the account up and are what the HTTP endpoint uses.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface UserProfileService
{
    /**
     * Describes one account's profile as the requester is allowed to see it.
     *
     * @param accountId the account to describe
     * @param requester who is asking
     * @return the projection, or empty when there is no such account
     */
    @NotNull
    Optional<ProfileProjection> read(@NotNull String accountId, @NotNull Requester requester);

    /**
     * Changes fields of one account's profile, all of them or none.
     *
     * @param accountId the account to change
     * @param requester who is asking
     * @param values the new values, keyed by the field name the catalogue knows; an entry with no values unsets the
     *            field
     * @return what came of it, or empty when there is no such account
     */
    @NotNull
    Optional<UpdateOutcome> update(@NotNull String accountId, @NotNull Requester requester,
        @NotNull Map<String, String[]> values);

    /**
     * Reads one field of one account, ignoring who is asking. For platform code acting on somebody's behalf, such as
     * deciding which language to answer them in; anything serving a request should go through
     * {@link #read(String, Requester)} instead, so that the read rules are applied.
     *
     * @param account the account to read
     * @param fieldName the field name the catalogue knows
     * @return the first recorded value, or empty when nothing is recorded or the catalogue has no such field
     */
    @NotNull
    Optional<String> getValue(@NotNull Authorizable account, @NotNull String fieldName);
}
