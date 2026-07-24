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
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
  type Ref
} from "react";

import ChevronLeftIcon from "@mui/icons-material/ChevronLeft";
import ChevronRightIcon from "@mui/icons-material/ChevronRight";
import ExpandLessIcon from "@mui/icons-material/ExpandLess";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import {
  Box,
  Drawer,
  IconButton,
  Tooltip,
  type TooltipProps
} from "@mui/material";
import {
  useTheme,
  type Breakpoint,
  type Theme
} from "@mui/material/styles";

import { ExtensionList, type Extension } from "@iap/ui-extension/ExtensionList";
import { loadExtensions } from "@iap/ui-extension/extensionManager";

// The extension points composing the page shell. The four Frame* points form the stable screen
// frame — always visible, never scrolling away: the full-width top and bottom bars and the two
// side rails between them. The Page* points are inside the frame, above and below the main
// content, and scroll with it.
// Naming convention: the vertical axis uses physical Top/Bottom names (vertical placement never
// flips with the writing direction); Start/End is reserved for the horizontal axis, where a
// right-to-left locale mirrors the layout.
const SHELL_POINTS = [
  "FrameTop",
  "FrameBottom",
  "FrameStart",
  "FrameEnd",
  "PageTop",
  "PageBottom"
] as const;

// Fallback dimensions for the frame regions. The active values are meant to be configured
// per-region in the theme's `iapShell` section (see frontend-commons/src/appTheme.ts); these only
// fill in when the theme doesn't specify them.
const DEFAULT_RAIL_CONFIG = { width: 200, collapseWidth: "md" as number | Breakpoint };
const DEFAULT_BAR_CONFIG = { collapseHeight: 500 };

const railConfig = (theme: Theme, edge: "start" | "end") =>
  ({ ...DEFAULT_RAIL_CONFIG, ...theme.iapShell?.[edge === "start" ? "frameStart" : "frameEnd"] });
const barConfig = (theme: Theme, edge: "start" | "end") =>
  ({ ...DEFAULT_BAR_CONFIG, ...theme.iapShell?.[edge === "start" ? "frameTop" : "frameBottom"] });

// Media queries matching viewports shorter/taller than the given height — MUI's own breakpoint
// helpers only cover widths. The 0.05px epsilon mirrors how MUI's `breakpoints.down` avoids
// overlapping its `up` counterpart.
const shorterThan = (height: number) => `@media (max-height: ${height - 0.05}px)`;
const tallerThan = (height: number) => `@media (min-height: ${height}px)`;

// How long the hint tooltip on a pull tab stays up, and how long after the page loads (or a
// resize settles) it appears.
const HINT_DURATION = 3000;
const HINT_APPEAR_DELAY = 1500;
const HINT_SEEN_KEY = "iap.pullTabHintSeen";
// At most one first-visit hint per page load across all regions (several may start collapsed,
// e.g. on a phone; flashing a hint on every one of them would be noise), claimed by whichever
// region's timer fires first.
let pageLoadHintClaimed = false;

const hintAlreadySeen = () => {
  try {
    return window.localStorage.getItem(HINT_SEEN_KEY) !== null;
  } catch {
    // No storage (e.g. blocked) — behave as already-seen rather than nag on every visit
    return true;
  }
};

const rememberHintSeen = () => {
  try {
    window.localStorage.setItem(HINT_SEEN_KEY, "true");
  } catch {
    // No storage — nothing to remember into
  }
};

// Drives the transient hint tooltip on a pull tab: it flashes when the region live-collapses
// and once ever (remembered in localStorage) when a page first opens with the region already
// collapsed — the moments when content silently hiding behind a subtle tab would otherwise be
// confusing. `expandedMedia` is the same media query that keeps the expanded region visible in
// CSS; `enabled` should be false when the region has nothing to show (so there is no tab to
// point at).
function useCollapseHint(expandedMedia: string, enabled: boolean): boolean {
  const [ hint, setHint ] = useState(false);

  useEffect(() => {
    // Skip environments that can't actually evaluate media queries (jsdom's matchMedia matches
    // nothing, not even "all") — there is no real screen to adapt to there.
    if (!enabled || typeof window.matchMedia !== "function" || !window.matchMedia("all").matches) {
      return;
    }
    const expanded = window.matchMedia(expandedMedia.replace(/^@media\s*/, ""));
    let pending: number | undefined;
    const flash = () => {
      setHint(true);
      pending = window.setTimeout(() => setHint(false), HINT_DURATION);
    };
    if (!expanded.matches && !pageLoadHintClaimed && !hintAlreadySeen()) {
      pending = window.setTimeout(() => {
        // Both the claim and the flash happen only when the timer actually fires, so that regions
        // scheduled in the same tick race fairly, and cancelled effects (e.g. React StrictMode's
        // mount/unmount/mount cycle) don't burn the hint without ever showing it
        if (!pageLoadHintClaimed) {
          pageLoadHintClaimed = true;
          rememberHintSeen();
          flash();
        }
      }, HINT_APPEAR_DELAY);
    }
    const onChange = (event: MediaQueryListEvent) => {
      window.clearTimeout(pending);
      if (event.matches) {
        setHint(false);
      } else {
        pending = window.setTimeout(flash, HINT_APPEAR_DELAY);
      }
    };
    expanded.addEventListener("change", onChange);
    return () => {
      window.clearTimeout(pending);
      expanded.removeEventListener("change", onChange);
    };
  }, [ expandedMedia, enabled ]);

  return hint;
}

