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

import { useEffect, useState } from "react";

import { Box, Divider, Link, Typography } from "@mui/material";

import { useMessage } from "@iap/frontend-commons/messages";
import { ExtensionList, type Extension } from "@iap/ui-extension/ExtensionList";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

import CredentialsForm from "./CredentialsForm";

// A sign-in method other than the primary one: rendered collapsed, as a quiet link labelled
// by the extension's `iap:collapsedLabel` (e.g. "Use a local account instead"). Once
// revealed, the method appears as its own section — separated by a divider and titled with
// the extension's name — so it is not read as part of the primary method above it.
function CollapsedMethod({ extension }: { extension: Extension }) {
  const [ open, setOpen ] = useState(false);
  const message = useMessage();
  // The extension names itself where it can; this is the wording for one that does not, so it is the
  // only part of the label that is ours to translate.
  const label = (extension["iap:collapsedLabel"] as string | undefined)
    ?? (extension["iap:extensionName"] as string | undefined)
    ?? message("iap.login.signInMethods.moreOptions.label");
  const title = extension["iap:extensionName"] as string | undefined;

  if (!open) {
    return (
      <Box sx={{ marginBlockStart: 3 }}>
        <Link component="button" type="button" variant="body2" color="text.secondary" onClick={() => setOpen(true)}>
          {label}
        </Link>
      </Box>
    );
  }
  return (
    <Box sx={{ marginBlockStart: 4 }}>
      <Divider sx={{ marginBlockEnd: 2 }} />
      {title && (
        <Typography variant="overline" component="h3" color="text.secondary">
          {title}
        </Typography>
      )}
      <Box sx={{ marginBlockStart: 1 }}>
        <ExtensionList extensions={[extension]} />
      </Box>
    </Box>
  );
}

// The auth action area of the login page: the sign-in methods registered on the
// `iap/login/signInMethod` extension point, in their configured order. The first (enabled)
// method renders in place; any further methods are collapsed behind their labels — which is
// how the local credentials form stays reachable for administrators once a deployment makes
// an external identity provider the primary method.
//
// The login module registers the credentials form as the default method; if no method at all
// is registered (or the extension point cannot be loaded), the credentials form is rendered
// directly, so the page never ends up with no way to sign in.
export default function SignInMethods() {
  const [ methods, setMethods ] = useState<Extension[]>();

  useEffect(() => {
    loadExtensions("SignInMethod")
      .then(setMethods)
      .catch((err: unknown) => {
        console.error("Something went wrong loading the sign-in methods", err);
        setMethods([]);
      });
  }, []);

  // Still loading; the methods arrive in one round trip
  if (!methods) {
    return null;
  }
  if (methods.length === 0) {
    console.warn("No sign-in method is registered; falling back to the credentials form");
    return <CredentialsForm />;
  }
  const [ primary, ...others ] = methods;
  return (
    <>
      <ExtensionList extensions={[primary]} />
      {others.map((method, index) => <CollapsedMethod key={"method-" + index} extension={method} />)}
    </>
  );
}
