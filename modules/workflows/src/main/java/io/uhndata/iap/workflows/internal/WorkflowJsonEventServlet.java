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
package io.uhndata.iap.workflows.internal;

import jakarta.servlet.Servlet;

import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.servlets.annotations.SlingServletResourceTypes;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.workflows.api.WorkflowEngine;

/**
 * Brings schemas under workflow control for POSTs with the {@code .json} extension, which is how events are
 * named: {@code POST /Schemas/x/1.0.activate.json}.
 *
 * <p>A POST without the extension is left to the Sling POST servlet, which only an administrator can use on
 * this content. That keeps imports such as {@code tools/dev/test-data/generate-test-data.sh} working.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = { Servlet.class })
@SlingServletResourceTypes(
    // Literals: schemas depends on workflows, so workflows cannot import its resource types
    resourceTypes = { "sch/SchemasHomepage", "sch/Schema", "sch/SchemaVersion", "sch/SchemaPart" },
    methods = { HttpConstants.METHOD_POST },
    extensions = { "json" })
public class WorkflowJsonEventServlet extends AbstractWorkflowEventServlet
{
    private static final long serialVersionUID = 2958132771047469905L;

    @Reference
    private transient WorkflowEngine engine;

    @Override
    protected WorkflowEngine getEngine()
    {
        return this.engine;
    }
}
