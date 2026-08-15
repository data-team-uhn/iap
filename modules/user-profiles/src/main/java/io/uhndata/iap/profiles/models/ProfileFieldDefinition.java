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
package io.uhndata.iap.profiles.models;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.autodoc.api.DocumentedItem;
import io.uhndata.iap.content.models.Content;

/**
 * A Sling Model wrapping an {@code iap:ProfileFieldDefinition} node, the definition of one thing this instance records
 * about a person: what it means, where its value lives on the account, and who may read and change it. The value
 * itself is not held here, but as a property under the account's home node at {@link #getStorage()}.
 *
 * <p>
 * The vocabularies are parsed rather than trusted, and they fail closed. An absent value takes the default declared by
 * the node type, which is where those defaults belong. A value outside the accepted set is a broken definition rather
 * than a synonym for the default: the getters still answer, so that nothing has to cope with a half-read definition,
 * but {@link #isUsable()} turns false and {@link #getConfigurationProblems()} explains what is wrong, and the profile
 * API refuses such a field rather than guessing at an intent nobody expressed.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = ProfileFieldDefinition.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ProfileFieldDefinition extends Content implements DocumentedItem
{
    /** The {@code sling:resourceType} of an {@code iap:ProfileFieldDefinition} node. */
    public static final String RESOURCE_TYPE = "iap/ProfileFieldDefinition";

    /**
     * Sorts field definitions in their intended display sequence: by their explicit {@link #getOrder() order} first,
     * definitions without an order last, ties broken by comparing labels.
     */
    public static final Comparator<ProfileFieldDefinition> DISPLAY_ORDER =
        Comparator.comparing(ProfileFieldDefinition::getOrder, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ProfileFieldDefinition::getLabel);

    /**
     * Whether a field describes the person or says how they want the application to behave.
     *
     * @since 0.1.0
     */
    public enum Kind
    {
        /** A fact about the person, stored under {@code profile/} by default. */
        PROFILE,
        /** A way the person wants the application to behave, stored under {@code preferences/} by default. */
        PREFERENCE
    }

    /**
     * The expected type of a field's value. Deliberately {@code sch:Question}'s vocabulary minus {@code file}, so
     * that the two can be consolidated later.
     *
     * @since 0.1.0
     */
    public enum DataType
    {
        /** A string. */
        TEXT,
        /** A whole number. */
        LONG,
        /** A decimal number. */
        DOUBLE,
        /** A yes-or-no value. */
        BOOLEAN,
        /** A date. */
        DATE
    }

    /**
     * Who may change a field, through the profile API.
     *
     * @since 0.1.0
     */
    public enum Writability
    {
        /** The person themselves, and user administrators. */
        OWNER,
        /** Only user administrators. */
        ADMIN,
        /** Nobody: the platform maintains the value. */
        NOBODY
    }

    /**
     * Who may read a field's value.
     *
     * @since 0.1.0
     */
    public enum Readability
    {
        /** Anyone signed in, which is what a staff directory entry amounts to. */
        AUTHENTICATED,
        /** The person themselves, and user administrators. */
        SELF,
        /** Only user administrators. */
        ADMIN
    }

    private static final String PROFILE_PREFIX = "profile/";

    private static final String PREFERENCES_PREFIX = "preferences/";

    private static final String NOT_ONE_OF = "`, which is not one of: ";

    /**
     * Everything wrong with this definition, worked out once. Every field of every profile is asked whether it is
     * usable several times over while one request is served, and the answer involves parsing four vocabularies and
     * compiling a regular expression, none of which can change while this view of the repository lasts.
     */
    private List<String> problems;

    @ValueMapValue
    private String name;

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private String kind;

    @ValueMapValue
    private String storage;

    @ValueMapValue
    private String dataType;

    @ValueMapValue
    private String pattern;

    @ValueMapValue
    private String[] allowedValues;

    @ValueMapValue
    private boolean required;

    @ValueMapValue
    private boolean multiple;

    @ValueMapValue
    private String writableBy;

    @ValueMapValue
    private String readableBy;

    @ValueMapValue
    private String idpClaim;

    @ValueMapValue(name = "category")
    private String[] categories;

    @ValueMapValue
    private Long order;

    @ValueMapValue
    private boolean system;

    /**
     * Reads one of the definition's vocabularies, answering nothing both for a value that was not stated and for one
     * that is not a word we know. The two are told apart by the caller, which is what lets a getter always answer
     * while {@link #isUsable()} still refuses a definition nobody can act on.
     *
     * @param <E> the vocabulary
     * @param type the vocabulary's class
     * @param value the stored value, matched without regard to case
     * @return the parsed constant, or empty when nothing usable is stated
     */
    @NotNull
    private static <E extends Enum<E>> Optional<E> lookup(@NotNull final Class<E> type,
        @Nullable final String value)
    {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT)));
        } catch (final IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Parses one of the definition's vocabularies, distinguishing "not stated" from "not a word we know".
     *
     * @param <E> the vocabulary
     * @param type the vocabulary's class
     * @param value the stored value, matched without regard to case
     * @param fallback what an absent value means, which is the default declared by the node type
     * @return the parsed constant, or the fallback if a value was stated and is not one of them
     */
    @NotNull
    private static <E extends Enum<E>> E parse(@NotNull final Class<E> type, @Nullable final String value,
        @NotNull final E fallback)
    {
        return lookup(type, value).orElse(fallback);
    }

    /**
     * Reports one of the vocabularies if it was stated as something that is not in it. Kept apart from {@link #parse}
     * so that the getters can always answer, and the decision to refuse a field is taken once, by {@link #isUsable()}.
     *
     * @param <E> the vocabulary
     * @param type the vocabulary's class
     * @param value the stored value
     * @param property the property the value came from, quoted as it is written in content
     * @param found the problems being collected
     */
    private static <E extends Enum<E>> void checkVocabulary(@NotNull final Class<E> type,
        @Nullable final String value, @NotNull final String property, @NotNull final List<String> found)
    {
        if (value != null && !value.isBlank() && lookup(type, value).isEmpty()) {
            found.add(property + " is `" + value + NOT_ONE_OF + accepted(type));
        }
    }

    /**
     * Lists a vocabulary the way it is written in content, for a diagnostic that can be acted on.
     *
     * @param <E> the vocabulary
     * @param type the vocabulary's class
     * @return the accepted values, lowercase and comma-separated
     */
    @NotNull
    private static <E extends Enum<E>> String accepted(@NotNull final Class<E> type)
    {
        return Arrays.stream(type.getEnumConstants())
            .map(constant -> constant.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.joining(", "));
    }

    /**
     * The field's identifier, as used by the profile API. This is the definition node's own name, unless overridden by
     * an explicit {@code name} property, which allows identifiers that would be awkward as node names.
     *
     * @return the field name
     */
    @Override
    @NotNull
    public String getName()
    {
        return this.name == null || this.name.isBlank() ? super.getName() : this.name;
    }

    /**
     * The human-readable name displayed in the UI, falling back to the field name when no explicit label is set.
     *
     * @return the display label
     */
    @NotNull
    public String getLabel()
    {
        return this.label == null || this.label.isBlank() ? getName() : this.label;
    }

    /**
     * A longer explanation displayed alongside the field.
     *
     * @return the description, or {@code null} if not set
     */
    @Override
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * Whether this field describes the person or says how they want the application to behave.
     *
     * @return the kind, the node type's default when nothing usable is stated
     */
    @NotNull
    public Kind getKind()
    {
        return parse(Kind.class, this.kind, Kind.PROFILE);
    }

    /**
     * Where the value lives, as a path relative to the account's home node, e.g. {@code profile/email}. Derived from
     * the field's {@link #getKind() kind} and name unless stated explicitly.
     *
     * @return the relative path
     */
    @NotNull
    public String getStorage()
    {
        if (this.storage != null && !this.storage.isBlank()) {
            return this.storage.trim();
        }
        return (getKind() == Kind.PREFERENCE ? PREFERENCES_PREFIX : PROFILE_PREFIX) + getName();
    }

    /**
     * The expected type of the value.
     *
     * @return the data type, the node type's default when nothing usable is stated
     */
    @NotNull
    public DataType getDataType()
    {
        return parse(DataType.class, this.dataType, DataType.TEXT);
    }

    /**
     * An optional regular expression that a value must match in full.
     *
     * @return the pattern, or {@code null} if values are unrestricted
     */
    @Nullable
    public String getPattern()
    {
        return this.pattern;
    }

    /**
     * An optional closed set of accepted values, offered as a choice in the UI.
     *
     * @return the accepted values, an empty list when the field is not a closed choice
     */
    @NotNull
    public List<String> getAllowedValues()
    {
        return this.allowedValues == null ? List.of() : List.of(this.allowedValues);
    }

    /**
     * Whether a value must be provided.
     *
     * @return {@code true} if the field is required
     */
    public boolean isRequired()
    {
        return this.required;
    }

    /**
     * Whether more than one value may be provided.
     *
     * @return {@code true} if the field is multi-valued
     */
    public boolean isMultiple()
    {
        return this.multiple;
    }

    /**
     * Who may change this field. A statement about the field: a field its owner may change is still read-only for them
     * when their account comes from an identity provider that supplies it.
     *
     * @return the write rule, the node type's default when nothing usable is stated
     */
    @NotNull
    public Writability getWritableBy()
    {
        return parse(Writability.class, this.writableBy, Writability.OWNER);
    }

    /**
     * Who may read this field's value.
     *
     * @return the read rule, the node type's default when nothing usable is stated
     */
    @NotNull
    public Readability getReadableBy()
    {
        return parse(Readability.class, this.readableBy, Readability.SELF);
    }

    /**
     * The identity provider claim this field is imported from. Documentation on its own: whether a given person's
     * value is imported depends on whether their account is external.
     *
     * @return the claim name, or {@code null} if this field is never imported
     */
    @Nullable
    public String getIdpClaim()
    {
        return this.idpClaim;
    }

    /**
     * The sections this field is displayed under, e.g. {@code identity} or {@code contact}.
     *
     * @return the categories, an empty list if none are set
     */
    @NotNull
    public List<String> getCategories()
    {
        return this.categories == null ? List.of() : List.of(this.categories);
    }

    /**
     * The optional explicit position of this field in a form; fields with a lower order are displayed first, fields
     * without an order are displayed last.
     *
     * @return the order, or {@code null} if not set
     */
    @Nullable
    public Long getOrder()
    {
        return this.order;
    }

    /**
     * Whether this field is managed by the platform itself, which prevents changing it through the regular
     * user-facing APIs.
     *
     * @return {@code true} if only the platform may set this field
     */
    public boolean isSystem()
    {
        return this.system;
    }

    /**
     * Everything wrong with this definition, worded so that whoever authored the node can act on it. A definition with
     * problems is still served, so that the catalogue does not silently lose a field, but the profile API refuses to
     * write it.
     *
     * @return the problems, an empty list when the definition is sound
     */
    @NotNull
    public List<String> getConfigurationProblems()
    {
        if (this.problems == null) {
            this.problems = List.copyOf(findConfigurationProblems());
        }
        return this.problems;
    }

    /**
     * Works out everything wrong with this definition.
     *
     * @return the problems, an empty list when the definition is sound
     */
    @NotNull
    private List<String> findConfigurationProblems()
    {
        final List<String> found = new ArrayList<>();
        checkVocabulary(Kind.class, this.kind, "`kind`", found);
        checkVocabulary(DataType.class, this.dataType, "`dataType`", found);
        checkVocabulary(Writability.class, this.writableBy, "`writableBy`", found);
        checkVocabulary(Readability.class, this.readableBy, "`readableBy`", found);
        if (this.pattern != null && !this.pattern.isBlank()) {
            try {
                Pattern.compile(this.pattern);
            } catch (final PatternSyntaxException ex) {
                found.add("`pattern` is not a valid regular expression: " + ex.getDescription());
            }
        }
        // Where the value goes is as much a part of a sound definition as the vocabularies are, and it fails closed
        // the same way: a path that steps outside the account would otherwise pass every rule the catalogue has and
        // then be refused at commit time, which the API can only report as though the account were not there.
        // Checked as the path that will actually be read and written rather than as the property alone: with `storage`
        // unstated the path is derived from the field name, and a name can step out of the account just as well
        final String where = getStorage();
        if (!insideTheAccount(where)) {
            found.add("`storage` resolves to `" + where
                + "`, which is not a path inside the account: it has to be relative, and may not step out of it");
        }
        return found;
    }

    /**
     * Whether a storage path stays inside the account it is read against: a relative path of ordinary names, with
     * nothing that walks up out of the home node.
     *
     * @param path the storage path, already trimmed
     * @return {@code true} if the path names something inside the account
     */
    private static boolean insideTheAccount(@NotNull final String path)
    {
        if (path.startsWith("/") || path.endsWith("/")) {
            return false;
        }
        for (final String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether this definition can be acted on at all.
     *
     * @return {@code true} when nothing is wrong with it
     */
    public boolean isUsable()
    {
        return getConfigurationProblems().isEmpty();
    }

    @Override
    @NotNull
    public String getDocumentationLabel()
    {
        return getLabel();
    }

    @Override
    @NotNull
    public List<String> getDocumentationCategories()
    {
        return getCategories();
    }

    @Override
    @NotNull
    public List<String> getDocumentationDetails()
    {
        final List<String> details = new ArrayList<>();
        details.add(typeDetail());
        addRuleDetails(details);
        addRestrictionDetails(details);
        getConfigurationProblems().forEach(problem -> details.add("**Misconfigured**: " + problem));
        return details;
    }

    /**
     * Describes what kind of value this field holds, and how many.
     *
     * @return one documentation bullet
     */
    @NotNull
    private String typeDetail()
    {
        return "**Type**: " + label(getDataType())
            + (isMultiple() ? ", more than one value allowed" : "") + (isRequired() ? ", required" : "");
    }

    /**
     * Describes who may read and change the field, saying nothing where the definition is broken, since the problem
     * itself is reported separately.
     *
     * @param details the bullets being collected
     */
    private void addRuleDetails(@NotNull final List<String> details)
    {
        details.add("**May be changed by**: " + label(getWritableBy()));
        details.add("**May be read by**: " + label(getReadableBy()));
    }

    /**
     * Describes everything that narrows what may be recorded in the field, or where the value comes from.
     *
     * @param details the bullets being collected
     */
    private void addRestrictionDetails(@NotNull final List<String> details)
    {
        if (this.idpClaim != null && !this.idpClaim.isBlank()) {
            details.add("**Imported from the identity provider** claim `" + this.idpClaim
                + "`, for accounts that come from one, and then read-only");
        }
        if (isSystem()) {
            details.add("**System**: maintained by the platform, cannot be changed through the API");
        }
        if (!getAllowedValues().isEmpty()) {
            details.add("**One of**: `" + String.join("`, `", getAllowedValues()) + "`");
        }
    }

    @Override
    @NotNull
    public JsonObjectBuilder documentationJsonBuilder()
    {
        final JsonObjectBuilder json = DocumentedItem.super.documentationJsonBuilder()
            // Whether this describes the person or how they want the application to behave. Said explicitly rather
            // than left to be inferred from `storage`: a definition may point anywhere inside the account -- the node
            // type's own example is `rep:fullname`, which sits under neither subtree -- so the prefix is not a
            // reliable answer, and a form that groups identity apart from settings needs a reliable one.
            .add("kind", label(getKind()))
            .add("dataType", label(getDataType()))
            .add("required", isRequired())
            .add("multiple", isMultiple())
            .add("system", isSystem())
            .add("usable", isUsable())
            .add("writableBy", label(getWritableBy()))
            .add("readableBy", label(getReadableBy()));
        addWhatIsSet(json);
        return json.add("path", getPath());
    }

    /**
     * Adds the properties that a definition may leave unset, so that the catalogue does not carry entries saying
     * nothing.
     *
     * @param json the object being built
     */
    private void addWhatIsSet(@NotNull final JsonObjectBuilder json)
    {
        final String claim = getIdpClaim();
        if (claim != null) {
            json.add("idpClaim", claim);
        }
        // Said so that a form can check what somebody typed where they typed it, rather than only learning that it
        // was not in the expected format once the whole request has been refused
        final String expected = getPattern();
        if (expected != null && !expected.isBlank()) {
            json.add("pattern", expected);
        }
        // Storage is always derivable, so unlike the rest of these it is always said
        json.add("storage", getStorage());
        if (!getAllowedValues().isEmpty()) {
            final JsonArrayBuilder values = Json.createArrayBuilder();
            getAllowedValues().forEach(values::add);
            json.add("allowedValues", values);
        }
        final Long fieldOrder = getOrder();
        if (fieldOrder != null) {
            json.add("order", fieldOrder);
        }
    }

    /**
     * Writes one of the vocabularies the way it is written in content.
     *
     * @param value the parsed constant
     * @return the lowercase name
     */
    @NotNull
    private static String label(@NotNull final Enum<?> value)
    {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
