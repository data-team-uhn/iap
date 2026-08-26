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
package io.uhndata.iap.workflows.internal;

import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SavePropertiesHandler}: writing the properties the activity says are editable, ignoring
 * everything else a request names, and refusing an activity that says nothing is.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SavePropertiesHandlerTest
{
    private static final String TITLE = "title";

    private final SlingContext context = new SlingContext();

    private final SavePropertiesHandler handler = new SavePropertiesHandler();

    @BeforeEach
    void setUp()
    {
        AuthoringFixture.setUp(this.context);
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(SavePropertiesHandler.NAME, this.handler.getName());
    }

    @Test
    void writesTheEditablePropertiesThePayloadCarries() throws WorkflowException, PersistenceException
    {
        this.handler.execute(this.save(Map.of(TITLE, "  Annual leave  "),
            new String[] { TITLE }, new String[] { TITLE }));

        assertEquals("Annual leave", this.definition().getValueMap().get(TITLE));
    }

    @Test
    void ignoresWhatTheActivityDoesNotSayIsEditable() throws WorkflowException, PersistenceException
    {
        // Without this the handler would be an open write to whatever a caller cared to name
        this.handler.execute(this.save(Map.of(TITLE, "Annual leave", "state", "ACTIVE", "description", "Sneaky"),
            new String[] { TITLE }, new String[] { TITLE }));

        final Resource definition = this.definition();
        assertEquals("Annual leave", definition.getValueMap().get(TITLE));
        assertNull(definition.getValueMap().get("state"));
        assertNull(definition.getValueMap().get("description"));
    }

    @Test
    void removesAnEditablePropertyThatArrivesEmpty() throws WorkflowException, PersistenceException
    {
        this.handler.execute(this.save(Map.of(TITLE, "Annual leave", "description", "For a while"),
            new String[] { TITLE, "description" }, new String[] { TITLE }));
        assertEquals("For a while", this.definition().getValueMap().get("description"));

        this.handler.execute(this.save(Map.of(TITLE, "Annual leave", "description", "   "),
            new String[] { TITLE, "description" }, new String[] { TITLE }));

        assertNull(this.definition().getValueMap().get("description"));
    }

    @Test
    void readsAnEditableListWrittenWithOneEntry() throws WorkflowException, PersistenceException
    {
        // A JCR multiple-valued property authored with one entry reads back as a single value
        this.handler.execute(this.save(Map.of(TITLE, "Annual leave"), TITLE, TITLE));

        assertEquals("Annual leave", this.definition().getValueMap().get(TITLE));
    }

    @Test
    void refusesARequiredPropertyThatArrivesEmpty()
    {
        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(this.save(Map.of(TITLE, "  "),
                new String[] { TITLE }, new String[] { TITLE })));
        assertTrue(refusal.getMessage().contains("A title is required"));
    }

    @Test
    void refusesAnActivityThatSaysNothingIsEditable()
    {
        final WorkflowDefinitionException refusal = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(this.save(Map.of(TITLE, "Annual leave"), null, null)));
        assertTrue(refusal.getMessage().contains("does not list which properties"));
    }

    /**
     * A task context saving properties onto the fixture's definition.
     *
     * @param payload what the event carries
     * @param editable the activity's editable configuration, or {@code null} to omit it
     * @param required the activity's required configuration, or {@code null} to omit it
     * @return the assembled context
     */
    private WorkflowTaskContextImpl save(final Map<String, Object> payload, final Object editable,
        final Object required)
    {
        final Map<String, Object> configuration = new HashMap<>();
        configuration.put("handler", SavePropertiesHandler.NAME);
        if (editable != null) {
            configuration.put("editable", editable);
        }
        if (required != null) {
            configuration.put("required", required);
        }
        final Activity activity =
            AuthoringFixture.activity(this.context, "save-" + configuration.hashCode(), configuration);
        return AuthoringFixture.context(this.definition(), "save", payload, activity, new HashMap<>());
    }

    /**
     * The fixture's workflow definition, read back from the repository.
     *
     * @return the definition resource
     */
    private Resource definition()
    {
        final Resource definition = this.context.resourceResolver().getResource(AuthoringFixture.DEFINITION);
        assertNotNull(definition);
        return definition;
    }
}
