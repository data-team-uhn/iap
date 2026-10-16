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
package io.uhndata.iap.conditions.models;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.autodoc.api.AutoDocumentable;
import io.uhndata.iap.autodoc.api.DocumentedItem;
import io.uhndata.iap.conditions.api.ConditionTypes;
import io.uhndata.iap.conditions.spi.OperandResolver;
import io.uhndata.iap.content.models.Content;

/**
 * The {@code /ConditionTypes} catalogue: every comparator, aggregator and operand source this instance knows, served
 * by autodoc at {@code /ConditionTypes.doc.json} and {@code .doc.md}. The condition builder reads it to offer what can
 * be compared, and how. The entries come from the {@link ConditionTypes} registry, not from stored nodes.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = { ConditionTypesHomepage.class, AutoDocumentable.class },
    resourceType = ConditionTypesHomepage.RESOURCE_TYPE, defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ConditionTypesHomepage extends Content implements AutoDocumentable
{
    /** The {@code sling:resourceType} of the {@code cond:TypesHomepage} node. */
    public static final String RESOURCE_TYPE = "cond/TypesHomepage";

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String description;

    @OSGiService
    private ConditionTypes types;

    @Override
    @NotNull
    public String getDocumentationTitle()
    {
        return this.title == null ? "Conditions" : this.title;
    }

    @Override
    @Nullable
    public String getDocumentationIntro()
    {
        return this.description;
    }

    @Override
    @NotNull
    public List<DocumentedItem> getDocumentedItems()
    {
        final List<DocumentedItem> items = new ArrayList<>(this.types.getComparators());
        items.addAll(this.types.getAggregators());
        this.types.getSources().forEach(source -> items.add(
            new Source(source, this.types.getCandidateProvider(source.getSource()) != null)));
        return items;
    }

    /**
     * An operand source as the catalogue lists it, with where its candidates can be asked for, when something lists
     * them. The path in the template is the content the condition is on.
     *
     * @param resolver the source
     * @param listsCandidates whether something lists the source's candidates
     * @version $Id$
     * @since 0.1.0
     */
    private record Source(OperandResolver resolver, boolean listsCandidates) implements DocumentedItem
    {
        @Override
        public String getName()
        {
            return this.resolver.getName();
        }

        @Override
        public String getDocumentationLabel()
        {
            return this.resolver.getDocumentationLabel();
        }

        @Override
        public String getDescription()
        {
            return this.resolver.getDescription();
        }

        @Override
        public List<String> getDocumentationCategories()
        {
            return this.resolver.getDocumentationCategories();
        }

        @Override
        public JsonObjectBuilder documentationJsonBuilder()
        {
            final JsonObjectBuilder json = this.resolver.documentationJsonBuilder();
            return this.listsCandidates ? json.add("candidates", "{+path}.conditionCandidates.json?source="
                + URLEncoder.encode(getName(), StandardCharsets.UTF_8)) : json;
        }
    }
}
