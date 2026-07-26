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
import { useColorScheme } from "@mui/material/styles";

// The registry of institutions participating in this deployment, one child node per
// institution. It lives directly under /libs/iap (anonymous-readable) rather than under
// /libs/iap/conf, because ConfigMetadata flattens every property below conf into one shared
// meta map, where an institution's `logoLight` would collide with the application logo's.
const INSTITUTIONS_URL = "/libs/iap/ParticipatingInstitutions.1.json";

interface Institution {
  name?: string;
  logoLight?: string;
  logoDark?: string;
  url?: string;
}

// The strip of participating institutions displayed under the sign-in form on "front door"
// deployments — the shared entry point serving users from every institution, where a single
// affiliation logo would be wrong. Institutions are content: child nodes of
// /libs/iap/ParticipatingInstitutions carrying `name`, `logoLight`/`logoDark` (image paths),
// and an optional `url`; an optional `label` property on the registry node overrides the strip
// heading. Single-institution deployments simply don't create the registry, and nothing is
// rendered.
export default function ParticipatingInstitutions() {
  const { mode, systemMode } = useColorScheme();
  const resolvedMode = (mode === "system" ? systemMode : mode) ?? "light";
  const [ label, setLabel ] = useState("Participating institutions");
  const [ institutions, setInstitutions ] = useState<Institution[]>([]);

  useEffect(() => {
    fetch(INSTITUTIONS_URL)
      // A missing registry is the expected state of single-institution deployments
      .then(response => response.ok
        ? (response.json() as Promise<Record<string, unknown>>)
        : Promise.reject(new Error(String(response.status))))
      .then(json => {
        if (typeof json.label === "string" && json.label) {
          setLabel(json.label);
        }
        setInstitutions(Object.values(json).filter(value => typeof value === "object" && value !== null));
      })
      .catch(() => setInstitutions([]));
  }, []);

  if (institutions.length === 0) {
    return null;
  }

  return (
    <Box sx={{ marginBlockStart: 5 }}>
      <Divider sx={{ marginBlockEnd: 2 }} />
      <Typography variant="overline" component="h3" color="text.secondary">
        {label}
      </Typography>
      <Box
        sx={{
          display: "flex",
          flexWrap: "wrap",
          justifyContent: "flex-start",
          alignItems: "center",
          columnGap: 3,
          rowGap: 1,
          marginBlockStart: 1,
        }}
      >
        {
          institutions.map((institution, index) => {
            const logo = resolvedMode === "dark"
              ? (institution.logoDark ?? institution.logoLight)
              : (institution.logoLight ?? institution.logoDark);
            const content = logo
              ? <Box component="img" src={logo} alt={institution.name ?? ""} sx={{ display: "block", maxBlockSize: 32, maxInlineSize: 120 }} />
              : <Typography variant="caption" color="text.secondary">{institution.name}</Typography>;
            return institution.url
              ? <Link key={"institution-" + index} href={institution.url} target="_blank" rel="noopener">{content}</Link>
              : <Box key={"institution-" + index}>{content}</Box>;
          })
        }
      </Box>
    </Box>
  );
}
