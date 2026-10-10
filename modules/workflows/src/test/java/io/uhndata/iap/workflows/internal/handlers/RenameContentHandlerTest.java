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
package io.uhndata.iap.workflows.internal.handlers;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.utils.internal.ContentMoverImpl;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link RenameContentHandler}: content takes the name asked for where it stands, or the rename is
 * refused.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class RenameContentHandlerTest
{
    private static final String BOX = "/box";

    private static final String NAME = "name";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final RenameContentHandler handler = new RenameContentHandler();

    private final Activity activity = Mockito.mock(Activity.class);

    private FieldsFixture fixture;

    @BeforeEach
    void setUp() throws RepositoryException, ReflectiveOperationException
    {
        this.fixture = new FieldsFixture(this.context);
        final Field mover = RenameContentHandler.class.getDeclaredField("mover");
        mover.setAccessible(true);
        mover.set(this.handler, new ContentMoverImpl());
        final Node box = this.fixture.session().getNode(BOX);
        for (final String name : List.of("a", "b", "c")) {
            box.addNode(name, "test:Item").setProperty("title", name);
        }
        this.fixture.session().save();
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(RenameContentHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void takesTheNameWhereItStands() throws WorkflowException, PersistenceException, RepositoryException
    {
        assertEquals("/box/bee", rename("/box/b", "bee"));
        assertEquals(List.of("a", "bee", "c"), this.fixture.children(BOX));

        assertEquals("/box/sea", rename("/box/c", "sea"));
        assertEquals(List.of("a", "bee", "sea"), this.fixture.children(BOX));

        // Its own name changes nothing
        assertEquals("/box/a", rename("/box/a", "a"));
        assertEquals(List.of("a", "bee", "sea"), this.fixture.children(BOX));
    }

    @Test
    void keepsToTheActivitysPattern() throws WorkflowException, PersistenceException, RepositoryException
    {
        Mockito.when(this.activity.get(ContentNames.NAME_PATTERN, String.class)).thenReturn("^[a-z]+$");

        assertThrows(InvalidPayloadException.class, () -> rename("/box/b", "B2"));
        assertEquals("/box/bee", rename("/box/b", "bee"));
    }

    @Test
    void refusesANameItCannotTake() throws RepositoryException
    {
        // Taken by a sibling
        assertThrows(InvalidPayloadException.class, () -> rename("/box/b", "a"));
        assertThrows(InvalidPayloadException.class, () -> rename("/box/b", Map.of()));
        for (final Object name : List.of(" ", List.of("x"), "x/y", "x:y", "x[1]", ".", "..")) {
            assertThrows(InvalidPayloadException.class, () -> rename("/box/b", Map.of(NAME, name)), name.toString());
        }
        assertEquals(List.of("a", "b", "c"), this.fixture.children(BOX));
    }

    @Test
    void leavesItsOwnNameAloneWhereNothingCanChange()
        throws WorkflowException, PersistenceException, RepositoryException
    {
        this.fixture.session().getWorkspace().getVersionManager().checkin(BOX);

        assertEquals("/box/a", rename("/box/a", "a"));
        assertEquals(List.of("a", "b", "c"), this.fixture.children(BOX));
    }

    @Test
    void checksOutWhatWasCheckedIn() throws WorkflowException, PersistenceException, RepositoryException
    {
        this.fixture.session().getWorkspace().getVersionManager().checkin(BOX);

        rename("/box/a", "first");

        assertEquals(List.of("first", "b", "c"), this.fixture.children(BOX));
    }

    @Test
    void reportsContentThatCannotBeRead() throws RepositoryException
    {
        final Resource target = Mockito.mock(Resource.class);
        final Node node = Mockito.mock(Node.class);
        Mockito.when(target.adaptTo(Node.class)).thenReturn(node);
        Mockito.when(target.getPath()).thenReturn("/box/a");
        Mockito.when(node.getParent()).thenThrow(new RepositoryException("gone"));
        final WorkflowTaskContext task = this.fixture.task("rename", this.activity, Map.of(NAME, "other"),
            new HashMap<>());
        Mockito.when(task.getTarget()).thenReturn(target);

        assertThrows(PersistenceException.class, () -> this.handler.execute(task));
    }

    private String rename(final String path, final String name)
        throws WorkflowException, PersistenceException, RepositoryException
    {
        return rename(path, Map.of(NAME, name));
    }

    private String rename(final String path, final Map<String, Object> payload)
        throws WorkflowException, PersistenceException, RepositoryException
    {
        final Map<String, Object> variables = new HashMap<>();
        final WorkflowTaskContext task = this.fixture.task("rename", this.activity, payload, variables);
        Mockito.when(task.getTarget()).thenReturn(this.context.resourceResolver().getResource(path));
        this.handler.execute(task);
        this.fixture.session().save();
        return (String) variables.get(WorkflowResult.CREATED_PATH_VARIABLE);
    }
}
