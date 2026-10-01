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

import { createContext, use, useEffect, useState, type ReactNode } from "react";

import MoreVertIcon from "@mui/icons-material/MoreVert";
import { Box, IconButton, Menu } from "@mui/material";

import { usePhone } from "@iap/frontend-commons/usePhone";

import { TOUCH_TARGET } from "./schemaTreeLayout";

interface InMenu {
  close: () => void;
  // What stands for an action chosen in the menu once the menu is gone
  trigger: HTMLElement;
}

// Set while actions are lines of a menu rather than buttons
export const ActionsMenuContext = createContext<InMenu | null>(null);

interface ActionsMenuProps {
  // Names the button that opens it, such as "Actions for “Consent”"
  label: string;
  // Shows the actions as they are even on a phone
  inline?: boolean;
  children: ReactNode;
}

// On a phone, the actions it holds as the named lines of one menu, which stays mounted so that what an action opens
// outlives it. With nothing to offer, it is not shown. Elsewhere, the actions as they are.
export function ActionsMenu({ label, inline, children }: ActionsMenuProps) {
  const phone = usePhone();
  const [ holder, setHolder ] = useState<HTMLSpanElement | null>(null);
  const [ anchor, setAnchor ] = useState<HTMLElement | null>(null);
  const [ list, setList ] = useState<HTMLUListElement | null>(null);
  const [ empty, setEmpty ] = useState(false);

  // Actions may come and go, or load, after the menu is drawn
  useEffect(() => {
    if (!list) {
      return;
    }
    const update = () => setEmpty(list.childElementCount === 0);
    update();
    const observer = new MutationObserver(update);
    observer.observe(list, { childList: true });
    return () => observer.disconnect();
  }, [ list ]);

  if (!phone) {
    return children;
  }
  const close = () => setAnchor(null);
  return (
    <Box component="span" ref={setHolder} tabIndex={-1} sx={{ display: "inline-flex", outline: "none" }}>
      { inline ? children : (
        <>
          <IconButton size="small" aria-label={label} aria-haspopup="menu" aria-expanded={anchor !== null}
            onClick={event => setAnchor(event.currentTarget)}
            sx={{ ...TOUCH_TARGET, display: empty ? "none" : undefined }}>
            <MoreVertIcon fontSize="small" />
          </IconButton>
          { holder && (
            <Menu anchorEl={anchor} open={anchor !== null} onClose={close} keepMounted disableAutoFocusItem
              slotProps={{ list: { ref: setList } }}>
              <ActionsMenuContext value={{ close, trigger: holder }}>
                {children}
              </ActionsMenuContext>
            </Menu>
          ) }
        </>
      ) }
    </Box>
  );
}

export function useInActionsMenu() {
  return use(ActionsMenuContext) !== null;
}
