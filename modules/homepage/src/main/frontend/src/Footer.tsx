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

import { Link as RouterLink } from "react-router";

import FooterContent from "@iap/frontend-commons/components/FooterContent";

// The standard page footer of the app shell. The content (affiliation logo, `iap/footer/link`
// extensions, version and credits) lives in the shared FooterContent, also rendered by the
// login page; this wrapper contributes the in-shell placement: the muted band, and client-side
// routing for in-app links.
// Registered on the `iap/coreUI/pageBottom` extension point, so it scrolls with the content.
function Footer() {
  return (
    <FooterContent
      sx={{ px: 3, py: 2, bgcolor: "background.muted" }}
      internalLinkProps={(url: string) => ({ component: RouterLink, to: url })}
    />
  );
}

export default Footer;
