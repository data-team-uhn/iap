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
package io.uhndata.iap.scripting;

import javax.script.Bindings;

import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.apache.sling.scripting.sightly.pojo.Use;
import org.jetbrains.annotations.NotNull;

/**
 * Sets the content type of the response from an HTL script. HTL calls only methods that take no arguments, so each
 * type has a method of its own:
 *
 * {@snippet lang=html :
 * <sly data-sly-use.contentType="io.uhndata.iap.scripting.ContentTypeSetter">${contentType.html}</sly>
 * }
 *
 * @version $Id$
 * @since 0.1.0
 */
public class ContentTypeSetter implements Use
{
    private SlingJakartaHttpServletResponse response;

    @Override
    public void init(@NotNull final Bindings bindings)
    {
        this.response = (SlingJakartaHttpServletResponse) bindings.get("jakartaResponse");
    }

    /** Sets the content type to {@code text/html;charset=UTF-8}. */
    public void html()
    {
        this.response.setContentType("text/html;charset=UTF-8");
    }

    /** Sets the content type to {@code application/javascript;charset=UTF-8}. */
    public void javascript()
    {
        this.response.setContentType("application/javascript;charset=UTF-8");
    }

    /** Sets the content type to {@code application/json;charset=UTF-8}. */
    public void json()
    {
        this.response.setContentType("application/json;charset=UTF-8");
    }

    /** Sets the content type to {@code text/csv;charset=UTF-8}. */
    public void csv()
    {
        this.response.setContentType("text/csv;charset=UTF-8");
    }

    /** Sets the content type to {@code text/plain;charset=UTF-8}. */
    public void text()
    {
        this.response.setContentType("text/plain;charset=UTF-8");
    }

    /** Sets the content type to {@code text/markdown;charset=UTF-8}. */
    public void markdown()
    {
        this.response.setContentType("text/markdown;charset=UTF-8");
    }
}
