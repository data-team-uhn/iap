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
package io.uhndata.iap.deletion.internal;

import javax.jcr.Node;
import javax.jcr.Session;

import org.junit.jupiter.api.Test;

import io.uhndata.iap.deletion.api.DeletionService;
import io.uhndata.iap.deletion.spi.DeletionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link UndeletableVeto}.
 *
 * @version $Id$
 */
class UndeletableVetoTest
{
    private final UndeletableVeto veto = new UndeletableVeto();

    // This guard looks only at the node, so the requester it is handed never matters
    private final Session requester = mock(Session.class);

    @Test
    void hasAStableName()
    {
        assertEquals("undeletable", this.veto.getName());
    }

    @Test
    void vetoesMarkedNodesInEveryMode() throws Exception
    {
        final Node node = mock(Node.class);
        when(node.isNodeType(DeletionService.UNDELETABLE_MIXIN)).thenReturn(true);
        assertNotNull(this.veto.veto(node, DeletionMode.ARCHIVE, this.requester));
        assertNotNull(this.veto.veto(node, DeletionMode.PERMANENT, this.requester));
        assertNotNull(this.veto.veto(node, DeletionMode.PURGE, this.requester));
    }

    @Test
    void allowsUnmarkedNodes() throws Exception
    {
        final Node node = mock(Node.class);
        when(node.isNodeType(DeletionService.UNDELETABLE_MIXIN)).thenReturn(false);
        assertNull(this.veto.veto(node, DeletionMode.ARCHIVE, this.requester));
    }
}
