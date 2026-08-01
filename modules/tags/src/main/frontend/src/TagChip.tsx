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

import { safeCssColor } from "@iap/frontend-commons/safeColor";

import { type TagDefinition, loadTagDefinitions } from "./tagDefinitions";

// The values of a serialized node's `tags` property, normalized: the property is multivalued,
// but tolerate a single string, and ignore anything that is not a string.
function tagNames(tags: unknown): string[] {
  if (typeof tags === "string") {
    return [tags];
  }
  return Array.isArray(tags) ? tags.filter((tag): tag is string => typeof tag === "string") : [];
}

// One tag rendered per its definition: the definition's label, on the definition's color.
// An unusable color — anything outside safeCssColor's whitelist — renders as a plain chip.
function DefinedTagChip({ definition }: { definition: TagDefinition }) {
  const theme = useTheme();
  const safeColor = safeCssColor(definition.color);
  const colors = safeColor ? { bgcolor: safeColor, color: theme.palette.getContrastText(safeColor) } : undefined;
  return <Chip size="small" label={definition.label ?? definition.name} sx={colors} />;
}

// Small chips displaying a node's tags, labeled and colored by their definitions under /Tags.
//
// With a `category`, this displays the single state the node's tags describe in that category:
// the node's first tag (in the definitions' own order) that is defined in the category, and
// nothing when there is none — in a specific category, only tags actually belonging to it are
// trustworthy enough to show.
//
// Without a category, this displays all of the node's tags: the defined ones styled by their
// definitions (in definition order), and the unrecognized ones after them, raw, as muted
// outlined chips — visible enough to be noticed, distinct enough to not look authoritative.
//
// Nothing is rendered while the definitions load, or for an untagged node.
function TagChip({ tags, category }: { tags?: unknown; category?: string }) {
  const [definitions, setDefinitions] = useState<TagDefinition[]>();

  useEffect(() => {
    let cancelled = false;
    // loadTagDefinitions cannot reject: fetch failures already resolve to an empty list
    void loadTagDefinitions(category).then(loaded => {
      if (!cancelled) {
        setDefinitions(loaded);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [category]);

  const names = tagNames(tags);
  if (!definitions || names.length === 0) {
    return null;
  }
  if (category) {
    const shown = definitions.find(definition => names.includes(definition.name));
    return shown ? <DefinedTagChip definition={shown} /> : null;
  }
  const defined = definitions.filter(definition => names.includes(definition.name));
  const recognized = new Set(defined.map(definition => definition.name));
  const unrecognized = names.filter(name => !recognized.has(name));
  return (
    <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: "wrap" }}>
      {defined.map(definition => <DefinedTagChip key={definition.name} definition={definition} />)}
      {unrecognized.map(name => <Chip key={name} size="small" variant="outlined" label={name} />)}
    </Stack>
  );
}

export default TagChip;
