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
 * A Sling Model wrapping a {@code wf:Parameter} node: one value a kind of workflow node takes, beyond those
 * every flow node has.
 *
 * <p>The parameter's own name is its node's name. It is also the name of the JCR item the value is stored as, and of
 * the XML attribute or element it is written as.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Parameter.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Parameter extends EntityPart
{
    /** The resource type of a parameter node. */
    public static final String RESOURCE_TYPE = "wf/Parameter";

    /** The resource type of one of a parameter's options. */
    public static final String OPTION_RESOURCE_TYPE = "wf/ParameterOption";

    /** The node type of a parameter. */
    public static final String NODE_TYPE = "wf:Parameter";

    /** The node type of one of a parameter's options. */
    public static final String OPTION_NODE_TYPE = "wf:ParameterOption";

    /** A parameter holding free text. */
    public static final String TYPE_TEXT = "text";

    /** A parameter holding true or false. */
    public static final String TYPE_BOOLEAN = "boolean";

    /** A parameter holding a whole number. */
    public static final String TYPE_LONG = "long";

    /** A parameter holding a reference to a node. */
    public static final String TYPE_REFERENCE = "reference";

    /** A parameter holding the name of a user or group. */
    public static final String TYPE_PRINCIPAL = "principal";

    /**
     * Inside the extension element the vocabulary entry names, such as {@code sourceType="wf/WorkflowVersion"} on
     * {@code <iap:copyContent>}.
     */
    public static final String LOCATION_EXTENSION_ELEMENT = "extensionElement";

    /** An IAP-namespaced attribute of the flow node's own element, such as {@code iap:performers="reviewers"}. */
    public static final String LOCATION_ATTRIBUTE = "attribute";

    /** The property holding an option's stored value. */
    public static final String OPTION_VALUE_PROPERTY = "value";

    private static final String LABEL_PROPERTY = "label";

    private static final String DESCRIPTION_PROPERTY = "description";

    private static final String DATA_TYPE_PROPERTY = "dataType";

    private static final String MIN_VALUES_PROPERTY = "minValues";

    private static final String MAX_VALUES_PROPERTY = "maxValues";

    private static final String REFERENCE_TYPE_PROPERTY = "referenceType";

    private static final String XML_LOCATION_PROPERTY = "xmlLocation";

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

    /**
     * One of the values a parameter offers.
     *
     * @param value the stored value of this option
     * @param label the displayed text associated with this option
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
     * A description of this parameter, to be displayed alongside its label.
     *
     * @return a description, or {@code null} if none is set
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
     * @return a data type name, {@link #TYPE_TEXT} if none is set
     */
    @NotNull
    public String getDataType()
    {
        return this.dataType == null ? TYPE_TEXT : this.dataType;
    }

    /**
     * How many values must be given.
     *
     * @return a count, zero when optional or by default
     */
    public long getMinValues()
    {
        return this.minValues == null ? 0 : this.minValues;
    }

    /**
     * How many values may be given. One is a single value and any other number a list. One if not set.
     *
     * @return a count, or zero for no limit
     */
    public long getMaxValues()
    {
        return this.maxValues == null ? 1 : this.maxValues;
    }

    /**
     * For a {@link #TYPE_REFERENCE reference}, the node type every referenced node must be, or a subtype of it.
     *
     * @return a JCR node type name, or {@code null} if unrestricted
     */
    @Nullable
    public String getReferenceType()
    {
        return this.referenceType;
    }

    /**
     * Where a diagram writes this value: {@link #LOCATION_EXTENSION_ELEMENT inside the extension element} its
     * vocabulary entry names, or {@link #LOCATION_ATTRIBUTE in an attribute} of the flow node's own element.
     *
     * @return a location, never empty
     */
    @NotNull
    public String getXmlLocation()
    {
        return this.xmlLocation == null ? LOCATION_EXTENSION_ELEMENT : this.xmlLocation;
    }

    /**
     * The values this parameter offers.
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
                final String value = values.get(OPTION_VALUE_PROPERTY, String.class);
                // An option with nothing to store is not an offer, however it is labelled
                if (value != null) {
                    result.add(new Option(value, values.get(LABEL_PROPERTY, value)));
                }
            }
        }
        return result;
    }

    /**
     * The parameters declared as children of a vocabulary entry, in order. A child counts by its node type, not its
     * resource type.
     *
     * @param parent the resource declaring them, or {@code null} when there is none
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
     * This parameter's whole declaration, as JSON.
     *
     * <p>The optional parts are left out rather than written empty, matching the rest of the catalogue. A missing
     * description, reference type or set of options is an absent key.</p>
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
        final List<Option> options = this.getOptions();
        if (!options.isEmpty()) {
            final JsonArrayBuilder array = Json.createArrayBuilder();
            options.forEach(option -> array.add(Json.createObjectBuilder()
                .add(OPTION_VALUE_PROPERTY, option.value())
                .add(LABEL_PROPERTY, option.label())));
            json.add("options", array);
        }
        return json;
    }
}
