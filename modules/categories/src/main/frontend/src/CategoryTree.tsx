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

import AddCircleOutlinedIcon from "@mui/icons-material/AddCircleOutlined";
import ArchiveOutlinedIcon from "@mui/icons-material/ArchiveOutlined";
import ArrowDownwardIcon from "@mui/icons-material/ArrowDownward";
import ArrowUpwardIcon from "@mui/icons-material/ArrowUpward";
import DeleteOutlinedIcon from "@mui/icons-material/DeleteOutlined";
import EditOutlinedIcon from "@mui/icons-material/EditOutlined";
import ExpandLessIcon from "@mui/icons-material/ExpandLess";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import UnarchiveOutlinedIcon from "@mui/icons-material/UnarchiveOutlined";
import {
  Chip,
  Collapse,
  IconButton,
  List,
  ListItem,
  ListItemText,
  Stack,
  Tooltip,
  Typography,
} from "@mui/material";

import type { CategoryNode } from "./categoryModel";

// Which way a row was asked to move among its siblings - as distinct from moving it to a different
// parent, which the edit dialog does.
export type MoveDirection = "up" | "down";

// The operations a row of the category tree can trigger; provided once by the manager and shared
// by every row.
export interface CategoryActions {
  onEdit: (node: CategoryNode) => void;
  onAddChild: (node: CategoryNode) => void;
  onDelete: (node: CategoryNode) => void;
  onToggleRetired: (node: CategoryNode) => void;
  // `order` uses the Sling :order syntax, e.g. "before <siblingName>", which is what the repository
  // needs; `direction` is the same move as the user asked for it, which is what a report needs. The
  // node comes along so that the manager can name it.
  onReorder: (node: CategoryNode, order: string, direction: MoveDirection) => void;
}

interface CategoryTreeProps {
  nodes: CategoryNode[];
  actions: CategoryActions;
  depth?: number;
}

interface CategoryTreeItemProps {
  node: CategoryNode;
  actions: CategoryActions;
  depth: number;
  // Reordering among siblings; undefined when already at the edge
  onMoveUp?: () => void;
  onMoveDown?: () => void;
}

// One row of the category tree: the category's label, status and schema binding, its management
// actions, and (collapsibly) its subcategories. Each row is its own outlined card, standing out
// against the administration area's tinted canvas, so the structure reads at a glance;
// indentation comes from the nested lists, not the row itself.
function CategoryTreeItem({ node, actions, depth, onMoveUp, onMoveDown }: CategoryTreeItemProps) {
  const [ open, setOpen ] = useState(true);
  const hasChildren = node.children.length > 0;

  return (
    <>
      <ListItem
        sx={{
          pl: 1,
          pr: 32,
          mb: 1,
          bgcolor: "background.paper",
          border: 1,
          borderColor: "divider",
          borderRadius: 1,
        }}
        secondaryAction={
          <Stack direction="row" spacing={0.5}>
            <Tooltip title="Move up">
              <span>
                <IconButton size="small" aria-label={`Move ${node.label} up`}
                  disabled={!onMoveUp} onClick={onMoveUp}>
                  <ArrowUpwardIcon fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
            <Tooltip title="Move down">
              <span>
                <IconButton size="small" aria-label={`Move ${node.label} down`}
                  disabled={!onMoveDown} onClick={onMoveDown}>
                  <ArrowDownwardIcon fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
            <Tooltip title="Add subcategory">
              <IconButton size="small" color="primary" aria-label={`Add subcategory to ${node.label}`}
                onClick={() => actions.onAddChild(node)}>
                <AddCircleOutlinedIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            <Tooltip title="Edit">
              <IconButton size="small" aria-label={`Edit ${node.label}`} onClick={() => actions.onEdit(node)}>
                <EditOutlinedIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            { /* The meaningful actions carry semantic color - primary for the constructive add,
                 amber for the "soft delete", red for the real one - while the routine actions
                 (move, edit, and the restorative unretire) stay quiet. */ }
            <Tooltip title={node.retired
              ? "Unretire: allow new submissions again"
              : "Retire: keep existing submissions, but allow no new ones"}>
              <IconButton size="small" color={node.retired ? "default" : "warning"}
                aria-label={`${node.retired ? "Unretire" : "Retire"} ${node.label}`}
                onClick={() => actions.onToggleRetired(node)}>
                { node.retired
                  ? <UnarchiveOutlinedIcon fontSize="small" />
                  : <ArchiveOutlinedIcon fontSize="small" /> }
              </IconButton>
            </Tooltip>
            <Tooltip title={hasChildren ? "Delete or move its subcategories first" : "Delete"}>
              <span>
                <IconButton size="small" color="error" aria-label={`Delete ${node.label}`}
                  disabled={hasChildren} onClick={() => actions.onDelete(node)}>
                  <DeleteOutlinedIcon fontSize="small" />
                </IconButton>
              </span>
            </Tooltip>
          </Stack>
        }
      >
        <IconButton
          size="small"
          aria-label={open ? `Collapse ${node.label}` : `Expand ${node.label}`}
          onClick={() => setOpen(!open)}
          sx={{ mr: 1, visibility: hasChildren ? "visible" : "hidden" }}
        >
          { open ? <ExpandLessIcon fontSize="small" /> : <ExpandMoreIcon fontSize="small" /> }
        </IconButton>
        <ListItemText
          sx={node.retired ? { opacity: 0.6 } : undefined}
          primary={
            <Stack direction="row" spacing={1} sx={{ alignItems: "center" }}>
              <Typography component="span">{node.label}</Typography>
              { node.retired && <Chip size="small" color="warning" label="Retired" /> }
              { node.schemaVersion
                && (
                  <Chip
                    size="small"
                    variant="outlined"
                    label={`Schema: ${node.schemaVersion.schemaName ?? "?"} v${node.schemaVersion.version ?? "?"}`}
                  />
                )}
            </Stack>
          }
          secondary={node.description}
          slotProps={{
            secondary: {
              sx: {
                display: "-webkit-box",
                WebkitLineClamp: 2,
                WebkitBoxOrient: "vertical",
                overflow: "hidden",
              },
            },
          }}
        />
      </ListItem>
      { hasChildren
        && (
          <Collapse in={open} timeout="auto" unmountOnExit>
            <CategoryTree nodes={node.children} actions={actions} depth={depth + 1} />
          </Collapse>
        )}
    </>
  );
}

// The recursive category tree listing. Sibling reordering is wired here, where the sibling names
// needed by Sling's :order syntax are known. The category cards stand out directly against the
// administration area's tinted canvas (see AdminScreen); nested lists indent their subtree (with
// a logical property, so the indentation mirrors under a right-to-left locale).
function CategoryTree({ nodes, actions, depth = 0 }: CategoryTreeProps) {
  return (
    <List disablePadding sx={depth > 0 ? { paddingInlineStart: 4 } : undefined}>
      { nodes.map((node, index) => (
        <CategoryTreeItem
          key={node.path}
          node={node}
          actions={actions}
          depth={depth}
          onMoveUp={index > 0
            ? () => actions.onReorder(node, `before ${nodes[index - 1].name}`, "up")
            : undefined}
          onMoveDown={index < nodes.length - 1
            ? () => actions.onReorder(node, `after ${nodes[index + 1].name}`, "down")
            : undefined}
        />
      ))}
    </List>
  );
}

export default CategoryTree;
