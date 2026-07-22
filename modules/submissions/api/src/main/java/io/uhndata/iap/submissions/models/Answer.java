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
package io.uhndata.iap.submissions.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.Question;

/**
 * A Sling Model wrapping a {@code sub:Answer} node: the answer to a single schema question. Only simple storage;
 * the value's expected type and meaning are dictated by the referenced question.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Answer.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Answer extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:Answer} node. */
    public static final String RESOURCE_TYPE = "sub/Answer";

    @ValueMapValue
    private String question;

    @ValueMapValue
    private String[] value;

    /**
     * The question this answers.
     *
     * @return a question, or {@code null} if not set or unresolvable
     */
    public Question getQuestion()
    {
        return this.getReference(this.question, Question.class);
    }

    /**
     * The submitted value(s).
     *
     * @return the stored value(s), or {@code null} if not yet answered
     */
    public String[] getValue()
    {
        return this.value;
    }
}
