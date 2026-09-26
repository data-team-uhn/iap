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

import { useState, type ReactNode } from "react";

import { DialogContentText, IconButton, Tooltip } from "@mui/material";

import ConfirmActionDialog from "@iap/frontend-commons/components/ConfirmActionDialog";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import { sendEvent } from "./schemaEvents";

interface EventActionProps {
  path: string;
  reload: () => void;
  report: (message: string) => void;
  label: string;
  event: string;
  title: string;
  explanation: ReactNode;
  // What to report once done
  done: string;
  color?: "primary" | "warning" | "error";
  icon: ReactNode;
}

// A lifecycle event on a schema or a version. It asks for confirmation first, because it changes what
// submitters can do, not just what this page shows.
export function EventAction(props: EventActionProps) {
  const { path, reload, report, label, event, title, explanation, done, color } = props;
  const [ confirming, setConfirming ] = useState(false);
  const doFetch = useAuthenticatedFetch();
  return (
    <>
      <ActionIcon label={label} icon={props.icon} onClick={() => setConfirming(true)} />
      { confirming && (
        <ConfirmActionDialog
          title={title}
          confirmLabel={label}
          confirmColor={color}
          onConfirm={async () => {
            await sendEvent(doFetch, path, event);
            report(done);
            reload();
          }}
          onClose={() => setConfirming(false)}
        >
          <DialogContentText>{explanation}</DialogContentText>
        </ConfirmActionDialog>
      ) }
    </>
  );
}

// One action as an icon button, named by its tooltip.
export function ActionIcon({ label, icon, onClick }: {
  label: string;
  icon: ReactNode;
  onClick: () => void;
}) {
  return (
    <Tooltip title={label}>
      <IconButton size="small" aria-label={label} onClick={onClick}>{icon}</IconButton>
    </Tooltip>
  );
}
