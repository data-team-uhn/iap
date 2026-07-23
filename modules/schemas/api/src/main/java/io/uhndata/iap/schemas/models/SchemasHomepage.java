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

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;

import io.uhndata.iap.entities.models.EntityHomepage;

/**
 * A Sling Model wrapping a {@code sch:SchemasHomepage} node, the root container of the {@code /Schemas} tree.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = SchemasHomepage.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class SchemasHomepage extends EntityHomepage
{
    /** The {@code sling:resourceType} of a {@code sch:SchemasHomepage} node. */
    public static final String RESOURCE_TYPE = "sch/SchemasHomepage";

    /**
     * The schemas defined under this homepage.
     *
     * @return a list of schemas, empty if none
     */
    public List<Schema> getSchemas()
    {
        return this.getChildren(Schema.RESOURCE_TYPE, Schema.class);
    }
}
