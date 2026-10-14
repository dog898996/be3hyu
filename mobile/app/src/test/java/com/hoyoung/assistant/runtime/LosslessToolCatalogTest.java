package com.hoyoung.assistant.runtime;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.hoyoung.assistant.phone.PhoneTools;
import com.hoyoung.assistant.store.InternalFiles;
import com.hoyoung.assistant.store.HoyoungStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Iterator;

import static org.junit.Assert.*;

/** Whole-catalog and unsupported-schema regression tests; no backend model is invoked. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public final class LosslessToolCatalogTest {
    @Test public void wholeExpandedProductionCatalogKeepsEveryDefinitionAndSavesCharactersIncludingHeader() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        JSONArray catalog = productionCatalog(context);
        assertTrue("A complete actual catalog is required", catalog.length() > 0);
        String before = catalog.toString();

        LosslessToolCatalog.Result result = LosslessToolCatalog.formatResult(catalog);
        assertTrue(result.usedCompact);
        assertTrue(result.roundTripVerified);
        assertEquals(before.length(), result.baselineCharacters);
        assertEquals(result.text.length(), result.packedCharacters);
        assertTrue("Count the full interpretation header, not just the packed rows",
                result.packedCharacters < result.baselineCharacters * 0.80);
        JSONArray restored = LosslessToolCatalog.decode(rowsFrom(result.text));
        assertSameJson(catalog, restored);
        for(JSONArray nativeCatalog:new JSONArray[]{new PhoneTools(context).catalog(),new InternalFiles(context).catalog()})
            for(int i=0;i<nativeCatalog.length();i++){JSONObject definition=nativeCatalog.getJSONObject(i);assertSameJson(definition,named(restored,definition.getString("name")));}
        assertEquals(before, catalog.toString());
        assertNotNull(named(restored,"phone_voice_record_read"));assertNotNull(named(restored,"phone_call_place"));
        for(String name:new String[]{"accounts.list","accounts.get","accounts.save","accounts.history","accounts.delete"})assertNotNull(named(restored,name));
        assertEquals("Relative id from files.list; not a host path.", named(restored,"files.read")
                .getJSONObject("inputSchema").getJSONObject("properties")
                .getJSONObject("fileId").getString("description"));
        System.out.println("LosslessToolCatalog full catalog: tools=" + catalog.length() + " baseline_chars="
                + result.baselineCharacters + " packed_chars=" + result.packedCharacters
                + " framing_chars=" + LosslessToolCatalog.HEADER.length());
    }

    @Test public void primitiveTypesAreShortenedButDescriptionsAndNestedConstraintsStayExact() throws Exception {
        JSONObject complex = new JSONObject().put("type", "array").put("description", "원문 그대로: \"a\\b\"\n💙")
                .put("items", new JSONObject().put("type", "object")
                        .put("properties", new JSONObject().put("nested", new JSONObject()
                                .put("type", new JSONArray().put("string").put("null"))
                                .put("enum", new JSONArray().put("true").put(true).put(JSONObject.NULL))))
                        .put("required", new JSONArray().put("nested")).put("additionalProperties", true))
                .put("minItems", 2).put("maxItems", 8).put("uniqueItems", true)
                .put("examples", new JSONArray().put(new JSONArray().put("정정하지 말 것")))
                .put("x-owner-extension", new JSONObject().put("exactQuote", "Aa"));
        JSONObject properties = new JSONObject().put("plain", new JSONObject().put("type", "string"))
                .put("described", new JSONObject().put("type", "integer").put("description", "0–24,000"))
                .put("complex", complex).put("unknown", new JSONObject().put("type", "future-kind"));
        JSONArray input = new JSONArray().put(nativeTool("deep", "Full constraints", properties,
                new JSONArray().put("complex").put("plain")));
        JSONArray encoded = LosslessToolCatalog.encode(input);
        assertEquals(1, encoded.getJSONArray(0).getInt(0));
        assertEquals("string", encoded.getJSONArray(0).getJSONObject(3).get("plain"));
        assertEquals("[\"integer\",\"0–24,000\"]", encoded.getJSONArray(0).getJSONObject(3).getJSONArray("described").toString());
        assertTrue(encoded.getJSONArray(0).getJSONObject(3).get("unknown") instanceof JSONObject);
        assertSameJson(complex, encoded.getJSONArray(0).getJSONObject(3).get("complex"));
        assertSameJson(input, LosslessToolCatalog.decode(new JSONArray(encoded.toString())));
    }

    @Test public void describedTypesSaveOnlyRepeatedKeysAndKeepUnicodeEscapesAndExtraFields() throws Exception {
        String description="정확히 \"a\\b\"\n💙\t 원문";
        JSONObject plain=new JSONObject().put("type","string").put("description",description);
        JSONObject extended=new JSONObject(plain.toString()).put("minLength",3);
        JSONObject nullable=new JSONObject().put("type","string").put("description",JSONObject.NULL);
        JSONObject future=new JSONObject().put("type","future-kind").put("description",description);
        JSONArray original=new JSONArray().put(nativeTool("described","exact",new JSONObject()
                .put("plain",plain).put("extended",extended).put("nullable",nullable).put("future",future),new JSONArray().put("plain")));
        JSONArray encoded=LosslessToolCatalog.encode(original);JSONObject properties=encoded.getJSONArray(0).getJSONObject(3);
        assertEquals(description,properties.getJSONArray("plain").getString(1));
        assertTrue(properties.get("extended") instanceof JSONObject);assertTrue(properties.get("nullable") instanceof JSONObject);assertTrue(properties.get("future") instanceof JSONObject);
        assertSameJson(original,LosslessToolCatalog.decode(new JSONArray(encoded.toString())));
    }

    @Test public void exactArgumentStringsAndUnicodeQuotesAreNeverSummarizedOrNormalized() throws Exception {
        String arguments = "query:string,quote?:'그대로'\n원문\r\n\t\\ \"💙\"; boolean≠\"boolean\"";
        JSONArray input = new JSONArray().put(new JSONObject().put("name", "원문.tool\"\\💙")
                .put("arguments", arguments));
        JSONArray encoded = LosslessToolCatalog.encode(input);
        assertEquals(0, encoded.getJSONArray(0).getInt(0));
        JSONObject restored = LosslessToolCatalog.decode(new JSONArray(encoded.toString())).getJSONObject(0);
        assertEquals(arguments, restored.getString("arguments"));
        assertArrayEquals(arguments.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                restored.getString("arguments").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertSameJson(input, new JSONArray().put(restored));
    }

    @Test public void orderedAndDuplicateToolsAndRequiredEntriesAreNotDeduplicatedOrSorted() throws Exception {
        JSONArray required = new JSONArray().put("z").put("a").put("z");
        JSONObject properties = new JSONObject().put("a", new JSONObject().put("type", "string"))
                .put("z", new JSONObject().put("type", "number"));
        JSONArray input = new JSONArray().put(nativeTool("same", "first", properties, required))
                .put(new JSONObject().put("name", "between").put("arguments", "{}"))
                .put(nativeTool("same", "second", properties, new JSONArray()));
        JSONArray restored = LosslessToolCatalog.decode(LosslessToolCatalog.encode(input));
        assertSameJson(input, restored);
        assertEquals("first", restored.getJSONObject(0).getString("description"));
        assertEquals("between", restored.getJSONObject(1).getString("name"));
        assertEquals("second", restored.getJSONObject(2).getString("description"));
        assertEquals("[\"z\",\"a\",\"z\"]", restored.getJSONObject(0)
                .getJSONObject("inputSchema").getJSONArray("required").toString());
    }

    @Test public void requiredAbsentAndEmptyAndAdditionalPropertiesValueTypesStayDistinct() throws Exception {
        JSONObject empty = nativeTool("empty", "", new JSONObject(), new JSONArray());
        JSONObject absent = new JSONObject(empty.toString());
        absent.getJSONObject("inputSchema").remove("required");
        JSONObject stringFalse = new JSONObject(empty.toString());
        stringFalse.getJSONObject("inputSchema").put("additionalProperties", "false");
        JSONObject trueValue = new JSONObject(empty.toString());
        trueValue.getJSONObject("inputSchema").put("additionalProperties", true);
        JSONObject absentExtra = new JSONObject(empty.toString());
        absentExtra.getJSONObject("inputSchema").remove("additionalProperties");
        JSONArray input = new JSONArray().put(empty).put(absent).put(stringFalse).put(trueValue).put(absentExtra);
        JSONArray encoded = LosslessToolCatalog.encode(input);
        assertEquals(1, encoded.getJSONArray(0).getInt(0));
        for (int i = 1; i < input.length(); i++) assertEquals(2, encoded.getJSONArray(i).getInt(0));
        JSONArray restored = LosslessToolCatalog.decode(encoded);
        assertSameJson(input, restored);
        assertFalse(restored.getJSONObject(1).getJSONObject("inputSchema").has("required"));
        assertEquals("false", restored.getJSONObject(2).getJSONObject("inputSchema").get("additionalProperties"));
        assertEquals(Boolean.TRUE, restored.getJSONObject(3).getJSONObject("inputSchema").get("additionalProperties"));
    }

    @Test public void unknownExtraFieldsAndBooleanPropertySchemasUseFullFallbackRows() throws Exception {
        JSONObject rootExtra = nativeTool("extra", "", new JSONObject(), new JSONArray()).put("strict", true);
        JSONObject schemaExtra = nativeTool("schema", "", new JSONObject(), new JSONArray());
        schemaExtra.getJSONObject("inputSchema").put("$schema", "future://exact");
        JSONObject argumentExtra = new JSONObject().put("name", "args").put("arguments", "{}")
                .put("description", "Preserve this too");
        JSONObject booleanProperty = nativeTool("boolean", "", new JSONObject().put("any", true), new JSONArray());
        JSONObject nullProperty = nativeTool("null", "", new JSONObject().put("any", JSONObject.NULL), new JSONArray());
        JSONObject literalProperty = nativeTool("literal", "", new JSONObject().put("any", "string"), new JSONArray());
        JSONArray input = new JSONArray().put(rootExtra).put(schemaExtra).put(argumentExtra)
                .put(booleanProperty).put(nullProperty).put(literalProperty);
        JSONArray encoded = LosslessToolCatalog.encode(input);
        for (int i = 0; i < input.length(); i++) assertEquals(2, encoded.getJSONArray(i).getInt(0));
        assertSameJson(input, LosslessToolCatalog.decode(encoded));
    }

    @Test public void invalidCatalogItemsArePreservedIncludingNullStringsNumbersAndArrays() throws Exception {
        JSONArray input = new JSONArray().put(JSONObject.NULL).put("not a definition").put(17)
                .put(false).put(new JSONArray().put("nested").put(JSONObject.NULL))
                .put(new JSONObject().put("name", false).put("arguments", "{}"));
        JSONArray encoded = LosslessToolCatalog.encode(input);
        for (int i = 0; i < input.length(); i++) assertEquals(2, encoded.getJSONArray(i).getInt(0));
        assertSameJson(input, LosslessToolCatalog.decode(new JSONArray(encoded.toString())));
        LosslessToolCatalog.Result result = LosslessToolCatalog.formatResult(input);
        assertFalse(result.usedCompact);
        assertEquals(input.toString(), result.text);
    }

    @Test public void framingOverheadPreventsPackingEvenWhenRawRowsAloneAreSmaller() throws Exception {
        JSONArray input = new JSONArray().put(nativeTool("one", "", new JSONObject(), new JSONArray()));
        assertTrue(LosslessToolCatalog.encode(input).toString().length() < input.toString().length());
        LosslessToolCatalog.Result result = LosslessToolCatalog.formatResult(input);
        assertTrue(result.roundTripVerified);
        assertTrue(result.packedCharacters > result.baselineCharacters);
        assertFalse(result.usedCompact);
        assertEquals(input.toString(), LosslessToolCatalog.format(input));
        assertEquals("[]", LosslessToolCatalog.format(new JSONArray()));
    }

    @Test public void encodeAndDecodeDoNotShareMutableNestedObjectsWithTheInput() throws Exception {
        JSONObject property = new JSONObject().put("type", "string").put("description", "original");
        JSONObject nativeDefinition = nativeTool("nested", "", new JSONObject().put("a", property), new JSONArray().put("a"));
        JSONObject fallback = new JSONObject().put("name", "future").put("extra", new JSONObject().put("value", "original"));
        JSONArray input = new JSONArray().put(nativeDefinition).put(fallback);
        JSONArray encoded = LosslessToolCatalog.encode(input);
        JSONArray decoded = LosslessToolCatalog.decode(encoded);
        encoded.getJSONArray(0).getJSONObject(3).getJSONArray("a").put(1, "changed encoded");
        encoded.getJSONArray(1).getJSONObject(1).getJSONObject("extra").put("value", "changed encoded");
        decoded.getJSONObject(0).getJSONObject("inputSchema").getJSONArray("required").put("other");
        assertEquals("original", property.getString("description"));
        assertEquals("original", fallback.getJSONObject("extra").getString("value"));
        assertEquals("original", decoded.getJSONObject(0).getJSONObject("inputSchema")
                .getJSONObject("properties").getJSONObject("a").getString("description"));
        assertEquals("original", decoded.getJSONObject(1).getJSONObject("extra").getString("value"));
        assertEquals(1, nativeDefinition.getJSONObject("inputSchema").getJSONArray("required").length());
    }

    @Test public void exactSerializationCacheIsStableAndInvalidatedByAnyMutation() throws Exception {
        JSONArray input = new JSONArray();
        for (int i = 0; i < 20; i++) input.put(nativeTool("tool" + i, "Full original description",
                new JSONObject().put("value", new JSONObject().put("type", "string")), new JSONArray().put("value")));
        LosslessToolCatalog.Result first = LosslessToolCatalog.formatResult(input);
        assertSame(first, LosslessToolCatalog.formatResult(input));
        assertEquals(first.text, LosslessToolCatalog.format(new JSONArray(input.toString())));
        input.getJSONObject(3).put("description", "Corrected original description");
        LosslessToolCatalog.Result updated = LosslessToolCatalog.formatResult(input);
        assertNotSame(first, updated);
        assertFalse(first.text.equals(updated.text));
        assertEquals("Corrected original description", LosslessToolCatalog.decode(rowsFrom(updated.text))
                .getJSONObject(3).getString("description"));
    }

    @Test public void JavaHashCollisionCannotReuseAnotherCatalogAndFalseDoesNotBecomeAString() throws Exception {
        JSONObject first = new JSONObject().put("name", "Aa").put("arguments", "{}");
        JSONObject second = new JSONObject().put("name", "BB").put("arguments", "{}");
        assertEquals(first.toString().hashCode(), second.toString().hashCode());
        LosslessToolCatalog.Result one = LosslessToolCatalog.formatResult(new JSONArray().put(first));
        LosslessToolCatalog.Result two = LosslessToolCatalog.formatResult(new JSONArray().put(second));
        assertNotSame(one, two);
        assertEquals(new JSONArray().put(second).toString(), two.text);
        JSONObject unknown = new JSONObject().put("name", "valueTypes").put("boolean", false)
                .put("string", "false").put("integer", 0).put("null", JSONObject.NULL);
        assertSameJson(new JSONArray().put(unknown), LosslessToolCatalog.decode(
                LosslessToolCatalog.encode(new JSONArray().put(unknown))));
    }

    @Test public void malformedEncodedRowsAlwaysFailInsteadOfDroppingOrCoercingDefinitions() throws Exception {
        String[] malformed = {"[null]", "[[0,\"x\"]]", "[[0,\"x\",{}]]", "[[0,\"x\",\"{}\",\"extra\"]]",
                "[[\"0\",\"x\",\"{}\"]]", "[[0.5,\"x\",\"{}\"]]", "[[3,{}]]", "[[2]]", "[[2,{},{}]]",
                "[[1,\"x\",\"desc\",{},null]]", "[[1,\"x\",\"desc\",{\"a\":false},[]]]",
                "[[1,\"x\",\"desc\",{\"a\":\"future-kind\"},[]]]", "[[1,\"x\",true,{},[]]]",
                "[[1,\"x\",\"desc\",{\"a\":[\"string\"]},[]]]", "[[1,\"x\",\"desc\",{\"a\":[\"string\",17]},[]]]",
                "[[1,\"x\",\"desc\",{\"a\":[\"future-kind\",\"x\"]},[]]]", "[[1,\"x\",\"desc\",{\"a\":[\"string\",\"x\",\"extra\"]},[]]]"};
        for (String rows : malformed) {
            assertThrows(rows, IllegalArgumentException.class,
                    () -> LosslessToolCatalog.decode(new JSONArray(rows)));
        }
        assertThrows(IllegalArgumentException.class, () -> LosslessToolCatalog.encode(null));
        assertThrows(IllegalArgumentException.class, () -> LosslessToolCatalog.decode(null));
        assertThrows(IllegalArgumentException.class, () -> LosslessToolCatalog.format(null));
    }

    private static JSONArray rowsFrom(String text) throws Exception {
        assertTrue(text.startsWith(LosslessToolCatalog.HEADER));
        return new JSONArray(text.substring(LosslessToolCatalog.HEADER.length()));
    }

    private static JSONObject nativeTool(String name, String description, JSONObject properties, JSONArray required) throws Exception {
        return new JSONObject().put("name", name).put("description", description)
                .put("inputSchema", new JSONObject().put("type", "object").put("properties", properties)
                        .put("required", required).put("additionalProperties", false));
    }

    private static void append(JSONArray to, JSONArray from) throws Exception {
        for (int i = 0; i < from.length(); i++) to.put(from.get(i));
    }

    private static void assertSameJson(Object expected, Object actual) throws Exception {
        if (expected instanceof JSONObject) {
            assertTrue(actual instanceof JSONObject);
            JSONObject left = (JSONObject) expected, right = (JSONObject) actual;
            assertEquals(left.length(), right.length());
            Iterator<String> keys = left.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                assertTrue("Missing field " + key, right.has(key));
                assertSameJson(left.get(key), right.get(key));
            }
        } else if (expected instanceof JSONArray) {
            assertTrue(actual instanceof JSONArray);
            JSONArray left = (JSONArray) expected, right = (JSONArray) actual;
            assertEquals(left.length(), right.length());
            for (int i = 0; i < left.length(); i++) assertSameJson(left.get(i), right.get(i));
        } else {
            assertEquals(expected, actual);
        }
    }

    private static JSONArray productionCatalog(Context context) throws Exception {
        HoyoungStore store=new HoyoungStore(context);
        AssistantRuntime runtime=new AssistantRuntime(context,store,new InternalFiles(context),new PhoneTools(context,store),Runnable::run);
        try {
            java.lang.reflect.Method catalog=AssistantRuntime.class.getDeclaredMethod("catalog");catalog.setAccessible(true);
            return new JSONArray(((JSONArray)catalog.invoke(runtime)).toString());
        } finally {
            java.lang.reflect.Field budget=AssistantRuntime.class.getDeclaredField("usageBudget");budget.setAccessible(true);
            ((UsageBudgetLedger)budget.get(runtime)).close();store.close();
        }
    }

    private static JSONObject named(JSONArray catalog,String name) throws Exception {
        for(int i=0;i<catalog.length();i++){JSONObject item=catalog.getJSONObject(i);if(name.equals(item.optString("name")))return item;}
        return null;
    }
}
