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

import { Link, Stack } from "@mui/material";

import { currentLocale } from "./messages";

interface LanguageSwitcherProps {
  languages: string[];
}

// The name of a language, written in that language.
//
// "Français", never "French": somebody who cannot read the current language cannot read the name of the
// one they are looking for either, which is the whole situation this control exists for. Falls back to the
// tag itself for a language the browser cannot name, which is still more use than a blank.
function nameOf(language: string): string {
  const named = new Intl.DisplayNames([ language ], { type: "language" }).of(language);
  return named ?? language;
}

// The current URL with the language changed, and everything else about it left alone.
function urlFor(language: string): string {
  const parameters = new URLSearchParams(window.location.search);
  parameters.set("locale", language);
  return `${window.location.pathname}?${parameters.toString()}`;
}

// Lets a reader pick the language of the page they are on.
//
// Ordinary links, not buttons, and deliberately: the server rendered this page's content-driven text into
// <meta> tags in whichever language was asked for, so changing language means asking the server again.
// A link says that honestly, survives being opened in a new tab or shared, and needs no script to work.
//
// Renders nothing for a single language — a control offering one choice is furniture, not a choice.
export default function LanguageSwitcher({ languages }: LanguageSwitcherProps) {
  const current = currentLocale();
  if (languages.length < 2) {
    return null;
  }
  return (
    <Stack direction="row" spacing={2} component="nav" aria-label="Language">
      {languages.map(language => (
        <Link
          key={language}
          href={urlFor(language)}
          underline="hover"
          // Marked on the element rather than by removing the link: a reader scanning for their own
          // language should find every one of them in the same place, including the one already chosen.
          aria-current={language === current ? "true" : undefined}
          sx={{ fontWeight: language === current ? "bold" : "normal" }}
        >
          {nameOf(language)}
        </Link>
      ))}
    </Stack>
  );
}
