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

import CloseIcon from "@mui/icons-material/Close";
import Box from "@mui/material/Box";
import IconButton from "@mui/material/IconButton";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";

import { useCatalogue } from "../useCatalogue";
import { useSelection } from "../useSelection";
import { MonoId, PhiBadge } from "./badges";

import type { SelectionGroup as SelectionGroupData } from "../selectionGrouping";

/** What was chosen out of one collection, and the ways to give it back. */
export default function SelectionGroup({ group }: { group: SelectionGroupData }) {
  const { display } = useCatalogue();
  const { deselectFields, readOnly } = useSelection();

  return (
    <Box sx={{ mb: 2.5 }}>
      <Box sx={{ display: "flex", alignItems: "baseline", gap: 1, mb: 0.75 }}>
        <Typography variant="overline" sx={{ color: "text.secondary" }}>
          {group.databaseLabel}
        </Typography>
        <Typography variant="subtitle2" component="p" sx={{ flex: 1, minWidth: 0 }}>
          {group.collectionLabel}
        </Typography>
        {/* Left out, not disabled, where nothing may be removed */}
        {!readOnly && (
          <Link
            component="button"
            type="button"
            variant="caption"
            onClick={() => { deselectFields(group.fields.map(field => field.key)); }}
            sx={{ color: "text.secondary", "&:hover": { color: "error.main" } }}
          >
            Remove all
          </Link>
        )}
      </Box>
      {group.fields.map(field => (
        <Box
          key={field.key}
          sx={{
            display: "flex",
            alignItems: "center",
            gap: 1,
            py: 0.625,
            borderTop: 1,
            borderColor: "divider",
          }}
        >
          <Box sx={{ minWidth: 0, flex: 1 }}>
            <Typography
              variant="body2"
              sx={{ fontWeight: "medium", overflow: "hidden", textOverflow: "ellipsis",
                whiteSpace: "nowrap" }}
            >
              {field.label}
            </Typography>
            {display.tree.field.showIdentifier && !field.labelIsFallback
              && <MonoId>{field.identifier}</MonoId>}
          </Box>
          {display.tree.field.showPhi && <PhiBadge phi={field.phi} />}
          {!readOnly && (
            <IconButton
              size="small"
              onClick={() => { deselectFields([ field.key ]); }}
              title="Remove field"
              aria-label={`Remove ${field.label}`}
              sx={{ flex: "none", color: "text.disabled", "&:hover": { color: "error.main" } }}
            >
              <CloseIcon sx={{ fontSize: 14 }} />
            </IconButton>
          )}
        </Box>
      ))}
    </Box>
  );
}
