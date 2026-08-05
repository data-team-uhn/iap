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
package io.uhndata.iap.workflows.models;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.content.models.Content;

import static io.uhndata.iap.workflows.models.WorkflowFixture.TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link Variable}, in particular reading the value out of the property its declared type points at.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class VariableTest
{
    private static final String TARGET_ID = "target-uuid";

    private final SlingContext context = new SlingContext();

    private int sequence;

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
    }

    @Test
    void takesItsNameFromTheNode()
    {
        final Variable variable = this.create(Map.of(
            "dataType", Variable.TYPE_LONG, "longValue", 3L));

        assertEquals("requestedDays", variable.getName());
        assertEquals(Variable.TYPE_LONG, variable.getDataType());
    }

    @Test
    void readsAStringValue()
    {
        assertEquals("annual leave",
            this.create(Map.of("dataType", Variable.TYPE_STRING, "stringValue", "annual leave")).getValue());
    }

    @Test
    void readsALongValue()
    {
        assertEquals(3L, this.create(Map.of("dataType", Variable.TYPE_LONG, "longValue", 3L)).getValue());
    }

    @Test
    void readsADoubleValue()
    {
        assertEquals(2.5d, this.create(Map.of("dataType", Variable.TYPE_DOUBLE, "doubleValue", 2.5d)).getValue());
    }

    @Test
    void readsABooleanValue()
    {
        assertEquals(true,
            this.create(Map.of("dataType", Variable.TYPE_BOOLEAN, "booleanValue", true)).getValue());
    }

    @Test
    void readsADateValue()
    {
        final Calendar when = Calendar.getInstance();
        when.set(2026, Calendar.AUGUST, 3, 8, 30, 0);
        final Variable variable = this.create(Map.of("dataType", Variable.TYPE_DATE, "dateValue", when));

        assertEquals(when, variable.getValue());
        // Calendar is mutable, so a caller must not be able to reach back into the model's own state
        assertNotSame(variable.getValue(), variable.getValue());
    }

    @Test
    void readsAReferenceValue()
        throws RepositoryException
    {
        this.context.create().resource("/Submissions/s1", TYPE, "sub/Submission");
        WorkflowFixture.resolveReference(this.context, TARGET_ID, "/Submissions/s1");
        final Variable variable = this.create(Map.of(
            "dataType", Variable.TYPE_REFERENCE, "referenceValue", TARGET_ID));

        final Object value = variable.getValue();

        assertNotNull(value);
        assertEquals("/Submissions/s1", ((Content) value).getPath());
    }

    @Test
    void hasNoValueWhenTheTypedPropertyIsUnset()
    {
        assertNull(this.create(Map.of("dataType", Variable.TYPE_STRING)).getValue());
        assertNull(this.create(Map.of("dataType", Variable.TYPE_LONG)).getValue());
        assertNull(this.create(Map.of("dataType", Variable.TYPE_DOUBLE)).getValue());
        assertNull(this.create(Map.of("dataType", Variable.TYPE_BOOLEAN)).getValue());
        assertNull(this.create(Map.of("dataType", Variable.TYPE_DATE)).getValue());
        assertNull(this.create(Map.of("dataType", Variable.TYPE_REFERENCE)).getValue());
    }

    @Test
    void hasNoValueWhenTheTypeIsUnknownOrMissing()
    {
        // A type the model does not know is not guessed at from whichever property happens to be set
        assertNull(this.create(Map.of("dataType", "quantity", "stringValue", "3 kg")).getValue());
        assertNull(this.create(Map.of("stringValue", "3 kg")).getValue());
    }

    /**
     * Creates a variable named {@code requestedDays}, under a path unique to this call so that a single test may
     * build several of them.
     *
     * @param properties the properties to set, on top of the resource type
     * @return the model wrapping it
     */
    private Variable create(final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put(TYPE, Variable.RESOURCE_TYPE);
        this.sequence++;
        final Resource resource =
            this.context.create().resource("/WorkflowInstances/i" + this.sequence + "/requestedDays", all);
        return resource.adaptTo(Variable.class);
    }
}
