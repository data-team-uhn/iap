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

import { readJson } from "@iap/frontend-commons/requestFailure";

// The registry of the institutions taking part in this deployment, one child node per
// institution, in display order. Everyone can read it, so it shows before signing in.
const INSTITUTIONS_URL = "/libs/iap/ParticipatingInstitutions.1.json";

interface Institution {
  name?: string;
  logoLight?: string;
  logoDark?: string;
  url?: string;
}

// The institutions taking part in a deployment that serves several, shown as a strip of logos
// under the sign-in form. Each is a child node of /libs/iap/ParticipatingInstitutions, with a
// `name`, an optional `url`, and the image paths of its logos, `logoLight` and `logoDark`. The
// registry's optional `label` replaces the heading. An institution without a logo is shown by
// name. Without the registry, nothing is shown.
export default function ParticipatingInstitutions() {
  const { mode, systemMode } = useColorScheme();
  const resolvedMode = (mode === "system" ? systemMode : mode) ?? "light";
  const [ label, setLabel ] = useState("Participating institutions");
  const [ institutions, setInstitutions ] = useState<Institution[]>([]);

  useEffect(() => {
    fetch(INSTITUTIONS_URL)
      // A deployment serving one institution has no registry
      .then(response => response.ok
        ? readJson<Record<string, unknown>>(response)
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
      <Typography variant="overline" component="h3">
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
              : <Typography variant="caption">{institution.name}</Typography>;
            return institution.url
              ? <Link key={"institution-" + index} href={institution.url} target="_blank" rel="noopener">{content}</Link>
              : <Box key={"institution-" + index}>{content}</Box>;
          })
        }
      </Box>
    </Box>
  );
}
