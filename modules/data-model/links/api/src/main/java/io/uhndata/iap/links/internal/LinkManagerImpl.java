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
package io.uhndata.iap.links.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.version.VersionManager;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.links.api.LinkManager;
import io.uhndata.iap.links.models.ExternalLink;
import io.uhndata.iap.links.models.Link;
import io.uhndata.iap.links.models.LinkDefinition;
import io.uhndata.iap.links.models.ResourceLink;

/**
 * Straightforward implementation of {@link LinkManager}. The only writes not going through the caller's own
 * resolver are the creation of a missing {@code iap:links} container — done through the links service user, since
 * it may require checking out a versionable resource — and, indirectly, the automatic completion of backlinks the
 * caller could not create, performed by {@link AutocreateBacklinksListener} after the links are committed.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class LinkManagerImpl implements LinkManager
{
    /** The subservice name mapped to the {@code iap-links} service user. */
    static final String SUBSERVICE = "links";

    private static final String UUID_PROPERTY = "jcr:uuid";

    private static final Logger LOGGER = LoggerFactory.getLogger(LinkManagerImpl.class);

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Override
    public LinkDefinition getDefinition(final ResourceResolver resolver, final String type)
    {
        if (type == null) {
            return null;
        }
        final Resource resource =
            resolver.getResource(type.startsWith("/") ? type : LINK_TYPES_PATH + "/" + type);
        return resource == null ? null : resource.adaptTo(LinkDefinition.class);
    }

    @Override
    public List<Link> getLinks(final Resource resource)
    {
        final Resource container = resource.getChild(CONTAINER_NAME);
        if (container == null) {
            return List.of();
        }
        return StreamSupport.stream(container.getChildren().spliterator(), false)
            .map(Link::toLink)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    @Override
    public List<Link> getLinks(final Resource resource, final String type)
    {
        final LinkDefinition definition = this.getDefinition(resource.getResourceResolver(), type);
        if (definition == null) {
            return List.of();
        }
        return this.getLinks(resource).stream()
            .filter(link -> {
                final LinkDefinition linkDefinition = link.getDefinition();
                return linkDefinition != null && definition.getPath().equals(linkDefinition.getPath());
            })
            .collect(Collectors.toList());
    }

    @Override
    public List<ResourceLink> getBacklinks(final Resource resource)
    {
        final Session session = resource.getResourceResolver().adaptTo(Session.class);
        if (session == null) {
            return List.of();
        }
        final List<ResourceLink> result = new ArrayList<>();
        try {
            final Node node = session.getNode(resource.getPath());
            this.collectBacklinks(node.getReferences(ResourceLink.REFERENCE_PROPERTY), resource, result);
            this.collectBacklinks(node.getWeakReferences(ResourceLink.REFERENCE_PROPERTY), resource, result);
        } catch (final RepositoryException e) {
            LOGGER.warn("Failed to retrieve the links pointing at {}: {}", resource.getPath(), e.getMessage(), e);
        }
        return result;
    }

    private void collectBacklinks(final PropertyIterator references, final Resource resource,
        final List<ResourceLink> result)
        throws RepositoryException
    {
        while (references.hasNext()) {
            final Property property = references.nextProperty();
            final Resource linkResource = resource.getResourceResolver().getResource(property.getParent().getPath());
            final Link link = linkResource == null ? null : Link.toLink(linkResource);
            if (link instanceof ResourceLink) {
                result.add((ResourceLink) link);
            }
        }
    }

    @Override
    public ResourceLink addLink(final Resource source, final Resource destination, final String type,
        final String label)
    {
        final LinkDefinition definition = this.requireDefinition(source, type);
        if (definition.isExternal()) {
            throw new IllegalArgumentException(
                "Link type " + type + " is external, use addExternalLink to instantiate it");
        }
        if (definition.isBacklinkOnly()) {
            throw new IllegalArgumentException(
                "Link type " + type + " can only be instantiated as an automatic backlink");
        }
        this.checkTypeRequirements(source, definition.getRequiredSourceTypes(), "source");
        this.checkTypeRequirements(destination, definition.getRequiredDestinationTypes(), "destination");
        return this.createResourceLink(source, destination, definition, label, true);
    }

    private ResourceLink createResourceLink(final Resource source, final Resource destination,
        final LinkDefinition definition, final String label, final boolean withBacklink)
    {
        final String destinationId = destination.getValueMap().get(UUID_PROPERTY, String.class);
        if (destinationId == null) {
            throw new IllegalArgumentException(
                "The linked resource " + destination.getPath() + " is not referenceable");
        }
        final Resource container = this.getOrCreateContainer(source);
        final String definitionId = (String) definition.get(UUID_PROPERTY);
        final Resource existing =
            this.findExisting(container, definitionId, ResourceLink.REFERENCE_PROPERTY, destinationId, label);
        final Resource linkResource = existing != null ? existing
            : this.createLinkNode(container,
                definition.isWeak() ? "iap:WeakLink" : "iap:Link",
                definitionId, ResourceLink.REFERENCE_PROPERTY, destinationId, label);
        final ResourceLink link = linkResource.adaptTo(ResourceLink.class);
        if (withBacklink && definition.hasBacklink()) {
            this.addBacklink(link);
        }
        return link;
    }

    @Override
    public ExternalLink addExternalLink(final Resource source, final String type, final String value,
        final String label)
    {
        final LinkDefinition definition = this.requireDefinition(source, type);
        if (!definition.isExternal()) {
            throw new IllegalArgumentException(
                "Link type " + type + " references resources, use addLink to instantiate it");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("External links require a value");
        }
        final String valuePattern = definition.getValuePattern();
        if (valuePattern != null && !value.matches(valuePattern)) {
            throw new IllegalArgumentException(
                "Value " + value + " does not match the pattern required by the " + type + " link type");
        }
        this.checkTypeRequirements(source, definition.getRequiredSourceTypes(), "source");
        final Resource container = this.getOrCreateContainer(source);
        final String definitionId = (String) definition.get(UUID_PROPERTY);
        final Resource existing =
            this.findExisting(container, definitionId, ExternalLink.VALUE_PROPERTY, value, label);
        final Resource linkResource = existing != null ? existing
            : this.createLinkNode(container, "iap:ExternalLink", definitionId, ExternalLink.VALUE_PROPERTY, value,
                label);
        return Objects.requireNonNull(linkResource.adaptTo(ExternalLink.class));
    }

    @Override
    public boolean addBacklink(final ResourceLink original)
    {
        final LinkDefinition definition = original.getDefinition();
        if (definition == null || !definition.hasBacklink()) {
            return false;
        }
        if (original.getBacklink() != null) {
            // The pair is already complete; recognizing this from the stored data alone is what
            // keeps automatic backlink creation from ping-ponging between the two resources.
            return true;
        }
        return this.createBacklink(original, definition.getBacklink());
    }

    private boolean createBacklink(final ResourceLink original, final LinkDefinition backlinkDefinition)
    {
        if (backlinkDefinition == null) {
            LOGGER.warn("The backlink declared for {} cannot be resolved", original.getPath());
            return false;
        }
        final Content destinationModel = original.getDestination();
        final Content linkingResource = original.getLinkingResource();
        if (destinationModel == null || linkingResource == null) {
            return false;
        }
        final Resource destination = destinationModel.getResource();
        final Resource back = linkingResource.getResource();
        if (back.getValueMap().get(UUID_PROPERTY, String.class) == null) {
            LOGGER.warn("No backlink to {} is possible, it is not referenceable", back.getPath());
            return false;
        }
        if (!this.mayAddChild(original.getResource().getResourceResolver(), destination)) {
            // This session may not write the reverse link; it will be completed by the links
            // service user once the original link is committed
            return false;
        }
        // No backlink for the reverse itself: the pair is complete once it is created
        this.createResourceLink(destination, back, backlinkDefinition, original.getLabel(), false);
        return true;
    }

    private boolean mayAddChild(final ResourceResolver resolver, final Resource target)
    {
        // A session is guaranteed here: resolving the link's definition, without which backlink
        // creation is never reached, already required one
        final Session session = Objects.requireNonNull(resolver.adaptTo(Session.class));
        try {
            return session.hasPermission(target.getPath(), Session.ACTION_ADD_NODE);
        } catch (final RepositoryException e) {
            LOGGER.warn("Failed to check write access to {}: {}", target.getPath(), e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean removeLink(final Link link, final boolean removeBacklink)
    {
        final ResourceResolver resolver = link.getResource().getResourceResolver();
        if (removeBacklink && link instanceof ResourceLink) {
            final ResourceLink backlink = ((ResourceLink) link).getBacklink();
            if (backlink != null) {
                this.delete(resolver, backlink.getResource());
            }
        }
        return this.delete(resolver, link.getResource());
    }

    @Override
    public int removeLinks(final Resource source, final Resource destination, final String type,
        final String label)
    {
        final LinkDefinition definition = this.getDefinition(source.getResourceResolver(), type);
        final Resource container = source.getChild(CONTAINER_NAME);
        if (definition == null || container == null) {
            return 0;
        }
        final String definitionId = (String) definition.get(UUID_PROPERTY);
        final String destinationId =
            destination == null ? null : destination.getValueMap().get(UUID_PROPERTY, String.class);
        int removed = 0;
        final List<Resource> matching = StreamSupport.stream(container.getChildren().spliterator(), false)
            .filter(child -> this.matches(child.getValueMap(), definitionId,
                destinationId == null ? null : ResourceLink.REFERENCE_PROPERTY, destinationId, label))
            .collect(Collectors.toList());
        for (final Resource link : matching) {
            if (this.delete(source.getResourceResolver(), link)) {
                ++removed;
            }
        }
        return removed;
    }

    boolean delete(final ResourceResolver resolver, final Resource resource)
    {
        try {
            resolver.delete(resource);
            return true;
        } catch (final PersistenceException e) {
            LOGGER.warn("Failed to delete link {}: {}", resource.getPath(), e.getMessage(), e);
            return false;
        }
    }

    private LinkDefinition requireDefinition(final Resource source, final String type)
    {
        final LinkDefinition definition = this.getDefinition(source.getResourceResolver(), type);
        if (definition == null) {
            throw new IllegalArgumentException("Unknown link type: " + type);
        }
        return definition;
    }

    private void checkTypeRequirements(final Resource resource, final String[] requiredTypes, final String role)
    {
        if (requiredTypes == null || requiredTypes.length == 0) {
            return;
        }
        final Session session = resource.getResourceResolver().adaptTo(Session.class);
        final boolean accepted = Arrays.stream(requiredTypes)
            .anyMatch(requiredType -> this.isOfType(resource, session, requiredType));
        if (!accepted) {
            throw new IllegalArgumentException("The " + role + " resource " + resource.getPath()
                + " is not of one of the required types " + Arrays.toString(requiredTypes));
        }
    }

    private boolean isOfType(final Resource resource, final Session session, final String requiredType)
    {
        if (session != null) {
            try {
                return session.getNode(resource.getPath()).isNodeType(requiredType);
            } catch (final RepositoryException e) {
                LOGGER.warn("Failed to check the node type of {}: {}", resource.getPath(), e.getMessage(), e);
                return false;
            }
        }
        // Without a JCR session only the directly stored types can be compared, without supertype expansion
        final ValueMap properties = resource.getValueMap();
        final String[] mixins = properties.get("jcr:mixinTypes", String[].class);
        return requiredType.equals(properties.get("jcr:primaryType", String.class))
            || (mixins != null && Arrays.asList(mixins).contains(requiredType));
    }

    private Resource getOrCreateContainer(final Resource owner)
    {
        final Resource existing = owner.getChild(CONTAINER_NAME);
        if (existing != null) {
            return existing;
        }
        try (ResourceResolver serviceResolver = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            final Resource serviceOwner = serviceResolver.getResource(owner.getPath());
            if (serviceOwner != null) {
                this.createContainerCheckedOut(serviceResolver, serviceOwner);
                owner.getResourceResolver().refresh();
            }
        } catch (final LoginException | PersistenceException | RepositoryException e) {
            LOGGER.warn("Failed to create the links container on {} with the {} service user: {}",
                owner.getPath(), SUBSERVICE, e.getMessage(), e);
        }
        Resource container = owner.getChild(CONTAINER_NAME);
        if (container == null) {
            // The owner may be invisible to the service user, typically because it is still
            // uncommitted in the caller's own session; create the container there instead
            container = this.createContainer(owner.getResourceResolver(), owner);
        }
        return container;
    }

    /**
     * Creates the links container with the version checkout/checkin dance needed when the owner is a versionable,
     * checked-in resource: a checked-in node rejects new children, links included.
     */
    private void createContainerCheckedOut(final ResourceResolver serviceResolver, final Resource owner)
        throws PersistenceException, RepositoryException
    {
        final Session session = serviceResolver.adaptTo(Session.class);
        final VersionManager versionManager =
            session == null ? null : session.getWorkspace().getVersionManager();
        final boolean wasCheckedOut = versionManager == null || versionManager.isCheckedOut(owner.getPath());
        if (!wasCheckedOut) {
            versionManager.checkout(owner.getPath());
        }
        this.createContainer(serviceResolver, owner);
        serviceResolver.commit();
        if (!wasCheckedOut) {
            versionManager.checkin(owner.getPath());
        }
    }

    Resource createContainer(final ResourceResolver resolver, final Resource owner)
    {
        try {
            return resolver.create(owner, CONTAINER_NAME, Map.of("jcr:primaryType", "iap:Links"));
        } catch (final PersistenceException e) {
            throw new IllegalArgumentException(
                "Cannot create the links container on " + owner.getPath() + ": " + e.getMessage(), e);
        }
    }

    Resource createLinkNode(final Resource container, final String primaryType, final String definitionId,
        final String targetProperty, final String targetValue, final String label)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put("jcr:primaryType", primaryType);
        properties.put(Link.TYPE_PROPERTY, definitionId);
        properties.put(targetProperty, targetValue);
        if (label != null && !label.isEmpty()) {
            properties.put(Link.LABEL_PROPERTY, label);
        }
        try {
            final Resource linkResource = container.getResourceResolver()
                .create(container, UUID.randomUUID().toString(), properties);
            this.retypeReferences(linkResource, targetProperty, "iap:WeakLink".equals(primaryType));
            return linkResource;
        } catch (final PersistenceException | RepositoryException e) {
            throw new IllegalArgumentException(
                "Cannot create a link on " + container.getPath() + ": " + e.getMessage(), e);
        }
    }

    /**
     * The resource API stores strings as STRING properties, without the conversion to the type required by the
     * node type definition that the JCR API performs, so the reference properties of a freshly created link must
     * be explicitly retyped, in the same pending session.
     */
    private void retypeReferences(final Resource linkResource, final String targetProperty, final boolean weak)
        throws RepositoryException
    {
        final Node node = linkResource.adaptTo(Node.class);
        if (node == null) {
            // No real repository behind the resolver, no property type enforcement either
            return;
        }
        node.setProperty(Link.TYPE_PROPERTY, node.getProperty(Link.TYPE_PROPERTY).getString(),
            PropertyType.REFERENCE);
        if (ResourceLink.REFERENCE_PROPERTY.equals(targetProperty)) {
            node.setProperty(targetProperty, node.getProperty(targetProperty).getString(),
                weak ? PropertyType.WEAKREFERENCE : PropertyType.REFERENCE);
        }
    }

    private Resource findExisting(final Resource container, final String definitionId, final String targetProperty,
        final String targetValue, final String label)
    {
        return StreamSupport.stream(container.getChildren().spliterator(), false)
            .filter(child -> this.matches(child.getValueMap(), definitionId, targetProperty, targetValue, label))
            .findFirst()
            .orElse(null);
    }

    /**
     * Raw property comparison, deliberately not going through the models: it must work on freshly created,
     * still uncommitted nodes, where the autocreated {@code sling:resourceType} needed for model dispatch is not
     * materialized yet.
     */
    private boolean matches(final ValueMap properties, final String definitionId, final String targetProperty,
        final String targetValue, final String label)
    {
        if (!Objects.equals(definitionId, properties.get(Link.TYPE_PROPERTY, String.class))) {
            return false;
        }
        if (targetProperty != null
            && !Objects.equals(targetValue, properties.get(targetProperty, String.class))) {
            return false;
        }
        if (label == null) {
            return true;
        }
        final String existingLabel = properties.get(Link.LABEL_PROPERTY, String.class);
        return label.isEmpty() ? (existingLabel == null || existingLabel.isEmpty())
            : label.equals(existingLabel);
    }
}
