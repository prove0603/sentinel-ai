package com.zhuangjie.sentinel.scanner;

import com.zhuangjie.sentinel.pojo.dto.ScannedSql;
import com.zhuangjie.sentinel.pojo.enums.SqlSourceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Scans MyBatis Mapper XML files, extracts SQL statements and expands dynamic SQL.
 */
@Slf4j
@Component
public class MapperXmlScanner implements SqlScanner {

    private static final Set<String> SQL_TAGS = Set.of("select", "insert", "update", "delete");

    private final DynamicSqlExpander expander = new DynamicSqlExpander();

    @Override
    public List<ScannedSql> scan(Path projectRoot) {
        List<Path> xmlFiles = findMapperXmlFiles(projectRoot);
        log.info("Found {} candidate XML files under {}", xmlFiles.size(), projectRoot);
        return parseMapperXmlFiles(projectRoot, xmlFiles);
    }

    @Override
    public List<ScannedSql> scanFiles(Path projectRoot, List<Path> xmlFiles) {
        log.info("Scanning {} specific mapper XML files", xmlFiles.size());
        return parseMapperXmlFiles(projectRoot, xmlFiles);
    }

    private List<ScannedSql> parseMapperXmlFiles(Path projectRoot, List<Path> xmlFiles) {
        List<ScannedSql> results = new ArrayList<>();
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        disableDtdLoading(dbf);

        for (Path xmlFile : xmlFiles) {
            if (!Files.exists(xmlFile)) {
                log.debug("Skipping non-existent file: {}", xmlFile);
                continue;
            }
            try {
                DocumentBuilder builder = dbf.newDocumentBuilder();
                Document doc = builder.parse(xmlFile.toFile());
                Element root = doc.getDocumentElement();

                if (!"mapper".equals(root.getTagName())) {
                    continue;
                }

                String namespace = root.getAttribute("namespace");
                String relativePath = projectRoot.relativize(xmlFile).toString().replace('\\', '/');

                Map<String, Element> sqlFragments = collectSqlFragments(root);
                List<ScannedSql> scanned = extractSqlStatements(root, namespace, relativePath, sqlFragments);
                results.addAll(scanned);

                log.debug("Scanned {} SQL statements from {}", scanned.size(), relativePath);
            } catch (Exception e) {
                log.warn("Failed to parse XML file: {}", xmlFile, e);
            }
        }

        log.info("MapperXmlScanner extracted {} SQL statements total", results.size());
        return results;
    }

    private List<Path> findMapperXmlFiles(Path projectRoot) {
        List<Path> xmlFiles = new ArrayList<>();
        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (fileName.endsWith(".xml") && !isExcludedPath(file)) {
                        xmlFiles.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    if (".git".equals(dirName) || "node_modules".equals(dirName)
                            || "target".equals(dirName) || "build".equals(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.debug("Cannot access file: {}", file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.error("Failed to walk project directory: {}", projectRoot, e);
        }
        return xmlFiles;
    }

    private boolean isExcludedPath(Path file) {
        String path = file.toString().replace('\\', '/').toLowerCase();
        return path.contains("/test/") || path.contains("/pom.xml")
                || path.contains("/target/") || path.contains("/build/");
    }

    /**
     * Collects all &lt;sql id="..."&gt; fragments for &lt;include&gt; resolution.
     */
    private Map<String, Element> collectSqlFragments(Element root) {
        Map<String, Element> fragments = new HashMap<>();
        NodeList sqlNodes = root.getElementsByTagName("sql");
        for (int i = 0; i < sqlNodes.getLength(); i++) {
            Element sqlElem = (Element) sqlNodes.item(i);
            String id = sqlElem.getAttribute("id");
            if (id != null && !id.isEmpty()) {
                fragments.put(id, sqlElem);
            }
        }
        return fragments;
    }

    private List<ScannedSql> extractSqlStatements(Element root, String namespace,
                                                   String relativePath,
                                                   Map<String, Element> sqlFragments) {
        List<ScannedSql> results = new ArrayList<>();

        for (String tag : SQL_TAGS) {
            NodeList nodes = root.getElementsByTagName(tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                Element elem = (Element) nodes.item(i);
                String id = elem.getAttribute("id");
                String sourceLocation = namespace + "." + id;
                String sqlType = tag.toUpperCase();
                boolean hasDynamic = expander.hasDynamicTags(elem);

                List<String> expandedSqls = hasDynamic
                        ? expander.expandAll(elem, sqlFragments)
                        : List.of(expandStaticSql(elem, sqlFragments));

                for (String sql : expandedSqls) {
                    if (sql.isBlank()) {
                        continue;
                    }
                    String normalized = SqlNormalizer.normalize(sql);
                    results.add(new ScannedSql(
                            sql, normalized, sqlType,
                            SqlSourceType.MAPPER_XML,
                            relativePath, sourceLocation,
                            hasDynamic
                    ));
                }
            }
        }
        return results;
    }

    /**
     * Expands a static SQL element (no dynamic tags) by resolving &lt;include&gt; references.
     */
    private String expandStaticSql(Element elem, Map<String, Element> sqlFragments) {
        return expander.expand(elem, sqlFragments, DynamicSqlExpander.ExpandMode.ALL_TRUE);
    }

    private void disableDtdLoading(DocumentBuilderFactory dbf) {
        try {
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception e) {
            log.debug("Could not disable DTD loading features", e);
        }
    }
}
