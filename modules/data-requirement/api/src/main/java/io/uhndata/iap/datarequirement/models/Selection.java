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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.schemas.models.Fulfiller;
import io.uhndata.iap.schemas.models.Requirement;

/**
 * A Sling Model wrapping a {@code datareq:Selection} node: the fields chosen in answer to one data requirement,
 * held as a part of the submission that made them.
 *
 * <p>Fields are held by key rather than by reference. A catalogue version is a full copy, so the same field in
 * two versions is two different nodes and a reference would have to be rewritten on every republication; a key
 * is stable by construction, and it is also what an export outside this platform carries.</p>
 *
 * <p>The version is recorded when the selection is first made and never moved afterwards. That is what keeps a
 * filed submission meaning what it meant, whatever has been published since — and it is why
 * {@link #getMissingFields()} is worth asking rather than assuming: a key that no longer resolves against a
 * <em>later</em> version is a difference to report, never a repair to make.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = { Selection.class, Fulfiller.class },
    resourceType = Selection.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Selection extends Fulfiller
{
    /** The {@code sling:resourceType} of a {@code datareq:Selection} node. */
    public static final String RESOURCE_TYPE = "datareq/Selection";

    @ValueMapValue
    private String fulfills;

    @ValueMapValue
    private String catalogueVersion;

    @ValueMapValue
    private String[] fields;

    /**
     * The requirement this selection answers.
     *
     * @return a requirement, or {@code null} if the reference cannot be resolved
     */
    @Override
    @Nullable
    public Requirement getFulfills()
    {
        return this.getReference(this.fulfills, Requirement.class);
    }

    /**
     * A selection meets the requirement it answers only by holding something.
     *
     * <p>Clearing a selection leaves the node where it is rather than removing it, because the version it was
     * bound to is worth keeping: coming back to it later carries on from the catalogue it started in. So an
     * empty selection is a real state, and it is the state of not having chosen yet.</p>
     *
     * @return {@code true} if any field has been chosen
     */
    @Override
    public boolean isFulfilling()
    {
        return !this.getFieldKeys().isEmpty();
    }

    /**
     * The catalogue version these fields were chosen from, and the one they are to be read against.
     *
     * @return a catalogue version, or {@code null} if the reference cannot be resolved
     */
    @Nullable
    public CatalogueVersion getCatalogueVersion()
    {
        return this.getReference(this.catalogueVersion, CatalogueVersion.class);
    }

    /**
     * The keys of the chosen fields, in the order they were stored.
     *
     * @return a list of field keys, empty if nothing has been chosen
     */
    @NotNull
    public List<String> getFieldKeys()
    {
        return this.fields == null ? List.of() : Arrays.asList(this.fields.clone());
    }

    /**
     * The chosen fields, resolved against the version they were chosen from.
     *
     * <p>Shorter than {@link #getFieldKeys()} only if the recorded version has lost fields since — which it
     * cannot, being immutable — so in practice this resolves every key. It is written to tolerate a gap anyway,
     * because content is editable and a broken catalogue should not make a submission unreadable.</p>
     *
     * @return the fields, empty if nothing was chosen or the version cannot be resolved
     */
    @NotNull
    public List<Field> getFields()
    {
        final CatalogueVersion version = this.getCatalogueVersion();
        if (version == null) {
            return List.of();
        }
        return this.getFieldKeys().stream()
            .map(version::getField)
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Which of the chosen fields a later version of the catalogue no longer offers.
     *
     * <p>Information for a reader, not a problem to fix: what was chosen stays chosen and stays readable against
     * its own version. This answers "how much has moved on since", which is worth telling a reviewer looking at
     * an older submission.</p>
     *
     * @param against the version to compare with, normally the catalogue's current one
     * @return the keys this selection holds that the given version does not offer, empty if none
     */
    @NotNull
    public List<String> getMissingFields(@NotNull final CatalogueVersion against)
    {
        return this.getFieldKeys().stream()
            .filter(key -> against.getField(key) == null)
            .toList();
    }
}
