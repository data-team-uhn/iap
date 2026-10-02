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

import { type ReactNode, useState } from "react";

import SwapHorizIcon from "@mui/icons-material/SwapHoriz";
import { Box, Button, Chip, FormControlLabel, MenuItem, Stack, Switch, TextField, Typography } from "@mui/material";
import { useNavigate } from "react-router";

import AdminScreen from "@iap/admin-console/AdminScreen";
import LoadError from "@iap/frontend-commons/components/LoadError";
import LoadingOverlay from "@iap/frontend-commons/components/LoadingOverlay";
import { usePageCrumbs } from "@iap/frontend-commons/pageCrumbs";

import { type ComparisonSummary, type LabelledDifference } from "./schemaComparisonModel";
import PartList, { FieldChanges } from "./SchemaComparisonOutline";
import { type JcrNode, labelOf, nameOf, pathOf, titleOf, versionsOf } from "./schemaModel";
import { comparisonPageUrl, schemaPageUrl } from "./useSchemaList";
import {
  type ComparedFields, type ComparedFieldSources, useComparedFields, useReferenceNames, useVersionComparison,
} from "./useVersionComparison";

// Words one after the other, the last joined with "and"
const listed = (words: string[]): string => (words.length < 2 ? words.join("")
  : `${words.slice(0, -1).join(", ")} and ${words.slice(-1).join("")}`);

// The counts of a comparison as one sentence, naming only what there is: "3 parts added, 1 removed and 2 moved."
// When no part changed, what the version says of itself that did, if anything.
function summaryOf({ added, removed, changed, moved }: ComparisonSummary, version: LabelledDifference[]): string {
  const counted = ([ [ added, "added" ], [ removed, "removed" ], [ changed, "changed" ], [ moved, "moved" ] ] as const)
    .filter(([ count ]) => count !== 0)
    .map(([ count, what ], at) => (at === 0
      ? `${count} ${count === 1 ? "part" : "parts"} ${what}`
      : `${count} ${what}`));
  if (counted.length > 0) {
    return `${listed(counted)}.`;
  }
  return version.length > 0
    ? `Only the version's ${listed(version.map(difference => difference.label.toLowerCase()))} changed.`
    : "The two versions ask for the same.";
}

interface ComparedVersionsProps {
  schemaPath: string;
  names: [ string, string ];
  // Until known, the versions are read meanwhile
  fields?: ComparedFields;
  referenceNames: Record<string, string>;
}

// Two versions read and compared: what happened, counted, then shown. One for each pair, so that what was read for
// another pair never shows under this one's title.
function ComparedVersions({ schemaPath, names, fields, referenceNames }: ComparedVersionsProps) {
  const [ everything, setEverything ] = useState(false);
  const { comparison, loading, loadError, reload } = useVersionComparison(schemaPath, names, fields);
  return (
    <>
      <LoadingOverlay open={loading} />
      { loadError && <LoadError title="The versions could not be compared" message={loadError} onRetry={reload} /> }
      { comparison && fields && (
        <>
          <Stack direction="row" sx={{ alignItems: "center", justifyContent: "space-between", gap: 1,
            flexWrap: "wrap" }}>
            <Typography>{summaryOf(comparison.summary, comparison.version)}</Typography>
            <FormControlLabel label="Show unchanged parts"
              control={<Switch checked={everything} onChange={event => setEverything(event.target.checked)} />} />
          </Stack>
          { comparison.version.length > 0 && (
            <Box sx={{ p: 1, border: 1, borderColor: "divider", borderRadius: 1, bgcolor: "background.paper" }}>
              <Stack spacing={1}>
                <Stack direction="row" spacing={1} sx={{ alignItems: "baseline" }}>
                  <Typography sx={{ fontWeight: "fontWeightBold" }}>This version</Typography>
                  <Chip size="small" variant="outlined" label="Changed" />
                </Stack>
                <FieldChanges differences={comparison.version} fields={fields.version} names={referenceNames} />
              </Stack>
            </Box>
          ) }
          <PartList parts={comparison.parts} fields={fields} names={referenceNames} everything={everything} />
        </>
      ) }
    </>
  );
}

interface SchemaVersionComparisonProps {
  schema: JcrNode;
  // The version compared with, then the one compared
  names: [ string, string ];
  // The workflow definitions describing the fields compared
  definitions: ComparedFieldSources;
  // What the schema's own page would say about it, such as that it is retired
  pageNotices: ReactNode;
}

// Two versions of a schema compared: what the later one added, removed, changed and moved, in one outline of it. The
// fields compared, and the names of what references point at, are read once for every pair compared.
function SchemaVersionComparison({ schema, names, definitions, pageNotices }: SchemaVersionComparisonProps) {
  const navigate = useNavigate();
  const schemaPage = schemaPageUrl(nameOf(schema));
  usePageCrumbs([ { path: schemaPage, label: titleOf(schema) } ]);
  const versions = versionsOf(schema);
  const [ before, after ] = names.map(name => versions.find(version => nameOf(version) === name));
  const { fields, loadError, reload } = useComparedFields(definitions);
  const referenceNames = useReferenceNames(fields ? [ ...fields.version, ...fields.part, ...fields.option ] : []);

  if (!before || !after) {
    return (
      <AdminScreen title="Versions compared" titlePrefix={titleOf(schema)}>
        <Typography>{`This schema has no version ${before ? names[1] : names[0]}.`}</Typography>
      </AdminScreen>
    );
  }
  const compare = (base: string, compared: string) => void navigate(comparisonPageUrl(nameOf(schema), base, compared));
  // Each picker offers every version but the one the other names
  const picker = (label: string, value: string, other: string, onChange: (name: string) => void) => (
    <TextField select size="small" label={label} value={value} onChange={event => onChange(event.target.value)}
      sx={{ minWidth: 160 }}>
      { versions.map(version => (
        <MenuItem key={nameOf(version)} value={nameOf(version)} disabled={nameOf(version) === other}>
          {`Version ${labelOf(version)}`}
        </MenuItem>
      )) }
    </TextField>
  );

  return (
    <AdminScreen title={`Version ${labelOf(after)} compared with ${labelOf(before)}`} titlePrefix={titleOf(schema)}
      disablePanel>
      <Stack spacing={2}>
        {pageNotices}
        <Stack direction="row" sx={{ alignItems: "center", gap: 1.5, flexWrap: "wrap" }}>
          { picker("Version", names[1], names[0], name => compare(names[0], name)) }
          { picker("Compared with", names[0], names[1], name => compare(name, names[1])) }
          <Button startIcon={<SwapHorizIcon />} onClick={() => compare(names[1], names[0])}>Swap</Button>
        </Stack>
        <LoadingOverlay open={!fields && !loadError} />
        { loadError && (
          <LoadError title="The versions could not be compared" message={loadError} onRetry={reload} />
        ) }
        <ComparedVersions key={names.join("/")} schemaPath={pathOf(schema)} names={names} fields={fields}
          referenceNames={referenceNames} />
      </Stack>
    </AdminScreen>
  );
}

export default SchemaVersionComparison;
