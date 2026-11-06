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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.jcr.Node;
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
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.links.api.LinkManager;
import io.uhndata.iap.links.models.ExternalLink;
import io.uhndata.iap.links.models.ExternalLinkDefinition;
import io.uhndata.iap.links.models.InternalLink;
import io.uhndata.iap.links.models.InternalLinkDefinition;
import io.uhndata.iap.links.models.Link;
import io.uhndata.iap.links.models.LinkDefinition;

/**
 * Resolves the link definitions, and carries out the {@link LinkOperations} behind the links models. Every write
 * goes through the resolver of the model that asked, except one. A missing {@code link:links} container is created
 * and committed by the {@code iap-links} service user. When that service user cannot see the owner, such as an owner
 * not committed yet, the caller's session creates the container instead.
 *
 * <p>
 * The definitions are cached until {@value LinkManager#LINK_TYPES_PATH} changes. The cached models read through the
 * service resolver they came from, so that resolver stays open while they are cached. It is closed when a newer set
 * replaces them.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { LinkManager.class, LinkOperations.class, ResourceChangeListener.class },
    property = { ResourceChangeListener.PATHS + "=" + LinkManager.LINK_TYPES_PATH })
public class LinkManagerImpl implements LinkManager, LinkOperations, ResourceChangeListener
{
    /** The subservice name mapped to the {@code iap-links} service user. */
    static final String SUBSERVICE_NAME = "links";

    /** The subservice name mapped to the {@code iap-link-types} service user, which reads the definitions. */
    static final String DEFINITIONS_SUBSERVICE = "link-types";

    private static final String UUID_PROPERTY = "jcr:uuid";

    private static final String PRIMARY_TYPE_PROPERTY = "jcr:primaryType";

    private static final String MIXIN_TYPES_PROPERTY = "jcr:mixinTypes";

    private static final Logger LOGGER = LoggerFactory.getLogger(LinkManagerImpl.class);

    private static final Map<String, Object> DEFINITIONS_SERVICE_USER =
        Map.of(ResourceResolverFactory.SUBSERVICE, DEFINITIONS_SUBSERVICE);

    @Reference
    private ResourceResolverFactory resolverFactory;

    /** Guards the cached definitions and the resolver they are bound to. */
    private final Object definitionsLock = new Object();

    /** The cached definitions, by name; {@code null} until the next lookup reads them again. */
    private Map<String, LinkDefinition> definitions;

    /** The service resolver the cached definitions read through. */
    private ResourceResolver definitionsResolver;

    @Deactivate
    protected void deactivate()
    {
        synchronized (this.definitionsLock) {
            this.definitions = null;
            closeResolver();
        }
    }

    @Override
    public void onChange(final List<ResourceChange> changes)
    {
        synchronized (this.definitionsLock) {
            // The next lookup reads them again, and closes the resolver of the stale ones
            this.definitions = null;
        }
    }

    @Override
    public LinkDefinition getDefinition(final String type)
    {
        final String name = this.toDefinitionName(type);
        if (name == null) {
            return null;
        }
        synchronized (this.definitionsLock) {
            if (this.definitions == null) {
                readDefinitions();
            }
            return this.definitions.get(name);
        }
    }

    /** Reduces a definition's name or path to its name; {@code null} for a path outside the definitions. */
    private String toDefinitionName(final String type)
    {
        if (type == null || !type.startsWith("/")) {
            return type;
        }
        if (type.startsWith(LINK_TYPES_PATH + "/")) {
            return type.substring(LINK_TYPES_PATH.length() + 1);
        }
        return null;
    }

    private void readDefinitions()
    {
        final ResourceResolver previous = this.definitionsResolver;
        try {
            this.definitionsResolver = this.resolverFactory.getServiceResourceResolver(DEFINITIONS_SERVICE_USER);
        } catch (final LoginException e) {
            this.definitionsResolver = previous;
            this.definitions = Map.of();
            LOGGER.error("Cannot read the link definitions, the link-types service user is not available: {}",
                e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(LinkManagerImpl.class, "serviceLogin"));
            return;
        }
        final Resource homepage = this.definitionsResolver.getResource(LINK_TYPES_PATH);
        this.definitions = homepage == null ? Map.of()
            : StreamSupport.stream(homepage.getChildren().spliterator(), false)
                .filter(child -> child.isResourceType(LinkDefinition.RESOURCE_TYPE))
                .map(child -> child.adaptTo(LinkDefinition.class))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(LinkDefinition::getName, Function.identity()));
        if (previous != null) {
            previous.close();
        }
    }

    private void closeResolver()
    {
        if (this.definitionsResolver != null) {
            this.definitionsResolver.close();
            this.definitionsResolver = null;
        }
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
        final LinkDefinition definition = this.getDefinition(type);
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
    public List<InternalLink> getBacklinks(final Resource resource)
    {
        final Session session = resource.getResourceResolver().adaptTo(Session.class);
        if (session == null) {
            return List.of();
        }
        try {
            return BacklinkFinder.find(session, resource);
        } catch (final RepositoryException e) {
            LOGGER.warn("Failed to retrieve the links pointing at {}: {}", resource.getPath(), e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(LinkManagerImpl.class, "getBacklinks").about(resource));
            return List.of();
        }
    }

    @Override
    public InternalLink addLink(final Resource source, final Content destination, final String type,
        final String label)
    {
        final LinkDefinition declared = this.requireDefinition(type);
        if (!(declared instanceof InternalLinkDefinition)) {
            throw new IllegalArgumentException(
                "Link type " + type + " is external, use addExternalLink to instantiate it");
        }
        final InternalLinkDefinition definition = (InternalLinkDefinition) declared;
        if (definition.isBacklinkOnly()) {
            throw new IllegalArgumentException(
                "Link type " + type + " can only be instantiated as an automatic backlink");
        }
        this.checkTypeRequirements(source, definition.getRequiredSourceTypes(), "source");
        // A model does not expose its resource, so the destination is checked through the source's session
        this.checkTypeRequirements(destination, source.getResourceResolver().adaptTo(Session.class),
            definition.getRequiredDestinationTypes(), "destination");
        return this.createInternalLink(source, destination, definition, label, true);
    }

    private InternalLink createInternalLink(final Resource source, final Content destination,
        final InternalLinkDefinition definition, final String label, final boolean withBacklink)
    {
        final String destinationId = (String) destination.get(UUID_PROPERTY);
        if (destinationId == null) {
            throw new IllegalArgumentException(
                "The linked content " + destination.getPath() + " is not referenceable");
        }
        final Resource container = this.getOrCreateContainer(source);
        final String definitionId = (String) definition.get(UUID_PROPERTY);
        final Resource existing =
            this.findExisting(container, definitionId, InternalLink.REFERENCE_PROPERTY, destinationId, label);
        final Resource linkResource = existing != null ? existing
            : this.createLinkNode(container,
                definition.isWeak() ? "link:WeakLink" : "link:Link",
                definitionId, InternalLink.REFERENCE_PROPERTY, destinationId, label);
        final InternalLink link = linkResource.adaptTo(InternalLink.class);
        if (withBacklink && definition.hasBacklink()) {
            this.addBacklink(linkResource);
        }
        return link;
    }

    @Override
    public ExternalLink addExternalLink(final Resource source, final String type, final String value,
        final String label)
    {
        final LinkDefinition declared = this.requireDefinition(type);
        if (!(declared instanceof ExternalLinkDefinition)) {
            throw new IllegalArgumentException(
                "Link type " + type + " references resources, use addLink to instantiate it");
        }
        final ExternalLinkDefinition definition = (ExternalLinkDefinition) declared;
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("External links require a value");
        }
        if (!definition.accepts(value)) {
            throw new IllegalArgumentException(
                "Value " + value + " does not match the pattern required by the " + type + " link type");
        }
        this.checkTypeRequirements(source, definition.getRequiredSourceTypes(), "source");
        final Resource container = this.getOrCreateContainer(source);
        final String definitionId = (String) definition.get(UUID_PROPERTY);
        final Resource existing =
            this.findExisting(container, definitionId, ExternalLink.VALUE_PROPERTY, value, label);
        final Resource linkResource = existing != null ? existing
            : this.createLinkNode(container, "link:ExternalLink", definitionId, ExternalLink.VALUE_PROPERTY, value,
                label);
        return Objects.requireNonNull(linkResource.adaptTo(ExternalLink.class));
    }

    @Override
    public boolean addBacklink(final Resource link)
    {
        final Link model = Link.toLink(link);
        if (!(model instanceof InternalLink)) {
            return false;
        }
        final InternalLink original = (InternalLink) model;
        final InternalLinkDefinition definition = original.getDefinition();
        if (definition == null || !definition.hasBacklink()) {
            return false;
        }
        if (original.getBacklink() != null) {
            // The pair is complete
            return true;
        }
        return this.createBacklink(link.getResourceResolver(), original, definition.getBacklink());
    }

    private boolean createBacklink(final ResourceResolver resolver, final InternalLink original,
        final InternalLinkDefinition backlinkDefinition)
    {
        if (backlinkDefinition == null) {
            LOGGER.warn("The backlink declared for {} cannot be resolved", original.getPath());
            return false;
        }
        final Content destinationModel = original.getDestination();
        final Content linkingResource = original.getSource();
        final Resource destination =
            destinationModel == null ? null : resolver.getResource(destinationModel.getPath());
        final Resource back = linkingResource == null ? null : resolver.getResource(linkingResource.getPath());
        if (destination == null || back == null) {
            return false;
        }
        if (linkingResource.get(UUID_PROPERTY) == null) {
            LOGGER.warn("No backlink to {} is possible, it is not referenceable", linkingResource.getPath());
            return false;
        }
        if (!this.mayAddChild(resolver, destination)) {
            // This session may not add the reverse; the backlinks listener adds it after the commit
            return false;
        }
        // The reverse needs no reverse of its own
        this.createInternalLink(destination, linkingResource, backlinkDefinition, original.getLabel(), false);
        return true;
    }

    private boolean mayAddChild(final ResourceResolver resolver, final Resource target)
    {
        final Session session = resolver.adaptTo(Session.class);
        if (session == null) {
            // No JCR session to check permissions with; the backlinks listener adds the reverse
            return false;
        }
        try {
            return session.hasPermission(target.getPath(), Session.ACTION_ADD_NODE);
        } catch (final RepositoryException e) {
            LOGGER.warn("Failed to check write access to {}: {}", target.getPath(), e.getMessage(), e);
            return false;
        }
    }

    @Override
    public boolean remove(final Resource link, final boolean removeBacklink)
    {
        final ResourceResolver resolver = link.getResourceResolver();
        final Link model = Link.toLink(link);
        if (model == null) {
            return false;
        }
        if (removeBacklink && model instanceof InternalLink) {
            final InternalLink backlink = ((InternalLink) model).getBacklink();
            final Resource backlinkResource =
                backlink == null ? null : resolver.getResource(backlink.getPath());
            if (backlinkResource != null) {
                this.delete(resolver, backlinkResource);
            }
        }
        return this.delete(resolver, link);
    }

    @Override
    public int removeLinks(final Resource source, final Content destination, final String type,
        final String label)
    {
        final LinkDefinition definition = this.getDefinition(type);
        final Resource container = source.getChild(CONTAINER_NAME);
        if (definition == null || container == null) {
            return 0;
        }
        final String definitionId = (String) definition.get(UUID_PROPERTY);
        final String destinationId =
            destination == null ? null : (String) destination.get(UUID_PROPERTY);
        int removed = 0;
        final List<Resource> matching = StreamSupport.stream(container.getChildren().spliterator(), false)
            .filter(child -> this.matches(child.getValueMap(), definitionId,
                destinationId == null ? null : InternalLink.REFERENCE_PROPERTY, destinationId, label))
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

    private LinkDefinition requireDefinition(final String type)
    {
        final LinkDefinition definition = this.getDefinition(type);
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
        final ValueMap properties = resource.getValueMap();
        final boolean accepted = Arrays.stream(requiredTypes)
            .anyMatch(requiredType -> this.isOfType(resource.getPath(),
                properties.get(PRIMARY_TYPE_PROPERTY, String.class),
                properties.get(MIXIN_TYPES_PROPERTY, String[].class), session, requiredType));
        if (!accepted) {
            throw new IllegalArgumentException("The " + role + " content " + resource.getPath()
                + " is not of one of the required types " + Arrays.toString(requiredTypes));
        }
    }

    // The same check on a model, which does not expose its resource: its stored types stand in for it
    private void checkTypeRequirements(final Content content, final Session session, final String[] requiredTypes,
        final String role)
    {
        if (requiredTypes == null || requiredTypes.length == 0) {
            return;
        }
        final boolean accepted = Arrays.stream(requiredTypes)
            .anyMatch(requiredType -> this.isOfType(content.getPath(), content.get(PRIMARY_TYPE_PROPERTY),
                (String[]) content.get(MIXIN_TYPES_PROPERTY), session, requiredType));
        if (!accepted) {
            throw new IllegalArgumentException("The " + role + " content " + content.getPath()
                + " is not of one of the required types " + Arrays.toString(requiredTypes));
        }
    }

    private boolean isOfType(final String path, final Object primaryType, final String[] mixins,
        final Session session, final String requiredType)
    {
        if (session != null) {
            try {
                return session.getNode(path).isNodeType(requiredType);
            } catch (final RepositoryException e) {
                LOGGER.warn("Failed to check the node type of {}: {}", path, e.getMessage(), e);
                return false;
            }
        }
        // Without a JCR session, only the stored types match, not their supertypes
        return requiredType.equals(primaryType)
            || (mixins != null && Arrays.asList(mixins).contains(requiredType));
    }

    private Resource getOrCreateContainer(final Resource owner)
    {
        final Resource existing = owner.getChild(CONTAINER_NAME);
        if (existing != null) {
            return existing;
        }
        try (ResourceResolver serviceResolver = this.resolverFactory
            .getServiceResourceResolver(Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME))) {
            final Resource serviceOwner = serviceResolver.getResource(owner.getPath());
            if (serviceOwner != null) {
                this.createContainerCheckedOut(serviceResolver, serviceOwner);
                owner.getResourceResolver().refresh();
            }
        } catch (final LoginException | PersistenceException | RepositoryException e) {
            LOGGER.warn("Failed to create the links container on {} with the {} service user: {}",
                owner.getPath(), SUBSERVICE_NAME, e.getMessage(), e);
        }
        Resource container = owner.getChild(CONTAINER_NAME);
        if (container == null) {
            // The service user failed, or cannot see the owner, such as one not committed yet
            container = this.createContainer(owner.getResourceResolver(), owner);
        }
        return container;
    }

    /**
     * Creates and commits the links container. A checked-in node accepts no new child, so a checked-in owner is
     * checked out first, and checked in again after.
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
            return resolver.create(owner, CONTAINER_NAME, Map.of(PRIMARY_TYPE_PROPERTY, "link:Links"));
        } catch (final PersistenceException e) {
            throw new IllegalArgumentException(
                "Cannot create the links container on " + owner.getPath() + ": " + e.getMessage(), e);
        }
    }

    Resource createLinkNode(final Resource container, final String primaryType, final String definitionId,
        final String targetProperty, final String targetValue, final String label)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(PRIMARY_TYPE_PROPERTY, primaryType);
        properties.put(Link.TYPE_PROPERTY, definitionId);
        properties.put(targetProperty, targetValue);
        if (label != null && !label.isEmpty()) {
            properties.put(Link.LABEL_PROPERTY, label);
        }
        try {
            final Resource linkResource = container.getResourceResolver()
                .create(container, UUID.randomUUID().toString(), properties);
            this.retypeReferences(linkResource, targetProperty, "link:WeakLink".equals(primaryType));
            return linkResource;
        } catch (final PersistenceException | RepositoryException e) {
            throw new IllegalArgumentException(
                "Cannot create a link on " + container.getPath() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Sets the {@code type} and {@code reference} of a new link again, as references. The resource API stores a
     * string as a STRING property, whatever the node type declares.
     */
    private void retypeReferences(final Resource linkResource, final String targetProperty, final boolean weak)
        throws RepositoryException
    {
        final Node node = linkResource.adaptTo(Node.class);
        if (node == null) {
            // No JCR node, so no property types to set
            return;
        }
        node.setProperty(Link.TYPE_PROPERTY, node.getProperty(Link.TYPE_PROPERTY).getString(),
            PropertyType.REFERENCE);
        if (InternalLink.REFERENCE_PROPERTY.equals(targetProperty)) {
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
     * Whether a link node's stored properties match a definition, a target and a label. A {@code null} target
     * property matches any target. A {@code null} label matches any label, and an empty one only a link without
     * a label.
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
