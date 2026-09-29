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

import { useState, type MouseEvent, type ReactNode } from "react";

import { DialogContentText, IconButton, Tooltip } from "@mui/material";

import { useAuthenticatedFetch } from "../reLogin";
import { isRefusal } from "../requestFailure";
import { sendEvent } from "../workflowEvents";
import ConfirmActionDialog from "./ConfirmActionDialog";
import { useNotice } from "./NoticeSnackbar";

interface EventActionProps {
  path: string;
  reload: () => void | Promise<void>;
  label: string;
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
  const { path, reload, label, event, title, explanation, done, color } = props;
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

// One action as an icon button, named by its tooltip. One that stays on until pressed again says whether it is.
export function ActionIcon({ label, icon, onClick, pressed, color = "default" }: {
  label: string;
  icon: ReactNode;
  onClick: (event: MouseEvent<HTMLElement>) => void;
  pressed?: boolean;
  color?: "default" | "primary";
}) {
  return (
    <Tooltip title={label}>
      <IconButton size="small" aria-label={label} aria-pressed={pressed} color={pressed ? "primary" : color}
        onClick={onClick}>
        {icon}
      </IconButton>
    </Tooltip>
  );
}
