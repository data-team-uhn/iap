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
package io.uhndata.iap.tags.internal;

import java.util.List;
import java.util.Set;

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TagDefinitionsSnapshot}.
 *
 * @version $Id$
 * @since 0.1.0
 */
class TagDefinitionsSnapshotTest
{
    private static final String TYPE_PROPERTY = "sling:resourceType";

    private static final String DEFINITION_TYPE = "iap/TagDefinition";

    @Test
    void readsDefinitionsByResourceType()
    {
        final NodeBuilder homepage = EmptyNodeState.EMPTY_NODE.builder();
        final NodeBuilder incomplete = homepage.child("incomplete");
        incomplete.setProperty(TYPE_PROPERTY, DEFINITION_TYPE);
        incomplete.setProperty("aggregated", true);
        final NodeBuilder sensitive = homepage.child("sensitive");
        sensitive.setProperty(TYPE_PROPERTY, DEFINITION_TYPE);
        sensitive.setProperty("inheritable", true);

        final TagDefinitionsSnapshot snapshot = new TagDefinitionsSnapshot(homepage.getNodeState());

        assertEquals(Set.of("incomplete", "sensitive"), snapshot.getNames());
        assertTrue(snapshot.isDefined("incomplete"));
        assertTrue(snapshot.isAggregated("incomplete"));
        assertFalse(snapshot.isInheritable("incomplete"));
        assertTrue(snapshot.isInheritable("sensitive"));
        assertFalse(snapshot.isAggregated("sensitive"));
        assertFalse(snapshot.isDefined("unknown"));
    }

    @Test
    void readsDefinitionsByPrimaryType()
    {
        final NodeBuilder homepage = EmptyNodeState.EMPTY_NODE.builder();
        homepage.child("draft").setProperty("jcr:primaryType", "iap:TagDefinition", Type.NAME);

        assertTrue(new TagDefinitionsSnapshot(homepage.getNodeState()).isDefined("draft"));
    }

    @Test
    void skipsNonDefinitionChildren()
    {
        final NodeBuilder homepage = EmptyNodeState.EMPTY_NODE.builder();
        homepage.child("config").setProperty(TYPE_PROPERTY, "iap/Content");
        homepage.child("untyped");

        assertTrue(new TagDefinitionsSnapshot(homepage.getNodeState()).getNames().isEmpty());
    }

    @Test
    void honorsExplicitNames()
    {
        final NodeBuilder homepage = EmptyNodeState.EMPTY_NODE.builder();
        final NodeBuilder survey = homepage.child("patientSurvey");
        survey.setProperty(TYPE_PROPERTY, DEFINITION_TYPE);
        survey.setProperty("name", "PATIENT SURVEY");
        final NodeBuilder blank = homepage.child("blankName");
        blank.setProperty(TYPE_PROPERTY, DEFINITION_TYPE);
        blank.setProperty("name", "");
        final NodeBuilder invalid = homepage.child("arrayName");
        invalid.setProperty(TYPE_PROPERTY, DEFINITION_TYPE);
        invalid.setProperty("name", List.of("a", "b"), Type.STRINGS);

        // The explicit name wins, blank or malformed names fall back to the node name
        assertEquals(Set.of("PATIENT SURVEY", "blankName", "arrayName"),
            new TagDefinitionsSnapshot(homepage.getNodeState()).getNames());
    }

    @Test
    void toleratesMalformedFlags()
    {
        final NodeBuilder homepage = EmptyNodeState.EMPTY_NODE.builder();
        final NodeBuilder definition = homepage.child("weird");
        definition.setProperty(TYPE_PROPERTY, DEFINITION_TYPE);
        definition.setProperty("aggregated", List.of("true", "false"), Type.STRINGS);

        assertFalse(new TagDefinitionsSnapshot(homepage.getNodeState()).isAggregated("weird"));
    }

    @Test
    void missingHomepageMeansNoDefinitions()
    {
        final TagDefinitionsSnapshot snapshot = new TagDefinitionsSnapshot(EmptyNodeState.MISSING_NODE);

        assertTrue(snapshot.getNames().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getNames().add("nope"));
    }
}
