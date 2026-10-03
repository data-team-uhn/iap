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
import { visuallyHidden } from "@mui/utils";

import { messageOf } from "@iap/frontend-commons/requestFailure";
import { usePhone } from "@iap/frontend-commons/usePhone";

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

export type Way = "up" | "down";

interface MoveModeValue {
  moving?: Moving;
  // Where the last move put what it moved, until it has been shown
  moved?: string;
  // Whether that move was an option's step
  stepped: boolean;
  // Counts moves, so that moving the same one again shows again
  steps: number;
  // The option a step is being saved for
  stepping?: string;
  // Where the moving part is being sent
  destination?: string;
  // Where on the screen what was pressed to move it was, for what moved to be shown there
  landing?: number;
  sending: boolean;
  isMoving: (node: JcrNode) => boolean;
  start: (moving: Moving) => void;
  cancel: () => void;
  moveTo: (node: JcrNode, parent: JcrNode, before: JcrNode | undefined, from: HTMLElement) => void;
  step: (option: JcrNode, question: JcrNode, before: JcrNode | undefined, way: Way, from: HTMLElement) => void;
}

const ignore = () => undefined;

const MoveModeContext = createContext<MoveModeValue>({
  sending: false, stepped: false, steps: 0, isMoving: () => false, start: ignore, cancel: ignore, moveTo: ignore,
  step: ignore,
});

export const useMoveMode = () => useContext(MoveModeContext);

// How long what was moved stays marked
const SHOWN_FOR = 3000;

const placeOf = (parent: JcrNode, before?: JcrNode): string => `${pathOf(parent)}/${before ? nameOf(before) : ""}`;

const moveParams = (parent: JcrNode, before?: JcrNode) =>
  ({ parent: pathOf(parent), ...before ? { before: nameOf(before) } : {} });

const PULSE = { "50%": { scale: "1.03" } };

// Keyframes under a name that changes with a count, so that an animation using them plays again as the count changes
function replaying(name: string, count: number, frames: object) {
  const named = `${name}${count % 2}`;
  return { name: named, keyframes: { [`@keyframes ${named}`]: frames } };
}

// Keeps an element where it is on the screen while the layout around it changes, as places to move to open and
// close above it
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

