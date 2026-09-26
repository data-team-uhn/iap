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
package io.uhndata.iap.schemas.models;

import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks, against the shipped {@code /libs/sch} definitions, that every requirement and form item resolves to
 * {@code sch/SchemaPart}, which is what lets one servlet binding and one workflow target cover them all.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SchemaPartTypeTest
{
    private static final String SCHEMA_PART = "sch/SchemaPart";

    private final SlingContext context = new SlingContext();

    @BeforeEach
    void setUp()
    {
        for (final String type : new String[] { "SchemaPart", "Requirement", "FormRequirement",
            "DocumentRequirement", "ApprovalRequirement", "FormItem", "Section", "Question", "AnswerOption",
            "SchemaVersion" }) {
            this.context.load().json("/SLING-INF/content/libs/sch/" + type + "/ROOT.json", "/libs/sch/" + type);
        }
    }

    @Test
    void requirementsAndFormItemsAreSchemaParts()
    {
        // Each node carries the supertype its node type hardcodes, as a real one would
        assertTrue(this.isPart("sch/Question", "sch/FormItem"));
        assertTrue(this.isPart("sch/Section", "sch/FormItem"));
        assertTrue(this.isPart("sch/FormRequirement", "sch/Requirement"));
        assertTrue(this.isPart("sch/DocumentRequirement", "sch/Requirement"));
        assertTrue(this.isPart("sch/ApprovalRequirement", "sch/Requirement"));
    }

    @Test
    void versionsAndOptionsAreNot()
    {
        assertFalse(this.isPart("sch/SchemaVersion", null));
        assertFalse(this.isPart("sch/AnswerOption", "data/EntityPart"));
    }

    private boolean isPart(final String type, final String superType)
    {
        final Resource resource = superType == null
            ? this.context.create().resource("/Schemas/s/v/" + type.replace('/', '-'), "sling:resourceType", type)
            : this.context.create().resource("/Schemas/s/v/" + type.replace('/', '-'), Map.of(
                "sling:resourceType", type,
                "sling:resourceSuperType", superType));
        return resource.isResourceType(SCHEMA_PART);
    }
}
