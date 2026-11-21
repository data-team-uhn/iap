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

import { useCallback, useEffect, useState } from "react";

import ForumOutlined from "@mui/icons-material/ForumOutlined";
import { Badge, Box, Typography } from "@mui/material";

import { useAuthenticatedFetch } from "@iap/frontend-commons/reLogin";

import DiscussionPanel from "./DiscussionPanel";
import { PersonName } from "./people";
import { countUnread, flatten, getMessages, type Message } from "./reviewModel";
import { markSeen, seenAt } from "./seen";

// PRETOTYPE. Where the reviewers' conversation on a submission stands: how much is new, and the latest
// message, opening the whole thread beside the queue.
export default function DiscussionCell({ path, me, title }: { path: string; me: string; title: string }) {
  const doFetch = useAuthenticatedFetch();
  const [ messages, setMessages ] = useState<Message[]>([]);
  const [ open, setOpen ] = useState(false);
  const [ seen, setSeen ] = useState(() => seenAt(me, path));

  const load = useCallback(() => doFetch(`${path}/discussion.2.json`)
    .then(response => response.ok ? response.json() as Promise<Record<string, unknown>> : undefined)
    .then(discussion => setMessages(getMessages(discussion))), [ doFetch, path ]);
  useEffect(() => {
    void load();
  }, [ load ]);

  const latest = flatten(messages).at(-1);
  const unread = countUnread(messages, me, seen);
  const openThread = () => {
    setOpen(true);
    setSeen(markSeen(me, path));
  };

  return (
    <>
      <Box
        role="button" tabIndex={0}
        aria-label={latest ? `Discussion, ${unread} new` : "Start a discussion"}
        onClick={event => {
          event.stopPropagation();
          openThread();
        }}
        onKeyDown={event => {
          if (event.key === "Enter") {
            event.stopPropagation();
            openThread();
          }
        }}
        sx={{ display: "flex", alignItems: "center", gap: 1.5, width: "100%", py: 0.5, cursor: "pointer" }}
      >
        <Badge badgeContent={unread} color="primary">
          <ForumOutlined fontSize="small" sx={{ color: latest ? "text.secondary" : "text.disabled" }} />
        </Badge>
        <Typography
          variant="body2"
          sx={{ color: latest ? "text.primary" : "text.disabled", fontWeight: unread ? "fontWeightBold" : undefined,
            minWidth: 0, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
        >
          {latest ? <><PersonName user={latest.author} />: {latest.text}</> : "Start a discussion"}
        </Typography>
      </Box>
      <DiscussionPanel
        open={open} path={path} title={title} messages={messages}
        onChanged={load}
        onClose={() => {
          setOpen(false);
          setSeen(markSeen(me, path));
        }}
      />
    </>
  );
}
