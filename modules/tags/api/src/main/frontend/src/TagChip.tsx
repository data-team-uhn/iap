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

// The values of a serialized node's `tags` property, normalized: the property is multivalued,
// but tolerate a single string, and ignore anything that is not a string.
function tagNames(tags: unknown): string[] {
  if (typeof tags === "string") {
    return [tags];
  }
  return Array.isArray(tags) ? tags.filter((tag): tag is string => typeof tag === "string") : [];
}

// One tag rendered per its definition: the definition's label, styled per its color and
// variant (see chipStyle), plus its icon, when it names a known one (see tagIcons). Unusable
// colors and unknown icon names degrade to less styling, never breakage.
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
        // The icon follows the text color instead of MUI's default muted icon tint — also on a
        // chip with no usable color, whose label is the stock one the icon should still match
        "& .MuiChip-icon": { color: "inherit" },
      }}
    />
  );
}

// Small chips displaying a node's tags, labeled and colored by their definitions under /Tags.
//
// With a `category`, this displays the tags the node carries in that category — all of them, in
// the definitions' own order. Not one: a category is a subject the tags on it speak about, not
// necessarily a lifecycle with one state at a time, and an error's triage markers say both that
// somebody dealt with it and what they decided. A name that no definition in the category matches
// is not shown at all, since in a specific category only tags actually belonging to it are
// trustworthy enough to show — outside one such a name is an unrecognized tag, but here it is a
// tag of some other category, which the caller did not ask about.
//
// Without a category, this displays all of the node's tags: the defined ones styled by their
// definitions (in definition order), and the unrecognized ones after them, raw, as muted
// outlined chips — visible enough to be noticed, distinct enough to not look authoritative.
//
// Nothing is rendered while the definitions load, or for an untagged node.
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
  // Nothing at all rather than an empty row, so that a cell or a heading holding one of these is
  // as narrow as it looks
  if (defined.length === 0 && unrecognized.length === 0) {
    return null;
  }
  return (
    <Stack direction="row" spacing={0.5} useFlexGap sx={{ flexWrap: "wrap" }}>
      {defined.map(definition => <DefinedTagChip key={definition.name} definition={definition} />)}
      {unrecognized.map(name => <Chip key={name} size="small" variant="outlined" label={name} />)}
    </Stack>
  );
}

export default TagChip;