// Moving one part of a tree at a time: choosing what moves, then where, as one event, and saying once it is done.
// Answer options move one step at a time instead, each step one event.
export function MoveMode({ report, children }: { report: (message: string) => void; children: ReactNode }) {
  const [ moving, setMoving ] = useState<Moving>();
  const [ moved, setMoved ] = useState<string>();
  const [ stepped, setStepped ] = useState(false);
  const [ steps, setSteps ] = useState(0);
  const [ stepping, setStepping ] = useState<string>();
  const [ destination, setDestination ] = useState<string>();
  const [ landing, setLanding ] = useState<number>();
  // For a screen reader, as soon as a step is asked for
  const [ announced, setAnnounced ] = useState("");
  const [ sending, setSending ] = useState(false);
  const [ error, setError ] = useState<string>();
  // Counts failures, so that a repeated one pulses again
  const [ failures, setFailures ] = useState(0);
  const send = useTreeEvent();
  const keepInPlace = useKeepInPlace(moving);

  const shown = useCallback((path: string | undefined, step: boolean) => {
    setStepped(step);
    setSteps(count => count + 1);
    setMoved(path);
  }, []);
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
  const moveTo = useCallback((node: JcrNode, parent: JcrNode, before: JcrNode | undefined, from: HTMLElement) => {
    setSending(true);
    setLanding(from.getBoundingClientRect().top);
    setDestination(placeOf(parent, before));
    setError(undefined);
    send(node, "move", moveParams(parent, before))
      .then(path => {
        setMoving(undefined);
        shown(path, false);
        report(`“${shownNameOf(node)}” was moved`);
      })
      .catch((failure: unknown) => {
        setError(messageOf(failure));
        setFailures(count => count + 1);
      })
      .finally(() => setSending(false));
  }, [ send, report, shown ]);
  const step = useCallback((option: JcrNode, question: JcrNode, before: JcrNode | undefined, way: Way,
    from: HTMLElement) => {
    setSending(true);
    setLanding(from.getBoundingClientRect().top);
    setStepping(pathOf(option));
    setAnnounced(`Moving “${shownNameOf(option)}” ${way}…`);
    send(option, "move", moveParams(question, before))
      .then(path => {
        shown(path, true);
        report(`“${shownNameOf(option)}” was moved ${way}`);
      })
      .catch((failure: unknown) => report(`“${shownNameOf(option)}” could not be moved. ${messageOf(failure)}`))
      .finally(() => {
        setStepping(undefined);
        setAnnounced("");
        setSending(false);
      });
  }, [ send, report, shown ]);

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
    moving, moved, stepped, steps, stepping, destination, landing, sending, start, cancel, moveTo, step,
    isMoving: (node: JcrNode) => moving !== undefined && pathOf(moving.node) === pathOf(node),
  }), [ moving, moved, stepped, steps, stepping, destination, landing, sending, start, cancel, moveTo, step ]);
  const failed = replaying("iapMoveFailed", failures, PULSE);
  return (
    <MoveModeContext.Provider value={value}>
      {/* Room for the bar, so that the last places can be scrolled clear of it */}
      <Box sx={{ pb: moving ? 10 : 0 }}>{children}</Box>
      <Box aria-live="polite" sx={visuallyHidden}>{announced}</Box>
      { moving && (
        <Snackbar open>
          <Alert
            severity={error ? "error" : "info"}
            role="status"
            sx={error ? {
              ...failed.keyframes,
              "animation": `${failed.name} 0.3s ease-in-out`,
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

// A place the moving part may go, named after what it would go before or at the end of
export function MoveSpot({ parent, before, item, onChoose }: MoveSpotProps) {
  const { moving, sending, destination, moveTo } = useMoveMode();
  const phone = usePhone();
  if (!moving || !isMoveSpot(moving.node, parent, before)) {
    return null;
  }
  const named = before ?? (resourceTypeOf(parent) === "sch/SchemaVersion" ? undefined : parent);
  const name = named && shownNameOf(named);
  const where = before ? "Move before" : name ? "Move to the end of" : "Move to the end";
  const loading = sending && destination === placeOf(parent, before);
  // On a phone, what moves goes unnamed: it is the part shown moving
  const what = phone ? "" : ` “${shownNameOf(moving.node)}”`;
  const said = loading ? `Moving${what} ${before ? "before" : name ? "to the end of" : "to the end"}` : where;
  return (
    <Box component={item ? "li" : "div"} sx={{ listStyle: "none", my: item ? 1 : 0 }}>
      <Button
        fullWidth
        variant="text"
        disabled={sending}
        loading={loading}
        startIcon={<SubdirectoryArrowRightIcon />}
        aria-label={name ? `${where} ${name}` : where}
        onClick={event => {
          onChoose?.();
          moveTo(moving.node, parent, before, event.currentTarget);
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
          // Labelled when pointed at or focused, and always on touch screens and while it is where the part goes
          "& .iap-move-spot-label": { opacity: 0, transition: "opacity 0.15s" },
          "&:hover .iap-move-spot-label, &.Mui-focusVisible .iap-move-spot-label": { opacity: 1 },
          "@media (hover: none)": { "& .iap-move-spot-label": { opacity: 1 } },
          ...loading ? { "& .iap-move-spot-label": { opacity: 1 } } : {},
        }}
      >
        <Box
          component="span"
          className="iap-move-spot-label"
          sx={{ display: "inline-flex", alignItems: "baseline", gap: 0.75, minWidth: 0, whiteSpace: "nowrap" }}
        >
          {/* Giving way to the name before it gives way itself */}
          <Box component="span" sx={{ minWidth: 0, flexShrink: 0.1, overflow: "hidden", textOverflow: "ellipsis" }}>
            {said}
          </Box>
          { name && (
            <Box
              component="span"
              sx={{ minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", px: 0.75, borderRadius: 0.5,
                bgcolor: "background.paper", color: "text.primary" }}
            >
              {name}
            </Box>
          ) }
          { loading && <span>…</span> }
        </Box>
      </Button>
    </Box>
  );
}

// How a part shows that it is the one moving, and how a part or an option shows that it has just moved
export function useMoveHighlight<T extends HTMLElement>(node: JcrNode) {
  const { moved, stepped, steps, stepping, landing, isMoving } = useMoveMode();
  const ref = useRef<T>(null);
  const justMoved = moved === pathOf(node);
  const moving = isMoving(node);

  // Before painting, so that it lands where it was sent from without a jump; a step's own button does that for it
  useLayoutEffect(() => {
    if (!justMoved || stepped || !ref.current || landing === undefined) {
      return;
    }
    ref.current.focus({ preventScroll: true });
    window.scrollBy(0, ref.current.getBoundingClientRect().top - landing);
  }, [ justMoved, stepped, landing, steps ]);

  return {
    ref,
    // For what it stands on
    surface: (theme: Theme) => {
      const { palette } = theme.vars ?? theme;
      const edge = palette.text.secondary;
      const pulse = replaying("iapMovedPulse", steps, PULSE);
      const shade = replaying("iapMovedShade", steps, { "0%, 60%": { backgroundColor: palette.background.tinted } });
      return {
        ...moving ? {
          backgroundColor: palette.background.muted,
          borderStyle: "dashed",
          borderLeftStyle: "solid",
          borderTopColor: edge,
          borderRightColor: edge,
          borderBottomColor: edge,
        } : {},
        ...stepping === pathOf(node) ? { backgroundColor: palette.background.tintedStrong } : {},
        ...justMoved ? {
          ...pulse.keyframes,
          ...shade.keyframes,
          "animation": `${pulse.name} 0.35s ease-in-out, ${shade.name} ${SHOWN_FOR}ms ease-out`,
          "@media (prefers-reduced-motion: reduce)": { animation: "none" },
        } : {},
      };
    },
    // For what it says
    content: moving ? { opacity: 0.6 } : {},
  };
}
