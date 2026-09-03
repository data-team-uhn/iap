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
package io.uhndata.iap.submissions.models;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sub:Chunks} node: the chunk tree of one {@link File}, with one child per chunk.
 * The file the tree was built from is this node's own parent.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Chunks.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Chunks extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:Chunks} node. */
    public static final String RESOURCE_TYPE = "sub/Chunks";

    /**
     * The chunks, in document order: the order the catalog lists them in, and the order a reader would meet them.
     *
     * @return a list of chunks, empty if none have been written yet
     */
    @NotNull
    public List<Chunk> getChunks()
    {
        return this.getChildren(Chunk.RESOURCE_TYPE, Chunk.class);
    }
}
