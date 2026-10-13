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

import {
  createContext, useCallback, useContext, useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode,
} from "react";

import SubdirectoryArrowRightIcon from "@mui/icons-material/SubdirectoryArrowRight";
import { Alert, Box, Button, Snackbar, type Theme } from "@mui/material";

import { useNotice } from "@iap/frontend-commons/components/NoticeSnackbar";
import { messageOf } from "@iap/frontend-commons/requestFailure";

import { type JcrNode, nameOf, pathOf } from "./schemaModel";
import { isMoveSpot } from "./schemaMoveModel";
import { useTreeEvent } from "./schemaTree";
import { resourceTypeOf, shownNameOf } from "./schemaVersionTreeModel";

interface Moving {
  node: JcrNode;
  // What it is called in the bar, such as "question"
  what: string;
  // What started the move, which gets the focus back when it is cancelled
  trigger?: HTMLElement;
}

interface MoveModeValue {
  moving?: Moving;
  // The path of what the last move moved, until it has been marked
  moved?: string;
  sending: boolean;
  isMoving: (node: JcrNode) => boolean;
  start: (moving: Moving) => void;
  cancel: () => void;
  moveTo: (node: JcrNode, parent: JcrNode, before?: JcrNode) => void;
}

const ignore = () => undefined;

const MoveModeContext = createContext<MoveModeValue>({
  sending: false, isMoving: () => false, start: ignore, cancel: ignore, moveTo: ignore,
});

export const useMoveMode = () => useContext(MoveModeContext);

// How long what was moved stays marked
const SHOWN_FOR = 3000;

// Keeps an element at the same place on the screen while places to move to open and close above it. The page
// scrolls by as much as the element was pushed.
function useKeepInPlace(change: unknown) {
  const kept = useRef<{ element: HTMLElement; top: number }>(undefined);
  useLayoutEffect(() => {
    const current = kept.current;
    kept.current = undefined;
    const shift = current?.element.isConnected ? current.element.getBoundingClientRect().top - current.top : 0;
    if (shift !== 0) {
      window.scrollBy(0, shift);
    }
  }, [ change ]);
  return useCallback((element?: HTMLElement) => {
    kept.current = element && { element, top: element.getBoundingClientRect().top };
  }, []);
}

