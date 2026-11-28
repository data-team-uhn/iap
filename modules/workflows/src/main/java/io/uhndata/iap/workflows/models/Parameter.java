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

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObjectBuilder;

import org.apache.sling.api.resource.Resource;
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
    public static final String TEXT_DATA_TYPE = "text";

    /** A parameter holding true or false. */
    public static final String BOOLEAN_DATA_TYPE = "boolean";

    /** A parameter holding a whole number. */
    public static final String LONG_DATA_TYPE = "long";

    /** A parameter holding a reference to a node. */
    public static final String REFERENCE_DATA_TYPE = "reference";

    /** A parameter holding the name of a user or group. */
    public static final String PRINCIPAL_DATA_TYPE = "principal";

    /** A parameter holding {@link #getParameters() parameters} of its own. */
    public static final String STRUCTURE_DATA_TYPE = "structure";

    /** The child node holding a {@link #STRUCTURE_DATA_TYPE structure}'s fields. */
    public static final String FIELDS_CHILD = "parameters";

    /**
     * Inside the extension element the vocabulary entry names, such as {@code sourceType="wf/WorkflowVersion"} on
     * {@code <iap:copyContent>}.
     */
    public static final String EXTENSION_ELEMENT_LOCATION = "extensionElement";

    /** An IAP-namespaced attribute of the flow node's own element, such as {@code iap:performers="reviewers"}. */
    public static final String ATTRIBUTE_LOCATION = "attribute";

    /** One of BPMN's own attributes, such as {@code cancelActivity="false"}. */
    public static final String BPMN_ATTRIBUTE_LOCATION = "bpmnAttribute";

    /** The text of one of BPMN's own elements, such as {@code <bpmn:timeDuration>P5D</bpmn:timeDuration>}. */
    public static final String BPMN_ELEMENT_LOCATION = "bpmnElement";

    /** The property holding the parameter's data type. */
    public static final String DATA_TYPE_PROPERTY = "dataType";

    /** The property holding the fewest values the parameter takes. */
    public static final String MIN_VALUES_PROPERTY = "minValues";

    /** The property holding the most values the parameter takes. */
    public static final String MAX_VALUES_PROPERTY = "maxValues";

    /** The most values a parameter takes when it sets no maximum. */
    public static final long DEFAULT_MAX_VALUES = 1;

    /** The property holding where a diagram writes the value. */
    public static final String XML_LOCATION_PROPERTY = "xmlLocation";

    /** The property holding the path of BPMN child elements down to the one holding the value. */
    public static final String XML_PATH_PROPERTY = "xmlPath";

    /** The property naming the field whose value names each element of a repeated structure. */
    public static final String KEY_PARAMETER_PROPERTY = "keyParameter";

    /** The property holding an option's stored value. */
    public static final String OPTION_VALUE_PROPERTY = "value";

    /** The property holding the resource type a reference must point at. */
    public static final String REFERENCE_TYPE_PROPERTY = "referenceType";

    private static final String LABEL_PROPERTY = "label";

    private static final String DESCRIPTION_PROPERTY = "description";

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

    @ValueMapValue
    private String keyParameter;

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
     * The type of data this parameter stores: {@link #TEXT_DATA_TYPE}, {@link #BOOLEAN_DATA_TYPE},
     * {@link #LONG_DATA_TYPE}, {@link #REFERENCE_DATA_TYPE}, {@link #PRINCIPAL_DATA_TYPE} or
     * {@link #STRUCTURE_DATA_TYPE}.
     *
     * @return a data type name, {@link #TEXT_DATA_TYPE} if none is set
     */
    @NotNull
    public String getDataType()
    {
        return this.dataType == null ? TEXT_DATA_TYPE : this.dataType;
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
        return this.maxValues == null ? DEFAULT_MAX_VALUES : this.maxValues;
    }

    /**
     * For a {@link #REFERENCE_DATA_TYPE reference}, the node type every referenced node must be, or a subtype of it.
     *
     * @return a JCR node type name, or {@code null} if unrestricted
     */
    @Nullable
    public String getReferenceType()
    {
        return this.referenceType;
    }

    /**
     * Where a diagram writes this value.
     *
     * <p>One of:</p>
     * <ul>
     * <li>{@link #EXTENSION_ELEMENT_LOCATION}, the default</li>
     * <li>{@link #ATTRIBUTE_LOCATION}</li>
     * <li>{@link #BPMN_ATTRIBUTE_LOCATION}</li>
     * <li>{@link #BPMN_ELEMENT_LOCATION}</li>
     * </ul>
     *
     * @return a location, never empty
     */
    @NotNull
    public String getXmlLocation()
    {
        return this.xmlLocation == null ? EXTENSION_ELEMENT_LOCATION : this.xmlLocation;
    }

    /**
     * For a BPMN location, the BPMN child elements leading from the flow node's element to the one holding the
     * value, separated by slashes, e.g. {@code multiInstanceLoopCharacteristics/complexBehaviorDefinition}.
     *
     * @return a path, or {@code null} when the flow node's element holds the value itself
     */
    @Nullable
    public String getXmlPath()
    {
        return this.xmlPath;
    }

    /**
     * For a repeated {@link #STRUCTURE_DATA_TYPE structure}, the field whose value names the node each element is
     * stored as, instead of being stored as a property.
     *
     * @return a field name, or {@code null} if the parameter names none
     */
    @Nullable
    public String getKeyParameter()
    {
        return this.keyParameter;
    }

    /**
     * The fields of a {@link #STRUCTURE_DATA_TYPE structure}, in the order they are declared under its
     * {@link #FIELDS_CHILD} child.
     *
     * @return the nested parameters, empty for a parameter of any other type
     */
    @NotNull
    public List<Parameter> getParameters()
    {
        return declaredIn(this.resource.getChild(FIELDS_CHILD));
    }

    /**
     * The values this parameter offers.
     *
     * @return the offered options, empty if the parameter takes anything its data type accepts
     */
    @NotNull
    public List<Option> getOptions()
    {
        return StreamSupport.stream(this.resource.getChildren().spliterator(), false)
            .filter(child -> isOfNodeType(child, OPTION_NODE_TYPE))
            .map(Resource::getValueMap)
            // An option with nothing to store is not an offer, however it is labelled
            .flatMap(values -> Stream.ofNullable(values.get(OPTION_VALUE_PROPERTY, String.class))
                .map(value -> new Option(value, values.get(LABEL_PROPERTY, value))))
            .collect(Collectors.toList());
    }

    /**
     * The parameters declared as children of a vocabulary entry or of a structure's {@link #FIELDS_CHILD}, in
     * order. A child counts by its node type, not its resource type.
     *
     * @param parent the resource declaring them, or {@code null} when there is none
     * @return the declared parameters, empty when there is no such node
     */
    @NotNull
    static List<Parameter> declaredIn(@Nullable final Resource parent)
    {
        return parent == null ? List.of() : StreamSupport.stream(parent.getChildren().spliterator(), false)
            .filter(child -> isOfNodeType(child, NODE_TYPE))
            .map(child -> child.adaptTo(Parameter.class))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
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
     * <p>A missing description, reference type, path, key, set of options or set of fields is an absent key.</p>
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
        if (this.getKeyParameter() != null) {
            json.add(KEY_PARAMETER_PROPERTY, this.getKeyParameter());
        }
        final List<Option> options = this.getOptions();
        if (!options.isEmpty()) {
            final JsonArrayBuilder array = Json.createArrayBuilder();
            options.forEach(option -> array.add(Json.createObjectBuilder()
                .add(OPTION_VALUE_PROPERTY, option.value())
                .add(LABEL_PROPERTY, option.label())));
            json.add("options", array);
        }
        final List<Parameter> fields = this.getParameters();
        if (!fields.isEmpty()) {
            final JsonArrayBuilder array = Json.createArrayBuilder();
            fields.forEach(field -> array.add(field.toJsonBuilder()));
            json.add(FIELDS_CHILD, array);
        }
        return json;
    }
}
