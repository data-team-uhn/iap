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
package io.uhndata.iap.extraction.internal;

import java.util.Optional;

import jakarta.json.JsonObject;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link ModelReplies}: reading a model's answer without ever throwing over it.
 *
 * @version $Id$
 * @since 0.1.0
 */
class ModelRepliesTest
{
    private static final String CONFIDENCE = "confidence";

    private static JsonObject object(final String json)
    {
        final JsonObject read = ModelReplies.readJsonObject(json);
        assertNotNull(read);
        return read;
    }

    @Test
    void readsTheObjectOutOfAReply()
    {
        assertEquals("yes", object("{\"answer\": \"yes\"}").getString("answer"));
    }

    @Test
    void readsTheObjectOutOfProseAroundIt()
    {
        assertEquals("yes", object("Certainly! {\"answer\": \"yes\"} Hope that helps.").getString("answer"));
    }

    @Test
    void readsNothingFromAReplyWithNoObjectInIt()
    {
        assertNull(ModelReplies.readJsonObject(null));
        assertNull(ModelReplies.readJsonObject("   "));
        assertNull(ModelReplies.readJsonObject("I cannot answer that."));
        assertNull(ModelReplies.readJsonObject("} backwards {"));
        assertNull(ModelReplies.readJsonObject("{ this is not, really, json }"));
    }

    @Test
    void readsANestedObjectOnlyWhenItIsOne()
    {
        final JsonObject parent = object("{\"field\": {\"value\": 1}, \"text\": \"no\"}");

        assertNotNull(ModelReplies.readObject(parent, "field"));
        assertNull(ModelReplies.readObject(parent, "text"));
        assertNull(ModelReplies.readObject(parent, "absent"));
    }

    @Test
    void readsAStringAndTakesAnythingElseAsItsText()
    {
        final JsonObject parent = object("{\"s\": \"word\", \"n\": 3, \"gone\": null}");

        assertEquals("word", ModelReplies.readString(parent, "s"));
        assertEquals("3", ModelReplies.readString(parent, "n"));
        assertNull(ModelReplies.readString(parent, "gone"));
        assertNull(ModelReplies.readString(parent, "absent"));
    }

    @Test
    void holdsAConfidenceBetweenZeroAndOne()
    {
        assertEquals(0.7, ModelReplies.readConfidence(object("{\"confidence\": 0.7}"), CONFIDENCE));
        assertEquals(1.0, ModelReplies.readConfidence(object("{\"confidence\": 7}"), CONFIDENCE));
        assertEquals(0.0, ModelReplies.readConfidence(object("{\"confidence\": -2}"), CONFIDENCE));
        assertEquals(0.0, ModelReplies.readConfidence(object("{\"confidence\": \"high\"}"), CONFIDENCE));
        assertEquals(0.0, ModelReplies.readConfidence(object("{}"), CONFIDENCE));
    }

    @Test
    void readsAWholeNumberAndNothingElse()
    {
        assertEquals(4L, ModelReplies.readLong(object("{\"page\": 4}"), "page"));
        assertNull(ModelReplies.readLong(object("{\"page\": 4.5}"), "page"));
        assertNull(ModelReplies.readLong(object("{\"page\": \"4\"}"), "page"));
        assertNull(ModelReplies.readLong(object("{\"page\": null}"), "page"));
        assertNull(ModelReplies.readLong(object("{}"), "page"));
    }

    // Anything but a real boolean has to read as no answer: turning it into false would let an unusable
    // reply settle the question the gate is asking
    @Test
    void readsAYesOrNoAndNothingElse()
    {
        assertEquals(Optional.of(Boolean.TRUE), ModelReplies.readBoolean(object("{\"ok\": true}"), "ok"));
        assertEquals(Optional.of(Boolean.FALSE), ModelReplies.readBoolean(object("{\"ok\": false}"), "ok"));
        assertEquals(Optional.empty(), ModelReplies.readBoolean(object("{\"ok\": \"true\"}"), "ok"));
        assertEquals(Optional.empty(), ModelReplies.readBoolean(object("{\"ok\": 1}"), "ok"));
        assertEquals(Optional.empty(), ModelReplies.readBoolean(object("{\"ok\": null}"), "ok"));
        assertEquals(Optional.empty(), ModelReplies.readBoolean(object("{}"), "ok"));
    }
}
