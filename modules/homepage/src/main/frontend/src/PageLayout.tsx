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

// The extension points of the page shell. The Frame* points are the frame around the screen,
// which never scrolls away. It has a full-width bar at the top and at the bottom, and a side rail
// at each side between them. The Page* points are above and below the main content, and scroll
// with it.
// Top and Bottom are physical. Start and End are logical, so that they swap in a right-to-left
// language.
const SHELL_POINTS = [
  "FrameTop",
  "FrameBottom",
  "FrameStart",
  "FrameEnd",
  "PageTop",
  "PageBottom"
] as const;

// The sizes of the frame regions where the theme's `iapShell` does not set them
const DEFAULT_RAIL_CONFIG = { width: 200, collapseWidth: "md" as number | Breakpoint };
const DEFAULT_BAR_CONFIG = { collapseHeight: 500 };

const railConfig = (theme: Theme, edge: "start" | "end") =>
  ({ ...DEFAULT_RAIL_CONFIG, ...theme.iapShell?.[edge === "start" ? "frameStart" : "frameEnd"] });
const barConfig = (theme: Theme, edge: "start" | "end") =>
  ({ ...DEFAULT_BAR_CONFIG, ...theme.iapShell?.[edge === "start" ? "frameTop" : "frameBottom"] });

// Media queries on the viewport's height, which MUI's breakpoints do not cover. `shorterThan`
// stops 0.05px short of the height, so that the two never match together.
const shorterThan = (height: number) => `@media (max-height: ${height - 0.05}px)`;
const tallerThan = (height: number) => `@media (min-height: ${height}px)`;

// How long, in milliseconds, the hint on a pull tab stays up, and how long after the page opens
// or the region collapses it appears
const HINT_DURATION = 3000;
const HINT_APPEAR_DELAY = 1500;
const HINT_SEEN_KEY = "iap.pullTabHintSeen";
// Whether a region has shown the hint for this page load. Several regions may open collapsed, on
// a phone, and only the first whose timer fires shows it.
let pageLoadHintClaimed = false;

const hintAlreadySeen = () => {
  try {
    return window.localStorage.getItem(HINT_SEEN_KEY) !== null;
  } catch {
    // Without storage, the hint counts as seen, so that it does not show on every visit
    return true;
  }
};

const rememberHintSeen = () => {
  try {
    window.localStorage.setItem(HINT_SEEN_KEY, "true");
  } catch {
    // Without storage, nothing is remembered
  }
};

