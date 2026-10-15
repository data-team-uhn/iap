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
package io.uhndata.iap.conditions.internal;

import jakarta.json.JsonObject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for how the built-in operand sources describe themselves in the catalogue.
 *
 * @version $Id$
 * @since 0.1.0
 */
class OperandResolverDescriptionsTest
{
    @Test
    void theLiteralSourceIsNotOfferedAsSomethingToCompare()
    {
        final JsonObject literal = new LiteralOperandResolver().documentationJsonBuilder().build();
        assertEquals("A specific value", literal.getString("label"));
        assertFalse(literal.getBoolean("offered"));
    }

    @Test
    void theAnswerSourceNamesAQuestion()
    {
        final JsonObject answer = new AnswerOperandResolver().documentationJsonBuilder().build();
        assertEquals("Question", answer.getString("valueLabel"));
        assertEquals("the answer to “{label}”", answer.getString("phrase"));
        assertEquals(10, answer.getInt("order"));
    }

    @Test
    void theTagsSourceHoldsSeveralTextValuesPickedFromTheTagCatalogue()
    {
        final JsonObject tags = new TagsOperandResolver().documentationJsonBuilder().build();
        assertEquals("text", tags.getString("type"));
        assertTrue(tags.getBoolean("multiple"));
        assertEquals("/Tags.doc.json", tags.getString("choices"));
        assertEquals("the tag list", tags.getString("phrase"));
    }

    @Test
    void thePropertySourcesNameAProperty()
    {
        final JsonObject property = new PropertyOperandResolver().documentationJsonBuilder().build();
        assertEquals("Property", property.getString("valueLabel"));
        assertEquals("the {label}", property.getString("phrase"));
        assertTrue(property.getBoolean("offered"));
        final JsonObject own = new OwnPropertyOperandResolver().documentationJsonBuilder().build();
        assertEquals("its own {label}", own.getString("phrase"));
        assertFalse(own.getBoolean("offered"));
        assertEquals(40, own.getInt("order"));
    }

    @Test
    void everyBuiltInSourceSaysWhatItReads()
    {
        assertTrue(new LiteralOperandResolver().getDescription().contains("condition"));
        assertTrue(new AnswerOperandResolver().getDescription().contains("answer"));
        assertTrue(new TagsOperandResolver().getDescription().contains("tag"));
        assertTrue(new PropertyOperandResolver().getDescription().contains("property"));
        assertTrue(new OwnPropertyOperandResolver().getDescription().contains("property"));
        assertEquals("The tags", new TagsOperandResolver().getDocumentationLabel());
        assertEquals("A property", new PropertyOperandResolver().getDocumentationLabel());
        assertEquals("One of its own properties", new OwnPropertyOperandResolver().getDocumentationLabel());
        assertEquals("The answer to a question", new AnswerOperandResolver().getDocumentationLabel());
    }
}
