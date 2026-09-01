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

import type { ReactNode } from "react";

import CloseIcon from "@mui/icons-material/Close";
import Box from "@mui/material/Box";
import IconButton from "@mui/material/IconButton";
import Link from "@mui/material/Link";
import Typography from "@mui/material/Typography";

import CodePill from "@iap/frontend-commons/components/CodePill";
import { TOUCH_REACH } from "@iap/frontend-commons/touchTarget";

import { FieldSourceName, PhiBadge } from "./badges";

import type { SelectionGroup as SelectionGroupData } from "../selectionGrouping";

/** Gives back the fields named, saying which control was pressed, so the panel can move focus on. */
export type RemoveFields = (keys: readonly string[], control: HTMLElement) => void;

// Marks every control that removes something, so the panel can find the next one once it is gone
const REMOVE_CONTROL = { "data-remove": true };

// Muted at rest, and the error colour once a pointer or the keyboard is on it
const REMOVE_STYLE = {
  flex: "none",
  color: "text.secondary",
  "&:hover, &:focus-visible": { color: "error.main" },
};

interface RemovableListProps {
  /** What the list is chosen from, such as a database. */
  source: string;
  title: string;
  /** The keys the list holds. */
  keys: readonly string[];
  onRemove: RemoveFields;
  children: ReactNode;
}

/** A titled list of chosen fields, with a way to give them all back. */
function RemovableList({ source, title, keys, onRemove, children }: RemovableListProps) {
  return (
    <Box sx={{ mb: 2.5 }}>
      <Box sx={{ display: "flex", alignItems: "baseline", gap: 1, mb: 0.75 }}>
        <Typography variant="overline">{source}</Typography>
        <Typography variant="subtitle2" component="p" sx={{ flex: 1, minWidth: 0, overflowWrap: "anywhere" }}>
          {title}
        </Typography>
        <Link
          component="button"
          type="button"
          variant="caption"
          {...REMOVE_CONTROL}
          onClick={event => { onRemove(keys, event.currentTarget); }}
          sx={REMOVE_STYLE}
        >
          Remove all
        </Link>
      </Box>
      {children}
    </Box>
  );
}

/** One chosen thing in a list, and the way to give it back. */
function RemovableItem({ name, fieldKey, onRemove, children }: {
  name: string;
  fieldKey: string;
  onRemove: RemoveFields;
  children: ReactNode;
}) {
  return (
    <Box sx={{ display: "flex", alignItems: "center", gap: 1, py: 0.625, borderTop: 1, borderColor: "divider" }}>
      <Box sx={{ minWidth: 0, flex: 1 }}>{children}</Box>
      <IconButton
        size="small"
        {...REMOVE_CONTROL}
        onClick={event => { onRemove([ fieldKey ], event.currentTarget); }}
        title="Remove field"
        aria-label={`Remove ${name}`}
        sx={{ ...REMOVE_STYLE, ...TOUCH_REACH }}
      >
        <CloseIcon sx={{ fontSize: 14 }} />
      </IconButton>
    </Box>
  );
}

/** What was chosen out of one collection. */
export default function SelectionGroup({ group, onRemove }: { group: SelectionGroupData; onRemove: RemoveFields }) {
  return (
    <RemovableList
      source={group.databaseLabel}
      title={group.collectionLabel}
      keys={group.fields.map(field => field.key)}
      onRemove={onRemove}
    >
      {group.fields.map(field => (
        <RemovableItem key={field.key} name={field.label} fieldKey={field.key} onRemove={onRemove}>
          <Box sx={{ display: "flex", alignItems: "center", gap: 1 }}>
            <Typography variant="body2" noWrap sx={{ flex: 1, minWidth: 0, fontWeight: "medium" }}>
              {field.label}
            </Typography>
            <PhiBadge phi={field.phi} />
          </Box>
          <FieldSourceName field={field} />
        </RemovableItem>
      ))}
    </RemovableList>
  );
}

/** What was chosen that the catalogue no longer offers, such as a field a newer version dropped. */
export function UnofferedGroup({ keys, onRemove }: { keys: readonly string[]; onRemove: RemoveFields }) {
  return (
    <RemovableList source="Not in this version" title="No longer offered" keys={keys} onRemove={onRemove}>
      {keys.map(key => (
        <RemovableItem key={key} name={key} fieldKey={key} onRemove={onRemove}>
          <CodePill name={key} />
        </RemovableItem>
      ))}
    </RemovableList>
  );
}
