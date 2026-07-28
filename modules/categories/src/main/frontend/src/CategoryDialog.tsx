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

import { Alert, Button, DialogActions, DialogContent, MenuItem, Stack, TextField } from "@mui/material";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";

import { childrenOf, flattenForParentPicker, hasDuplicateLabel, type CategoryNode } from "./categoryModel";
import SchemaVersionSelect from "./SchemaVersionSelect";
import { CATEGORIES_ROOT, type CategoryFields } from "./useCategoryTree";

// What the manager receives when the dialog is saved: the edited fields, plus the chosen parent
// (which, when changed on an existing category, additionally means a move).
export interface CategorySubmission {
  fields: CategoryFields;
  parentPath: string;
}

interface CategoryDialogProps {
  // "create" adds a new category under `parentPath`; "edit" modifies `node`.
  mode: "create" | "edit";
  node?: CategoryNode;
  parentPath: string;
  // The whole current tree, for parent picking and duplicate label detection.
  tree: CategoryNode[];
  onClose: () => void;
  // Persists the submission; a rejection keeps the dialog open and displays the error.
  onSave: (submission: CategorySubmission) => Promise<void>;
}

// The create/edit dialog for one category: label, description, schema version binding, and (when
// editing) the parent category, changing which moves the category and its whole subtree.
function CategoryDialog({ mode, node, parentPath, tree, onClose, onSave }: CategoryDialogProps) {
  const [ label, setLabel ] = useState(node?.label ?? "");
  const [ description, setDescription ] = useState(node?.description ?? "");
  const [ schemaVersion, setSchemaVersion ] = useState(node?.schemaVersion?.uuid ?? "");
  const [ parent, setParent ] = useState(parentPath);
  const [ saving, setSaving ] = useState(false);
  const [ saveError, setSaveError ] = useState<string>();

  // Only leaf categories may carry a schema version; a category that already has subcategories
  // does not get the picker at all
  const isLeaf = !node || node.children.length === 0;
  const duplicateLabel = label.trim() !== ""
    && hasDuplicateLabel(childrenOf(tree, parent), label, node?.path);
  const valid = label.trim() !== "" && !duplicateLabel;

  const parentOptions = [
    { path: CATEGORIES_ROOT, label: "— Top level —", depth: -1 },
    ...flattenForParentPicker(tree, node?.path),
  ];

  const save = () => {
    setSaving(true);
    setSaveError(undefined);
    const fields: CategoryFields = {
      label: label.trim(),
      description,
      // An empty selection on a category that had a binding explicitly removes it (null);
      // otherwise an empty selection simply doesn't touch the property
      schemaVersion: schemaVersion === ""
        ? (node?.schemaVersion ? null : undefined)
        : schemaVersion,
    };
    onSave({ fields, parentPath: parent })
      .then(onClose)
      .catch((error: unknown) => {
        setSaveError(error instanceof Error ? error.message : String(error));
        setSaving(false);
      });
  };

  return (
    <ResponsiveDialog
      open
      title={mode === "create" ? "New category" : `Edit ${node?.label ?? "category"}`}
      withCloseButton
      onClose={onClose}
    >
      <DialogContent dividers>
        <Stack spacing={3} sx={{ pt: 1 }}>
          <TextField
            required
            fullWidth
            label="Label"
            value={label}
            onChange={event => setLabel(event.target.value)}
            error={duplicateLabel}
            helperText={duplicateLabel
              ? "A category with this label already exists at the same level"
              : "The name submitters will see"}
          />
          <TextField
            fullWidth
            multiline
            minRows={3}
            label="Description"
            value={description}
            onChange={event => setDescription(event.target.value)}
            helperText={"Shown to submitters as guidance, and used as an AI prompt — describe "
              + "what belongs in this category"}
          />
          { isLeaf && <SchemaVersionSelect value={schemaVersion} onChange={setSchemaVersion} /> }
          { mode === "edit"
            && (
              <TextField
                select
                fullWidth
                label="Parent category"
                value={parent}
                onChange={event => setParent(event.target.value)}
                helperText="Changing the parent moves this category and all of its subcategories"
              >
                { parentOptions.map(option => (
                  <MenuItem key={option.path} value={option.path}>
                    <span style={{ paddingInlineStart: `${(option.depth + 1) * 16}px` }}>{option.label}</span>
                  </MenuItem>
                ))}
              </TextField>
            )}
          { saveError && <Alert severity="error">{saveError}</Alert> }
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={saving}>Cancel</Button>
        <Button variant="contained" onClick={save} disabled={!valid || saving}>
          { mode === "create" ? "Create" : "Save" }
        </Button>
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default CategoryDialog;
