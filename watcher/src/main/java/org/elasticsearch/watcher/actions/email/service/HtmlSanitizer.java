/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.actions.email.service;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.owasp.html.CssSchema;
import org.owasp.html.ElementPolicy;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 *
 */
public class HtmlSanitizer {

    static final String[] FORMATTING_TAGS = new String[] {
            "b", "i", "s", "u", "o", "sup", "sub", "ins", "del", "strong",
            "strike", "tt", "code", "big", "small", "br", "span", "em"
    };
    static final String[] BLOCK_TAGS = new String[] {
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote"
    };
    static final String[] TABLE_TAGS = new String[] {
            "table", "hr", "tr", "td"
    };
    static final String[] DEFAULT_ALLOWED = new String[] {
            "body", "head", "_tables", "_links", "_blocks", "_formatting", "img:embedded"
    };

    private final boolean enabled;
    @SuppressForbidden( reason = "PolicyFactory uses guava Function")
    private final PolicyFactory policy;

    @Inject
    public HtmlSanitizer(Settings settings) {
        enabled = settings.getAsBoolean("watcher.actions.email.html.sanitization.enabled", true);
        String[] allow = settings.getAsArray("watcher.actions.email.html.sanitization.allow", DEFAULT_ALLOWED);
        String[] disallow = settings.getAsArray("watcher.actions.email.html.sanitization.disallow", Strings.EMPTY_ARRAY);
        policy = createCommonPolicy(allow, disallow);
    }

    public String sanitize(String html) {
        if (!enabled) {
            return html;
        }
        return policy.sanitize(html);
    }

    @SuppressForbidden( reason = "PolicyFactory uses guava Function")
    static PolicyFactory createCommonPolicy(String[] allow, String[] disallow) {
        HtmlPolicyBuilder policyBuilder = new HtmlPolicyBuilder();

        if (Arrays.binarySearch(allow, "_all") > -1) {
            return policyBuilder
                    .allowElements(TABLE_TAGS)
                    .allowElements(BLOCK_TAGS)
                    .allowElements(FORMATTING_TAGS)
                    .allowStyling(CssSchema.DEFAULT)
                    .allowStandardUrlProtocols().allowElements("a")
                    .allowAttributes("href").onElements("a").requireRelNofollowOnLinks()
                    .allowElements("img")
                    .allowAttributes("src").onElements("img")
                    .allowStandardUrlProtocols()
                    .allowUrlProtocols("cid")
                    .toFactory();
        }

        EnumSet<Images> images = EnumSet.noneOf(Images.class);

        for (String tag : allow) {
            tag = tag.toLowerCase(Locale.ROOT);
            switch (tag) {
                case "_tables":
                    policyBuilder.allowElements(TABLE_TAGS);
                    break;
                case "_links":
                    policyBuilder.allowElements("a")
                            .allowAttributes("href").onElements("a")
                            .allowStandardUrlProtocols()
                            .requireRelNofollowOnLinks();
                    break;
                case "_blocks":
                    policyBuilder.allowElements(BLOCK_TAGS);
                    break;
                case "_formatting":
                    policyBuilder.allowElements(FORMATTING_TAGS);
                    break;
                case "_styles":
                    policyBuilder.allowStyling(CssSchema.DEFAULT);
                    break;
                case "img:all":
                case "img":
                    images.add(Images.ALL);
                    break;
                case "img:embedded":
                    images.add(Images.EMBEDDED);
                    break;
                default:
                    policyBuilder.allowElements(tag);
            }
        }
        for (String tag : disallow) {
            tag = tag.toLowerCase(Locale.ROOT);
            switch (tag) {
                case "_tables":
                    policyBuilder.disallowElements(TABLE_TAGS);
                    break;
                case "_links":
                    policyBuilder.disallowElements("a");
                    break;
                case "_blocks":
                    policyBuilder.disallowElements(BLOCK_TAGS);
                    break;
                case "_formatting":
                    policyBuilder.disallowElements(FORMATTING_TAGS);
                    break;
                case "_styles":
                    policyBuilder.disallowAttributes("style");
                    break;
                case "img:all":
                case "img":
                    images.remove(Images.ALL);
                    break;
                case "img:embedded":
                    images.remove(Images.EMBEDDED);
                    break;
                default:
                    policyBuilder.disallowElements(tag);
            }
        }

        if (!images.isEmpty()) {
            policyBuilder.allowAttributes("src").onElements("img").allowUrlProtocols("cid");
            if (images.contains(Images.ALL)) {
                policyBuilder.allowElements("img");
                policyBuilder.allowStandardUrlProtocols();
            } else {
                // embedded
                policyBuilder.allowElements(EmbeddedImgOnlyPolicy.INSTANCE, "img");
            }
        }

        return policyBuilder.toFactory();
    }



    /**
     * An {@code img} tag policy that only accept {@code cid:} values in its {@code src} attribute.
     * If such value is found, the content id is verified against the available attachements of the
     * email and if the content/attachment is not found, the element is dropped.
     */
    private static class EmbeddedImgOnlyPolicy implements ElementPolicy {

        private static EmbeddedImgOnlyPolicy INSTANCE = new EmbeddedImgOnlyPolicy();

        @Override
        public String apply(String elementName, List<String> attrs) {
            if (!"img".equals(elementName) || attrs.size() == 0) {
                return elementName;
            }
            String attrName = null;
            for (String attr : attrs) {
                if (attrName == null) {
                    attrName = attr.toLowerCase(Locale.ROOT);
                    continue;
                }
                // reject external image source (only allow embedded ones)
                if ("src".equals(attrName) && !attr.startsWith("cid:")) {
                    return null;
                }
            }
            return elementName;
        }
    }

    enum Images {
        ALL,
        EMBEDDED
    }
}
