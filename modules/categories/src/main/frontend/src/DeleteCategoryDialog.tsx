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

import { Alert, Button, DialogActions, DialogContent, DialogContentText } from "@mui/material";

import ResponsiveDialog from "@iap/frontend-commons/components/ResponsiveDialog";

import { CategoryReferencedError } from "./useCategoryTree";

import type { CategoryNode } from "./categoryModel";

interface DeleteCategoryDialogProps {
  node: CategoryNode;
  onClose: () => void;
  // Deletes the category; a rejection keeps the dialog open and displays the error.
  onDelete: (node: CategoryNode) => Promise<void>;
  // Retires the category instead, offered when deletion is refused because submissions exist.
  onRetire: (node: CategoryNode) => Promise<void>;
}

// The delete confirmation dialog. When the server refuses the deletion because the category
// has submissions, the dialog switches to offering to retire the category instead.
function DeleteCategoryDialog({ node, onClose, onDelete, onRetire }: DeleteCategoryDialogProps) {
  const [ working, setWorking ] = useState(false);
  const [ error, setError ] = useState<string>();
  const [ referenced, setReferenced ] = useState(false);

  const run = (action: (node: CategoryNode) => Promise<void>) => {
    setWorking(true);
    setError(undefined);
    action(node)
      .then(onClose)
      .catch((err: unknown) => {
        if (err instanceof CategoryReferencedError) {
          setReferenced(true);
        } else {
          setError(err instanceof Error ? err.message : String(err));
        }
        setWorking(false);
      });
  };

  return (
    <ResponsiveDialog open title={`Delete ${node.label}?`} width="xs" withCloseButton onClose={onClose}>
      <DialogContent dividers>
        { referenced
          ? (
            <Alert severity="warning">
              This category has submissions and cannot be deleted. Retiring it keeps the existing
              submissions but allows no new ones.
            </Alert>
          )
          : (
            <DialogContentText>
              The category &quot;{node.label}&quot; will be permanently deleted. This cannot be undone.
            </DialogContentText>
          )}
        { error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert> }
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={working}>Cancel</Button>
        { referenced
          ? (
            <Button variant="contained" color="warning" onClick={() => run(onRetire)} disabled={working}>
              Retire instead
            </Button>
          )
          : (
            <Button variant="contained" color="error" onClick={() => run(onDelete)} disabled={working}>
              Delete
            </Button>
          )}
      </DialogActions>
    </ResponsiveDialog>
  );
}

export default DeleteCategoryDialog;
