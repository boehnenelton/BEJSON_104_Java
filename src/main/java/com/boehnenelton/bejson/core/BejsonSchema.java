/*
 * File Name:       BejsonSchema.java
 * Relative Path:   src/main/java/com/boehnenelton/bejson/core/BejsonSchema.java
 * Description:     Port of lib_bejson_Core_bejson_schema.py: authoritative schema constants (generated from the Python
 *                  module's values) plus schema extract / validate-against helpers.
 * VERSION:         1.0.0
 * Release_Version: 300
 * Author:          Elton Boehnen · boehnenelton2024@gmail.com · boehnenelton2024.pages.dev · github.com/boehnenelton
 * RELATIONAL_ID:   8f171233-6cbe-46b4-9975-0a8c10f44b77
 */
package com.boehnenelton.bejson.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

public final class BejsonSchema {
    private BejsonSchema() { }

    public static final List<Object> SCHEMA_LIST_MANAGER_v100 = Json.asList(Json.loads("[{\"name\": \"id\", \"type\": \"string\"}, {\"name\": \"parent_id\", \"type\": \"string\"}, {\"name\": \"title\", \"type\": \"string\"}, {\"name\": \"description\", \"type\": \"string\"}]"));
    public static final List<Object> SCHEMA_MFDB_ENTITY_v5 = Json.asList(Json.loads("[{\"name\": \"version\", \"type\": \"string\"}, {\"name\": \"file_path\", \"type\": \"string\"}, {\"name\": \"file_name\", \"type\": \"string\"}, {\"name\": \"content\", \"type\": \"string\"}, {\"name\": \"is_binary\", \"type\": \"boolean\"}, {\"name\": \"is_base64\", \"type\": \"boolean\"}]"));
    public static final List<Object> SCHEMA_MFDB_MANIFEST_v5 = Json.asList(Json.loads("[{\"name\": \"entity_name\", \"type\": \"string\"}, {\"name\": \"file_path\", \"type\": \"string\"}, {\"name\": \"description\", \"type\": \"string\"}, {\"name\": \"record_count\", \"type\": \"integer\"}, {\"name\": \"schema_version\", \"type\": \"string\"}, {\"name\": \"primary_key\", \"type\": \"string\"}, {\"name\": \"changelog\", \"type\": \"string\"}, {\"name\": \"chunked_at\", \"type\": \"string\"}, {\"name\": \"tags\", \"type\": \"string\"}]"));
    public static final Map<String, Object> SCHEMA_MODEL_REGISTRY_GEMINI = Json.asMap(Json.loads("{\"Format\": \"BEJSON\", \"Format_Version\": \"104a\", \"Format_Creator\": \"Elton Boehnen\", \"Records_Type\": [\"AI_Model\"], \"Fields\": [{\"name\": \"display_name\", \"type\": \"string\"}, {\"name\": \"model_id\", \"type\": \"string\"}, {\"name\": \"currently_active\", \"type\": \"boolean\"}, {\"name\": \"thinking_enabled\", \"type\": \"boolean\"}, {\"name\": \"flag\", \"type\": \"string\"}, {\"name\": \"service\", \"type\": \"string\"}], \"Values\": [[\"Gemini 3.6 Flash\", \"gemini-3.6-flash\", true, true, \"\", \"\"], [\"Gemini 3.1 Pro (Preview)\", \"gemini-3.1-pro-preview\", false, true, \"\", \"\"], [\"Gemini 3.1 Flash-Lite\", \"gemini-3.1-flash-lite\", false, false, \"\", \"\"], [\"Gemini 2.5 Flash\", \"gemini-2.5-flash\", false, false, \"\", \"\"]]}"));
    public static final List<Object> SCHEMA_PROJECT_v140 = Json.asList(Json.loads("[{\"name\": \"record_type_parent\", \"type\": \"string\"}, {\"name\": \"project_id\", \"type\": \"string\"}, {\"name\": \"project_name\", \"type\": \"string\"}, {\"name\": \"project_path\", \"type\": \"string\"}, {\"name\": \"version\", \"type\": \"string\"}, {\"name\": \"created_at\", \"type\": \"string\"}, {\"name\": \"project_type\", \"type\": \"string\"}, {\"name\": \"is_active\", \"type\": \"boolean\"}, {\"name\": \"is_visible\", \"type\": \"boolean\"}, {\"name\": \"is_missing\", \"type\": \"boolean\"}, {\"name\": \"description\", \"type\": \"string\"}, {\"name\": \"tags\", \"type\": \"string\"}, {\"name\": \"primary_agent\", \"type\": \"string\"}, {\"name\": \"last_sync\", \"type\": \"string\"}, {\"name\": \"file_count\", \"type\": \"integer\"}, {\"name\": \"total_size_kb\", \"type\": \"number\"}, {\"name\": \"git_enabled\", \"type\": \"boolean\"}, {\"name\": \"priority\", \"type\": \"integer\"}, {\"name\": \"category\", \"type\": \"string\"}, {\"name\": \"internal_notes\", \"type\": \"string\"}, {\"name\": \"is_archived\", \"type\": \"boolean\"}, {\"name\": \"is_reset_protected\", \"type\": \"boolean\"}]"));

    /** Copy of a document's schema: all header keys and Fields, with Values emptied (shallow copy like dict.copy()). */
    public static Map<String, Object> extract(Map<String, Object> doc) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>(doc);
        schema.put("Values", new ArrayList<Object>());
        return schema;
    }

    /** True when the document's Fields match schemaFields in count, names and types (order-sensitive). */
    public static boolean validateAgainst(Map<String, Object> doc, List<Object> schemaFields) {
        List<Object> docFields = doc.containsKey("Fields") ? Json.asList(doc.get("Fields")) : new ArrayList<Object>();
        if (docFields == null || docFields.size() != schemaFields.size()) return false;
        for (int i = 0; i < docFields.size(); i++) {
            Map<String, Object> df = Json.asMap(docFields.get(i));
            Map<String, Object> sf = Json.asMap(schemaFields.get(i));
            if (!Json.pyEquals(df.get("name"), sf.get("name")) || !Json.pyEquals(df.get("type"), sf.get("type"))) return false;
        }
        return true;
    }
}