// Lets one part or answer option of a tree move at a time. The user chooses what moves, then where it goes. The move
// is sent as one event, and a notice says when it is done.
export function MoveMode({ children }: { children: ReactNode }) {
  const [ moving, setMoving ] = useState<Moving>();
  const [ moved, setMoved ] = useState<string>();
  const [ sending, setSending ] = useState(false);
  const [ error, setError ] = useState<string>();
  // Counts failed moves, so that the bar pulses again even when a failure repeats the last message
  const [ failures, setFailures ] = useState(0);
  const send = useTreeEvent();
  const notify = useNotice();
  const keepInPlace = useKeepInPlace(moving);

  const start = useCallback((next: Moving) => {
    keepInPlace(next.trigger);
    setMoved(undefined);
    setError(undefined);
    setMoving(next);
  }, [ keepInPlace ]);
  const cancel = useCallback(() => {
    keepInPlace(moving?.trigger);
    moving?.trigger?.focus({ preventScroll: true });
    setError(undefined);
    setMoving(undefined);
  }, [ moving, keepInPlace ]);
  const moveTo = useCallback((node: JcrNode, parent: JcrNode, before?: JcrNode) => {
    setSending(true);
    setError(undefined);
    send(node, "move", { parent: pathOf(parent), ...before ? { before: nameOf(before) } : {} })
      .then(path => {
        setMoving(undefined);
        setMoved(path);
        notify({ title: `“${shownNameOf(node)}” was moved`, severity: "success" });
      })
      .catch((failure: unknown) => {
        setError(messageOf(failure));
        setFailures(count => count + 1);
      })
      .finally(() => setSending(false));
  }, [ send, notify ]);

  useEffect(() => {
    if (!moving) {
      return undefined;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        cancel();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [ moving, cancel ]);

  useEffect(() => {
    if (!moved) {
      return undefined;
    }
    const timer = setTimeout(() => setMoved(undefined), SHOWN_FOR);
    return () => clearTimeout(timer);
  }, [ moved ]);

  const value = useMemo(() => ({
    moving, moved, sending, start, cancel, moveTo,
    isMoving: (node: JcrNode) => moving !== undefined && pathOf(moving.node) === pathOf(node),
  }), [ moving, moved, sending, start, cancel, moveTo ]);
  // The bar floats over the page where notices appear, so the page does not shift when it opens. The padding under
  // the tree lets its last places scroll clear of the bar.
  return (
    <MoveModeContext.Provider value={value}>
      <Box sx={{ pb: moving ? 10 : 0 }}>{children}</Box>
      { moving && (
        <Snackbar open>
          <Alert
            severity={error ? "error" : "info"}
            role="status"
            sx={error ? {
              "@keyframes iapMoveFailedA": { "50%": { scale: "1.04" } },
              "@keyframes iapMoveFailedB": { "50%": { scale: "1.04" } },
              // Two names for one pulse: changing the name is what makes it play again
              "animation": `${failures % 2 ? "iapMoveFailedA" : "iapMoveFailedB"} 0.3s ease-in-out`,
              "@media (prefers-reduced-motion: reduce)": { animation: "none" },
            } : {}}
            action={<Button color="inherit" size="small" onClick={cancel}>Cancel</Button>}
          >
            <span key={failures}>
              { error ?? `Choose where the ${moving.what} goes. Each move is saved at once.` }
            </span>
          </Alert>
        </Snackbar>
      ) }
    </MoveModeContext.Provider>
  );
}

interface MoveSpotProps {
  // What the moving node would go into, and the child it would go before, if not last
  parent: JcrNode;
  before?: JcrNode;
  // Whether it stands in a list, among its items
  item?: boolean;
  // What else choosing it does, such as opening the part it goes into
  onChoose?: () => void;
}

// A place the moving node may go, shown only while something is moving and only where the move would change
// something. It is named after the sibling it goes before, or else the part it goes at the end of. The end of the
// version itself has no name. On a phone, it is tall enough for a finger.
export function MoveSpot({ parent, before, item, onChoose }: MoveSpotProps) {
  const { moving, sending, moveTo } = useMoveMode();
  if (!moving || !isMoveSpot(moving.node, parent, before)) {
    return null;
  }
  const named = before ?? (resourceTypeOf(parent) === "sch/SchemaVersion" ? undefined : parent);
  const name = named && shownNameOf(named);
  const where = before ? "Move before" : name ? "Move to the end of" : "Move to the end";
  return (
    <Box component={item ? "li" : "div"} sx={{ listStyle: "none", my: item ? 1 : 0 }}>
      <Button
        fullWidth
        variant="text"
        disabled={sending}
        startIcon={<SubdirectoryArrowRightIcon />}
        aria-label={name ? `${where} ${name}` : where}
        onClick={() => {
          onChoose?.();
          moveTo(moving.node, parent, before);
        }}
        sx={{
          minWidth: 0,
          minHeight: { xs: 36, sm: 30 },
          justifyContent: "flex-start",
          px: 1,
          borderRadius: 0,
          bgcolor: "background.tinted",
          color: "text.secondary",
          typography: "body2",
          "&:hover": { bgcolor: "background.tintedStrong", color: "primary.main" },
          // A place has no border, so keyboard focus gets an outline
          "&.Mui-focusVisible": {
            bgcolor: "background.tintedStrong", color: "primary.main",
            outline: 2, outlineColor: "primary.main", outlineOffset: -2,
          },
          // The label shows on hover and keyboard focus, and always on touch screens, which cannot hover
          "& .iap-move-spot-label": { opacity: 0, transition: "opacity 0.15s" },
          "&:hover .iap-move-spot-label, &.Mui-focusVisible .iap-move-spot-label": { opacity: 1 },
          "@media (hover: none)": { "& .iap-move-spot-label": { opacity: 1 } },
        }}
      >
        <Box
          component="span"
          className="iap-move-spot-label"
          sx={{ display: "inline-flex", alignItems: "baseline", gap: 0.75, minWidth: 0, whiteSpace: "nowrap" }}
        >
          <span>{where}</span>
          { name && (
            <Box
              component="span"
              sx={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", px: 0.75, borderRadius: 0.5,
                bgcolor: "background.paper", color: "text.primary" }}
            >
              {name}
            </Box>
          ) }
        </Box>
      </Button>
    </Box>
  );
}

// Marks the part or option that is moving, and the one just moved. The moving one is shaded and dashed, and its
// content faded. A bordered element keeps the solid left edge that shows its type; anything else is outlined. The one
// just moved is scrolled into view, focused, and tinted for a moment.
export function useMoveHighlight<T extends HTMLElement>(node: JcrNode, { bordered }: { bordered: boolean }) {
  const { moved, isMoving } = useMoveMode();
  const ref = useRef<T>(null);
  const justMoved = moved === pathOf(node);
  const moving = isMoving(node);

  useEffect(() => {
    if (justMoved) {
      ref.current?.focus({ preventScroll: true });
      ref.current?.scrollIntoView({ block: "nearest" });
    }
  }, [ justMoved ]);

  return {
    ref,
    // Styles for the element itself
    surface: (theme: Theme) => {
      const { palette } = theme.vars ?? theme;
      const edge = palette.text.secondary;
      return {
        ...moving ? {
          backgroundColor: palette.background.muted,
          ...bordered
            ? { borderStyle: "dashed", borderLeftStyle: "solid", borderTopColor: edge, borderRightColor: edge,
              borderBottomColor: edge }
            : { outline: `1px dashed ${edge}` },
        } : {},
        ...justMoved ? {
          "@keyframes iapSchemaPartMoved": { "0%, 60%": { backgroundColor: palette.background.tinted } },
          "animation": `iapSchemaPartMoved ${SHOWN_FOR}ms ease-out`,
          "@media (prefers-reduced-motion: reduce)": { animation: "none" },
        } : {},
      };
    },
    // Styles for its content
    content: moving ? { opacity: 0.6 } : {},
  };
}
