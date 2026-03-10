package com.zhuangjie.sentinel.scanner;

import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Expands MyBatis dynamic SQL elements into concrete SQL execution paths.
 * <p>
 * Generates two expansion modes:
 * <ul>
 *   <li>ALL_TRUE — all &lt;if&gt; conditions are included (worst-case complex query)</li>
 *   <li>ALL_FALSE — all &lt;if&gt; conditions are excluded (worst-case missing conditions)</li>
 * </ul>
 */
@Slf4j
public class DynamicSqlExpander {

    private static final Pattern LEADING_AND_OR = Pattern.compile("(?i)^\\s*(AND|OR)\\s+");
    private static final Pattern TRAILING_COMMA = Pattern.compile(",\\s*$");

    public enum ExpandMode {
        ALL_TRUE, ALL_FALSE
    }

    /**
     * Expands a single SQL element in the given mode.
     */
    public String expand(Element sqlElement, Map<String, Element> sqlFragments, ExpandMode mode) {
        StringBuilder sb = new StringBuilder();
        expandChildren(sqlElement, sb, sqlFragments, mode);
        return SqlNormalizer.normalize(sb.toString());
    }

    /**
     * Expands into both ALL_TRUE and ALL_FALSE paths, returning distinct non-blank results.
     */
    public List<String> expandAll(Element sqlElement, Map<String, Element> sqlFragments) {
        List<String> results = new ArrayList<>();
        for (ExpandMode mode : ExpandMode.values()) {
            String expanded = expand(sqlElement, sqlFragments, mode);
            if (!expanded.isBlank() && !results.contains(expanded)) {
                results.add(expanded);
            }
        }
        return results;
    }

    /**
     * Checks whether the SQL element contains any dynamic SQL tags.
     */
    public boolean hasDynamicTags(Element element) {
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String tag = ((Element) child).getTagName();
                if (isDynamicTag(tag)) {
                    return true;
                }
                if (hasDynamicTags((Element) child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isDynamicTag(String tagName) {
        return switch (tagName) {
            case "if", "choose", "when", "otherwise", "where", "set", "trim", "foreach" -> true;
            default -> false;
        };
    }

    private void expandChildren(Node node, StringBuilder sb, Map<String, Element> fragments, ExpandMode mode) {
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(child.getTextContent());
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                Element elem = (Element) child;
                switch (elem.getTagName()) {
                    case "if" -> expandIf(elem, sb, fragments, mode);
                    case "choose" -> expandChoose(elem, sb, fragments, mode);
                    case "where" -> expandWhere(elem, sb, fragments, mode);
                    case "set" -> expandSet(elem, sb, fragments, mode);
                    case "trim" -> expandTrim(elem, sb, fragments, mode);
                    case "foreach" -> expandForeach(elem, sb, fragments, mode);
                    case "include" -> expandInclude(elem, sb, fragments, mode);
                    case "bind" -> { /* no SQL output */ }
                    default -> expandChildren(elem, sb, fragments, mode);
                }
            }
        }
    }

    private void expandIf(Element elem, StringBuilder sb, Map<String, Element> fragments, ExpandMode mode) {
        if (mode == ExpandMode.ALL_TRUE) {
            expandChildren(elem, sb, fragments, mode);
        }
    }

    private void expandChoose(Element elem, StringBuilder sb, Map<String, Element> fragments, ExpandMode mode) {
        NodeList children = elem.getChildNodes();
        Element firstWhen = null;
        Element otherwise = null;

        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                if ("when".equals(child.getTagName()) && firstWhen == null) {
                    firstWhen = child;
                } else if ("otherwise".equals(child.getTagName())) {
                    otherwise = child;
                }
            }
        }

        if (mode == ExpandMode.ALL_TRUE && firstWhen != null) {
            expandChildren(firstWhen, sb, fragments, mode);
        } else if (otherwise != null) {
            expandChildren(otherwise, sb, fragments, mode);
        } else if (firstWhen != null) {
            expandChildren(firstWhen, sb, fragments, mode);
        }
    }

    private void expandWhere(Element elem, StringBuilder sb, Map<String, Element> fragments, ExpandMode mode) {
        StringBuilder inner = new StringBuilder();
        expandChildren(elem, inner, fragments, mode);
        String content = inner.toString().trim();
        if (!content.isEmpty()) {
            content = LEADING_AND_OR.matcher(content).replaceFirst("");
            sb.append(" WHERE ").append(content);
        }
    }

    private void expandSet(Element elem, StringBuilder sb, Map<String, Element> fragments, ExpandMode mode) {
        StringBuilder inner = new StringBuilder();
        expandChildren(elem, inner, fragments, mode);
        String content = inner.toString().trim();
        if (!content.isEmpty()) {
            content = TRAILING_COMMA.matcher(content).replaceFirst("");
            sb.append(" SET ").append(content);
        }
    }

    private void expandTrim(Element elem, StringBuilder sb, Map<String, Element> fragments, ExpandMode mode) {
        StringBuilder inner = new StringBuilder();
        expandChildren(elem, inner, fragments, mode);
        String content = inner.toString().trim();
        if (content.isEmpty()) {
            return;
        }

        String prefixOverrides = elem.getAttribute("prefixOverrides");
        if (prefixOverrides != null && !prefixOverrides.isEmpty()) {
            for (String po : prefixOverrides.split("\\|")) {
                String trimmed = po.trim();
                if (!trimmed.isEmpty()) {
                    content = content.replaceFirst("(?i)^\\s*" + Pattern.quote(trimmed) + "\\s*", "");
                }
            }
        }

        String suffixOverrides = elem.getAttribute("suffixOverrides");
        if (suffixOverrides != null && !suffixOverrides.isEmpty()) {
            for (String so : suffixOverrides.split("\\|")) {
                String trimmed = so.trim();
                if (!trimmed.isEmpty()) {
                    content = content.replaceFirst("(?i)\\s*" + Pattern.quote(trimmed) + "\\s*$", "");
                }
            }
        }

        String prefix = elem.getAttribute("prefix");
        if (prefix != null && !prefix.isEmpty()) {
            sb.append(" ").append(prefix).append(" ");
        }
        sb.append(content);
        String suffix = elem.getAttribute("suffix");
        if (suffix != null && !suffix.isEmpty()) {
            sb.append(" ").append(suffix);
        }
    }

    private void expandForeach(Element elem, StringBuilder sb, Map<String, Element> fragments, ExpandMode mode) {
        String open = elem.getAttribute("open");
        String close = elem.getAttribute("close");
        String separator = elem.getAttribute("separator");

        StringBuilder item = new StringBuilder();
        expandChildren(elem, item, fragments, mode);
        String itemStr = item.toString().trim();

        if (open != null && !open.isEmpty()) {
            sb.append(open);
        }

        sb.append(itemStr);
        if (separator != null && !separator.isEmpty()) {
            sb.append(separator).append(" ").append(itemStr);
            sb.append(separator).append(" ").append(itemStr);
        }

        if (close != null && !close.isEmpty()) {
            sb.append(close);
        }
    }

    private void expandInclude(Element elem, StringBuilder sb, Map<String, Element> fragments, ExpandMode mode) {
        String refid = elem.getAttribute("refid");
        if (refid != null && fragments.containsKey(refid)) {
            expandChildren(fragments.get(refid), sb, fragments, mode);
        }
    }
}
