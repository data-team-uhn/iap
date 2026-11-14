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
package io.uhndata.iap.datarequirement.models;

import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * The base of the three levels inside a catalogue version: a {@link Database}, a {@link Collection} and a
 * {@link Field}. Each carries the source system's own name for itself, and a label a curator may give it.
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class CatalogueItem extends EntityPart
{
    @ValueMapValue
    private String identifier;

    @ValueMapValue
    private String label;

    /**
     * The source system's own name for this item. It is one of the three identifiers a {@link Field#getKey() field
     * key} is built from, so renaming it rewrites every key that contains it.
     *
     * @return the name exactly as the source system spells it
     */
    @NotNull
    public String getIdentifier()
    {
        return this.identifier;
    }

    /**
     * What a reader is shown. Falls back to the {@link #getIdentifier() identifier}, so a catalogue nobody has
     * curated still names everything in it.
     *
     * @return a label, never empty
     */
    @NotNull
    public String getLabel()
    {
        return this.label == null || this.label.isEmpty() ? this.identifier : this.label;
    }
}
