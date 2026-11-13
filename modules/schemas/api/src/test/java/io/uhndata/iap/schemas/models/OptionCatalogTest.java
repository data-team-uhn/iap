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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.autodoc.api.AutoDocumentable;
import io.uhndata.iap.autodoc.api.DocumentedItem;
import io.uhndata.iap.content.models.Content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OptionCatalog}: options from a content path, as labeled leaves or as a
 * documented catalogue.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class OptionCatalogTest
{
    private final SlingContext context = new SlingContext();

    @Test
    void isEmptyWhenThePathIsMissing()
    {
        assertTrue(OptionCatalog.read(this.context.resourceResolver(), "/Nowhere").isEmpty());
        assertTrue(OptionCatalog.read(this.context.resourceResolver(), "  ").isEmpty());
        assertTrue(OptionCatalog.read(this.context.resourceResolver(), null).isEmpty());
    }

    @Test
    void readsLabeledLeavesInTreeOrder()
    {
        this.context.create().resource("/Choices/group", Map.of("label", "A grouping"));
        this.context.create().resource("/Choices/group/data", Map.of("label", "Chart review",
            "description", "Reads records\n   already held."));
        this.context.create().resource("/Choices/trial", Map.of("label", "Clinical trial"));

        final List<OfferedOption> options = OptionCatalog.read(this.context.resourceResolver(), "/Choices");

        assertEquals(List.of("/Choices/group/data", "/Choices/trial"),
            options.stream().map(OfferedOption::value).toList(),
            "a node with labeled children is a grouping, not a choice");
        assertEquals("Chart review", options.get(0).label());
        assertEquals("Reads records\n   already held.", options.get(0).description());
        assertEquals("", options.get(1).description());
    }

    // A catalogue is read through its own items, not walked as a folder, so a labeled child it leaves out is not
    // an option
    @Test
    void readsACatalogueThroughItsDocumentedItems()
    {
        this.context.addModelsForClasses(Content.class, Catalog.class, Entry.class);
        this.context.create().resource("/Catalog", Map.of("sling:resourceType", Catalog.RESOURCE_TYPE));
        this.context.create().resource("/Catalog/stray", Map.of("label", "Not an item", "hidden", true));

        assertEquals(List.of(new OfferedOption("other", "other", "")),
            OptionCatalog.read(this.context.resourceResolver(), "/Catalog"));
    }

    @Test
    void readsTheItemsOfACatalogue()
    {
        this.context.addModelsForClasses(Content.class, Catalog.class, Entry.class);
        this.context.create().resource("/Catalog", Map.of("sling:resourceType", Catalog.RESOURCE_TYPE));
        this.context.create().resource("/Catalog/trial", Map.of("sling:resourceType", Entry.RESOURCE_TYPE,
            "label", "Clinical trial", "description", "Tests an intervention."));

        final List<OfferedOption> options = OptionCatalog.read(this.context.resourceResolver(), "/Catalog");

        assertEquals(List.of(new OfferedOption("/Catalog/trial", "Clinical trial", "Tests an intervention."),
            new OfferedOption("other", "other", "")), options,
            "a content item is stored by its path, a name-only item by its name");
    }

    // A path inside a catalogue is read through the catalogue, so an item it leaves out (here a retired one) is
    // left out too
    @Test
    void readsTheLiveItemsOfACatalogueUnderAPathInsideIt()
    {
        this.context.addModelsForClasses(Content.class, Catalog.class, Entry.class);
        this.context.create().resource("/Catalog", Map.of("sling:resourceType", Catalog.RESOURCE_TYPE));
        this.context.create().resource("/Catalog/group", Map.of("sling:resourceType", Entry.RESOURCE_TYPE,
            "label", "Group"));
        this.context.create().resource("/Catalog/group/live", Map.of("sling:resourceType", Entry.RESOURCE_TYPE,
            "label", "Live"));
        this.context.create().resource("/Catalog/group/retired", Map.of("sling:resourceType", Entry.RESOURCE_TYPE,
            "label", "Retired", "hidden", true));
        this.context.create().resource("/Catalog/elsewhere", Map.of("sling:resourceType", Entry.RESOURCE_TYPE,
            "label", "Elsewhere"));
        this.context.create().resource("/Catalog/group2/item", Map.of("sling:resourceType", Entry.RESOURCE_TYPE,
            "label", "Under a longer name"));

        final List<OfferedOption> options = OptionCatalog.read(this.context.resourceResolver(), "/Catalog/group");

        assertEquals(List.of("/Catalog/group/live"), options.stream().map(OfferedOption::value).toList());
    }

    // Sling Models falls back to the first AutoDocumentable model, so a model not made for the node's type is no
    // catalogue: the node is walked instead
    @Test
    void walksAPathThatAdaptsToNoCatalogueModel()
    {
        final ResourceResolver resolver = Mockito.mock(ResourceResolver.class);
        createTypedResource(resolver, "/Bare");
        final Resource unannotated = createTypedResource(resolver, "/Unannotated");
        final AutoDocumentable model = Mockito.mock(AutoDocumentable.class);
        final DocumentedItem item = Mockito.mock(DocumentedItem.class);
        Mockito.when(item.getName()).thenReturn("documented");
        Mockito.doReturn(List.of(item)).when(model).getDocumentedItems();
        Mockito.when(unannotated.adaptTo(AutoDocumentable.class)).thenReturn(model);

        assertEquals(List.of("/Bare/child"),
            OptionCatalog.read(resolver, "/Bare").stream().map(OfferedOption::value).toList());
        assertEquals(List.of("/Unannotated/child"),
            OptionCatalog.read(resolver, "/Unannotated").stream().map(OfferedOption::value).toList());
    }

    @Test
    void isEmptyWhenThePathHoldsNoLabeledLeaves()
    {
        this.context.create().resource("/Empty", Map.of("title", "Nothing to choose"));
        this.context.create().resource("/Empty/unlabeled", Map.of("label", " "));

        assertTrue(OptionCatalog.read(this.context.resourceResolver(), "/Empty").isEmpty());
    }

    /** A typed resource with one labeled child, so walking it gives one option. */
    private static Resource createTypedResource(final ResourceResolver resolver, final String path)
    {
        final Resource resource = Mockito.mock(Resource.class);
        final Resource child = Mockito.mock(Resource.class);
        Mockito.when(resolver.getResource(path)).thenReturn(resource);
        Mockito.when(resource.getValueMap())
            .thenReturn(new ValueMapDecorator(Map.of("sling:resourceType", "some/Type")));
        Mockito.when(resource.getChildren()).thenReturn(List.of(child));
        Mockito.when(child.getPath()).thenReturn(path + "/child");
        Mockito.when(child.getValueMap()).thenReturn(new ValueMapDecorator(Map.of("label", "Walked")));
        return resource;
    }

    /** A catalogue listing its live leaves (hidden stands for retired), then one item that is not content. */
    @Model(adaptables = Resource.class, adapters = AutoDocumentable.class, resourceType = Catalog.RESOURCE_TYPE)
    public static class Catalog implements AutoDocumentable
    {
        static final String RESOURCE_TYPE = "test/Catalog";

        @SlingObject
        private Resource resource;

        @Override
        public String getDocumentationTitle()
        {
            return "Catalog";
        }

        @Override
        public String getDocumentationIntro()
        {
            return null;
        }

        @Override
        public List<? extends DocumentedItem> getDocumentedItems()
        {
            final List<DocumentedItem> items = new ArrayList<>();
            addLeaves(this.resource, items);
            items.add(new DocumentedItem()
            {
                @Override
                public String getName()
                {
                    return "other";
                }

                @Override
                public String getDescription()
                {
                    return null;
                }
            });
            return items;
        }

        private static void addLeaves(final Resource node, final List<DocumentedItem> items)
        {
            node.getChildren().forEach(child -> {
                if (child.getValueMap().get("hidden", false)) {
                    return;
                }
                if (child.hasChildren()) {
                    addLeaves(child, items);
                } else {
                    items.add(child.adaptTo(Entry.class));
                }
            });
        }
    }

    /** A catalogue item that is also content. */
    @Model(adaptables = Resource.class, resourceType = Entry.RESOURCE_TYPE,
        defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
    public static class Entry extends Content implements DocumentedItem
    {
        static final String RESOURCE_TYPE = "test/Entry";

        @Override
        public String getDocumentationLabel()
        {
            return get("label", String.class);
        }

        @Override
        public String getDescription()
        {
            return get("description", String.class);
        }
    }
}
