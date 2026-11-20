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
package io.uhndata.iap.tags.testing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.jetbrains.annotations.NotNull;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import io.uhndata.iap.tags.models.Taggable;

/**
 * Gives the resources of a mock repository tags, kept in each node's own {@code tags} property, where the tags
 * service keeps them. The service does not run under sling-mock. Without this, a resource adapts to no
 * {@code Taggable}, and a model reading tags reports none.
 *
 * <p>The view follows the service's rules where a test can see them. A write stays in the session until the test
 * commits it. A write that changes nothing writes nothing. Placing an undefined tag is refused with an
 * {@code IllegalArgumentException}, and removing one is allowed. Every tag counts as defined except
 * {@link #UNDEFINED} and the tags given to {@link Builder#refusing}. A replacement retires the content's tags that
 * share a category with the new one, and a tag given no categories shares none. {@code hasTag} also finds an
 * inheritable tag placed on an ancestor.</p>
 *
 * <p>System tags, applicability to node types and tags aggregated from descendants are left out. Every tag may be
 * written whatever the {@code allowSystem} argument says, and the effective tags and applicable definitions answer
 * empty.</p>
 *
 * <p>A module's tests use it through the {@code iap-tags-api} test jar, in the module's {@code tests} profile.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class PropertyTagging
{
    /** A tag that is never defined, so placing it is refused. */
    public static final String UNDEFINED = "undefined";

    private static final String TAGS_PROPERTY = "tags";

    private final Set<String> undefined;

    private final Map<String, Set<String>> categories;

    private final Set<String> inheritable;

    private final Map<String, View> views = new HashMap<>();

    private final Set<String> refusedPaths = new HashSet<>();

    private final List<String> replacements = new ArrayList<>();

    private PropertyTagging(final Builder builder)
    {
        this.undefined = Set.copyOf(builder.undefined);
        this.categories = Map.copyOf(builder.categories);
        this.inheritable = Set.copyOf(builder.inheritable);
    }

    /**
     * Installs the tagging in a context with the default definitions. Only {@link #UNDEFINED} is refused, and no
     * tag has categories or is inheritable.
     *
     * @param context the test's Sling context
     * @return the installed tagging, to refuse writes on or to inspect
     */
    @NotNull
    public static PropertyTagging installIn(@NotNull final SlingContext context)
    {
        return builder().installIn(context);
    }

    /**
     * Starts describing the tag definitions the views follow.
     *
     * @return a builder knowing only {@link #UNDEFINED}, which it refuses
     */
    @NotNull
    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * The view a resource adapts to. Every resource object at one path adapts to the same Mockito mock, so a test
     * can verify what its subject asked of it.
     *
     * @param resource a resource
     * @return the view of the tags at the resource's path
     */
    @NotNull
    public Taggable viewOf(@NotNull final Resource resource)
    {
        final View view = this.views.computeIfAbsent(resource.getPath(), path -> new View());
        view.resource = resource;
        return view.taggable;
    }

    /**
     * Makes every write to a resource's tags fail with a {@code PersistenceException}, as the tags service fails when
     * the session may not modify the node. Reads, and writes that change nothing, still succeed.
     *
     * @param path the absolute path of the resource that refuses writes
     */
    public void refuseWritesTo(@NotNull final String path)
    {
        this.refusedPaths.add(path);
    }

    /**
     * The tags given to {@code tagReplacing}, in the order they were asked for, refused ones left out.
     *
     * @return the replacing tag names; empty if nothing replaced a tag
     */
    @NotNull
    public List<String> getReplacements()
    {
        return List.copyOf(this.replacements);
    }

    private static Set<String> tagsOf(final Resource resource)
    {
        return new LinkedHashSet<>(List.of(resource.getValueMap().get(TAGS_PROPERTY, new String[0])));
    }

    private static String name(final InvocationOnMock invocation)
    {
        return invocation.getArgument(0);
    }

    private void requireDefined(final String name)
    {
        if (this.undefined.contains(name)) {
            throw new IllegalArgumentException("Undefined tag: " + name);
        }
    }

    private boolean shareCategory(final String first, final String second)
    {
        final Set<String> claimed = this.categories.getOrDefault(second, Set.of());
        return this.categories.getOrDefault(first, Set.of()).stream().anyMatch(claimed::contains);
    }

    /**
     * Describes the tag definitions the views follow: which tags are undefined, each tag's categories, and which tags
     * are inheritable.
     */
    public static final class Builder
    {
        private final Set<String> undefined = new HashSet<>(Set.of(UNDEFINED));

        private final Map<String, Set<String>> categories = new HashMap<>();

        private final Set<String> inheritable = new HashSet<>();

        private Builder()
        {
        }

        /**
         * Makes more tags undefined besides {@link #UNDEFINED}, so that placing them is refused.
         *
         * @param names the names of the tags without a definition
         * @return this builder
         */
        @NotNull
        public Builder refusing(@NotNull final String... names)
        {
            this.undefined.addAll(Arrays.asList(names));
            return this;
        }

        /**
         * Gives a tag its categories. Placing it with {@code tagReplacing} retires the tags sharing one of them.
         *
         * @param tag a tag name, such as {@code approved}
         * @param names the tag's categories, such as {@code lifecycle} and {@code review}
         * @return this builder
         */
        @NotNull
        public Builder inCategories(@NotNull final String tag, @NotNull final String... names)
        {
            this.categories.put(tag, Set.of(names));
            return this;
        }

        /**
         * Makes tags inheritable, so a node carries them when an ancestor has them placed.
         *
         * @param names the names of the inheritable tags, such as {@code retired}
         * @return this builder
         */
        @NotNull
        public Builder inheritable(@NotNull final String... names)
        {
            this.inheritable.addAll(Arrays.asList(names));
            return this;
        }

        /**
         * Installs the tagging in a context with these definitions.
         *
         * @param context the test's Sling context
         * @return the installed tagging, to refuse writes on or to inspect
         */
        @NotNull
        public PropertyTagging installIn(@NotNull final SlingContext context)
        {
            final PropertyTagging tagging = new PropertyTagging(this);
            context.registerAdapter(Resource.class, Taggable.class, (Function<Resource, Taggable>) tagging::viewOf);
            return tagging;
        }
    }

    /**
     * The tags at one path. The mock answers from the resource last adapted there.
     */
    private final class View
    {
        private final Taggable taggable = Mockito.mock(Taggable.class);

        private Resource resource;

        View()
        {
            try {
                stub();
            } catch (final PersistenceException e) {
                throw new IllegalStateException("Stubbing a method never calls it", e);
            }
        }

        private void stub() throws PersistenceException
        {
            Mockito.when(this.taggable.getTags()).thenAnswer(invocation -> tagsOf(this.resource));
            Mockito.when(this.taggable.hasOwnTag(Mockito.anyString()))
                .thenAnswer(invocation -> tagsOf(this.resource).contains(name(invocation)));
            Mockito.when(this.taggable.hasTag(Mockito.anyString())).thenAnswer(invocation -> carries(name(invocation)));
            Mockito.when(this.taggable.tag(Mockito.anyString())).thenAnswer(invocation -> place(name(invocation)));
            Mockito.when(this.taggable.tag(Mockito.anyString(), Mockito.anyBoolean()))
                .thenAnswer(invocation -> place(name(invocation)));
            Mockito.when(this.taggable.tagReplacing(Mockito.anyString(), Mockito.anyBoolean()))
                .thenAnswer(invocation -> replace(name(invocation)));
            Mockito.when(this.taggable.untag(Mockito.anyString())).thenAnswer(invocation -> remove(name(invocation)));
            Mockito.when(this.taggable.untag(Mockito.anyString(), Mockito.anyBoolean()))
                .thenAnswer(invocation -> remove(name(invocation)));
            Mockito.doAnswer(this::setTags).when(this.taggable).setTags(Mockito.anyCollection());
            Mockito.doAnswer(this::setTags).when(this.taggable).setTags(Mockito.anyCollection(), Mockito.anyBoolean());
        }

        private boolean carries(final String name)
        {
            return tagsOf(this.resource).contains(name) || (PropertyTagging.this.inheritable.contains(name)
                && Stream.iterate(this.resource.getParent(), Objects::nonNull, Resource::getParent)
                    .anyMatch(ancestor -> tagsOf(ancestor).contains(name)));
        }

        private boolean place(final String name) throws PersistenceException
        {
            requireDefined(name);
            return change(tags -> tags.add(name));
        }

        private boolean replace(final String name) throws PersistenceException
        {
            requireDefined(name);
            PropertyTagging.this.replacements.add(name);
            return change(tags -> {
                tags.removeIf(existing -> shareCategory(existing, name));
                tags.add(name);
            });
        }

        private boolean remove(final String name) throws PersistenceException
        {
            return change(tags -> tags.remove(name));
        }

        private Void setTags(final InvocationOnMock invocation) throws PersistenceException
        {
            final Collection<String> names = invocation.getArgument(0);
            final Set<String> current = tagsOf(this.resource);
            names.stream().filter(name -> !current.contains(name)).forEach(PropertyTagging.this::requireDefined);
            change(tags -> {
                tags.clear();
                tags.addAll(names);
            });
            return null;
        }

        /**
         * Edits the tags, and writes them back if the edit changed them.
         *
         * @param edit changes the set of tag names in place
         * @return whether the tags changed
         * @throws PersistenceException if the resource refuses writes or cannot be modified
         */
        private boolean change(final Consumer<Set<String>> edit) throws PersistenceException
        {
            final Set<String> current = tagsOf(this.resource);
            final Set<String> target = new LinkedHashSet<>(current);
            edit.accept(target);
            if (target.equals(current)) {
                return false;
            }
            write(target);
            return true;
        }

        private void write(final Set<String> tags) throws PersistenceException
        {
            final String path = this.resource.getPath();
            final ModifiableValueMap properties = this.resource.adaptTo(ModifiableValueMap.class);
            if (PropertyTagging.this.refusedPaths.contains(path) || properties == null) {
                throw new PersistenceException("The resource " + path + " cannot be modified");
            }
            properties.put(TAGS_PROPERTY, tags.toArray(new String[0]));
        }
    }
}
