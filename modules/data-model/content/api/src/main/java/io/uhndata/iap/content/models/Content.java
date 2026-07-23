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

/**
 * A Sling Model wrapping an {@code iap:Content} node, the base type of all IAP data nodes. It exposes the generic
 * properties shared by all content nodes, hiding the underlying resource/JCR access from its users.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Content.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Content
{
    /** The {@code sling:resourceType} of an {@code iap:Content} node. */
    public static final String RESOURCE_TYPE = "iap/Content";

    @SlingObject
    protected Resource resource;

    @ValueMapValue(name = "jcr:created")
    private Calendar created;

    @ValueMapValue(name = "jcr:createdBy")
    private String createdBy;

    /**
     * The path of the wrapped resource.
     *
     * @return an absolute repository path
     */
    public String getPath()
    {
        return this.resource.getPath();
    }

    /**
     * The name of the wrapped resource, the last segment of its path.
     *
     * @return a node name
     */
    public String getName()
    {
        return this.resource.getName();
    }

    /**
     * The specific type of the wrapped resource, e.g. {@code iap/Homepage}.
     *
     * @return a resource type name
     */
    public String getType()
    {
        return this.resource.getResourceType();
    }

    /**
     * The date when the resource was created.
     *
     * @return a calendar, or {@code null} if the creation date is not recorded
     */
    public Calendar getCreated()
    {
        return this.created;
    }

    /**
     * The user that created the resource.
     *
     * @return a user name, or {@code null} if the creator is not recorded
     */
    public String getCreatedBy()
    {
        return this.createdBy;
    }

    /**
     * The value of an arbitrary property of the wrapped resource. Every node type declares residual ({@code *})
     * properties for exactly this: extensibility properties whose names aren't known in advance and thus have no
     * dedicated getter of their own.
     *
     * @param name the property name
     * @return the property value (a scalar, or an array for a multi-valued property), or {@code null} if not set
     */
    public Object get(final String name)
    {
        return this.resource.getValueMap().get(name);
    }

    /**
     * A JSON representation of the wrapped resource.
     *
     * @return a JSON object, or {@code null} if the resource cannot be serialized to JSON
     */
    public JsonObject toJson()
    {
        return this.resource.adaptTo(JsonObject.class);
    }

    /**
     * Resolves a JCR reference property's value to the resource it points at, adapted to the given model type.
     * Used to implement typed accessors for {@code REFERENCE} properties, e.g. {@code Submission.getSchemaVersion()}.
     *
     * @param identifier the identifier stored in a {@code REFERENCE} (or {@code WEAKREFERENCE}) property
     * @param type the model class the referenced resource is adapted to
     * @param <T> the model type
     * @return the adapted resource, or {@code null} if the identifier is {@code null}, unresolvable, or the
     *         resource resolver isn't backed by a JCR session
     */
    protected <T> T getReference(final String identifier, final Class<T> type)
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
    protected <T> List<T> getChildren(final String resourceType, final Class<T> type)
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
     * Adapts the wrapped resource's specific named child to the given model type. Unlike {@link #getChildren},
     * this does not need a resource type check: the child is already uniquely identified by name, its type being
     * whatever the node type of the parent declares for that name.
     *
     * @param name the name of the child node to adapt
     * @param type the model class the child is adapted to
     * @param <T> the model type
     * @return the adapted child, or {@code null} if there is no such child
     */
    protected <T> T getChild(final String name, final Class<T> type)
    {
        final Resource child = this.resource.getChild(name);
        return child == null ? null : child.adaptTo(type);
    }
}
