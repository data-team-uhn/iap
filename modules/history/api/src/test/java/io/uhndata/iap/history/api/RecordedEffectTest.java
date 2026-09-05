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
package io.uhndata.iap.history.api;

import java.util.ArrayList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.nodetype.NodeType;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RecordedEffect}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class RecordedEffectTest
{
    @Test
    void keepsWhatItWasTold()
    {
        final RecordedEffect effect =
            new RecordedEffect("uuid", "/content/study", "sub:Submission", "submitted", List.of("status", "title"));

        assertEquals("uuid", effect.subject());
        assertEquals("/content/study", effect.subjectPath());
        assertEquals("sub:Submission", effect.subjectType());
        assertEquals("submitted", effect.role());
        assertEquals(List.of("status", "title"), effect.changes());
    }

    @Test
    void insistsOnEverythingThatMakesTheRecordLegible()
    {
        assertThrows(NullPointerException.class,
            () -> new RecordedEffect(null, "/p", "t", "role", List.of()));
        assertThrows(NullPointerException.class,
            () -> new RecordedEffect("uuid", null, "t", "role", List.of()));
        assertThrows(NullPointerException.class,
            () -> new RecordedEffect("uuid", "/p", null, "role", List.of()));
        assertThrows(NullPointerException.class,
            () -> new RecordedEffect("uuid", "/p", "t", null, List.of()));
    }

    @Test
    void treatsAnAbsentChangeListAsNoChangesNamed()
    {
        assertTrue(new RecordedEffect("uuid", "/p", "t", "role", null).changes().isEmpty());
    }

    @Test
    void cannotHaveItsChangesAlteredAfterwards()
    {
        final List<String> changes = new ArrayList<>(List.of("status"));
        final RecordedEffect effect = new RecordedEffect("uuid", "/p", "t", "role", changes);
        changes.add("title");

        assertEquals(List.of("status"), effect.changes(),
            "A record already described must not change because the caller kept the list");
    }

    @Test
    void readsTheSubjectOffTheNodeSoTheyCannotDisagree() throws Exception
    {
        final NodeType type = Mockito.mock(NodeType.class);
        Mockito.when(type.getName()).thenReturn("sub:Submission");
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getIdentifier()).thenReturn("uuid");
        Mockito.when(node.getPath()).thenReturn("/Submissions/aStudy");
        Mockito.when(node.getPrimaryNodeType()).thenReturn(type);

        final RecordedEffect effect = RecordedEffect.on(node, "submitted", "status");

        assertEquals("uuid", effect.subject());
        assertEquals("/Submissions/aStudy", effect.subjectPath());
        assertEquals("sub:Submission", effect.subjectType());
        assertEquals("submitted", effect.role());
        assertEquals(List.of("status"), effect.changes());
    }
}