interface RegionProps {
  // The extensions registered on this region's extension point, already in display order.
  extensions: Extension[];
  // Which edge of the screen this region sits at: block start/end for the frame bars,
  // inline start/end for the side rails.
  edge: "start" | "end";
}

interface PullTabProps {
  // The accessible name of the tab, also its hover tooltip.
  label: string;
  // The friendlier message shown while the collapse hint flashes.
  hintTitle: string;
  // Transiently true right after the region collapses (see useCollapseHint); opens the tooltip.
  hint: boolean;
  // Which side of the tab the tooltip floats to — pointing into the screen. Physical, like the
  // drawer anchors; at a screen edge the popper repositions an overflowing tooltip inward anyway.
  placement: TooltipProps["placement"];
  onClick: () => void;
  // Placement, visibility, and shape for the specific screen edge this tab hugs.
  sx: Record<string, unknown>;
  // The chevron to display, pointing into the screen where the drawer will open.
  children: ReactNode;
}

// A slim half-pill button at the screen edge, revealed when its frame region is collapsed;
// clicking it pulls the region's content over the page as a drawer.
function PullTab({ label, hintTitle, hint, placement, onClick, sx, children }: PullTabProps) {
  // The tooltip must be controlled (the hint opens it programmatically), so the regular
  // hover/focus behaviour is re-implemented through onOpen/onClose.
  const [ hover, setHover ] = useState(false);
  return (
    <Tooltip
      title={hint ? hintTitle : label}
      open={hint || hover}
      onOpen={() => setHover(true)}
      onClose={() => setHover(false)}
      placement={placement}
      arrow
    >
      <IconButton
        aria-label={label}
        onClick={onClick}
        size="small"
        sx={{
          position: "fixed",
          zIndex: "appBar",
          bgcolor: "background.paper",
          border: "1px solid",
          borderColor: "divider",
          borderRadius: 0,
          // Draw the eye whenever the tab (re)appears: CSS restarts the animation each time the
          // element regains a rendering box, which is exactly when a region collapses into the
          // tab (or the page opens with it collapsed).
          "@keyframes iapPullTabPulse": {
            "0%, 100%": { scale: "1" },
            "50%": { scale: "1.2" },
          },
          animation: "iapPullTabPulse 0.9s ease-in-out 0.3s 2",
          "@media (prefers-reduced-motion: reduce)": { animation: "none" },
          ...sx,
        }}
      >
        {children}
      </IconButton>
    </Tooltip>
  );
}

type FrameBarProps = RegionProps & {
  // Receives the bar element (or null while there is none), so the shell can measure its height
  // and keep the side rails out from under it.
  barRef: Ref<HTMLDivElement>;
};

