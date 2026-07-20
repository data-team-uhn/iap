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

import io.uhndata.iap.entities.models.Entity;

/**
 * A Sling Model wrapping a {@code sch:SchemaVersion} node; holds the requirements a submission must fulfill.
 * Submissions reference a specific version.
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
    private boolean active;

    @ValueMapValue
    private String workflow;

    /**
     * The version label, e.g. "1.0".
     *
     * @return a version label
     */
    public String getVersion()
    {
        return this.version;
    }

    /**
     * An optional description of this version.
     *
     * @return a description, or {@code null} if not set
     */
    public String getDescription()
    {
        return this.description;
    }

    /**
     * Whether new submissions may be created against this version.
     *
     * @return {@code true} if this version accepts new submissions
     */
    public boolean isActive()
    {
        return this.active;
    }

    /**
     * The identifier of the {@code wf:WorkflowVersion} driving submissions under this schema version.
     * TODO: replace the return type with a {@code WorkflowVersion} model once Sling Models are written for the
     * {@code wf:} node types.
     *
     * @return an UUID, or {@code null} if not set
     */
    public String getWorkflow()
    {
        return this.workflow;
    }

    /**
     * Every requirement a submission against this version must fulfill, regardless of its specific subtype.
     *
     * @return a list of requirements, empty if none
     */
    public List<Requirement> getRequirements()
    {
        return this.getChildren(Requirement.RESOURCE_TYPE, Requirement.class);
    }

    /**
     * The questionnaire requirements of this version.
     *
     * @return a list of questionnaire requirements, empty if none
     */
    public List<QuestionnaireRequirement> getQuestionnaireRequirements()
    {
        return this.getChildren(QuestionnaireRequirement.RESOURCE_TYPE, QuestionnaireRequirement.class);
    }

    /**
     * The document requirements of this version.
     *
     * @return a list of document requirements, empty if none
     */
    public List<DocumentRequirement> getDocumentRequirements()
    {
        return this.getChildren(DocumentRequirement.RESOURCE_TYPE, DocumentRequirement.class);
    }

    /**
     * The approval requirements of this version.
     *
     * @return a list of approval requirements, empty if none
     */
    public List<ApprovalRequirement> getApprovalRequirements()
    {
        return this.getChildren(ApprovalRequirement.RESOURCE_TYPE, ApprovalRequirement.class);
    }
}