// Whether the hint on a region's pull tab is up. It shows for a while each time the region
// collapses. It also shows once per browser, when a page opens with a region collapsed.
// `expandedMedia` is the media query under which the region is expanded. `enabled` is false
// when the region has nothing to show, and so no tab.
function useCollapseHint(expandedMedia: string, enabled: boolean): boolean {
  const [ hint, setHint ] = useState(false);

  useEffect(() => {
    // There is no screen without a matchMedia, or with one that does not even match "all", as in
    // a test
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
        // The hint is claimed when the timer fires, not when it is set. An effect cancelled before
        // then, as StrictMode cancels the first, leaves the hint to the next one.
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
  // The extensions on the region's extension point, in their order
  extensions: Extension[];
  // The edge of the screen the region is at: the block start or end for a frame bar, and the
  // inline start or end for a side rail
  edge: "start" | "end";
}

interface PullTabProps {
  // The tab's accessible name, which its tooltip shows
  label: string;
  // What the tooltip says while the hint is up
  hintTitle: string;
  // Whether the hint is up, which opens the tooltip
  hint: boolean;
  // The side of the tab the tooltip shows on, towards the middle of the screen
  placement: TooltipProps["placement"];
  onClick: () => void;
  // The tab's placement, visibility and shape at its edge of the screen
  sx: Record<string, unknown>;
  // The chevron, pointing the way the drawer opens
  children: ReactNode;
}

// A small half-round button at the edge of the screen, for a collapsed frame region. Clicking it
// opens the region's content in a drawer over the page.
function PullTab({ label, hintTitle, hint, placement, onClick, sx, children }: PullTabProps) {
  // The hint opens the tooltip, so the tooltip is controlled, and hovering opens it through
  // onOpen and onClose
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
          // Pulses twice each time the tab appears. CSS restarts the animation whenever the tab
          // goes from `display: none` to shown, which is when its region collapses.
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
  // Given the bar's element, or null while there is none, so that the shell can measure its
  // height and keep the side rails below it
  barRef: Ref<HTMLDivElement>;
};

// A frame bar, pinned to the top or the bottom of the screen. Its extensions are stacked in one
// opaque, full-width container, which the content scrolls under. On a screen shorter than the
// bar's collapse height, the bar is hidden, and a pull tab at that edge opens its extensions in a
// drawer. A bar without extensions renders nothing.
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
          // A hidden bar is measured as 0px high, so the side rails take its height
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

// A side rail of the frame, at the start or the end of the screen. It spans the height between
// the frame bars, has its configured width, and scrolls on its own. On a screen narrower than the
// rail's collapse width, the rail is hidden, and a pull tab at that edge opens its extensions in a
// drawer. A rail without extensions renders nothing, and the main content takes its width.
function SideRail({ extensions, edge }: RegionProps) {
  const [ open, setOpen ] = useState(false);
  const theme = useTheme();
  const { width, collapseWidth } = railConfig(theme, edge);
  // The media query under which the rail is shown
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
      { /* A drawer's anchor is a physical side. MUI swaps left and right inside its RtlProvider.
           The chevrons above do not swap. */ }
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
  // The main content of the page
  children: ReactNode;
}

// The page shell. A frame of two bars and two side rails stays on the screen. Between the rails,
// the PageTop extensions, the main content and the PageBottom extensions scroll together. A frame
// region collapses into a pull tab on a screen too narrow for a rail or too short for a bar. The
// theme's `iapShell` sets each region's threshold.
//
// The shell's styles place things with logical properties, such as `inset-inline-start`, rather
// than left and right.
function PageLayout({ children }: PageLayoutProps) {
  const [ regions, setRegions ] = useState<Record<string, Extension[]>>({});
  const region = (point: string) => regions[point] ?? [];

  useEffect(() => {
    async function loadRegion(point: string) {
      try {
        const extensions = await loadExtensions(point);
        setRegions(loaded => ({ ...loaded, [point]: extensions }));
      } catch (err) {
        console.error(`Something went wrong loading the ${point} extensions`, err);
      }
    }
    SHELL_POINTS.forEach(point => void loadRegion(point));
  }, []);

  // The heights of the two frame bars, which the side rails span the screen between. The shell
  // gives them to the rails as CSS variables.
  const frameTopRef = useRef<HTMLDivElement>(null);
  const frameBottomRef = useRef<HTMLDivElement>(null);
  const [ frameInsets, setFrameInsets ] = useState({ top: 0, bottom: 0 });

  // Measures the bars again when the loaded extensions change, and whenever a bar changes size,
  // such as when it is hidden or its content wraps
  useEffect(() => {
    // Without a ResizeObserver, as in jsdom, the bars count as 0px high
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

  const shellVariables = {
    "--iap-frame-top": `${frameInsets.top}px`,
    "--iap-frame-bottom": `${frameInsets.bottom}px`,
    // The padding around the main content, 3 spacing units. What lines up with the content's
    // edges uses it too, such as the breadcrumb trail.
    "--iap-content-gutter": "24px",
  } as CSSProperties;

  return (
    <Box
      style={shellVariables}
      sx={{ display: "flex", flexDirection: "column", minBlockSize: "100dvh" }}
    >
      <FrameBar extensions={region("FrameTop")} edge="start" barRef={frameTopRef} />
      { /* The middle grows, so that the bottom bar stays at the bottom of a short page */ }
      <Box sx={{ display: "flex", flexGrow: 1 }}>
        <SideRail extensions={region("FrameStart")} edge="start" />
        { /* minInlineSize lets the middle shrink below the width of its content, rather than push
             the rails off the screen */ }
        <Box sx={{ display: "flex", flexDirection: "column", flexGrow: 1, minInlineSize: 0 }}>
          <ExtensionList extensions={region("PageTop")} />
          <Box component="main" sx={{ flexGrow: 1, p: "var(--iap-content-gutter)" }}>
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
