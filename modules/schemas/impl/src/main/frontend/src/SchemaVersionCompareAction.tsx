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

import { useState } from "react";

import CompareArrowsIcon from "@mui/icons-material/CompareArrows";
import { ListItemText, Menu, MenuItem } from "@mui/material";
import { useNavigate } from "react-router";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { defaultBase, sourceOf } from "./comparisonDefaults";
import { ActionIcon } from "./EventAction";
import { type JcrNode, labelOf, nameOf, pathOf, tagsOf, versionsOf } from "./schemaModel";
import { readNode } from "./useNode";
import { comparisonPageUrl } from "./useSchemaList";

import type { SchemaVersionActionProps } from "./SchemaVersionActions";

// Compares this version with another, chosen from the schema's others: the one the rules choose by default first, and
// each said how it stands to this one where it does. Where it was copied from is read when the choice is opened.
// Offered only where there is another version to compare it with.
function SchemaVersionCompareAction({ schema, version, comparisonDefaults = [] }: SchemaVersionActionProps) {
  const navigate = useNavigate();
  const doFetch = useAuthenticatedFetch();
  const [ anchor, setAnchor ] = useState<HTMLElement | null>(null);
  const [ links, setLinks ] = useState<JcrNode>();
  const versions = versionsOf(schema);
  if (versions.length < 2) {
    return null;
  }
  const source = sourceOf(links, versions);
  const previous = defaultBase(version, versions, [ "previous" ]);
  const chosen = defaultBase(version, versions, comparisonDefaults, source);
  const others = versions.filter(other => nameOf(other) !== nameOf(version));
  const offered = chosen ? [ chosen, ...others.filter(other => other !== chosen) ] : others;
  const standingOf = (other: JcrNode): string | undefined => {
    if (tagsOf(other).includes("active")) {
      return "The active version";
    }
    return other === source ? "What it was copied from" : other === previous ? "The previous version" : undefined;
  };
  const open = (trigger: HTMLElement) => {
    setAnchor(trigger);
    readNode(doFetch, `${pathOf(version)}/link:links`, "1").then(setLinks, () => setLinks({}));
  };

  return (
    <>
      <ActionIcon label="Compare with…" icon={<CompareArrowsIcon fontSize="small" />} onClick={open} />
      <Menu anchorEl={anchor} open={anchor !== null} onClose={() => setAnchor(null)}>
        { offered.map(other => (
          <MenuItem key={nameOf(other)} onClick={() => {
            setAnchor(null);
            void navigate(comparisonPageUrl(nameOf(schema), nameOf(other), nameOf(version)));
          }}>
            <ListItemText primary={`Version ${labelOf(other)}`} secondary={standingOf(other)} />
          </MenuItem>
        )) }
      </Menu>
    </>
  );
}

export default SchemaVersionCompareAction;
