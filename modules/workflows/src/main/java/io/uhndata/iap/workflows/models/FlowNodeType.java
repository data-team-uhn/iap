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
package io.uhndata.iap.workflows.models;

import java.io.StringReader;
import java.util.List;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;

import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.autodoc.api.DocumentedItem;
import io.uhndata.iap.entities.models.Entity;

/**
 * The abstract base of the {@code /WorkflowTypes} vocabulary: one entry saying that a given BPMN XML element means
 * a given kind of workflow node. Corresponds to the {@code wf:FlowNodeType} node type.
 *
 * <p>This is the translation table between the two representations of a workflow. Reading a BPMN document, the
 * parser matches each element against the {@link #getXmlElement() element} and {@link #getXmlChildElement() child
 * element} of every entry and keeps the highest-{@link #getPriority() priority} match, so that a start event
 * carrying a message definition is recognized as a message start event rather than a plain one; the winning entry
 * then says which {@link #getJcrNodeType() node type} to store the node as. Adding a new kind of node is therefore
 * a matter of adding an entry here, not of adding a node type or a model.</p>
 *
 * <p>The same entries, served as {@link DocumentedItem documentation} at {@code /WorkflowTypes.doc.json}, are what
 * populates the toolbars of the visual editor, so this catalogue is a UI contract as much as a parser table.</p>
 *
 * <p>Like the other abstract bases in the IAP data model, this class is deliberately not itself a registered Sling
 * Model, so that {@code resource.adaptTo(FlowNodeType.class)} dispatches to the concrete subtype.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class FlowNodeType extends Entity implements DocumentedItem
{
    /** The {@code sling:resourceType} of a {@code wf:FlowNodeType} node. */
    public static final String RESOURCE_TYPE = "wf/FlowNodeType";

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private String[] category;

    @ValueMapValue
    private long priority;

    @ValueMapValue
    private String xmlElement;

    @ValueMapValue
    private String xmlChildElement;

    @ValueMapValue
    private String jcrNodeType;

    @ValueMapValue
    private String jcrProperties;

    @ValueMapValue
    private String[] properties;

    /**
     * How specific this entry is, used to pick a winner when several entries match the same XML element: a message
     * start event and a plain start event both match {@code bpmn:startEvent}, and the higher priority of the former
     * is what stops every start event from being read as a plain one.
     *
     * @return a priority, higher being more specific
     */
    public long getPriority()
    {
        return this.priority;
    }

    /**
     * The BPMN XML element this entry recognizes, e.g. {@code bpmn:startEvent}. Mandatory in the node type, so this
     * is only {@code null} for an entry stored as a node of another type carrying this one's resource type.
     *
     * @return a namespaced XML element name, or {@code null} if the entry does not say what it recognizes
     */
    @Nullable
    public String getXmlElement()
    {
        return this.xmlElement;
    }

    /**
     * A child element that must also be present for this entry to match, which is how the flavours of an event are
     * told apart, e.g. {@code bpmn:messageEventDefinition}.
     *
     * @return a namespaced XML element name, or {@code null} if the parent element alone is enough to match
     */
    @Nullable
    public String getXmlChildElement()
    {
        return this.xmlChildElement;
    }

    /**
     * The node type a matching XML element is stored as, e.g. {@code wf:StartEvent}. Several entries may share one,
     * since what tells them apart afterwards is the reference back to this entry. Mandatory in the node type, so
     * this is only {@code null} for an entry stored as a node of another type carrying this one's resource type.
     *
     * @return a JCR node type name, or {@code null} if the entry does not say what it is stored as
     */
    @Nullable
    public String getJcrNodeType()
    {
        return this.jcrNodeType;
    }

    /**
     * The fixed properties to set on a node stored from a matching element, e.g. {@code terminate} for a terminate
     * end event, which shares {@code wf:EndEvent} with the ordinary ones and differs only by this.
     *
     * <p>Only for what genuinely varies between entries sharing a node type. Anything the node type already
     * determines — {@code catching}, say — is autocreated by the node type itself and protected against being
     * written, so naming it here would be a second, weaker source of truth for a value that cannot vary.</p>
     *
     * @return a JSON object of property names and values, or {@code null} if this entry sets no fixed properties or
     *         the stored value is not valid JSON
     */
    @Nullable
    public JsonObject getJcrProperties()
    {
        if (this.jcrProperties == null) {
            return null;
        }
        try (JsonReader reader = Json.createReader(new StringReader(this.jcrProperties))) {
            return reader.readObject();
        } catch (final RuntimeException e) {
            // A malformed vocabulary entry must not take the whole catalogue down with it
            return null;
        }
    }

    /**
     * The names of the attributes to copy across from a matching XML element onto the stored node, on top of the
     * ones every flow node has.
     *
     * @return a copy of the property names, empty if nothing beyond the common properties is kept
     */
    @NotNull
    public List<String> getProperties()
    {
        return this.properties == null ? List.of() : List.of(this.properties);
    }

    /**
     * The human-readable name of this entry, e.g. "Message Start Event". Mandatory in the node type, so the
     * fallback is only reached by an entry stored as a node of another type carrying this one's resource type —
     * which must still be listed under a name rather than taking the catalogue down with it.
     *
     * @return the configured label, or the node name if none is set
     */
    @Override
    @NotNull
    public String getDocumentationLabel()
    {
        return this.label == null ? this.getName() : this.label;
    }

    @Override
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * The toolbar groups this entry appears under in the visual editor, e.g. {@code Start Events}. Falls back on
     * the {@link #getDefaultCategory() group implied by the kind of entry} when none is declared, so that a new
     * entry always lands somewhere sensible.
     *
     * @return a copy of the group names, never empty
     */
    @Override
    @NotNull
    public List<String> getDocumentationCategories()
    {
        return this.category == null || this.category.length == 0
            ? List.of(this.getDefaultCategory())
            // A copy: Arrays.asList would be a live view of the model's own array, writable through by any caller
            : List.of(this.category);
    }

    /**
     * The toolbar group entries of this kind belong to when they do not declare one of their own.
     *
     * @return a group name
     */
    @NotNull
    protected abstract String getDefaultCategory();

    /**
     * {@inheritDoc}
     *
     * <p>On top of the common fields, this adds everything the visual editor needs to build a toolbar entry and to
     * map it back onto BPMN: the {@code priority}, the {@code xmlElement} and {@code xmlChildElement} it stands
     * for, and the {@code jcrNodeType} it is stored as, plus the {@code jcrProperties} and {@code properties} when
     * the entry declares any.</p>
     *
     * <p>Every one of them is added only once there is something to add, the mandatory ones included: an entry
     * missing them is malformed, and the catalogue serving the rest of the vocabulary without it is a better
     * answer than no catalogue at all.</p>
     */
    @Override
    @NotNull
    public JsonObjectBuilder documentationJsonBuilder()
    {
        final JsonObjectBuilder json = DocumentedItem.super.documentationJsonBuilder()
            .add("priority", this.getPriority());
        if (this.xmlElement != null) {
            json.add("xmlElement", this.xmlElement);
        }
        if (this.jcrNodeType != null) {
            json.add("jcrNodeType", this.jcrNodeType);
        }
        if (this.xmlChildElement != null) {
            json.add("xmlChildElement", this.xmlChildElement);
        }
        final JsonObject fixedProperties = this.getJcrProperties();
        if (fixedProperties != null) {
            json.add("jcrProperties", fixedProperties);
        }
        final List<String> copiedProperties = this.getProperties();
        if (!copiedProperties.isEmpty()) {
            json.add("properties", Json.createArrayBuilder(copiedProperties));
        }
        return json;
    }
}
