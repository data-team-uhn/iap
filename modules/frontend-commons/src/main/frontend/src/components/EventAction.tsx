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
import { Link as RouterLink } from "react-router";

import { useAuthenticatedFetch } from "../reLogin";
import { isRefusal } from "../requestFailure";
import { TOUCH_TARGET } from "../touchTarget";
import { sendEvent } from "../workflowEvents";
import { ActionsMenuContext } from "./ActionsMenu";
import ConfirmActionDialog from "./ConfirmActionDialog";
import { useNotice } from "./NoticeSnackbar";

interface EventActionProps {
  path: string;
  reload: () => void | Promise<void>;
  label: string;
  // What its confirmation says while the event is sent, such as "Removing…"
  workingLabel?: string;
  event: string;
  title: string;
  explanation: ReactNode;
  // The notice raised once the event is done, if any
  done?: string;
  color?: "primary" | "warning" | "error";
  icon: ReactNode;
}

// A workflow event sent to a node from an icon button. It asks for confirmation first, because the
// event changes the node itself, not just what this page shows.
export function EventAction(props: EventActionProps) {
  const { path, reload, label, workingLabel, event, title, explanation, done, color } = props;
  const [ confirming, setConfirming ] = useState(false);
  const doFetch = useAuthenticatedFetch();
  const notify = useNotice();
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
            if (done) {
              notify({ title: done, severity: "success" });
            }
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
// again says whether it is. What it does is given what stands for it: the button, or what held the menu. An action
// that only goes somewhere is a link there, in a menu too.
export function ActionIcon({ label, icon, onClick, pressed, color = "default", disabled, loading, ref, to }: {
  label: string;
  icon: ReactNode;
  onClick?: (trigger: HTMLElement) => void;
  pressed?: boolean;
  color?: "default" | "primary";
  disabled?: boolean;
  loading?: boolean;
  ref?: Ref<HTMLButtonElement>;
  to?: string;
}) {
  const menu = use(ActionsMenuContext);
  const named = (
    <>
      <ListItemIcon>{icon}</ListItemIcon>
      <ListItemText>{label}</ListItemText>
    </>
  );
  if (menu && to !== undefined) {
    return <MenuItem component={RouterLink} to={to} onClick={menu.close}>{named}</MenuItem>;
  }
  if (menu) {
    return (
      <MenuItem disabled={disabled} onClick={() => {
        menu.close();
        onClick?.(menu.trigger);
      }}>
        {named}
      </MenuItem>
    );
  }
  if (to !== undefined) {
    return (
      <Tooltip title={label}>
        <IconButton size="small" aria-label={label} component={RouterLink} to={to} sx={TOUCH_TARGET}>{icon}</IconButton>
      </Tooltip>
    );
  }
  return (
    <Tooltip title={label}>
      {/* A disabled button fires no events, so the tooltip listens on what holds it */}
      <Box component="span" sx={{ display: "inline-flex" }}>
        <IconButton ref={ref} size="small" aria-label={label} aria-pressed={pressed} color={pressed ? "primary" : color}
          disabled={disabled} loading={loading} onClick={event => onClick?.(event.currentTarget)} sx={TOUCH_TARGET}>
          {icon}
        </IconButton>
      </Box>
    </Tooltip>
  );
}
