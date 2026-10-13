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

import java.util.ArrayList;
import java.util.List;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code wf:Parameter} node: one value a kind of workflow node takes, beyond the ones
 * every flow node has. Corresponds to the {@code wf:Parameter} node type.
 *
 * <p>A parameter is what makes the vocabulary self-describing. It says what the value is called, what may be put
 * in it, how many of it there may be and where a diagram writes it.</p>
 *
 * <p>The parameter's own name is its node's name, which is also the JCR property it is stored in and the XML
 * attribute or element it is written as.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Parameter.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Parameter extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code wf:Parameter} node. */
    public static final String RESOURCE_TYPE = "wf/Parameter";

    /** The {@code sling:resourceType} of a {@code wf:ParameterOption} node, one of the values a parameter offers. */
    public static final String OPTION_RESOURCE_TYPE = "wf/ParameterOption";

    /** The node type of a parameter, which a node must have or extend to be read as one. */
    public static final String NODE_TYPE = "wf:Parameter";

    /** The node type of an option, likewise. */
    public static final String OPTION_NODE_TYPE = "wf:ParameterOption";

    /** The {@link #getDataType() data type} for free text. */
    public static final String TYPE_TEXT = "text";

    /** The data type for yes or no. */
    public static final String TYPE_BOOLEAN = "boolean";

    /** The data type for a whole number. */
    public static final String TYPE_LONG = "long";

    /** The data type for the path of another node. */
    public static final String TYPE_REFERENCE = "reference";

    /** The data type for the name of a user or group. */
    public static final String TYPE_PRINCIPAL = "principal";

    /** The {@link #getXmlLocation() location} of a value inside the extension element its entry names. */
    public static final String LOCATION_EXTENSION_ELEMENT = "extensionElement";

    /** The {@link #getXmlLocation() location} of a value in an IAP-namespaced attribute of the element itself. */
    public static final String LOCATION_ATTRIBUTE = "attribute";

    /** The {@link #getXmlLocation() location} of a value in one of BPMN's own attributes. */
    public static final String LOCATION_BPMN_ATTRIBUTE = "bpmnAttribute";

    /** The {@link #getXmlLocation() location} of a value in the text of one of BPMN's own elements. */
    public static final String LOCATION_BPMN_ELEMENT = "bpmnElement";

    private static final String VALUE_PROPERTY = "value";

    private static final String LABEL_PROPERTY = "label";

    private static final String DESCRIPTION_PROPERTY = "description";

    private static final String DATA_TYPE_PROPERTY = "dataType";

    private static final String MIN_VALUES_PROPERTY = "minValues";

    private static final String MAX_VALUES_PROPERTY = "maxValues";

    private static final String REFERENCE_TYPE_PROPERTY = "referenceType";

    private static final String XML_LOCATION_PROPERTY = "xmlLocation";

    private static final String XML_PATH_PROPERTY = "xmlPath";

    @ValueMapValue
    private String label;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private String dataType;

    @ValueMapValue
    private Long minValues;

    @ValueMapValue
    private Long maxValues;

    @ValueMapValue
    private String referenceType;

    @ValueMapValue
    private String xmlLocation;

    @ValueMapValue
    private String xmlPath;

    /**
     * One of the values a parameter offers. A part of the parameter rather than a model of its own: an option is
     * two strings that are only ever read together with the parameter offering them.
     *
     * @param value what a diagram picking this option stores, and what a handler then reads
     * @param label what the author picking it reads, the value itself when the option gives no other
     * @since 0.1.0
     */
    public record Option(@NotNull String value, @NotNull String label)
    {
    }

    /**
     * What an author sees this parameter called.
     *
     * @return the configured label, or the parameter's name if none is set
     */
    @NotNull
    public String getLabel()
    {
        return this.label == null || this.label.isEmpty() ? this.getName() : this.label;
    }

    /**
     * What this parameter means, offered to whoever is filling it in.
     *
     * @return a description, or {@code null} if the parameter does not explain itself
     */
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * What may be put in this parameter: {@link #TYPE_TEXT}, {@link #TYPE_BOOLEAN}, {@link #TYPE_LONG},
     * {@link #TYPE_REFERENCE} or {@link #TYPE_PRINCIPAL}. Autocreated by the node type, so the
     * fallback is only reached by a parameter stored as a node of another type.
     *
     * @return a data type name, never empty
     */
    @NotNull
    public String getDataType()
    {
        return this.dataType == null ? TYPE_TEXT : this.dataType;
    }

    /**
     * How many values must be given. One or more is what makes the parameter required.
     *
     * @return a count, zero when a diagram may leave the parameter out
     */
    public long getMinValues()
    {
        return this.minValues == null ? 0 : this.minValues;
    }

    /**
     * How many values may be given, zero meaning unbounded. Anything but exactly one means the stored property is
     * a list. An absent maximum reads as one, which is what the node type autocreates.
     *
     * @return a count, or zero for no limit
     */
    public long getMaxValues()
    {
        return this.maxValues == null ? 1 : this.maxValues;
    }

    /**
     * The node type every value of a {@link #TYPE_REFERENCE reference} parameter must be of.
     *
     * @return a JCR node type name, or {@code null} if any node that exists will do
     */
    @Nullable
    public String getReferenceType()
    {
        return this.referenceType;
    }

    /**
     * Where a diagram writes this value. IAP's markup is {@link #LOCATION_EXTENSION_ELEMENT the extension element}
     * its vocabulary entry names, or {@link #LOCATION_ATTRIBUTE an attribute} of the flow node's own element.
     * BPMN's own is {@link #LOCATION_BPMN_ATTRIBUTE an attribute} or {@link #LOCATION_BPMN_ELEMENT an element's
     * text}.
     *
     * @return a location, never empty
     */
    @NotNull
    public String getXmlLocation()
    {
        return this.xmlLocation == null ? LOCATION_EXTENSION_ELEMENT : this.xmlLocation;
    }

    /**
     * For a value BPMN holds, the BPMN child elements leading from the flow node's element to the one holding it,
     * separated by slashes, e.g. {@code timerEventDefinition}.
     *
     * @return a path, or {@code null} when the flow node's element holds the value itself
     */
    @Nullable
    public String getXmlPath()
    {
        return this.xmlPath;
    }

    /**
     * The values this parameter offers, in the order they are declared.
     *
     * @return the offered options, empty if the parameter takes anything its data type accepts
     */
    @NotNull
    public List<Option> getOptions()
    {
        final List<Option> result = new ArrayList<>();
        for (final Resource child : this.resource.getChildren()) {
            if (isOfNodeType(child, OPTION_NODE_TYPE)) {
                final ValueMap values = child.getValueMap();
                final String value = values.get(VALUE_PROPERTY, String.class);
                // An option with nothing to store is not an offer, however it is labelled
                if (value != null) {
                    result.add(new Option(value, values.get(LABEL_PROPERTY, value)));
                }
            }
        }
        return result;
    }

    /**
     * The parameters declared as children of a vocabulary entry or of a structure's {@link #FIELDS_NODE}, in
     * order. A child is one by its node type rather than its resource type, as the parser reads it. The node type
     * is what makes a stored parameter what it is.
     *
     * @param parent the node declaring them, may be {@code null}
     * @return the declared parameters, empty when there is no such node
     */
    @NotNull
    static List<Parameter> declaredIn(@Nullable final Resource parent)
    {
        final List<Parameter> result = new ArrayList<>();
        if (parent != null) {
            for (final Resource child : parent.getChildren()) {
                final Parameter parameter = isOfNodeType(child, NODE_TYPE) ? child.adaptTo(Parameter.class) : null;
                if (parameter != null) {
                    result.add(parameter);
                }
            }
        }
        return result;
    }

    /** Whether a resource is a node of the given type or a subtype of it. */
    private static boolean isOfNodeType(final Resource resource, final String nodeType)
    {
        final Node node = resource.adaptTo(Node.class);
        try {
            return node != null && node.isNodeType(nodeType);
        } catch (final RepositoryException e) {
            // A node that cannot say what it is cannot be read as a declaration either
            return false;
        }
    }

    /**
     * This parameter as the documentation catalogue serves it: its whole declaration.
     *
     * <p>The optional parts are left out rather than written empty, matching the rest of the catalogue. A missing
     * description, reference type, path or set of options is an absent key.</p>
     *
     * @return a builder holding this parameter's declaration
     */
    @NotNull
    public JsonObjectBuilder toJsonBuilder()
    {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add("name", this.getName())
            .add(LABEL_PROPERTY, this.getLabel());
        if (this.description != null) {
            json.add(DESCRIPTION_PROPERTY, this.description);
        }
        json.add(DATA_TYPE_PROPERTY, this.getDataType())
            .add(MIN_VALUES_PROPERTY, this.getMinValues())
            .add(MAX_VALUES_PROPERTY, this.getMaxValues());
        if (this.getReferenceType() != null) {
            json.add(REFERENCE_TYPE_PROPERTY, this.getReferenceType());
        }
        json.add(XML_LOCATION_PROPERTY, this.getXmlLocation());
        if (this.getXmlPath() != null) {
            json.add(XML_PATH_PROPERTY, this.getXmlPath());
        }
        final List<Option> options = this.getOptions();
        if (!options.isEmpty()) {
            final JsonArrayBuilder array = Json.createArrayBuilder();
            options.forEach(option -> array.add(Json.createObjectBuilder()
                .add(VALUE_PROPERTY, option.value())
                .add(LABEL_PROPERTY, option.label())));
            json.add("options", array);
        }
        return json;
    }
}
