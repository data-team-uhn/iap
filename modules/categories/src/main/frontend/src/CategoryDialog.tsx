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

import { Alert, AlertTitle, Button, DialogActions, DialogContent, MenuItem, Stack, TextField } from "@mui/material";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";
import { messageOf } from "@iap/frontend-commons/requestFailure";
import { useAsyncAction } from "@iap/frontend-commons/useAsyncAction";

import { childrenOf, findNode, flattenForParentPicker, hasDuplicateLabel, type CategoryNode } from "./categoryModel";
import SchemaVersionSelect from "./SchemaVersionSelect";
import { CATEGORIES_ROOT, type CategoryFields } from "./useCategoryTree";

// What saving the dialog hands over: the fields, the chosen parent, and whether that parent has to
// give up its schema version to take the child. A new parent for an existing category means a move.
export interface CategorySubmission {
  fields: CategoryFields;
  parentPath: string;
  unbindParent: boolean;
}

// One of the writes a save can take: unbinding the new parent, creating or updating the category,
// then the move. A failure names its step, or an edit applied in part reads as one never applied.
export type SaveStep = "unbind" | "create" | "update" | "move";

// A save failure, with the step it happened at.
export class SaveStepFailure extends Error {
  readonly step: SaveStep;

  constructor(step: SaveStep, cause: unknown) {
    super(messageOf(cause));
    this.name = "SaveStepFailure";
    this.step = step;
  }
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

// The dialog that creates or edits one category: its label, description and schema version, and,
// when editing, its parent. A new parent moves the category with its whole subtree.
function CategoryDialog({ mode, node, parentPath, tree, onClose, onSave }: CategoryDialogProps) {
  const [ label, setLabel ] = useState(node?.label ?? "");
  const [ description, setDescription ] = useState(node?.description ?? "");
  const [ schemaVersion, setSchemaVersion ] = useState(node?.schemaVersion?.uuid ?? "");
  const [ parent, setParent ] = useState(parentPath);
  const { working: saving, failure: saveError, run } = useAsyncAction({
    onFailure: (error: unknown) => ({ lead: leadFor(error), detail: messageOf(error) }),
    onSuccess: onClose,
  });

  // Only a top-level category's schema is used: it is what a new submission under it follows
  const showSchemaVersion = parent === CATEGORIES_ROOT;
  const duplicateLabel = label.trim() !== ""
    && hasDuplicateLabel(childrenOf(tree, parent), label, node?.path);
  const valid = label.trim() !== "" && !duplicateLabel;

  // Moving a category out of the top level hides the picker, and a hidden binding could never be
  // cleared by hand, so it is cleared here. Moving it back brings the old binding back. Adjusted
  // during render rather than in an effect, which would commit the stale value for one extra frame.
  const [ shownSchemaVersion, setShownSchemaVersion ] = useState(showSchemaVersion);
  if (showSchemaVersion !== shownSchemaVersion) {
    setShownSchemaVersion(showSchemaVersion);
    setSchemaVersion(showSchemaVersion ? node?.schemaVersion?.uuid ?? "" : "");
  }

  // A category below the top level may still hold a binding from before only top categories used
  // one. Giving it a child is the moment to drop it, and the administrator is told first.
  const gainingChild = mode === "create" || parent !== parentPath
    ? findNode(tree, parent)
    : undefined;
  // A top-level category keeps its schema when it gains a child: it is what the submitter picks
  const unbindParent = gainingChild?.schemaVersion !== undefined
    && gainingChild.path.slice(0, gainingChild.path.lastIndexOf("/")) !== CATEGORIES_ROOT;

  const parentOptions = [
    { path: CATEGORIES_ROOT, label: "— Top level —", depth: -1 },
    ...flattenForParentPicker(tree, node?.path),
  ];

  // How a sentence names the chosen parent, such as "the top level" for the tree's root.
  const parentName = parent === CATEGORIES_ROOT
    ? "the top level"
    : parentOptions.find(option => option.path === parent)?.label ?? "the chosen category";

  // The title of a save failure: what did not happen. The dialog's own title names the category, so
  // this one does not. A failed move is the one case where something did happen, and the title says
  // what.
  const leadFor = (error: unknown): string => {
    if (error instanceof SaveStepFailure) {
      if (error.step === "unbind") {
        // Unbinding runs first, so its failure leaves everything as it was
        return `The schema version bound to ${parentName} could not be removed, so nothing was changed`;
      }
      if (error.step === "move") {
        return `The changes were saved, but the category could not be moved to ${parentName}`;
      }
    }
    return mode === "create" ? "The category could not be created" : "The changes could not be saved";
  };

  const save = () => {
    const fields: CategoryFields = {
      label: label.trim(),
      description,
      // No selection removes an existing binding (null), and otherwise leaves the property alone
      schemaVersion: schemaVersion === ""
        ? (node?.schemaVersion ? null : undefined)
        : schemaVersion,
    };
    run(() => onSave({ fields, parentPath: parent, unbindParent }));
  };

  return (
    <ResponsiveDialog
      open
      title={mode === "create" ? "New category" : `Edit ${node?.label ?? "category"}`}
      withCloseButton
      closeDisabled={saving}
      onClose={onClose}
    >
      <DialogContent dividers>
        <Stack spacing={3} sx={{ pt: 1 }}>
          { unbindParent
            && (
              <Alert severity="warning">
                <AlertTitle>{gainingChild.label} will lose its schema version</AlertTitle>
                Only top-level categories carry a schema version, so the one bound
                to {gainingChild.label} is not used. It is removed when {gainingChild.label} gets a
                subcategory.
              </Alert>
            )}
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
          { showSchemaVersion && <SchemaVersionSelect value={schemaVersion} onChange={setSchemaVersion} /> }
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
          { saveError
            && (
              <Alert severity="error">
                <AlertTitle>{saveError.lead}</AlertTitle>
                {saveError.detail}
              </Alert>
            )}
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
