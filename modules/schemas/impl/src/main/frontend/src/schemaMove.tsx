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
  // Where the last move put what it moved, until it has been shown
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

// Keeps an element where it is on the screen while the layout around it changes, as places to move to open and
// close above it: the page scrolls by as much as the element was pushed.
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

// Moving one part or answer option of a tree at a time: choosing what moves, then where, as one event, and saying
// once it is done.
export function MoveMode({ report, children }: { report: (message: string) => void; children: ReactNode }) {
  const [ moving, setMoving ] = useState<Moving>();
  const [ moved, setMoved ] = useState<string>();
  const [ sending, setSending ] = useState(false);
  const [ error, setError ] = useState<string>();
  // How many moves have failed, so that each failure is noticed, even one saying what the last one said
  const [ failures, setFailures ] = useState(0);
  const send = useTreeEvent();
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
        report(`“${shownNameOf(node)}” was moved`);
      })
      .catch((failure: unknown) => {
        setError(messageOf(failure));
        setFailures(count => count + 1);
      })
      .finally(() => setSending(false));
  }, [ send, report ]);

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
  // The bar stands where notices do, over the page rather than in it, so nothing moves to make room for it; room is
  // made under the tree instead, so that its last places can be scrolled clear of it
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

// A place the moving node may go, shown only while something is moving and only where it would go somewhere new,
// named after what it goes before, or else what it goes at the end of; the version's own end needs no name. On a
// phone, it is tall enough for a finger.
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
          // A place with no border of its own still shows where the keyboard is
          "&.Mui-focusVisible": {
            bgcolor: "background.tintedStrong", color: "primary.main",
            outline: 2, outlineColor: "primary.main", outlineOffset: -2,
          },
          // Where it is only when pointed at or reached, and always where nothing can point without pressing
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

// How a part or an option shows that it is the one moving, cut out and on its way elsewhere, and that it is the one
// just moved: that one is brought into view, given the focus, and marked for a moment. What has a border of its own
// dashes it, keeping its left edge, which says what it is; anything else is outlined.
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
    // For what it stands on
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
    // For what it says
    content: moving ? { opacity: 0.6 } : {},
  };
}