// One of the frame bars: `iap/coreUI/frameTop` pinned to the top of the screen,
// `iap/coreUI/frameBottom` pinned to its bottom. All the extensions of a bar share one pinned,
// opaque, full-width container, so they stack instead of overlapping and the content scrolling
// past doesn't show through them. On screens shorter than the bar's configured collapse height,
// the bar leaves the flow entirely; instead, a pull tab pinned to the matching screen edge pulls
// the same content over the page as a temporary drawer. A bar with no extensions renders nothing.
function FrameBar({ extensions, edge, barRef }: FrameBarProps) {
  const [ open, setOpen ] = useState(false);
  const theme = useTheme();
  const { collapseHeight } = barConfig(theme, edge);
  const hint = useCollapseHint(tallerThan(collapseHeight), extensions.length > 0);

  if (extensions.length === 0) {
    return null;
  }

  return (
    <>
      <Box
        ref={barRef}
        sx={{
          position: "sticky",
          ...(edge === "start" ? { insetBlockStart: 0 } : { insetBlockEnd: 0 }),
          zIndex: "appBar",
          bgcolor: "background.default",
          // When collapsed, the measuring ResizeObserver reports the bar as 0 high, freeing the
          // side rails to span the full viewport height.
          [shorterThan(collapseHeight)]: { display: "none" },
        }}
      >
        <ExtensionList extensions={extensions} />
      </Box>
      <PullTab
        label={`Open the ${edge === "start" ? "top" : "bottom"} panel`}
        hintTitle={`The ${edge === "start" ? "top" : "bottom"} panel is available here`}
        hint={hint}
        placement={edge === "start" ? "bottom" : "top"}
        onClick={() => setOpen(true)}
        sx={{
          display: "none",
          [shorterThan(collapseHeight)]: { display: "inline-flex" },
          insetInlineStart: "50%",
          translate: "-50% 0",
          ...(edge === "start"
            ? {
              insetBlockStart: 0,
              borderBlockStartWidth: 0,
              borderEndStartRadius: "50%",
              borderEndEndRadius: "50%",
            }
            : {
              insetBlockEnd: 0,
              borderBlockEndWidth: 0,
              borderStartStartRadius: "50%",
              borderStartEndRadius: "50%",
            }),
        }}
      >
        {edge === "start" ? <ExpandMoreIcon /> : <ExpandLessIcon />}
      </PullTab>
      <Drawer
        anchor={edge === "start" ? "top" : "bottom"}
        open={open}
        onClose={() => setOpen(false)}
        sx={{ [tallerThan(collapseHeight)]: { display: "none" } }}
      >
        <ExtensionList extensions={extensions} />
      </Drawer>
    </>
  );
}

// One of the side rails of the frame: `iap/coreUI/frameStart` and `iap/coreUI/frameEnd`. A rail
// spans the band of the screen left between the frame bars (whose measured heights the shell
// publishes as the CSS variables used below), keeps its configured width, and scrolls its own
// content independently of the page. On screens narrower than the rail's configured collapse
// width, the rail leaves the page flow entirely; instead, a slim pull tab pinned to the matching
// screen edge pulls the same content over the page as a temporary drawer. A rail with no
// extensions renders nothing, giving its width back to the main content (and no pull tab).
function SideRail({ extensions, edge }: RegionProps) {
  const [ open, setOpen ] = useState(false);
  const theme = useTheme();
  const { width, collapseWidth } = railConfig(theme, edge);
  // Media query matching viewports wide enough for this rail to stay in the page flow
  const expanded = theme.breakpoints.up(collapseWidth);
  const hint = useCollapseHint(expanded, extensions.length > 0);

  if (extensions.length === 0) {
    return null;
  }

  return (
    <>
      <Box
        component="aside"
        sx={{
          position: "sticky",
          insetBlockStart: "var(--iap-frame-top, 0px)",
          blockSize: "calc(100dvh - var(--iap-frame-top, 0px) - var(--iap-frame-bottom, 0px))",
          inlineSize: width,
          flexShrink: 0,
          overflowY: "auto",
          display: "none",
          [expanded]: { display: "block" },
          ...(edge === "start" ? { borderInlineEnd: "1px solid" } : { borderInlineStart: "1px solid" }),
          borderColor: "divider",
        }}
      >
        <ExtensionList extensions={extensions} />
      </Box>
      { /* Deliberately just "side panel", not the code-facing start/end vocabulary: the tab's own
           position already tells the user which side it is. */ }
      <PullTab
        label="Open the side panel"
        hintTitle="The side panel is available here"
        hint={hint}
        placement={edge === "start" ? "right" : "left"}
        onClick={() => setOpen(true)}
        sx={{
          [expanded]: { display: "none" },
          insetBlockStart: "50%",
          translate: "0 -50%",
          ...(edge === "start"
            ? {
              insetInlineStart: 0,
              borderInlineStartWidth: 0,
              borderStartEndRadius: "50%",
              borderEndEndRadius: "50%",
            }
            : {
              insetInlineEnd: 0,
              borderInlineEndWidth: 0,
              borderStartStartRadius: "50%",
              borderEndStartRadius: "50%",
            }),
        }}
      >
        {edge === "start" ? <ChevronRightIcon /> : <ChevronLeftIcon />}
      </PullTab>
      { /* The drawer is a detached overlay, outside the page's writing direction, so unlike the
           rest of the shell it must be anchored physically. When a right-to-left mode lands, this
           mapping (and the chevron choice above) is the one place that must also consult the
           theme direction — in RTL, "start" anchors right. */ }
      <Drawer
        anchor={edge === "start" ? "left" : "right"}
        open={open}
        onClose={() => setOpen(false)}
        sx={{ [expanded]: { display: "none" } }}
        slotProps={{ paper: { sx: { inlineSize: width } } }}
      >
        <ExtensionList extensions={extensions} />
      </Drawer>
    </>
  );
}

