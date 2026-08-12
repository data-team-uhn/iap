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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.jcr.RepositoryException;
import javax.jcr.ValueFactory;

import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.profiles.api.ProfileProjection;
import io.uhndata.iap.profiles.api.Requester;
import io.uhndata.iap.profiles.api.UpdateOutcome;
import io.uhndata.iap.profiles.api.UserProfileService;
import io.uhndata.iap.profiles.models.ProfileFieldDefinition;
import io.uhndata.iap.profiles.models.ProfileFieldsHomepage;

/**
 * Reads and changes profiles with the platform's own credentials, applying the catalogue's per-field rules itself.
 *
 * <p>
 * It has to own the repository access rather than borrow the caller's session, for two independent reasons. User homes
 * are not readable by everyone, so reaching somebody else's account at all needs rights the requester does not have.
 * And an account synchronized from an identity provider is protected at commit time against every session but a system
 * one, so this service user is the only thing that can write inside one -- which is what makes "this field is imported
 * and read-only" a rule rather than a suggestion.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = UserProfileService.class)
@Designate(ocd = UserProfilesConfig.class)
public class UserProfileServiceImpl implements UserProfileService
{
    /** The one account that is always a user administrator, whatever the configuration says. */
    private static final String SUPERUSER = "admin";

    private static final Logger LOGGER = LoggerFactory.getLogger(UserProfileServiceImpl.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    private Set<String> administratorPrincipals = Set.of();

    private Accounts accounts;

    /** Constructor for OSGi, which injects the resolver factory into the field above. */
    public UserProfileServiceImpl()
    {
        // Everything is injected
    }

    /**
     * Constructor taking the account access to use, so that tests need neither an OSGi container nor a repository with
     * user management in it.
     *
     * @param accounts reaches accounts with the platform's own credentials
     */
    UserProfileServiceImpl(@NotNull final Accounts accounts)
    {
        this.accounts = accounts;
    }

    /**
     * Records the configured administrator principals, and takes the injected resolver factory into use.
     *
     * @param config the component's configuration
     */
    @Activate
    void activate(final UserProfilesConfig config)
    {
        this.administratorPrincipals = Set.of(config.administratorPrincipals());
        if (this.accounts == null) {
            this.accounts = new Accounts(this.resolverFactory);
        }
    }

    @Override
    @NotNull
    public Optional<ProfileProjection> read(@NotNull final String accountId, @NotNull final Requester requester)
    {
        try (ResourceResolver resolver = this.accounts.open()) {
            final Authorizable account = this.accounts.find(resolver, accountId);
            final ProfileFieldsHomepage catalogue = catalogue(resolver);
            if (account == null || catalogue == null) {
                return Optional.empty();
            }
            return Optional.of(project(account, requester, catalogue));
        } catch (final LoginException | RepositoryException e) {
            LOGGER.warn("Cannot read the profile of {}: {}", accountId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    @NotNull
    public Optional<UpdateOutcome> update(@NotNull final String accountId, @NotNull final Requester requester,
        @NotNull final Map<String, String[]> values)
    {
        try (ResourceResolver resolver = this.accounts.open()) {
            final Authorizable account = this.accounts.find(resolver, accountId);
            final ProfileFieldsHomepage catalogue = catalogue(resolver);
            if (account == null || catalogue == null) {
                return Optional.empty();
            }
            final boolean self = requester.is(account.getID());
            final boolean admin = isAdministrator(requester);
            if (!self && !admin) {
                // Not about any one field: somebody who is neither the person nor a user administrator has no
                // business writing here at all, and saying so once is clearer than refusing every field in turn
                return Optional.of(UpdateOutcome.forbidden("this is not your profile to change"));
            }
            final boolean external = AccountFacts.externalIdp(account) != null;
            final Map<String, String> refused = refusals(values, catalogue, external, self, admin);
            if (!refused.isEmpty()) {
                // Nothing is written when anything is refused: a profile half saved is worse to hand back than one
                // that was turned down, because the person cannot tell which half took
                return Optional.of(new UpdateOutcome(List.of(), refused));
            }
            return Optional.of(apply(resolver, account, catalogue, values));
        } catch (final LoginException | RepositoryException e) {
            LOGGER.warn("Cannot change the profile of {}: {}", accountId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    @NotNull
    public Optional<String> getValue(@NotNull final Authorizable account, @NotNull final String fieldName)
    {
        try (ResourceResolver resolver = this.accounts.open()) {
            final ProfileFieldsHomepage catalogue = catalogue(resolver);
            if (catalogue == null) {
                return Optional.empty();
            }
            final Optional<ProfileFieldDefinition> definition = catalogue.getDefinition(fieldName);
            if (definition.isEmpty()) {
                return Optional.empty();
            }
            return AccountFacts.storedValues(account, definition.get().getStorage()).stream().findFirst();
        } catch (final LoginException | RepositoryException e) {
            LOGGER.warn("Cannot read the {} of an account: {}", fieldName, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * Reads the catalogue of what may be recorded.
     *
     * @param resolver a resolver holding the platform's credentials
     * @return the catalogue, or {@code null} when the instance has none, which means a broken installation
     */
    @Nullable
    private ProfileFieldsHomepage catalogue(@NotNull final ResourceResolver resolver)
    {
        final Resource resource = resolver.getResource(ProfileFieldsHomepage.PATH);
        return resource == null ? null : resource.adaptTo(ProfileFieldsHomepage.class);
    }

    /**
     * Whether the requester may act on anybody's profile.
     *
     * @param requester who is asking
     * @return {@code true} for a user administrator
     */
    private boolean isAdministrator(@NotNull final Requester requester)
    {
        return SUPERUSER.equals(requester.getId()) || requester.holdsAnyOf(this.administratorPrincipals);
    }

    /**
     * Assembles one account's profile as the requester may see it.
     *
     * @param account the account being described
     * @param requester who is asking
     * @param catalogue what may be recorded
     * @return the projection
     * @throws RepositoryException if the account cannot be read
     */
    @NotNull
    private ProfileProjection project(@NotNull final Authorizable account, @NotNull final Requester requester,
        @NotNull final ProfileFieldsHomepage catalogue) throws RepositoryException
    {
        return ProfileRules.project(account, AccountFacts.externalIdp(account), requester.is(account.getID()),
            isAdministrator(requester), catalogue);
    }

    /**
     * Checks a whole request without writing anything.
     *
     * @param values what was asked for
     * @param catalogue what may be recorded
     * @param external whether the account comes from an identity provider
     * @param self whether the requester is the person themselves
     * @param admin whether the requester is a user administrator
     * @return the fields that cannot be written, each with the reason, empty when the request can be carried out
     */
    @NotNull
    private Map<String, String> refusals(@NotNull final Map<String, String[]> values,
        @NotNull final ProfileFieldsHomepage catalogue, final boolean external, final boolean self,
        final boolean admin)
    {
        final Map<String, String> refused = new LinkedHashMap<>();
        for (final Map.Entry<String, String[]> asked : values.entrySet()) {
            final Optional<ProfileFieldDefinition> definition = catalogue.getDefinition(asked.getKey());
            if (definition.isEmpty()) {
                refused.put(asked.getKey(), "this instance records no such thing");
                continue;
            }
            final String reason = firstProblem(definition.get(), asked.getValue(), external, self, admin);
            if (reason != null) {
                refused.put(asked.getKey(), reason);
            }
        }
        return refused;
    }

    /**
     * The first thing standing in the way of recording one field, whether that is who is asking or what they asked.
     *
     * @param definition what the catalogue says
     * @param asked the new values
     * @param external whether the account comes from an identity provider
     * @param self whether the requester is the person themselves
     * @param admin whether the requester is a user administrator
     * @return the reason, or {@code null} when there is none
     */
    @Nullable
    private String firstProblem(@NotNull final ProfileFieldDefinition definition, @Nullable final String[] asked,
        final boolean external, final boolean self, final boolean admin)
    {
        final String forbidden = ProfileRules.mayWrite(definition, external, self, admin);
        return forbidden == null ? ValueRules.rejects(definition, asked) : forbidden;
    }

    /**
     * Writes a request that has already been checked.
     *
     * @param resolver a resolver holding the platform's credentials
     * @param account the account to change
     * @param catalogue what may be recorded
     * @param values what was asked for
     * @return what was written
     * @throws RepositoryException if the changes are refused by the repository
     */
    @NotNull
    private UpdateOutcome apply(@NotNull final ResourceResolver resolver, @NotNull final Authorizable account,
        @NotNull final ProfileFieldsHomepage catalogue, @NotNull final Map<String, String[]> values)
        throws RepositoryException
    {
        final ValueFactory factory = this.accounts.values(resolver);
        final List<String> changed = new ArrayList<>();
        for (final Map.Entry<String, String[]> asked : values.entrySet()) {
            final ProfileFieldDefinition definition = catalogue.getDefinition(asked.getKey()).orElseThrow();
            if (AccountFacts.record(account, factory, definition.getStorage(),
                ValueRules.stated(asked.getValue()), definition.isMultiple())) {
                changed.add(definition.getName());
            }
        }
        if (!changed.isEmpty()) {
            this.accounts.save(resolver);
        }
        return new UpdateOutcome(changed, Map.of());
    }
}
