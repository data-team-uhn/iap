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
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.apache.jackrabbit.api.security.user.Authorizable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.profiles.api.ProfileField;
import io.uhndata.iap.profiles.api.ProfileProjection;
import io.uhndata.iap.profiles.models.ProfileFieldDefinition;
import io.uhndata.iap.profiles.models.ProfileFieldDefinition.Writability;
import io.uhndata.iap.profiles.models.ProfileFieldsHomepage;

/**
 * Who may read and change what, and what one account's profile looks like once that is applied. All of it decided from
 * the catalogue and three facts about the person asking, with nothing here that touches a repository beyond reading the
 * values, so the rules can be read as rules.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ProfileRules
{
    private static final String NOT_ADMIN = "only a user administrator may change this field";

    private ProfileRules()
    {
        // Utility class
    }

    /**
     * Assembles one account's profile as the requester may see it.
     *
     * @param account the account being described
     * @param idp the identity provider the account comes from, {@code null} for a local account
     * @param self whether the requester is the person themselves
     * @param admin whether the requester is a user administrator
     * @param catalogue what may be recorded
     * @return the projection
     * @throws RepositoryException if the account cannot be read
     */
    @NotNull
    static ProfileProjection project(@NotNull final Authorizable account, @Nullable final String idp,
        final boolean self, final boolean admin, @NotNull final ProfileFieldsHomepage catalogue)
        throws RepositoryException
    {
        final List<ProfileField> fields = new ArrayList<>();
        final JsonArrayBuilder serialized = Json.createArrayBuilder();
        for (final ProfileFieldDefinition definition : catalogue.getDefinitions()) {
            final ProfileField field = projectField(definition, account, idp != null, self, admin);
            fields.add(field);
            serialized.add(describe(definition, field));
        }
        final Set<String> principals = AccountFacts.principalNames(account);
        final JsonArrayBuilder held = Json.createArrayBuilder();
        principals.forEach(held::add);
        final String json = Json.createObjectBuilder()
            .add("account", account.getID())
            .add("external", idp != null)
            .add("idp", idp == null ? "" : idp)
            // What a persona chooser will read: whether somebody may act as a reviewer is a question about the
            // principals they hold, and this is the one place that already has to know them
            .add("principals", held)
            .add("fields", serialized)
            .build().toString();
        return new ProfileProjection(account.getID(), idp, principals, fields, json);
    }

    /**
     * Serializes one field, definition and verdict together, so that a client has everything it needs to render one
     * control without a second request and without repeating the rules. The definition is always described, even when
     * the value is withheld: the catalogue is public, so what may be recorded is not a secret, and a form that
     * silently omitted a field would be lying about its own shape.
     *
     * @param definition what the catalogue says
     * @param field what was resolved for this requester
     * @return a JSON object
     */
    @NotNull
    private static JsonObjectBuilder describe(@NotNull final ProfileFieldDefinition definition,
        @NotNull final ProfileField field)
    {
        final JsonObjectBuilder json = definition.documentationJsonBuilder()
            .add("name", field.getName())
            .add("readable", field.isReadable())
            .add("editable", field.isEditable())
            .add("provenance", field.getProvenance().name().toLowerCase(Locale.ROOT));
        if (field.isReadable()) {
            final JsonArrayBuilder recorded = Json.createArrayBuilder();
            field.getValues().forEach(recorded::add);
            json.add("values", recorded);
        }
        final List<String> problems = definition.getConfigurationProblems();
        if (!problems.isEmpty()) {
            final JsonArrayBuilder reported = Json.createArrayBuilder();
            problems.forEach(reported::add);
            json.add("problems", reported);
        }
        return json;
    }

    /**
     * Projects one field, resolving what the requester may do with it.
     *
     * @param definition what the catalogue says
     * @param account the account being described
     * @param external whether the account comes from an identity provider
     * @param self whether the requester is the person themselves
     * @param admin whether the requester is a user administrator
     * @return the projected field
     * @throws RepositoryException if the value cannot be read
     */
    @NotNull
    private static ProfileField projectField(@NotNull final ProfileFieldDefinition definition,
        @NotNull final Authorizable account, final boolean external, final boolean self, final boolean admin)
        throws RepositoryException
    {
        final boolean readable = definition.isUsable() && mayRead(definition, self, admin);
        final boolean editable = mayWrite(definition, external, self, admin) == null;
        // A field the requester may not read is described as if nothing were recorded: saying that there is something
        // would already be telling them something
        final List<String> values =
            readable ? AccountFacts.storedValues(account, definition.getStorage()) : List.of();
        return new ProfileField(definition.getName(), values, readable, editable,
            provenance(definition, external, values));
    }

    /**
     * Whether the requester may see a field's value. Only ever asked of a definition that is sound, so the rule is
     * known to be one of the three.
     *
     * @param definition what the catalogue says
     * @param self whether the requester is the person themselves
     * @param admin whether the requester is a user administrator
     * @return {@code true} if the value is theirs to read
     */
    private static boolean mayRead(@NotNull final ProfileFieldDefinition definition, final boolean self,
        final boolean admin)
    {
        return switch (definition.getReadableBy()) {
            case AUTHENTICATED -> true;
            case SELF -> self || admin;
            case ADMIN -> admin;
        };
    }

    /**
     * Why the requester may not change a field, if they may not.
     *
     * @param definition what the catalogue says
     * @param external whether the account comes from an identity provider
     * @param self whether the requester is the person themselves
     * @param admin whether the requester is a user administrator
     * @return the reason, worded for whoever is looking at the form, or {@code null} when they may
     */
    @Nullable
    static String mayWrite(@NotNull final ProfileFieldDefinition definition, final boolean external,
        final boolean self, final boolean admin)
    {
        if (!definition.isUsable()) {
            return "this field is misconfigured: " + String.join("; ", definition.getConfigurationProblems());
        }
        if (definition.isSystem()) {
            return "this field is maintained by the platform";
        }
        if (external && definition.getIdpClaim() != null) {
            return "this comes from your institutional account, and has to be changed there";
        }
        return whoMayWrite(definition.getWritableBy(), self, admin);
    }

    /**
     * Applies a field's write rule. Only ever asked of a definition that is sound, so the rule is known to be one of
     * the three.
     *
     * @param rule what the catalogue says
     * @param self whether the requester is the person themselves
     * @param admin whether the requester is a user administrator
     * @return the reason they may not, or {@code null} when they may
     */
    @Nullable
    private static String whoMayWrite(@NotNull final Writability rule, final boolean self, final boolean admin)
    {
        return switch (rule) {
            case OWNER -> self || admin ? null : NOT_ADMIN;
            case ADMIN -> admin ? null : NOT_ADMIN;
            case NOBODY -> "nothing may change this field";
        };
    }

    /**
     * Where a value came from, as far as can be told without recording it.
     *
     * @param definition what the catalogue says
     * @param external whether the account comes from an identity provider
     * @param values what is recorded
     * @return the provenance
     */
    @NotNull
    private static ProfileField.Provenance provenance(@NotNull final ProfileFieldDefinition definition,
        final boolean external, @NotNull final List<String> values)
    {
        if (values.isEmpty()) {
            return ProfileField.Provenance.UNSET;
        }
        if (definition.isSystem()) {
            return ProfileField.Provenance.PLATFORM;
        }
        if (external && definition.getIdpClaim() != null) {
            return ProfileField.Provenance.IDP;
        }
        return ProfileField.Provenance.LOCAL;
    }
}
