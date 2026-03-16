package com.txplugin.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonEscapeTest {

    // ── escape() — теперь package-private ─────────────────────────────────────

    @Test
    void escape_quotes() {
        assertEquals("He said \\\"hi\\\"", SocketReporter.escape("He said \"hi\""));
    }

    @Test
    void escape_backslash() {
        assertEquals("C:\\\\path", SocketReporter.escape("C:\\path"));
    }

    @Test
    void escape_newline() {
        assertEquals("line1\\nline2", SocketReporter.escape("line1\nline2"));
    }

    @Test
    void escape_carriageReturn() {
        assertEquals("a\\rb", SocketReporter.escape("a\rb"));
    }

    @Test
    void escape_tab() {
        assertEquals("a\\tb", SocketReporter.escape("a\tb"));
    }

    @Test
    void escape_controlChar_bell() {
        // \u0007 — BEL, control char < 0x20
        assertEquals("\\u0007", SocketReporter.escape("\u0007"));
    }

    @Test
    void escape_controlChar_null() {
        assertEquals("\\u0000", SocketReporter.escape("\u0000"));
    }

    @Test
    void escape_surrogatePair_emoji() {
        // U+1F600 GRINNING FACE — surrogate pair high=D83D, low=DE00
        String emoji = new String(Character.toChars(0x1F600));
        String escaped = SocketReporter.escape(emoji);
        // Оба суррогата должны быть заэскейплены в формате U+XXXX
        assertEquals("\\ud83d\\ude00", escaped);
    }

    @Test
    void escape_surrogatePair_cjkExtensionB() {
        // U+20000 — первый символ CJK Extension B, high=D840, low=DC00
        String cjk = new String(Character.toChars(0x20000));
        String escaped = SocketReporter.escape(cjk);
        assertEquals("\\ud840\\udc00", escaped);
    }

    @Test
    void escape_emptyString_returnsEmpty() {
        assertEquals("", SocketReporter.escape(""));
    }

    @Test
    void escape_null_returnsEmpty() {
        assertEquals("", SocketReporter.escape(null));
    }

    @Test
    void escape_pureAscii_unchanged() {
        assertEquals("hello world 123", SocketReporter.escape("hello world 123"));
    }

    @Test
    void escape_mixedContent() {
        String input = "Line1\nHe said \"hello\"\tEnd";
        String escaped = SocketReporter.escape(input);
        assertEquals("Line1\\nHe said \\\"hello\\\"\\tEnd", escaped);
    }

    // ── toJson() — через рефлексию ────────────────────────────────────────────

    private static String invokeToJson(TransactionRecord r) throws Exception {
        Method m = SocketReporter.class.getDeclaredMethod("toJson", TransactionRecord.class);
        m.setAccessible(true);
        return (String) m.invoke(null, r);
    }

    @Test
    void toJson_isValidJson_parsedByGson() throws Exception {
        TransactionRecord r = buildRecord();
        String json = invokeToJson(r);
        assertDoesNotThrow(() -> JsonParser.parseString(json),
            "toJson() должен генерировать валидный JSON");
    }

    @Test
    void toJson_doesNotContainNewlineInsideJson() throws Exception {
        TransactionRecord r = buildRecord();
        r.exceptionMessage = "line1\nline2"; // newline в поле
        String json = invokeToJson(r);
        // NDJSON: нет переносов строки внутри JSON-объекта
        assertFalse(json.contains("\n"), "JSON строка не должна содержать raw newlines (NDJSON)");
    }

    @Test
    void toJson_nullableFields_serializeAsJsonNull() throws Exception {
        TransactionRecord r = buildRecord();
        r.exceptionType = null;
        r.exceptionMessage = null;
        r.stackTrace = null;

        JsonObject obj = JsonParser.parseString(invokeToJson(r)).getAsJsonObject();

        assertTrue(obj.get("exceptionType").isJsonNull(), "exceptionType должен быть null");
        assertTrue(obj.get("exceptionMessage").isJsonNull(), "exceptionMessage должен быть null");
        assertTrue(obj.get("stackTrace").isJsonNull(), "stackTrace должен быть null");
    }

    @Test
    void toJson_nonNullableStrings_neverNull() throws Exception {
        TransactionRecord r = new TransactionRecord();
        // Все строковые поля — null по умолчанию, str() заменяет на ""
        JsonObject obj = JsonParser.parseString(invokeToJson(r)).getAsJsonObject();

        // str() использует "" для null — поле должно быть строкой, не null
        assertFalse(obj.get("className").isJsonNull());
        assertFalse(obj.get("methodName").isJsonNull());
        assertFalse(obj.get("status").isJsonNull());
    }

    @Test
    void toJson_sqlQueries_serializesAsJsonArray() throws Exception {
        TransactionRecord r = buildRecord();
        r.sqlQueries = new ArrayList<>(List.of("SELECT 1", "SELECT 2"));

        JsonObject obj = JsonParser.parseString(invokeToJson(r)).getAsJsonObject();
        JsonArray arr = obj.getAsJsonArray("sqlQueries");

        assertEquals(2, arr.size());
        assertEquals("SELECT 1", arr.get(0).getAsString());
        assertEquals("SELECT 2", arr.get(1).getAsString());
    }

    @Test
    void toJson_parameterTypes_serializesAsJsonString() throws Exception {
        TransactionRecord r = buildRecord();
        r.parameterTypes = "String,int,List";

        JsonObject obj = JsonParser.parseString(invokeToJson(r)).getAsJsonObject();
        // parameterTypes хранится как строка (comma-separated) и в агенте, и в плагине
        assertEquals("String,int,List", obj.get("parameterTypes").getAsString());
    }

    @Test
    void toJson_emptyParameterTypes_serializesAsEmptyString() throws Exception {
        TransactionRecord r = buildRecord();
        r.parameterTypes = "";

        JsonObject obj = JsonParser.parseString(invokeToJson(r)).getAsJsonObject();
        assertEquals("", obj.get("parameterTypes").getAsString());
    }

    @Test
    void toJson_roundtrip_allScalarFieldsPreserved() throws Exception {
        TransactionRecord r = buildRecord();
        r.transactionId = "test-uuid";
        r.className = "com.example.OrderService";
        r.methodName = "processOrder";
        r.parameterTypes = "String,int";
        r.startTimeMs = 1700000000000L;
        r.endTimeMs   = 1700000000342L;
        r.durationMs  = 342L;
        r.status = "COMMITTED";
        r.propagation = "REQUIRED";
        r.isolationLevel = "DEFAULT";
        r.readOnly = false;
        r.timeout = -1;
        r.sqlQueryCount = 2;
        r.batchCount = 0;
        r.threadName = "main";
        r.exceptionType = null;
        r.exceptionMessage = null;
        r.stackTrace = null;

        JsonObject obj = JsonParser.parseString(invokeToJson(r)).getAsJsonObject();

        assertEquals("test-uuid",                 obj.get("transactionId").getAsString());
        assertEquals("com.example.OrderService",  obj.get("className").getAsString());
        assertEquals("processOrder",              obj.get("methodName").getAsString());
        assertEquals(1700000000000L,              obj.get("startTimeMs").getAsLong());
        assertEquals(342L,                        obj.get("durationMs").getAsLong());
        assertEquals("COMMITTED",                 obj.get("status").getAsString());
        assertEquals("REQUIRED",                  obj.get("propagation").getAsString());
        assertFalse(obj.get("readOnly").getAsBoolean());
        assertEquals(-1,                          obj.get("timeout").getAsInt());
        assertEquals(2,                           obj.get("sqlQueryCount").getAsInt());
        assertEquals("main",                      obj.get("threadName").getAsString());
    }

    @Test
    void toJson_sqlQueries_withSpecialChars_escapedCorrectly() throws Exception {
        TransactionRecord r = buildRecord();
        r.sqlQueries = new ArrayList<>(List.of("SELECT \"name\" FROM `users`\nWHERE id = ?"));

        JsonObject obj = JsonParser.parseString(invokeToJson(r)).getAsJsonObject();
        String query = obj.getAsJsonArray("sqlQueries").get(0).getAsString();

        // Gson должен распарсить эскейпы обратно в исходную строку
        assertEquals("SELECT \"name\" FROM `users`\nWHERE id = ?", query);
    }

    // ── Вспомогательный метод ─────────────────────────────────────────────────

    private static TransactionRecord buildRecord() {
        TransactionRecord r = new TransactionRecord();
        r.transactionId  = "00000000-0000-0000-0000-000000000000";
        r.className      = "TestService";
        r.methodName     = "doWork";
        r.parameterTypes = "";
        r.startTimeMs    = 1000L;
        r.endTimeMs      = 1100L;
        r.durationMs     = 100L;
        r.status         = "COMMITTED";
        r.propagation    = "REQUIRED";
        r.isolationLevel = "DEFAULT";
        r.readOnly       = false;
        r.timeout        = -1;
        r.sqlQueryCount  = 0;
        r.batchCount     = 0;
        r.sqlQueries     = new ArrayList<>();
        r.threadName     = "test-thread";
        return r;
    }
}
