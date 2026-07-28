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

import ExpandLessIcon from "@mui/icons-material/ExpandLess";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import { Chip, CircularProgress, Collapse, IconButton, List, ListItem, Stack, Typography } from "@mui/material";

import { useCategoryTree } from "./useCategoryTree";

import type { CategoryNode } from "./categoryModel";

// One category of the summary tree: its label (and retired state), a chevron to collapse its
// subtree, and, collapsibly, its subcategories. Leaves keep an invisible chevron so all labels
// align. Top-level categories start collapsed, keeping the widget compact; the deeper levels
// start open, so expanding a top-level category reveals its whole subtree in one click.
function CategoryItem({ node, depth }: { node: CategoryNode; depth: number }) {
  const [ open, setOpen ] = useState(depth > 0);
  const hasChildren = node.children.length > 0;

  return (
    <>
      <ListItem component="div" disableGutters sx={{ py: 0, pl: depth * 3 }}>
        <IconButton
          size="small"
          aria-label={open ? `Collapse ${node.label}` : `Expand ${node.label}`}
          onClick={() => setOpen(!open)}
          sx={{ mr: 0.5, visibility: hasChildren ? "visible" : "hidden" }}
        >
          { open ? <ExpandLessIcon fontSize="small" /> : <ExpandMoreIcon fontSize="small" /> }
        </IconButton>
        <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
          <Typography variant="body2">{node.label}</Typography>
          { node.retired && <Chip size="small" color="warning" label="Retired" /> }
        </Stack>
      </ListItem>
      { hasChildren
        && (
          <Collapse in={open} timeout="auto" unmountOnExit>
            <CategoryBranch nodes={node.children} depth={depth + 1} />
          </Collapse>
        )}
    </>
  );
}

function CategoryBranch({ nodes, depth }: { nodes: CategoryNode[]; depth: number }) {
  return (
    <List component="div" dense disablePadding>
      { nodes.map(node => <CategoryItem key={node.path} node={node} depth={depth} />) }
    </List>
  );
}

// The administration console widget summarizing the categories: the whole tree, condensed to
// labels only, read-only but collapsible. It reuses the same fetch-and-parse plumbing as the full
// management UI, which itself is behind the widget frame's "Manage categories" action (see the
// extension node).
function CategoriesWidget() {
  const { tree, loading, loadError } = useCategoryTree();

  if (loading) {
    return <CircularProgress size={24} sx={{ display: "block", mx: "auto", my: 2 }} />;
  }
  if (loadError) {
    return <Typography color="error" variant="body2">The categories could not be loaded.</Typography>;
  }
  if (tree.length === 0) {
    return <Typography color="textSecondary" variant="body2">No categories are defined yet.</Typography>;
  }

  return <CategoryBranch nodes={tree} depth={0} />;
}

export default CategoriesWidget;
