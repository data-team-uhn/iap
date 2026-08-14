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

// What the manager receives when the dialog is saved: the edited fields, the chosen parent (which,
// when changed on an existing category, additionally means a move), and whether that parent has to
// give up a schema version of its own to take the child.
export interface CategorySubmission {
  fields: CategoryFields;
  parentPath: string;
  unbindParent: boolean;
}

// Which write did not happen. Saving can take several of them - unbinding the new parent, the
// fields, then the move - so a failure has to say which one it was, or a half-applied edit reads as
// one that did not happen at all.
export type SaveStep = "unbind" | "create" | "update" | "move";

// A save failure that knows which step it belongs to. The manager raises it, having done the steps;
// the dialog words it, having the labels.
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

// The create/edit dialog for one category: label, description, schema version binding, and (when
// editing) the parent category, changing which moves the category and its whole subtree.
function CategoryDialog({ mode, node, parentPath, tree, onClose, onSave }: CategoryDialogProps) {
  const [ label, setLabel ] = useState(node?.label ?? "");
  const [ description, setDescription ] = useState(node?.description ?? "");
  const [ schemaVersion, setSchemaVersion ] = useState(node?.schemaVersion?.uuid ?? "");
  const [ parent, setParent ] = useState(parentPath);
  const { working: saving, failure: saveError, run } = useAsyncAction({
    onFailure: (error: unknown) => ({ lead: leadFor(error), detail: messageOf(error) }),
    onSuccess: onClose,
  });

  // Only leaf categories may carry a schema version; a category that already has subcategories
  // does not get the picker at all
  const isLeaf = !node || node.children.length === 0;
  const duplicateLabel = label.trim() !== ""
    && hasDuplicateLabel(childrenOf(tree, parent), label, node?.path);
  const valid = label.trim() !== "" && !duplicateLabel;

  // Filing a category under another - by creating it there, or by moving it there - is what ends
  // that parent's days as a leaf, and only leaves may carry a schema version. The binding therefore
  // has to go, and the administrator has to be told before it does: once the parent has a child the
  // picker is hidden for it, so a binding left behind could never be found or cleared again.
  const gainingChild = mode === "create" || parent !== parentPath
    ? findNode(tree, parent)
    : undefined;
  const unbindParent = gainingChild?.schemaVersion !== undefined;

  const parentOptions = [
    { path: CATEGORIES_ROOT, label: "— Top level —", depth: -1 },
    ...flattenForParentPicker(tree, node?.path),
  ];

  // The chosen parent as it would be referred to in a sentence, which is not how it is listed in
  // the picker: the top level is an option there, but a place here.
  const parentName = parent === CATEGORIES_ROOT
    ? "the top level"
    : parentOptions.find(option => option.path === parent)?.label ?? "the chosen category";

  // What did not happen, said before why, as the load report and the tree's notices also do. The
  // dialog's own title names the category, so these do not repeat it. A move that failed after the
  // fields were saved is the one case where something did happen, and saying so beats being brief.
  const leadFor = (error: unknown): string => {
    if (error instanceof SaveStepFailure) {
      if (error.step === "unbind") {
        // Unbinding runs first precisely so that failing it changes nothing
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
      // An empty selection on a category that had a binding explicitly removes it (null);
      // otherwise an empty selection simply doesn't touch the property
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
                Submissions are filed under categories that have no subcategories, so only those
                carry a schema version. Giving {gainingChild.label} a subcategory means submissions
                are filed under that subcategory instead, and its own binding is removed.
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
