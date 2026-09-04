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
package io.uhndata.iap.content.models;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.json.JsonObject;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.SlingObject;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A Sling Model wrapping an {@code data:Content} node, the base type of all IAP data nodes. It exposes the generic
 * properties shared by all content nodes, hiding the underlying resource/JCR access from its users. Subclasses may
 * go below the model API through the protected {@link #resource} field; nothing outside the model hierarchy can.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Content.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Content
{
    /** The {@code sling:resourceType} of an {@code data:Content} node. */
    public static final String RESOURCE_TYPE = "data/Content";

    @SlingObject
    protected Resource resource;

    @ValueMapValue(name = "jcr:created")
    private Calendar created;

    @ValueMapValue(name = "jcr:createdBy")
    private String writtenBy;

    @ValueMapValue
    private String createdBy;

    /**
     * View the same repository content as another content model, e.g. a links-aware view of a submission. The
     * bound keeps the conversion inside the model world: only content models can be requested, never the wrapped
     * resource itself, so this adds nothing beyond what any content model can already see.
     *
     * @param type the model class to view this content as
     * @param <T> the model type
     * @return the adapted model, or {@code null} if this content cannot be viewed as the requested model
     */
    @Nullable
    public <T extends Content> T as(@NotNull final Class<T> type)
    {
        return this.resource.adaptTo(type);
    }

    /**
     * The path of the wrapped resource.
     *
     * @return an absolute repository path
     */
    @NotNull
    public String getPath()
    {
        return this.resource.getPath();
    }

    /**
     * The name of the wrapped resource, the last segment of its path.
     *
     * @return a node name
     */
    @NotNull
    public String getName()
    {
        return this.resource.getName();
    }

    /**
     * The specific type of the wrapped resource, e.g. {@code app/Homepage}.
     *
     * @return a resource type name
     */
    @NotNull
    public String getType()
    {
        return this.resource.getResourceType();
    }

    /**
     * Whether this content is of the given resource type, either directly or through its
     * {@code sling:resourceSuperType} chain, same as {@link Resource#isResourceType}.
     *
     * @param type a resource type name, e.g. {@code sub/Submission}
     * @return {@code true} if this content is of (a subtype of) the given resource type
     */
    public boolean isOfType(@NotNull final String type)
    {
        return this.resource.isResourceType(type);
    }

    /**
     * The content enclosing this one, i.e. the parent of the wrapped resource. As the generic
     * {@link Content} view, since the parent may be of any type, and even a node that isn't IAP content at all;
     * ask for its {@link #getType() type} to find out what it actually is.
     *
     * @return the parent content, or {@code null} if this is the repository root
     */
    @Nullable
    public Content getParent()
    {
        final Resource parent = this.resource.getParent();
        return parent == null ? null : parent.adaptTo(Content.class);
    }

    /**
     * The date when the resource was created.
     *
     * @return a copy of the creation date, or {@code null} if the creation date is not recorded
     */
    @Nullable
    public Calendar getCreated()
    {
        // A copy, since Calendar is mutable and callers must not be able to alter the model's own state
        return this.created == null ? null : (Calendar) this.created.clone();
    }

    /**
     * The user that created the resource.
     *
     * <p>Content raised through a workflow is <em>written</em> by the engine's service user, since that is who
     * holds the rights to write it, so the repository's own {@code jcr:createdBy} records the machinery rather
     * than the person. Where the engine has recorded who it was acting for, that is the answer; {@code
     * jcr:createdBy} is the fallback for everything else.</p>
     *
     * @return a user name, or {@code null} if the creator is not recorded
     */
    @Nullable
    public String getCreatedBy()
    {
        return this.createdBy == null ? this.writtenBy : this.createdBy;
    }

    /**
     * The value of an arbitrary property of the wrapped resource. Every node type declares residual ({@code *})
     * properties for exactly this: extensibility properties whose names aren't known in advance and thus have no
     * dedicated getter of their own.
     *
     * @param name the property name
     * @return the property value (a scalar, or an array for a multi-valued property), or {@code null} if not set
     */
    @Nullable
    public Object get(@NotNull final String name)
    {
        return this.resource.getValueMap().get(name);
    }

    /**
     * The value of an arbitrary property of the wrapped resource, converted to the requested type. Conversion
     * follows the usual {@link org.apache.sling.api.resource.ValueMap} rules, so a single value can be read as an
     * array and vice versa.
     *
     * @param name the property name
     * @param type the type the value is converted to
     * @param <T> the value type
     * @return the converted property value, or {@code null} if the property isn't set or cannot be converted
     */
    @Nullable
    public <T> T get(@NotNull final String name, @NotNull final Class<T> type)
    {
        return this.resource.getValueMap().get(name, type);
    }

    /**
     * A JSON representation of the wrapped resource.
     *
     * @return a JSON object, or {@code null} if the resource cannot be serialized to JSON
     */
    @Nullable
    public JsonObject toJson()
    {
        return this.resource.adaptTo(JsonObject.class);
    }

    /**
     * Resolves a JCR reference property's value to the content it points at, read through the same session as this
     * content. Used to implement typed accessors for {@code REFERENCE} properties, e.g.
     * {@code Submission.getSchemaVersion()}. The type bound keeps the lookup inside the model world: only content
     * models can be requested, never the wrapped resource itself.
     *
     * @param identifier the identifier stored in a {@code REFERENCE} (or {@code WEAKREFERENCE}) property
     * @param type the model class the referenced content is adapted to
     * @param <T> the model type
     * @return the adapted content, or {@code null} if the identifier is {@code null}, unresolvable, or the
     *         resource resolver isn't backed by a JCR session
     */
    @Nullable
    public <T extends Content> T getReference(@Nullable final String identifier, @NotNull final Class<T> type)
    {
        if (identifier == null) {
            return null;
        }
        final Session session = this.resource.getResourceResolver().adaptTo(Session.class);
        if (session == null) {
            return null;
        }
        try {
            final Node target = session.getNodeByIdentifier(identifier);
            final Resource targetResource = this.resource.getResourceResolver().getResource(target.getPath());
            return targetResource == null ? null : targetResource.adaptTo(type);
        } catch (RepositoryException e) {
            return null;
        }
    }

    /**
     * Lists the children of the wrapped resource that are of the given resource type, adapted to the given model
     * type. A child matches {@code resourceType} either directly or through its {@code sling:resourceSuperType}
     * chain, same as {@link Resource#isResourceType(String)}. This is used to implement the typed child listing
     * methods of subclasses, e.g. {@code Schema.getVersions()}. Adaptation itself is not a reliable type filter
     * on its own: a Sling Model registered for a resource type will happily adapt a resource of a different,
     * unrelated type, so the resource type check always comes first.
     *
     * @param resourceType the resource type (or one of its subtypes) a child must have to be included
     * @param type the model class every matching child is adapted to
     * @param <T> the model type
     * @return a list of matching, adapted children, in the same order as the underlying resource's children; empty
     *         if none of the children match
     */
    @NotNull
    protected <T extends Content> List<T> getChildren(@NotNull final String resourceType,
        @NotNull final Class<T> type)
    {
        final List<T> result = new ArrayList<>();
        for (final Resource child : this.resource.getChildren()) {
            if (child.isResourceType(resourceType)) {
                final T adapted = child.adaptTo(type);
                if (adapted != null) {
                    result.add(adapted);
                }
            }
        }
        return result;
    }

    /**
     * Adapts the parent of the wrapped resource to the given model type, provided the parent is of the given
     * resource type. Used to implement the "owner" accessors of the parts that are stored inside something else,
     * e.g. {@code SequenceFlow.getSource()}.
     *
     * <p>The resource type check is not a nicety: adaptation is not a type filter at all. A model registered for
     * one resource type will happily adapt a resource of an unrelated one — with a single implementation it is
     * returned outright, and with several, a resource matching none of them is handed to whichever happens to
     * come first rather than rejected. Either way, adapting an unrelated parent would quietly yield a model
     * wrapping the wrong node rather than {@code null}.</p>
     *
     * @param resourceType the resource type (or one of its subtypes) the parent must have
     * @param type the model class the parent is adapted to
     * @param <T> the model type
     * @return the adapted parent, or {@code null} if the wrapped resource has no parent, or its parent is of
     *         another type
     */
    @Nullable
    protected <T extends Content> T getParent(@NotNull final String resourceType, @NotNull final Class<T> type)
    {
        final Resource parent = this.resource.getParent();
        return parent == null || !parent.isResourceType(resourceType) ? null : parent.adaptTo(type);
    }

    /**
     * Lists all the children of the wrapped resource, adapted to the given model type. Unlike
     * {@link #getChildren(String, Class)} this filters nothing, so it is the right tool for walking through
     * arbitrary content — e.g. searching a subtree — where the interesting nodes aren't known by their type.
     *
     * @param type the model class every child is adapted to
     * @param <T> the model type
     * @return a list of adapted children, in the same order as the underlying resource's children; empty if there
     *         are no children, or if none of them adapt to the requested model
     */
    @NotNull
    public <T extends Content> List<T> getChildren(@NotNull final Class<T> type)
    {
        final List<T> result = new ArrayList<>();
        for (final Resource child : this.resource.getChildren()) {
            final T adapted = child.adaptTo(type);
            if (adapted != null) {
                result.add(adapted);
            }
        }
        return result;
    }

    /**
     * Adapts the wrapped resource's specific named child to the given model type, without checking what that child
     * actually is. Only safe where the name determines the type — a node type declaring one named child, e.g.
     * {@code bpmn.xml} — since adaptation is not a type filter on its own: a model registered for one resource type
     * will happily adapt a resource of an unrelated one. Where the parent declares several residual children, as
     * {@code wf:WorkflowInstance} does for its tokens, variables and tasks, a name says nothing about the type, and
     * {@link #getChild(String, String, Class)} is the one to use.
     *
     * @param name the name of the child node to adapt, which may also be a path relative to this content, e.g.
     *            {@code form/age}
     * @param type the model class the child is adapted to
     * @param <T> the model type
     * @return the adapted child, or {@code null} if there is no such child
     */
    @Nullable
    public <T extends Content> T getChild(@NotNull final String name, @NotNull final Class<T> type)
    {
        final Resource child = this.resource.getChild(name);
        return child == null ? null : child.adaptTo(type);
    }

    /**
     * Adapts the wrapped resource's specific named child to the given model type, provided that child is of the
     * given resource type. This is the lookup to use wherever a node type declares more than one kind of residual
     * child, so that a name alone does not say what will be found under it, e.g.
     * {@code WorkflowInstance.getVariable(name)}, whose siblings include tokens and task instances.
     *
     * <p>The resource type check is what makes a miss report as a miss: adapting an unrelated child would otherwise
     * hand back a model wrapping the wrong node, and a caller's {@code != null} test would take that for the thing
     * it asked for.</p>
     *
     * @param name the name of the child node to adapt, which may also be a path relative to this content
     * @param resourceType the resource type (or one of its subtypes) the child must have
     * @param type the model class the child is adapted to
     * @param <T> the model type
     * @return the adapted child, or {@code null} if there is no such child, or it is of another type
     */
    @Nullable
    public <T extends Content> T getChild(@NotNull final String name, @NotNull final String resourceType,
        @NotNull final Class<T> type)
    {
        final Resource child = this.resource.getChild(name);
        return child == null || !child.isResourceType(resourceType) ? null : child.adaptTo(type);
    }
}
