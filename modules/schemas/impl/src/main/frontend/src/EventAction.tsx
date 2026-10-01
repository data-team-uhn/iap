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

import { use, useState, type ReactNode, type Ref } from "react";

import { Box, DialogContentText, IconButton, ListItemIcon, ListItemText, MenuItem, Tooltip } from "@mui/material";

import ConfirmActionDialog from "@iap/frontend-commons/components/ConfirmActionDialog";
import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";
import { isRefusal } from "@iap/frontend-commons/requestFailure";

import { ActionsMenuContext } from "./ActionsMenu";
import { sendEvent } from "./schemaEvents";
import { TOUCH_TARGET } from "./schemaTreeLayout";

interface EventActionProps {
  path: string;
  reload: () => void | Promise<void>;
  // What to say once it is done, and where, if anywhere
  announce?: { report: (message: string) => void; message: string };
  label: string;
  // What its confirmation says while the event is sent, such as "Removing…"
  workingLabel?: string;
  event: string;
  title: string;
  explanation: ReactNode;
  color?: "primary" | "warning" | "error";
  icon: ReactNode;
}

// A lifecycle event on a schema or a version. It asks for confirmation first, because it changes what
// submitters can do, not just what this page shows.
export function EventAction(props: EventActionProps) {
  const { path, reload, announce, label, workingLabel, event, title, explanation, color } = props;
  const [ confirming, setConfirming ] = useState(false);
  const doFetch = useAuthenticatedFetch();
  return (
    <>
      <ActionIcon label={label} icon={props.icon} onClick={() => setConfirming(true)} />
      { confirming && (
        <ConfirmActionDialog
          title={title}
          confirmLabel={label}
          workingLabel={workingLabel}
          confirmColor={color}
          onConfirm={async () => {
            await sendEvent(doFetch, path, event);
            announce?.report(announce.message);
            await reload();
          }}
          isFinal={isRefusal}
          onClose={() => setConfirming(false)}
        >
          <DialogContentText>{explanation}</DialogContentText>
        </ConfirmActionDialog>
      ) }
    </>
  );
}

// One action as an icon button, named by its tooltip, or in a menu as a named line. One that stays on until pressed
// again says whether it is. What it does is given what stands for it: the button, or what held the menu.
export function ActionIcon({ label, icon, onClick, pressed, color = "default", disabled, loading, ref }: {
  label: string;
  icon: ReactNode;
  onClick: (trigger: HTMLElement) => void;
  pressed?: boolean;
  color?: "default" | "primary";
  disabled?: boolean;
  loading?: boolean;
  ref?: Ref<HTMLButtonElement>;
}) {
  const menu = use(ActionsMenuContext);
  if (menu) {
    return (
      <MenuItem disabled={disabled} onClick={() => {
        menu.close();
        onClick(menu.trigger);
      }}>
        <ListItemIcon>{icon}</ListItemIcon>
        <ListItemText>{label}</ListItemText>
      </MenuItem>
    );
  }
  return (
    <Tooltip title={label}>
      {/* A disabled button fires no events, so the tooltip listens on what holds it */}
      <Box component="span" sx={{ display: "inline-flex" }}>
        <IconButton ref={ref} size="small" aria-label={label} aria-pressed={pressed} color={pressed ? "primary" : color}
          disabled={disabled} loading={loading} onClick={event => onClick(event.currentTarget)} sx={TOUCH_TARGET}>
          {icon}
        </IconButton>
      </Box>
    </Tooltip>
  );
}
