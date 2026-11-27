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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
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
 * parser matches each element against the {@link #getXmlElement() element}, {@link #getXmlChildElement() child
 * element} and {@link #getXmlExtensionElement() extension element} of every entry. It keeps the
 * highest-{@link #getPriority() priority} match. That is how a start event carrying a message definition is
 * recognized as a message start event rather than a plain one. The winning entry then says which
 * {@link #getJcrNodeType() node type} to store the node as. Its {@link #getParameters() parameters} say what else
 * to read out of the element. Adding a new kind of node is therefore a matter of adding an entry here, not of
 * adding a node type or a model.</p>
 *
 * <p>The same entries are served as {@link DocumentedItem documentation} at {@code /WorkflowTypes.doc.json}. The
 * visual editor's properties panel reads them there, so this catalogue is a UI contract as much as a parser
 * table.</p>
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
    private String xmlExtensionElement;

    @ValueMapValue
    private String jcrNodeType;

    @ValueMapValue
    private String jcrProperties;

    @ValueMapValue
    private boolean elementTypeDefault;

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
     * The element inside {@code bpmn:extensionElements}, in the IAP namespace, this entry is written as, e.g.
     * {@code addTag}. It tells two entries sharing an XML element apart by what they were configured with rather
     * than by what BPMN calls them: one service task handler from another. It is also where every parameter located
     * in an extension element is written.
     *
     * @return an element name, or {@code null} if the entry is recognized without one
     */
    @Nullable
    public String getXmlExtensionElement()
    {
        return this.xmlExtensionElement;
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
     * Whether an element changed to this entry's element type in the editor becomes this entry, e.g. Exclusive
     * Gateway among the gateways.
     *
     * @return {@code true} if this entry is its element type's default
     */
    public boolean isElementTypeDefault()
    {
        return this.elementTypeDefault;
    }

    /**
     * The values a node of this kind carries beyond the ones every flow node has, in the order they are declared.
     * Each says what it is called, what may be put in it and where a diagram writes it.
     *
     * @return the declared parameters, empty if nothing beyond the common properties is kept
     */
    @NotNull
    public List<Parameter> getParameters()
    {
        return Parameter.declaredIn(this.resource);
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
     * The element type the visual editor lists this entry under, e.g. {@code Service Task}: the first category the
     * entry declares. An entry declaring none falls under the element type its {@code xmlElement} names, so
     * {@code bpmn:serviceTask} gives {@code Service Task}, and an entry without one falls back on the
     * {@link #getDefaultCategory() kind of entry}. The catalogue lists each entry once, under this.
     *
     * @return the element type, alone
     */
    @Override
    @NotNull
    public List<String> getDocumentationCategories()
    {
        return List.of(this.category != null && this.category.length > 0 ? this.category[0]
            : this.xmlElement != null ? elementTypeNamed(this.xmlElement) : this.getDefaultCategory());
    }

    /**
     * The group the visual editor lists this entry under within its element type, e.g. {@code Content}: the second
     * category the entry declares.
     *
     * @return the group, or {@code null} for an entry that declares none
     */
    @Nullable
    public String getGroup()
    {
        return this.category != null && this.category.length > 1 ? this.category[1] : null;
    }

    /** The words of a BPMN element's local name, capitalized: {@code bpmn:callActivity} gives {@code Call Activity}. */
    private static String elementTypeNamed(final String xmlElement)
    {
        final String local = xmlElement.substring(xmlElement.indexOf(':') + 1);
        return Arrays.stream(local.split("(?=\\p{Upper})"))
            .map(word -> word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1))
            .collect(Collectors.joining(" "));
    }

    /**
     * The category of an entry of this kind that declares neither a category nor an {@code xmlElement}, e.g.
     * {@code Events}.
     *
     * @return a category name
     */
    @NotNull
    protected abstract String getDefaultCategory();

    /**
     * {@inheritDoc}
     *
     * <p>On top of the common fields, this adds what the visual editor needs to recognize this entry's elements
     * and to offer a field for each of its parameters. That is the {@code priority} and the {@code xmlElement},
     * {@code xmlChildElement} and {@code xmlExtensionElement} it is written as. Then come the {@code jcrNodeType}
     * it is stored as and its {@code jcrProperties}, the {@code group} it is listed under within its element type,
     * {@code elementTypeDefault} when true, and its {@code parameters}.</p>
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
        addIfPresent(json, "xmlElement", this.xmlElement);
        addIfPresent(json, "jcrNodeType", this.jcrNodeType);
        addIfPresent(json, "xmlChildElement", this.xmlChildElement);
        addIfPresent(json, "xmlExtensionElement", this.xmlExtensionElement);
        final JsonObject fixedProperties = this.getJcrProperties();
        if (fixedProperties != null) {
            json.add("jcrProperties", fixedProperties);
        }
        addIfPresent(json, "group", this.getGroup());
        if (this.elementTypeDefault) {
            json.add("elementTypeDefault", true);
        }
        final List<Parameter> parameters = this.getParameters();
        if (!parameters.isEmpty()) {
            final JsonArrayBuilder declared = Json.createArrayBuilder();
            parameters.forEach(parameter -> declared.add(parameter.toJsonBuilder()));
            json.add("parameters", declared);
        }
        return json;
    }

    private static void addIfPresent(final JsonObjectBuilder json, final String name, final String value)
    {
        if (value != null) {
            json.add(name, value);
        }
    }
}
