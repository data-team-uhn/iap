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

import java.util.Calendar;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code wf:Variable} node: one named piece of data a running workflow carries along, such
 * as the number of days a leave request asks for. The variable's name is the node's own name, so looking one up is
 * a direct child lookup rather than a scan.
 *
 * <p>The value is stored in whichever of the typed properties matches the {@link #getDataType() declared type},
 * rather than in one untyped property, so that the repository indexes it as what it is and comparisons behave the
 * way the type says they should.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Variable.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Variable extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code wf:Variable} node. */
    public static final String RESOURCE_TYPE = "wf/Variable";

    /** The {@link #getDataType() data type} of a variable holding text. */
    public static final String TYPE_STRING = "string";

    /** The {@link #getDataType() data type} of a variable holding a whole number. */
    public static final String TYPE_LONG = "long";

    /** The {@link #getDataType() data type} of a variable holding a fractional number. */
    public static final String TYPE_DOUBLE = "double";

    /** The {@link #getDataType() data type} of a variable holding a yes-or-no value. */
    public static final String TYPE_BOOLEAN = "boolean";

    /** The {@link #getDataType() data type} of a variable holding a date. */
    public static final String TYPE_DATE = "date";

    /** The {@link #getDataType() data type} of a variable pointing at another piece of content. */
    public static final String TYPE_REFERENCE = "reference";

    @ValueMapValue
    private String dataType;

    @ValueMapValue
    private String stringValue;

    @ValueMapValue
    private Long longValue;

    @ValueMapValue
    private Double doubleValue;

    @ValueMapValue
    private Boolean booleanValue;

    @ValueMapValue
    private Calendar dateValue;

    @ValueMapValue
    private String referenceValue;

    /**
     * The declared type of this variable's value, one of {@link #TYPE_STRING}, {@link #TYPE_LONG},
     * {@link #TYPE_DOUBLE}, {@link #TYPE_BOOLEAN}, {@link #TYPE_DATE} or {@link #TYPE_REFERENCE}.
     *
     * @return a data type name
     */
    @NotNull
    public String getDataType()
    {
        return this.dataType;
    }

    /**
     * This variable's value, read from whichever typed property its {@link #getDataType() declared type} says to
     * read, and returned as the matching Java type: a {@link String}, {@link Long}, {@link Double},
     * {@link Boolean}, {@link Calendar}, or the {@link Content} a reference points at.
     *
     * @return the value, or {@code null} if the variable is unset, its type is not one of the known ones, or a
     *         reference cannot be resolved
     */
    @Nullable
    public Object getValue()
    {
        return switch (this.dataType == null ? "" : this.dataType) {
            case TYPE_STRING -> this.stringValue;
            case TYPE_LONG -> this.longValue;
            case TYPE_DOUBLE -> this.doubleValue;
            case TYPE_BOOLEAN -> this.booleanValue;
            // A copy, since Calendar is mutable and callers must not be able to alter the model's own state
            case TYPE_DATE -> this.dateValue == null ? null : (Calendar) this.dateValue.clone();
            case TYPE_REFERENCE -> this.getReference(this.referenceValue, Content.class);
            default -> null;
        };
    }
}
