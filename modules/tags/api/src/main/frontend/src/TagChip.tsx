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

import { Chip, Stack } from "@mui/material";
import { useTheme } from "@mui/material/styles";

import { chipStyle } from "@iap/frontend-commons/chipStyle";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { type TagDefinition, loadTagDefinitions } from "./tagDefinitions";
import { tagIcon } from "./tagIcons";

// The values of a serialized node's `tags` property, as an array. A single string counts as one
// value, and anything that is not a string is left out.
function tagNames(tags: unknown): string[] {
  if (typeof tags === "string") {
    return [tags];
  }
  return Array.isArray(tags) ? tags.filter((tag): tag is string => typeof tag === "string") : [];
}

// One tag, as its definition shows it: its label, styled by its color and variant (see
// chipStyle), and its icon if tagIcons lists it. A color chipStyle refuses, or an unknown icon
// name, only leaves that styling out.
function DefinedTagChip({ definition }: { definition: TagDefinition }) {
  const theme = useTheme();
  const style = chipStyle(theme, definition.color, definition.variant);
  return (
    <Chip
      size="small"
      label={definition.label ?? definition.name}
      icon={tagIcon(definition.icon)}
      sx={{
        ...style,
        // The icon takes the text color instead of MUI's muted icon color, on a chip without a
        // usable color too
        "& .MuiChip-icon": { color: "inherit" },
      }}
    />
  );
}

// Small chips showing a node's tags, labeled and colored by their definitions under /Tags.
//
// With a `category`, it shows every tag the node carries in that category, in the definitions'
// order. A category can hold several tags at once. A tag that no definition in the category
// names belongs to another category, and is not shown.
//
// Without a category, it shows all of the node's tags. The defined ones come first, styled by
// their definitions and in their order. The others follow as outlined chips with their raw names.
//
// It shows nothing while the definitions load, or for a node without tags.
function TagChip({ tags, category }: { tags?: unknown; category?: string }) {
  const [definitions, setDefinitions] = useState<TagDefinition[]>();
  const fetchUtil = useAuthenticatedFetch();

  useEffect(() => {
    let cancelled = false;
    // loadTagDefinitions cannot reject: fetch failures already resolve to an empty list
    void loadTagDefinitions(category, fetchUtil).then(loaded => {
      if (!cancelled) {
        setDefinitions(loaded);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [category, fetchUtil]);

  const names = tagNames(tags);
  if (!definitions || names.length === 0) {
    return null;
  }
  const defined = definitions.filter(definition => names.includes(definition.name));
  const recognized = new Set(defined.map(definition => definition.name));
  const unrecognized = category ? [] : names.filter(name => !recognized.has(name));
  // Nothing, rather than an empty row that would still take up room in a cell or a heading
  if (defined.length === 0 && unrecognized.length === 0) {
    return null;
  }
  return (
    <Stack direction="row" spacing={0.5} sx={{ flexWrap: "wrap" }}>
      {defined.map(definition => <DefinedTagChip key={definition.name} definition={definition} />)}
      {unrecognized.map(name => <Chip key={name} size="small" variant="outlined" label={name} />)}
    </Stack>
  );
}

export default TagChip;