interface PageLayoutProps {
  // The main content of the page.
  children: ReactNode;
}

// The overall page shell: a stable screen frame (the FrameTop/FrameBottom bars and the
// FrameStart/FrameEnd side rails, always visible), around a scrolling middle that stacks the
// PageTop extensions, the main content, and the PageBottom extensions — so the Page* regions sit
// between the rails and scroll naturally with the content instead of spanning over the rails.
// On screens too small for a frame region (narrow for the rails, short for the bars, with the
// thresholds configured per-region in the theme's `iapShell` section), that region collapses
// into an edge pull tab that opens its content as a drawer over the page.
//
// The shell only uses CSS logical properties (inset-block/inline, inline-size, ...) and flex
// order, never left/right, so the whole layout mirrors automatically if a future right-to-left
// locale sets `dir="rtl"` on the document — the "start" rail then lands on the right, as RTL
// users expect.
function PageLayout({ children }: PageLayoutProps) {
  const [ regions, setRegions ] = useState<Record<string, Extension[]>>({});
  const region = (point: string) => regions[point] ?? [];

  useEffect(() => {
    SHELL_POINTS.forEach(point => {
      void (async () => {
        try {
          const extensions = await loadExtensions(point);
          setRegions(loaded => ({ ...loaded, [point]: extensions }));
        } catch (err) {
          console.error(`Something went wrong loading the ${point} extensions`, err);
        }
      })();
    });
  }, []);

  // The measured heights of the two frame bars, so the side rails can span exactly the band of
  // the screen between them. Published to the rails as CSS variables on the shell root.
  const frameTopRef = useRef<HTMLDivElement>(null);
  const frameBottomRef = useRef<HTMLDivElement>(null);
  const [ frameInsets, setFrameInsets ] = useState({ top: 0, bottom: 0 });

  // (Re-)measure whenever a bar appears, disappears (the loaded extensions change, the screen
  // becomes too short and the bar collapses, ...), or resizes (its own content changes, the
  // window narrows and its content wraps, ...).
  useEffect(() => {
    // Not available in some non-browser environments (jsdom); the rails then keep the 0px
    // fallbacks of the CSS variables, i.e. they span the full viewport height.
    if (typeof ResizeObserver === "undefined") {
      return;
    }
    const measure = () => setFrameInsets({
      top: frameTopRef.current?.offsetHeight ?? 0,
      bottom: frameBottomRef.current?.offsetHeight ?? 0,
    });
    measure();
    const observer = new ResizeObserver(measure);
    [ frameTopRef.current, frameBottomRef.current ].forEach(bar => bar && observer.observe(bar));
    return () => observer.disconnect();
  }, [ regions ]);

  const frameInsetVariables = {
    "--iap-frame-top": `${frameInsets.top}px`,
    "--iap-frame-bottom": `${frameInsets.bottom}px`,
  } as CSSProperties;

  return (
    <Box
      style={frameInsetVariables}
      sx={{ display: "flex", flexDirection: "column", minBlockSize: "100dvh" }}
    >
      <FrameBar extensions={region("FrameTop")} edge="start" barRef={frameTopRef} />
      { /* The middle band grows to keep a short page's frame bottom bar at the bottom. */ }
      <Box sx={{ display: "flex", flexGrow: 1 }}>
        <SideRail extensions={region("FrameStart")} edge="start" />
        { /* The scrolling middle: page-top extensions, then the main content, then page-bottom
             extensions, all between the rails. minInlineSize lets it shrink below its content's
             intrinsic width instead of pushing the rails off-screen. */ }
        <Box sx={{ display: "flex", flexDirection: "column", flexGrow: 1, minInlineSize: 0 }}>
          <ExtensionList extensions={region("PageTop")} />
          { /* Page gutters: CssBaseline resets the browser's default body margin, so the main
               content provides its own padding. */ }
          <Box component="main" sx={{ flexGrow: 1, p: 3 }}>
            {children}
          </Box>
          <ExtensionList extensions={region("PageBottom")} />
        </Box>
        <SideRail extensions={region("FrameEnd")} edge="end" />
      </Box>
      <FrameBar extensions={region("FrameBottom")} edge="end" barRef={frameBottomRef} />
    </Box>
  );
}

export default PageLayout;
