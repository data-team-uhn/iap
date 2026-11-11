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
package io.uhndata.iap.utils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the selectors of a request: a list of selectors, the depth they ask for, or the options they give one
 * processor. In a path, every dot separates one selector from the next. A selector whose value holds a dot is given
 * whole instead, through a {@code selector} query parameter or by code calling {@link #withSelectors}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class SelectorUtils
{
    /** The depth value that means "no limit": {@value}. */
    public static final int UNLIMITED_DEPTH = -1;

    /** The selector asking for unlimited depth. */
    private static final String INFINITY_SELECTOR = "infinity";

    /** A selector asking for a depth: a whole number, possibly negative, of at most 9 digits so that it fits an int. */
    private static final Pattern DEPTH_SELECTOR = Pattern.compile("-?\\d{1,9}");

    /**
     * The selectors given outside the path, by the current request or by code, for as long as they apply.
     *
     * <p>
     * A thread-local, because the code that reads selectors works from a resource and not from the request. Sling
     * locks a resource's metadata once it is resolved. A {@code ResourceWrapper} carrying other metadata does not
     * help either, since {@code adaptTo} on it adapts the resource it wraps.
     * </p>
     */
    private static final ThreadLocal<List<String>> EXPLICIT_SELECTORS = new ThreadLocal<>();

    private SelectorUtils()
    {
    }

    /**
     * Splits the path info into selectors, after URL-decoding it. The extension is split off too, as the last of
     * them: {@code .deep.json} gives {@code deep} and {@code json}.
     *
     * <p>
     * The selectors given outside the path, if any, follow them; see {@link #setRequestSelectors} and
     * {@link #withSelectors}. A depth or an option given that way therefore overrides one in the path.
     * </p>
     *
     * @param resolutionPathInfo the part of the request path after the resource's path, such as
     *     {@code .deep.json}, possibly URL-encoded; {@code null} or blank if there is none
     * @return the selectors, without the dots between them; empty if there are none
     */
    @NotNull
    public static List<String> parseSelectors(@Nullable final String resolutionPathInfo)
    {
        final List<String> selectors = StringUtils.isBlank(resolutionPathInfo) ? new ArrayList<>()
            : Arrays.stream(URLDecoder.decode(resolutionPathInfo, StandardCharsets.UTF_8).split("\\."))
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.toCollection(ArrayList::new));
        selectors.addAll(explicitSelectors());
        return selectors;
    }

    /**
     * The depth the selectors ask for: how many levels of descendants to include. {@code 0} is the resource alone,
     * {@code 1} adds its children, and so on. {@code infinity}, in any case, or a negative number means
     * {@link #UNLIMITED_DEPTH unlimited}. Other selectors are ignored. Where several ask for a depth, the last one
     * wins: {@code .3.deep.2.1b.json} asks for {@code 2}.
     *
     * @param resolutionPathInfo the part of the request path after the resource's path, such as
     *     {@code .deep.2.json}, possibly URL-encoded; {@code null} or blank if there is none
     * @return the depth; empty if no selector asks for one
     */
    @NotNull
    public static OptionalInt parseDepth(@Nullable final String resolutionPathInfo)
    {
        return parseSelectors(resolutionPathInfo).stream()
            .filter(SelectorUtils::isDepthSelector)
            .reduce((first, last) -> last)
            .map(SelectorUtils::toDepth)
            .map(OptionalInt::of)
            .orElseGet(OptionalInt::empty);
    }

    /**
     * The options the selectors give one processor: {@code .dataFilter:status=SUBMITTED} gives {@code dataFilter} the
     * pair {@code (status, SUBMITTED)}. A key given several times appears once for each. The key ends at the first
     * {@code =}, and the value keeps everything after it.
     *
     * @param optionPrefix the name of the processor the options are for, such as {@code dataFilter}, with or without
     *     the colon after it; {@code null} or blank to read no options
     * @param resolutionPathInfo the part of the request path after the resource's path, possibly URL-encoded;
     *     {@code null} or blank if there is none
     * @return each option's key and value, in order; the value is empty for an option without {@code =}
     */
    @NotNull
    public static List<Pair<String, String>> parseOptions(@Nullable final String optionPrefix,
        @Nullable final String resolutionPathInfo)
    {
        if (StringUtils.isBlank(optionPrefix)) {
            return Collections.emptyList();
        }
        final String prefix = Strings.CS.appendIfMissing(optionPrefix, ":");
        return parseSelectors(resolutionPathInfo).stream()
            .filter(s -> Strings.CS.startsWith(s, prefix))
            .map(s -> StringUtils.substringAfter(s, prefix))
            .map(s -> Pair.of(StringUtils.substringBefore(s, "="), StringUtils.substringAfter(s, "=")))
            .collect(Collectors.toList());
    }

    /**
     * The options the selectors give one processor, as {@link #parseOptions} reads them. For a key given several
     * times, the last value wins.
     *
     * @param optionPrefix the name of the processor the options are for, such as {@code dataFilter}, with or without
     *     the colon after it; {@code null} or blank to read no options
     * @param resolutionPathInfo the part of the request path after the resource's path, possibly URL-encoded;
     *     {@code null} or blank if there is none
     * @return each option's key, mapped to its value
     */
    @NotNull
    public static Map<String, String> parseOptionsToMap(@Nullable final String optionPrefix,
        @Nullable final String resolutionPathInfo)
    {
        List<Pair<String, String>> allOptions = parseOptions(optionPrefix, resolutionPathInfo);
        Map<String, String> result = new HashMap<>();
        allOptions.stream().forEach(pair -> result.put(pair.getKey(), pair.getValue()));
        return result;
    }

    /**
     * Records the selectors the current request gives outside its path, such as in a query parameter. Every parse on
     * this thread includes them until they are {@link #clearRequestSelectors() cleared}.
     *
     * @param selectors the selectors, each one whole and with nothing escaped; blank ones are ignored, and
     *     {@code null} records none
     */
    public static void setRequestSelectors(@Nullable final List<String> selectors)
    {
        EXPLICIT_SELECTORS.set(selectors == null ? List.of()
            : selectors.stream().filter(StringUtils::isNotBlank).collect(Collectors.toList()));
    }

    /**
     * Runs code with the given selectors in place of those given outside the path, such as code serializing a
     * resource it resolved itself. Every parse on this thread includes them until the code returns. The request's own
     * {@code selector} parameters do not apply meanwhile, and apply again afterwards.
     *
     * @param <T> the type of what the code returns
     * @param selectors the selectors, each one whole and with nothing escaped; blank ones are ignored
     * @param code the code to run
     * @return what the code returns
     */
    public static <T> T withSelectors(@NotNull final List<String> selectors, @NotNull final Supplier<T> code)
    {
        final List<String> previous = EXPLICIT_SELECTORS.get();
        setRequestSelectors(selectors);
        try {
            return code.get();
        } finally {
            if (previous == null) {
                EXPLICIT_SELECTORS.remove();
            } else {
                EXPLICIT_SELECTORS.set(previous);
            }
        }
    }

    /**
     * Forgets the selectors recorded for this thread. Call it when the request ends, or the next request served on
     * the thread gets them.
     */
    public static void clearRequestSelectors()
    {
        EXPLICIT_SELECTORS.remove();
    }

    /**
     * The selectors recorded for this thread.
     *
     * @return the selectors; empty if none are recorded
     */
    @NotNull
    private static List<String> explicitSelectors()
    {
        final List<String> recorded = EXPLICIT_SELECTORS.get();
        return recorded == null ? List.of() : recorded;
    }

    private static boolean isDepthSelector(final String selector)
    {
        return INFINITY_SELECTOR.equalsIgnoreCase(selector) || DEPTH_SELECTOR.matcher(selector).matches();
    }

    private static int toDepth(final String selector)
    {
        return INFINITY_SELECTOR.equalsIgnoreCase(selector) ? UNLIMITED_DEPTH : Integer.parseInt(selector);
    }
}
