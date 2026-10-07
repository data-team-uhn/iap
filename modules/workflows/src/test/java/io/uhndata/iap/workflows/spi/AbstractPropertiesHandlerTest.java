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
package io.uhndata.iap.workflows.spi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.Activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AbstractPropertiesHandler}: a request is checked whole, then written, emptied properties
 * removed and references stored as references.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AbstractPropertiesHandlerTest
{
    private static final String TITLE = "title";

    private static final String NOTE = "note";

    private static final String OWNER = "owner";

    private static final String THING = "/content/thing";

    private final SlingContext context = new SlingContext();

    private final WorkflowTaskContext task = Mockito.mock(WorkflowTaskContext.class);

    private final List<String> allowed = new ArrayList<>(List.of(TITLE, NOTE, OWNER));

    private final Map<String, Object> requested = new LinkedHashMap<>();

    private final AbstractPropertiesHandler handler = new SavingHandler();

    @BeforeEach
    void setUp()
    {
        this.context.create().resource(THING, TITLE, "Old", NOTE, "Kept");
        this.context.create().resource("/people/alice", "sling:resourceType", "test/Person");
        this.context.create().resource("/places/home", "sling:resourceType", "test/Place");
        final Activity activity = Mockito.mock(Activity.class);
        Mockito.when(activity.getPath()).thenReturn("/SystemWorkflows/saveTest/v1/save");
        Mockito.when(this.task.getActivity()).thenReturn(activity);
        Mockito.when(this.task.getTarget()).thenReturn(this.thing());
        Mockito.when(this.task.getResourceResolver()).thenReturn(this.context.resourceResolver());
    }

    @Test
    void writesWhatTheRequestAsksForTrimmedAndLeavesTheRestAlone() throws WorkflowException, PersistenceException
    {
        this.requested.put(TITLE, "  New  ");

        this.handler.execute(this.task);

        assertEquals("New", this.thing().getValueMap().get(TITLE));
        assertEquals("Kept", this.thing().getValueMap().get(NOTE));
    }

    @Test
    void removesAPropertyThatArrivesBlank() throws WorkflowException, PersistenceException
    {
        this.requested.put(NOTE, "   ");

        this.handler.execute(this.task);

        assertNull(this.thing().getValueMap().get(NOTE));
    }

    @Test
    void removesAPropertyAskedToBeCleared() throws WorkflowException, PersistenceException
    {
        this.requested.put(NOTE, null);

        this.handler.execute(this.task);

        assertNull(this.thing().getValueMap().get(NOTE));
    }

    @Test
    void refusesToEmptyAMandatoryProperty()
    {
        this.requested.put(TITLE, "");

        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(this.task));
        assertTrue(refusal.getMessage().contains("title cannot be empty"));
    }

    @Test
    void refusesWhatIsNotText()
    {
        this.requested.put(TITLE, new String[] { "Two", "titles" });

        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(this.task));
        assertTrue(refusal.getMessage().contains("title must be a string"));
    }

    @Test
    void refusesAPropertyTheActivityDoesNotAllow()
    {
        this.requested.put("colour", "red");

        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(this.task));
        assertTrue(refusal.getMessage().contains("colour cannot be edited here"));
    }

    @Test
    void ignoresAnEntryTheActivityDoesNotAllowWhereTheSubclassSaysSo() throws WorkflowException, PersistenceException
    {
        this.requested.put("colour", "red");
        this.requested.put(TITLE, "New");

        new LenientHandler().execute(this.task);

        assertEquals("New", this.thing().getValueMap().get(TITLE));
        assertNull(this.thing().getValueMap().get("colour"));
    }

    @Test
    void refusesAnActivityAllowingNothing()
    {
        this.allowed.clear();
        this.requested.put(TITLE, "New");

        final WorkflowDefinitionException refusal = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(this.task));
        assertTrue(refusal.getMessage().contains("does not list the properties"));
    }

    @Test
    void checksTheWholeRequestBeforeWritingAnything()
    {
        this.requested.put(TITLE, "New");
        this.requested.put(NOTE, "Changed");
        this.requested.put("colour", "red");

        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(this.task));

        assertEquals("Old", this.thing().getValueMap().get(TITLE));
        assertEquals("Kept", this.thing().getValueMap().get(NOTE));
    }

    @Test
    void storesAReferenceAsOne() throws WorkflowException, PersistenceException, RepositoryException
    {
        final Node thing = Mockito.mock(Node.class);
        final Node alice = Mockito.mock(Node.class);
        // Writable as it stands, so there is nothing to check out
        Mockito.when(thing.isCheckedOut()).thenReturn(true);
        final Map<String, Node> nodes = Map.of(THING, thing, "/people/alice", alice);
        this.context.registerAdapter(Resource.class, Node.class,
            (Function<Resource, Node>) resource -> nodes.get(resource.getPath()));
        this.requested.put(OWNER, "/people/alice");

        this.handler.execute(this.task);

        Mockito.verify(thing).setProperty(OWNER, alice);
    }

    @Test
    void refusesAReferenceToNothingOrToTheWrongKindOfThing()
    {
        this.requested.put(OWNER, "/nowhere");
        final InvalidPayloadException missing = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(this.task));
        assertTrue(missing.getMessage().contains("There is nothing owner can point to at /nowhere"));

        this.requested.put(OWNER, "/places/home");
        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(this.task));
    }

    @Test
    void reportsATargetThatCannotBeModified()
    {
        final Resource readOnly = Mockito.mock(Resource.class);
        Mockito.when(readOnly.getPath()).thenReturn(THING);
        Mockito.when(this.task.getTarget()).thenReturn(readOnly);
        this.requested.put(TITLE, "New");

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> this.handler.execute(this.task));
        assertTrue(failure.getMessage().contains("cannot be modified"));
    }

    /**
     * A handler with a mandatory title, an optional note and an owner that is a reference to a person.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private class SavingHandler extends AbstractPropertiesHandler
    {
        @Override
        public String getName()
        {
            return "saveTestProperties";
        }

        @Override
        @NotNull
        protected List<String> allowed(@NotNull final WorkflowTaskContext taskContext)
        {
            return AbstractPropertiesHandlerTest.this.allowed;
        }

        @Override
        @NotNull
        protected EditableProperty property(@NotNull final WorkflowTaskContext taskContext, @NotNull final String name)
        {
            return new Property(name, TITLE.equals(name), OWNER.equals(name) ? "test/Person" : null);
        }

        @Override
        @NotNull
        protected Map<String, Object> requested(@NotNull final WorkflowTaskContext taskContext)
        {
            return AbstractPropertiesHandlerTest.this.requested;
        }
    }

    /**
     * The same handler, taking requests that carry entries which are not properties.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private final class LenientHandler extends SavingHandler
    {
        @Override
        protected boolean refusesUnlisted()
        {
            return false;
        }
    }

    private Resource thing()
    {
        return this.context.resourceResolver().getResource(THING);
    }

    /**
     * One property of the handler under test.
     *
     * @param name its name
     * @param mandatory whether it may not be emptied
     * @param referenceType what it points to, or {@code null} for text
     * @version $Id$
     * @since 0.1.0
     */
    private record Property(@NotNull String name, boolean mandatory, @Nullable String referenceType)
        implements AbstractPropertiesHandler.EditableProperty
    {
    }
}
