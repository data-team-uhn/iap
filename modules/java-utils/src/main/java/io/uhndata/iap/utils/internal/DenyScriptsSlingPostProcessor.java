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
package io.uhndata.iap.utils.internal;

import java.util.List;
import java.util.Locale;

import org.apache.sling.api.SlingJakartaHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.servlets.post.Modification;
import org.apache.sling.servlets.post.SlingJakartaPostProcessor;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.utils.UserIds;

/**
 * For security purposes, deny uploading HTML or JavaScript files for anybody other than the admin.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class DenyScriptsSlingPostProcessor implements SlingJakartaPostProcessor
{
    @Override
    public void process(final SlingJakartaHttpServletRequest request, final List<Modification> changes) throws Exception
    {
        ResourceResolver rr = request.getResourceResolver();
        // Compared against the repository's user id rather than the name as typed at login. That is why the
        // capitalisation does not matter here, and also why an account that merely spells its name like the
        // administrator's cannot borrow the exemption
        if ("admin".equals(UserIds.canonical(rr))) {
            return;
        }

        for (Modification m : changes) {
            final Resource r = rr.getResource(m.getSource());
            if (r == null || r.getResourceMetadata() == null) {
                continue;
            }
            final String contentType = r.getResourceMetadata().getContentType();
            if (contentType == null) {
                continue;
            }
            if (contentType.toLowerCase(Locale.ROOT).contains("script")) {
                throw new Exception("Script files are not allowed");
            }
            if (contentType.toLowerCase(Locale.ROOT).contains("html")) {
                throw new Exception("HTML files are not allowed");
            }
        }
    }
}
