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
package io.uhndata.iap.schemas.models;

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * A Sling Model wrapping a {@code sch:SchemaVersion} node: one version of a schema, holding the requirements a
 * submission must fulfill. A submission references the version it is filed against.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = SchemaVersion.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class SchemaVersion extends Entity
{
    /** The {@code sling:resourceType} of a {@code sch:SchemaVersion} node. */
    public static final String RESOURCE_TYPE = "sch/SchemaVersion";

    @ValueMapValue
    private String version;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private String workflow;

    /**
     * The version's label, such as "1.0".
     *
     * @return the label
     */
    @NotNull
    public String getVersion()
    {
        return this.version;
    }

    /**
     * What sets this version apart.
     *
     * @return the description; {@code null} if there is none
     */
    @Nullable
    public String getDescription()
    {
        return this.description;
    }

    /**
     * The schema this version belongs to: its parent.
     *
     * @return the schema; {@code null} if this version is stored outside one
     */
    @Nullable
    public Schema getSchema()
    {
        return this.getParent(Schema.RESOURCE_TYPE, Schema.class);
    }

    /**
     * The workflow version that the submissions filed against this schema version go through.
     *
     * @return the workflow version; {@code null} if none is set, or if the reference names nothing this session can
     *         read
     */
    @Nullable
    public WorkflowVersion getWorkflow()
    {
        return this.getReference(this.workflow, WorkflowVersion.class);
    }

    /**
     * Every requirement a submission against this version must fulfill, each read as the model of its own type.
     *
     * @return the requirements, in stored order; empty if there are none
     */
    @NotNull
    public List<Requirement> getRequirements()
    {
        return this.getChildren(Requirement.RESOURCE_TYPE, Requirement.class);
    }

    /**
     * The form requirements of this version.
     *
     * @return the form requirements, in stored order; empty if there are none
     */
    @NotNull
    public List<FormRequirement> getFormRequirements()
    {
        return this.getChildren(FormRequirement.RESOURCE_TYPE, FormRequirement.class);
    }

    /**
     * The document requirements of this version.
     *
     * @return the document requirements, in stored order; empty if there are none
     */
    @NotNull
    public List<DocumentRequirement> getDocumentRequirements()
    {
        return this.getChildren(DocumentRequirement.RESOURCE_TYPE, DocumentRequirement.class);
    }

    /**
     * The approval requirements of this version.
     *
     * @return the approval requirements, in stored order; empty if there are none
     */
    @NotNull
    public List<ApprovalRequirement> getApprovalRequirements()
    {
        return this.getChildren(ApprovalRequirement.RESOURCE_TYPE, ApprovalRequirement.class);
    }
}
