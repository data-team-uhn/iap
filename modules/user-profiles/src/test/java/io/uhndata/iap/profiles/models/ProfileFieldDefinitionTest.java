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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.json.JsonObject;

import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.profiles.models.ProfileFieldDefinition.DataType;
import io.uhndata.iap.profiles.models.ProfileFieldDefinition.Kind;
import io.uhndata.iap.profiles.models.ProfileFieldDefinition.Readability;
import io.uhndata.iap.profiles.models.ProfileFieldDefinition.Writability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProfileFieldDefinition}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ProfileFieldDefinitionTest
{
    private static final String RESOURCE_TYPE = "sling:resourceType";

    private static final String FIELDS_PATH = "/ProfileFields/";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, ProfileFieldDefinition.class);
    }

    private ProfileFieldDefinition definition(final String nodeName, final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put(RESOURCE_TYPE, ProfileFieldDefinition.RESOURCE_TYPE);
        return this.context.create().resource(FIELDS_PATH + nodeName, all)
            .adaptTo(ProfileFieldDefinition.class);
    }

    @Test
    void adaptsResourceToModel()
    {
        assertNotNull(definition("email", Map.of()));
    }

    @Test
    void exposesDefinitionProperties()
    {
        final ProfileFieldDefinition field = definition("email", Map.ofEntries(
            Map.entry("label", "Email address"),
            Map.entry("description", "Where the platform writes to you"),
            Map.entry("kind", "profile"),
            Map.entry("dataType", "text"),
            Map.entry("pattern", ".+@.+"),
            Map.entry("required", true),
            Map.entry("multiple", true),
            Map.entry("writableBy", "admin"),
            Map.entry("readableBy", "authenticated"),
            Map.entry("idpClaim", "email"),
            Map.entry("category", new String[] { "contact" }),
            Map.entry("order", 20L)));

        assertEquals("email", field.getName());
        assertEquals("Email address", field.getLabel());
        assertEquals("Where the platform writes to you", field.getDescription());
        assertEquals(Kind.PROFILE, field.getKind());
        assertEquals(DataType.TEXT, field.getDataType());
        assertEquals(".+@.+", field.getPattern());
        assertTrue(field.isRequired());
        assertTrue(field.isMultiple());
        assertEquals(Writability.ADMIN, field.getWritableBy());
        assertEquals(Readability.AUTHENTICATED, field.getReadableBy());
        assertEquals("email", field.getIdpClaim());
        assertEquals(List.of("contact"), field.getCategories());
        assertEquals(20L, field.getOrder());
        assertFalse(field.isSystem());
        assertTrue(field.isUsable());
    }

    @Test
    void fallsBackToTheNodeNameAndTheNameAsLabel()
    {
        final ProfileFieldDefinition field = definition("institution", Map.of("name", "  ", "label", " "));

        assertEquals("institution", field.getName());
        assertEquals("institution", field.getLabel());
        assertNull(field.getDescription());
        assertNull(field.getOrder());
        assertEquals(List.of(), field.getCategories());
        assertEquals(List.of(), field.getAllowedValues());
        assertNull(field.getPattern());
        assertNull(field.getIdpClaim());
    }

    @Test
    void prefersAnExplicitNameOverTheNodeName()
    {
        assertEquals("preferred_language", definition("language", Map.of("name", "preferred_language")).getName());
    }

    @Test
    void appliesTheNodeTypeDefaultsWhenNothingIsStated()
    {
        // The vocabularies are declared with defaults in the node type, and an absent value means exactly those --
        // this is the only case where a missing value is not treated as a broken definition
        final ProfileFieldDefinition field = definition("title", Map.of());

        assertEquals(Kind.PROFILE, field.getKind());
        assertEquals(DataType.TEXT, field.getDataType());
        assertEquals(Writability.OWNER, field.getWritableBy());
        assertEquals(Readability.SELF, field.getReadableBy());
        assertTrue(field.isUsable());
    }

    @Test
    void readsTheVocabulariesWithoutRegardToCase()
    {
        final ProfileFieldDefinition field = definition("locale", Map.of(
            "kind", "PREFERENCE", "dataType", "Boolean", "writableBy", " Owner ", "readableBy", "aDmIn"));

        assertEquals(Kind.PREFERENCE, field.getKind());
        assertEquals(DataType.BOOLEAN, field.getDataType());
        assertEquals(Writability.OWNER, field.getWritableBy());
        assertEquals(Readability.ADMIN, field.getReadableBy());
    }

    @Test
    void derivesTheStorageFromTheKindAndName()
    {
        assertEquals("profile/institution", definition("institution", Map.of()).getStorage());
        assertEquals("preferences/locale", definition("locale", Map.of("kind", "preference")).getStorage());
    }

    @Test
    void prefersAnExplicitStorageOverTheDerivedOne()
    {
        // The identity provider synchronisation writes wherever its own mapping says, which is why this can be stated
        assertEquals("rep:fullname", definition("fullName", Map.of("storage", " rep:fullname ")).getStorage());
    }

    @Test
    void reportsAnUnrecognizedKindAndCannotDeriveStorage()
    {
        final ProfileFieldDefinition field = definition("mystery", Map.of("kind", "whatever"));

        // The getters still answer, with the node type's default, so nothing has to cope with a half-read definition
        assertEquals(Kind.PROFILE, field.getKind());
        assertEquals("profile/mystery", field.getStorage());
        // What makes it fail closed is this, and the profile API refuses the field on the strength of it
        assertFalse(field.isUsable());
        assertEquals(1, field.getConfigurationProblems().size());
        assertTrue(field.getConfigurationProblems().get(0).contains("`kind` is `whatever`"));
        assertTrue(field.getConfigurationProblems().get(0).contains("profile, preference"));
    }

    @Test
    void reportsAnUnrecognizedDataType()
    {
        // Not a synonym for text: a definition asking for something we cannot store is broken, and saying so is the
        // whole point of parsing the vocabulary rather than trusting it
        final ProfileFieldDefinition field = definition("photo", Map.of("dataType", "file"));

        assertEquals(DataType.TEXT, field.getDataType());
        assertFalse(field.isUsable());
        assertTrue(field.getConfigurationProblems().get(0).contains("text, long, double, boolean, date"));
    }

    @Test
    void reportsUnrecognizedReadAndWriteRules()
    {
        final ProfileFieldDefinition field = definition("secret", Map.of(
            "writableBy", "everyone", "readableBy", "nobody"));

        assertEquals(Writability.OWNER, field.getWritableBy());
        assertEquals(Readability.SELF, field.getReadableBy());
        assertEquals(2, field.getConfigurationProblems().size());
        assertTrue(field.getConfigurationProblems().get(0).contains("owner, admin, nobody"));
        assertTrue(field.getConfigurationProblems().get(1).contains("authenticated, self, admin"));
    }

    @Test
    void reportsAPatternThatIsNotARegularExpression()
    {
        final ProfileFieldDefinition field = definition("phone", Map.of("pattern", "[unclosed"));

        assertFalse(field.isUsable());
        assertTrue(field.getConfigurationProblems().get(0).contains("`pattern` is not a valid regular expression"));
    }

    @Test
    void acceptsABlankPatternAsNoPatternAtAll()
    {
        assertTrue(definition("anything", Map.of("pattern", "   ")).isUsable());
    }

    @Test
    void reportsAStorageThatStepsOutsideTheAccount()
    {
        // Fails closed like the vocabularies do: such a field would pass every rule the catalogue has and then be
        // refused at commit time, which the API can only report as though the account were not there
        for (final String outside : List.of("/home/users/asmith/profile/email", "profile/", "profile//email",
            "../asmith/profile/email", "./profile/email")) {
            final ProfileFieldDefinition field = definition("field" + outside.hashCode(),
                Map.of("storage", outside));

            assertFalse(field.isUsable(), outside);
            assertTrue(field.getConfigurationProblems().get(0).contains("not a path inside the account"), outside);
        }
    }

    @Test
    void reportsADerivedStoragePathThatStepsOutsideTheAccount()
    {
        // With `storage` unstated the path is derived from the field name, so checking the property alone would leave
        // the same step out of the account available to anybody authoring a name
        final ProfileFieldDefinition field = definition("sneaky", Map.of("name", "../asmith/profile/email"));

        assertEquals("profile/../asmith/profile/email", field.getStorage());
        assertFalse(field.isUsable());
        assertTrue(field.getConfigurationProblems().get(0).contains("not a path inside the account"));
    }

    @Test
    void acceptsAStorageInsideTheAccount()
    {
        assertTrue(definition("nested", Map.of("storage", "profile/contact/email")).isUsable());
    }

    @Test
    void exposesAClosedSetOfValues()
    {
        assertEquals(List.of("en", "fr"),
            definition("locale", Map.of("allowedValues", new String[] { "en", "fr" })).getAllowedValues());
    }

    @Test
    void ordersByExplicitOrderThenLabel()
    {
        final ProfileFieldDefinition first = definition("a", Map.of("order", 10L));
        final ProfileFieldDefinition second = definition("b", Map.of("order", 20L));
        final ProfileFieldDefinition unordered = definition("c", Map.of());
        final ProfileFieldDefinition alsoUnordered = definition("d", Map.of());

        assertTrue(ProfileFieldDefinition.DISPLAY_ORDER.compare(first, second) < 0);
        assertTrue(ProfileFieldDefinition.DISPLAY_ORDER.compare(second, unordered) < 0);
        assertTrue(ProfileFieldDefinition.DISPLAY_ORDER.compare(unordered, alsoUnordered) < 0);
    }

    @Test
    void documentsItself()
    {
        final ProfileFieldDefinition field = definition("email", Map.of(
            "label", "Email address",
            "dataType", "text",
            "required", true,
            "multiple", true,
            "idpClaim", "email",
            "system", true,
            "allowedValues", new String[] { "work", "home" },
            "category", new String[] { "contact" }));

        assertEquals("Email address", field.getDocumentationLabel());
        assertEquals(List.of("contact"), field.getDocumentationCategories());
        final List<String> details = field.getDocumentationDetails();
        assertTrue(details.get(0).contains("text, more than one value allowed, required"));
        assertTrue(details.stream().anyMatch(detail -> detail.contains("May be changed by**: owner")));
        assertTrue(details.stream().anyMatch(detail -> detail.contains("May be read by**: self")));
        assertTrue(details.stream().anyMatch(detail -> detail.contains("Imported from the identity provider")));
        assertTrue(details.stream().anyMatch(detail -> detail.contains("System")));
        assertTrue(details.stream().anyMatch(detail -> detail.contains("`work`, `home`")));
    }

    @Test
    void documentsWhatIsWrongWithABrokenDefinition()
    {
        final ProfileFieldDefinition field = definition("broken", Map.of(
            "kind", "nonsense", "dataType", "nonsense", "writableBy", "nonsense", "readableBy", "nonsense"));

        final List<String> details = field.getDocumentationDetails();
        // Every vocabulary reads as its default, and each is separately reported as misconfigured, so the catalogue
        // says both what the field will behave as and that nobody should rely on it
        assertTrue(details.get(0).contains("**Type**: text"));
        assertEquals(4, details.stream().filter(detail -> detail.contains("Misconfigured")).count());
    }

    @Test
    void serializesItselfForTheCatalogue()
    {
        final ProfileFieldDefinition field = definition("email", Map.of(
            "label", "Email address",
            "idpClaim", "email",
            "pattern", ".+@.+",
            "allowedValues", new String[] { "work" },
            "order", 20L));

        final JsonObject json = field.documentationJsonBuilder().build().asJsonObject();
        assertEquals(".+@.+", json.getString("pattern"));
        assertEquals("text", json.getString("dataType"));
        assertFalse(json.getBoolean("required"));
        assertFalse(json.getBoolean("multiple"));
        assertFalse(json.getBoolean("system"));
        assertTrue(json.getBoolean("usable"));
        assertEquals("owner", json.getString("writableBy"));
        assertEquals("self", json.getString("readableBy"));
        assertEquals("email", json.getString("idpClaim"));
        assertEquals("profile/email", json.getString("storage"));
        assertEquals(1, json.getJsonArray("allowedValues").size());
        assertEquals(20L, json.getJsonNumber("order").longValue());
        assertEquals("/ProfileFields/email", json.getString("path"));
    }

    @Test
    void leavesOutWhatIsNotSetWhenSerializing()
    {
        final JsonObject json = definition("broken", Map.of("kind", "nonsense", "dataType", "nonsense",
            "writableBy", "nonsense", "readableBy", "nonsense")).documentationJsonBuilder().build().asJsonObject();

        assertEquals("text", json.getString("dataType"));
        assertEquals("owner", json.getString("writableBy"));
        assertEquals("self", json.getString("readableBy"));
        assertFalse(json.getBoolean("usable"));
        // Storage is always derivable, so it is always said; these four are only there when stated
        assertEquals("profile/broken", json.getString("storage"));
        assertFalse(json.containsKey("idpClaim"));
        assertFalse(json.containsKey("pattern"));
        assertFalse(json.containsKey("allowedValues"));
        assertFalse(json.containsKey("order"));
    }
}
