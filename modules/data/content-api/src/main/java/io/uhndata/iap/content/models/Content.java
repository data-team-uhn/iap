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
 * The model of any IAP content: a {@code data:Content} node, or a node of a type that extends it. It reads the
 * properties every content node has, and finds the content around it: its parent, its children, and the targets of
 * its references.
 *
 * <p>The lookups hand back content models, never the resource. Only subclasses reach the resource, through the
 * protected {@link #resource} field.</p>
 *
 * <p>A Sling Model adapts a resource whatever its resource type, so adapting is no type check. The typed lookups
 * check the resource type before they adapt, and a child or parent of another type comes back as {@code null}.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Content.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Content
{
    /** The resource type of {@code data:Content}, which the resource type of all IAP content extends. */
    public static final String RESOURCE_TYPE = "data/Content";

    @SlingObject
    protected Resource resource;

    /** The node's {@code jcr:uuid}; {@code null} unless the node is referenceable. */
    @ValueMapValue(name = "jcr:uuid")
    protected String identifier;

    @ValueMapValue(name = "jcr:created")
    private Calendar created;

    @ValueMapValue(name = "jcr:createdBy")
    private String writtenBy;

    @ValueMapValue
    private String createdBy;

    /**
     * The same content, read through another content model, such as {@code Taggable} to read and place its tags.
     *
     * @param <T> the model type
     * @param type the model class to read this content as
     * @return the model; {@code null} if this content cannot be adapted to it
     */
    @Nullable
    public <T extends Content> T as(@NotNull final Class<T> type)
    {
        return this.resource.adaptTo(type);
    }

    /**
     * The path of this content.
     *
     * @return an absolute path, such as {@code /Submissions/abc}
     */
    @NotNull
    public String getPath()
    {
        return this.resource.getPath();
    }

    /**
     * The name of this content's node, the last segment of its path.
     *
     * @return the node name
     */
    @NotNull
    public String getName()
    {
        return this.resource.getName();
    }

    /**
     * The resource type of this content.
     *
     * @return a resource type, such as {@code sub/Submission}
     */
    @NotNull
    public String getType()
    {
        return this.resource.getResourceType();
    }

    /**
     * Whether this content is of the given resource type, or of a type that extends it through
     * {@code sling:resourceSuperType}.
     *
     * @param type a resource type, such as {@code data/Entity}
     * @return {@code true} if this content's resource type is {@code type} or extends it
     */
    public boolean isOfType(@NotNull final String type)
    {
        return this.resource.isResourceType(type);
    }

    /**
     * The content that holds this one, read as a plain {@code Content} whatever its type. The parent may not be IAP
     * content at all, and {@link #getType()} says what it is.
     *
     * @return the parent; {@code null} for the repository root
     */
    @Nullable
    public Content getParent()
    {
        final Resource parent = this.resource.getParent();
        return parent == null ? null : parent.adaptTo(Content.class);
    }

    /**
     * When this content was created.
     *
     * @return a copy of {@code jcr:created}; {@code null} if the node has none
     */
    @Nullable
    public Calendar getCreated()
    {
        return this.created == null ? null : (Calendar) this.created.clone();
    }

    /**
     * The user who created this content. A workflow creates content as the engine's service user, so Oak's
     * {@code jcr:createdBy} names that service user. The engine records the user it acted for in {@code createdBy}.
     *
     * @return a username, from {@code createdBy} if it is set and from {@code jcr:createdBy} otherwise; {@code null}
     *         if neither is set
     */
    @Nullable
    public String getCreatedBy()
    {
        return this.createdBy == null ? this.writtenBy : this.createdBy;
    }

    /**
     * The value of any property of this content, including one that no getter reads.
     *
     * @param name the property name
     * @return the value, or an array of values for a multi-valued property; {@code null} if the property is not set
     */
    @Nullable
    public Object get(@NotNull final String name)
    {
        return this.resource.getValueMap().get(name);
    }

    /**
     * The value of any property of this content, converted to the given type. The conversion follows Sling's
     * {@link org.apache.sling.api.resource.ValueMap} rules, so a single value can be read as an array, and an array
     * as its first value.
     *
     * @param <T> the value type
     * @param name the property name
     * @param type the type to convert the value to, such as {@code String[].class}
     * @return the converted value; {@code null} if the property is not set, or cannot be converted
     */
    @Nullable
    public <T> T get(@NotNull final String name, @NotNull final Class<T> type)
    {
        return this.resource.getValueMap().get(name, type);
    }

    /**
     * This content as JSON.
     *
     * @return the JSON object; {@code null} if nothing can serialize the resource
     */
    @Nullable
    public JsonObject toJson()
    {
        return this.resource.adaptTo(JsonObject.class);
    }

    /**
     * The content that a {@code REFERENCE} or {@code WEAKREFERENCE} property points to, read through the same
     * session as this content.
     *
     * @param <T> the model type
     * @param identifier the property's value, a {@code jcr:uuid}; {@code null} if the property is not set
     * @param type the model class to adapt the target to
     * @return the target; {@code null} if the identifier is {@code null} or names no node this session can read, or
     *         if the resource resolver has no JCR session
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
     * The children of this content that are of the given resource type, read as the given model.
     *
     * @param <T> the model type
     * @param resourceType a resource type; a child matches if its resource type is this one or extends it
     * @param type the model class to adapt each matching child to
     * @return the matching children, in stored order; empty if none match
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
     * The parent of this content, read as the given model if it is of the given resource type.
     *
     * @param <T> the model type
     * @param resourceType a resource type; the parent matches if its resource type is this one or extends it
     * @param type the model class to adapt the parent to
     * @return the parent; {@code null} for the repository root, or if the parent is of another type
     */
    @Nullable
    protected <T extends Content> T getParent(@NotNull final String resourceType, @NotNull final Class<T> type)
    {
        final Resource parent = this.resource.getParent();
        return parent == null || !parent.isResourceType(resourceType) ? null : parent.adaptTo(type);
    }

    /**
     * All the children of this content, read as the given model whatever their resource type.
     *
     * @param <T> the model type
     * @param type the model class to adapt each child to
     * @return the children that adapt to it, in stored order; empty if there are none
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
     * The named child of this content, read as the given model without checking its resource type. Use it where the
     * name decides the type, as for a child that a node type declares by name, such as {@code cond:condition}.
     * Elsewhere, use {@link #getChild(String, String, Class)}.
     *
     * @param <T> the model type
     * @param name the child's name, or a path relative to this content, such as {@code form/age}
     * @param type the model class to adapt the child to
     * @return the child; {@code null} if there is none
     */
    @Nullable
    public <T extends Content> T getChild(@NotNull final String name, @NotNull final Class<T> type)
    {
        final Resource child = this.resource.getChild(name);
        return child == null ? null : child.adaptTo(type);
    }

    /**
     * The named child of this content, read as the given model if it is of the given resource type. Use it where a
     * node type admits several kinds of child under any name, as {@code wf:WorkflowInstance} does for its tokens,
     * variables and tasks.
     *
     * @param <T> the model type
     * @param name the child's name, or a path relative to this content
     * @param resourceType a resource type; the child matches if its resource type is this one or extends it
     * @param type the model class to adapt the child to
     * @return the child; {@code null} if there is none, or if it is of another type
     */
    @Nullable
    public <T extends Content> T getChild(@NotNull final String name, @NotNull final String resourceType,
        @NotNull final Class<T> type)
    {
        final Resource child = this.resource.getChild(name);
        return child == null || !child.isResourceType(resourceType) ? null : child.adaptTo(type);
    }
}
