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
package io.uhndata.iap.emailcatcher.internal;

import java.util.Set;

import org.apache.sling.commons.messaging.mail.MailService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import io.uhndata.iap.status.spi.StatusReport;
import io.uhndata.iap.status.spi.StatusReporter;

/**
 * Reports whether outgoing email is being caught rather than delivered.
 *
 * <p>
 * While the catcher is on, the report is a warning: nothing the platform emails reaches anybody. While it is
 * off, the report is at debug level, which a status report shows only when asked. A {@code null} report would
 * read the same as the catcher not being installed.
 * </p>
 *
 * <p>
 * The catcher counts as on while its mail service is registered, whatever its setting asks for.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class CaughtMailStatusReporter implements StatusReporter
{
    private static final String REPORT_TITLE = "Email Catcher";

    /**
     * The catcher's mail service; {@code null} while the catcher is off. Dynamic and greedy, so that a switch
     * takes effect without restarting this component.
     */
    @Reference(target = "(" + CaughtMailService.CATCHER_PROPERTY + "=true)",
        cardinality = ReferenceCardinality.OPTIONAL,
        policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY)
    private volatile MailService catcher;

    @Override
    public String getName()
    {
        return REPORT_TITLE;
    }

    @Override
    public StatusReport report(final boolean unprivileged)
    {
        if (this.catcher == null) {
            return new StatusReport(REPORT_TITLE, StatusReport.Status.DEBUG,
                "The email catcher is off, so email is being delivered normally.");
        }
        // An unprivileged report does not name the internal path
        final String text = "Outgoing email is being caught, not delivered: nothing the platform sends is"
            + " reaching anybody."
            + (unprivileged ? "" : " Messages are filed under " + CaughtMailService.CAUGHT_MAIL_PATH + ".");
        return new StatusReport(REPORT_TITLE, StatusReport.Status.WARNING, text);
    }

    @Override
    public Set<String> getTags()
    {
        return Set.of("problems", "email");
    }
}
