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

import { ExtensionList, type Extension } from "@iap/ui-extension/ExtensionList";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

import CredentialsForm from "./CredentialsForm";

// A sign-in method after the first, shown as a link until it is opened. The link's text is the
// extension's `ext:collapsedLabel`, such as "Use a local account instead", or else its `ext:name`,
// or else "More sign-in options". Once opened, the method is a section of its own, under a divider.
// Its title is its `ext:name`, so it does not read as part of the method above it.
function CollapsedMethod({ extension }: { extension: Extension }) {
  const [ open, setOpen ] = useState(false);
  const label = (extension["ext:collapsedLabel"] as string | undefined)
    ?? (extension["ext:name"] as string | undefined)
    ?? "More sign-in options";
  const title = extension["ext:name"] as string | undefined;

  if (!open) {
    return (
      <Box sx={{ marginBlockStart: 3 }}>
        <Link component="button" type="button" variant="body2" color="textSecondary" onClick={() => setOpen(true)}>
          {label}
        </Link>
      </Box>
    );
  }
  return (
    <Box sx={{ marginBlockStart: 4 }}>
      <Divider sx={{ marginBlockEnd: 2 }} />
      {title && (
        <Typography variant="overline" component="h3">
          {title}
        </Typography>
      )}
      <Box sx={{ marginBlockStart: 1 }}>
        <ExtensionList extensions={[extension]} />
      </Box>
    </Box>
  );
}

// The ways of signing in that the login page offers: the enabled extensions on the
// `iap/login/signInMethod` point, in their `defaultOrder`. The first is shown in place, and each
// of the others behind a link.
//
// With no method enabled, or when the point cannot be loaded, it shows the credentials form, so
// there is always a way to sign in.
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

  // Nothing is shown while the methods load
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
