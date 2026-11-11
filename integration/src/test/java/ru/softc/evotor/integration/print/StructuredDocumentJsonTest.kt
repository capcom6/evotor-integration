package ru.softc.evotor.integration.print

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

/**
 * Round-trip и правила кодеков структурированного печатного документа.
 *
 * Кодек - единственная точка сериализации контракта: ручная сборка JSON в приложениях
 * запрещена, поэтому все правила формата проверяются здесь.
 */
class StructuredDocumentJsonTest {

    private fun samplePosition(): StructuredPosition = StructuredPosition(
        name = "Espresso",
        quantity = BigDecimal("2.500"),
        measurePrecision = 3,
        productUuid = "product-1",
        subPositions = listOf(
            StructuredPosition(
                name = "Syrup",
                quantity = BigDecimal("1.50"),
                measurePrecision = 2,
                productUuid = "product-sub",
                subPositions = emptyList(),
                customFields = emptyList()
            )
        ),
        customFields = listOf(PrintCustomField("weight", "Вес", "250"))
    )

    private fun sampleDocument(): StructuredDocument = StructuredDocument(
        header = linkedMapOf("receiptNumber" to "42", "cashier" to "Ivanov"),
        positions = listOf(samplePosition()),
        customFields = listOf(PrintCustomField("total", "Итого", "1250.00"))
    )

    /**
     * Разбор только для проверок. В кодеке используется `JsonParser().parse()`,
     * а не `parseString`: статический метод есть с Gson 2.8.6, а версия Gson
     * у потребителя берётся из его графа и встречается 2.8.5 (POM библиотеки
     * секции dependencies не содержит). НЕ переносите `parseString` в кодек.
     */
    private fun parse(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    @Test
    fun roundTripKeepsAllFields() {
        val original = sampleDocument()

        val restored = StructuredDocumentJson.fromJson(StructuredDocumentJson.toJson(original))

        assertEquals(original, restored)
        assertEquals(2, restored.header.size)
        assertEquals("42", restored.header["receiptNumber"])
        assertEquals("Ivanov", restored.header["cashier"])
        assertEquals(1, restored.positions.size)
        assertEquals("Espresso", restored.positions[0].name)
        assertEquals(3, restored.positions[0].measurePrecision)
        assertEquals("product-1", restored.positions[0].productUuid)
        assertEquals(1, restored.customFields.size)
        assertEquals("total", restored.customFields[0].key)
        assertEquals("Итого", restored.customFields[0].title)
        assertEquals("1250.00", restored.customFields[0].value)
    }

    @Test
    fun quantityIsWrittenAsStringAndKeepsScale() {
        val json = StructuredDocumentJson.toJson(sampleDocument())

        val quantity = parse(json).getAsJsonArray("positions")[0].asJsonObject.get("quantity")
        assertTrue("quantity must be a JSON string", quantity.asJsonPrimitive.isString)
        assertEquals("2.500", quantity.asString)

        val restored = StructuredDocumentJson.fromJson(json)
        assertEquals(3, restored.positions[0].quantity.scale())
        assertEquals("2.500", restored.positions[0].quantity.toString())
    }

    @Test
    fun readerAcceptsQuantityAsStringAndAsNumber() {
        val asString = """{"positions":[{"name":"Espresso","quantity":"2.500"}]}"""
        val asNumber = """{"positions":[{"name":"Espresso","quantity":2.500}]}"""

        val fromString = StructuredDocumentJson.fromJson(asString).positions[0].quantity
        val fromNumber = StructuredDocumentJson.fromJson(asNumber).positions[0].quantity

        assertEquals(3, fromString.scale())
        assertEquals("2.500", fromString.toString())
        assertEquals(3, fromNumber.scale())
        assertEquals("2.500", fromNumber.toString())
    }

    @Test
    fun unknownKeysOfDocumentAndPositionAreIgnored() {
        val json = """
            {
              "protocolVersion": 1,
              "unknownDocumentKey": {"nested": 1},
              "positions": [
                {"name": "Espresso", "quantity": "1.000", "unknownPositionKey": "kept"}
              ]
            }
        """.trimIndent()

        val document = StructuredDocumentJson.fromJson(json)

        assertEquals(1, document.positions.size)
        assertEquals("Espresso", document.positions[0].name)
        assertEquals(BigDecimal("1.000"), document.positions[0].quantity)
    }

    @Test
    fun missingOptionalFieldsFallBackToDefaults() {
        val document = StructuredDocumentJson.fromJson("""{"positions":[{"quantity":"1.000"}]}""")

        assertTrue(document.header.isEmpty())
        assertTrue(document.customFields.isEmpty())
        assertEquals(1, document.positions.size)
        assertEquals("", document.positions[0].name)
        // W1: отсутствующий measurePrecision берётся из scale количества,
        // а не равен 0. Здесь quantity "1.000" -> scale 3.
        assertEquals(3, document.positions[0].measurePrecision)
        assertNull(document.positions[0].productUuid)
        assertTrue(document.positions[0].subPositions.isEmpty())
        assertTrue(document.positions[0].customFields.isEmpty())
    }

    @Test
    fun nullProductUuidSurvivesRoundTrip() {
        val document = StructuredDocument(
            header = emptyMap(),
            positions = listOf(
                StructuredPosition(
                    name = "Espresso",
                    quantity = BigDecimal("2.500"),
                    measurePrecision = 3,
                    productUuid = null,
                    subPositions = emptyList(),
                    customFields = emptyList()
                )
            ),
            customFields = emptyList()
        )

        val restored = StructuredDocumentJson.fromJson(StructuredDocumentJson.toJson(document))

        assertNull(restored.positions[0].productUuid)
        assertEquals(document, restored)
    }

    @Test
    fun nestedSubPositionsSurviveRoundTrip() {
        val original = sampleDocument()

        val restored = StructuredDocumentJson.fromJson(StructuredDocumentJson.toJson(original))

        assertEquals(1, restored.positions[0].subPositions.size)
        assertEquals("Syrup", restored.positions[0].subPositions[0].name)
        assertEquals(2, restored.positions[0].subPositions[0].quantity.scale())
        assertEquals(original, restored)
    }

    @Test
    fun positionCustomFieldsSurviveRoundTrip() {
        val original = sampleDocument()

        val restored = StructuredDocumentJson.fromJson(StructuredDocumentJson.toJson(original))

        assertEquals(1, restored.positions[0].customFields.size)
        assertEquals("weight", restored.positions[0].customFields[0].key)
        assertEquals("Вес", restored.positions[0].customFields[0].title)
        assertEquals("250", restored.positions[0].customFields[0].value)
        assertEquals(original, restored)
    }

    @Test
    fun splitByPositionsDividesPositionsBetweenPrinters() {
        val document = StructuredDocument(
            header = mapOf("receiptNumber" to "42"),
            positions = listOf(
                StructuredPosition("First", BigDecimal("1.000"), 0, "p1", emptyList(), emptyList()),
                StructuredPosition("Second", BigDecimal("2.000"), 0, "p2", emptyList(), emptyList())
            ),
            customFields = listOf(PrintCustomField("total", "Итого", "3.000"))
        )
        val json = StructuredDocumentJson.toJson(document)

        val split = StructuredDocumentJson.splitByPositions(json) { position ->
            when (position.productUuid) {
                "p1" -> listOf(10L)
                "p2" -> listOf(20L)
                else -> emptyList()
            }
        }

        assertEquals(setOf(10L, 20L), split.keys)
        val first = parse(split.getValue(10L))
        assertEquals(1, first.getAsJsonArray("positions").size())
        assertEquals("First", first.getAsJsonArray("positions")[0].asJsonObject.get("name").asString)
        val second = parse(split.getValue(20L))
        assertEquals(1, second.getAsJsonArray("positions").size())
        assertEquals("Second", second.getAsJsonArray("positions")[0].asJsonObject.get("name").asString)
    }

    @Test
    fun splitByPositionsKeepsEveryOtherFieldOfTheDocument() {
        val document = StructuredDocument(
            header = mapOf("receiptNumber" to "42"),
            positions = listOf(
                StructuredPosition("First", BigDecimal("1.000"), 3, "p1", emptyList(), emptyList())
            ),
            customFields = listOf(PrintCustomField("total", "Итого", "3.000"))
        )

        val copy = parse(StructuredDocumentJson.splitByPositions(StructuredDocumentJson.toJson(document)) { listOf(10L) }.getValue(10L))

        assertFalse(copy.has("protocolVersion"))
        assertEquals("42", copy.getAsJsonObject("header").get("receiptNumber").asString)
        assertEquals(1, copy.getAsJsonArray("customFields").size())
        assertEquals("total", copy.getAsJsonArray("customFields")[0].asJsonObject.get("key").asString)
        assertEquals(3, copy.getAsJsonArray("positions")[0].asJsonObject.get("measurePrecision").asInt)
    }

    @Test
    fun splitByPositionsKeepsUnknownKeysOfDocumentAndPositions() {
        val json = """
            {
              "protocolVersion": 1,
              "header": {"receiptNumber": "42"},
              "unknownDocumentKey": {"nested": 1},
              "positions": [
                {"name": "First", "quantity": "1.000", "unknownPositionKey": "kept"}
              ],
              "customFields": []
            }
        """.trimIndent()

        val copy = parse(StructuredDocumentJson.splitByPositions(json) { listOf(10L) }.getValue(10L))

        assertTrue(copy.has("unknownDocumentKey"))
        assertEquals(1, copy.getAsJsonObject("unknownDocumentKey").get("nested").asInt)
        assertEquals(
            "kept",
            copy.getAsJsonArray("positions")[0].asJsonObject.get("unknownPositionKey").asString
        )
    }

    @Test
    fun splitByPositionsSendsOnePositionToSeveralPrinters() {
        val json = StructuredDocumentJson.toJson(
            StructuredDocument(
                header = emptyMap(),
                positions = listOf(
                    StructuredPosition("First", BigDecimal("1.000"), 0, "p1", emptyList(), emptyList())
                ),
                customFields = emptyList()
            )
        )

        val split = StructuredDocumentJson.splitByPositions(json) { listOf(10L, 20L) }

        assertEquals(setOf(10L, 20L), split.keys)
        for (printerId in listOf(10L, 20L)) {
            val positions = parse(split.getValue(printerId)).getAsJsonArray("positions")
            assertEquals(1, positions.size())
            assertEquals("First", positions[0].asJsonObject.get("name").asString)
        }
    }

    @Test
    fun splitByPositionsDropsPositionWithEmptyDestination() {
        val json = StructuredDocumentJson.toJson(
            StructuredDocument(
                header = emptyMap(),
                positions = listOf(
                    StructuredPosition("First", BigDecimal("1.000"), 0, "p1", emptyList(), emptyList()),
                    StructuredPosition("Second", BigDecimal("2.000"), 0, "p2", emptyList(), emptyList())
                ),
                customFields = emptyList()
            )
        )

        val split = StructuredDocumentJson.splitByPositions(json) { position ->
            if (position.productUuid == "p1") listOf(10L) else emptyList()
        }

        assertEquals(setOf(10L), split.keys)
        val positions = parse(split.getValue(10L)).getAsJsonArray("positions")
        assertEquals(1, positions.size())
        assertEquals("First", positions[0].asJsonObject.get("name").asString)
    }

    @Test
    fun splitByPositionsReturnsEmptyMapForDocumentWithoutPositions() {
        val json = StructuredDocumentJson.toJson(
            StructuredDocument(mapOf("receiptNumber" to "42"), emptyList(), emptyList())
        )

        assertTrue(StructuredDocumentJson.splitByPositions(json) { listOf(10L) }.isEmpty())
    }

    @Test
    fun malformedJsonIsRejected() {
        try {
            StructuredDocumentJson.fromJson("{not a json")
            fail("Malformed JSON must be rejected")
        } catch (expected: IllegalArgumentException) {
            // ожидаемый отказ: документ нечитаем
        }
    }

    @Test
    fun rootThatIsNotAnObjectIsRejected() {
        try {
            StructuredDocumentJson.fromJson("[]")
            fail("Document root must be a JSON object")
        } catch (expected: IllegalArgumentException) {
            // ожидаемый отказ: корневой элемент не документ
        }
    }

    @Test
    fun unparsableQuantityIsRejected() {
        try {
            StructuredDocumentJson.fromJson("""{"positions":[{"name":"Espresso","quantity":"2.5x"}]}""")
            fail("Quantity must be a decimal number")
        } catch (expected: IllegalArgumentException) {
            // ожидаемый отказ: количество нечитаемо
        }
    }

    @Test
    fun positionThatIsNotAnObjectIsRejected() {
        try {
            StructuredDocumentJson.fromJson("""{"positions":["Espresso"]}""")
            fail("Position must be a JSON object")
        } catch (expected: IllegalArgumentException) {
            // ожидаемый отказ: позиция нечитаема
        }
    }

    @Test
    fun emptyDocumentRoundTrip() {
        val empty = StructuredDocument(emptyMap(), emptyList(), emptyList())

        val restored = StructuredDocumentJson.fromJson(StructuredDocumentJson.toJson(empty))

        assertEquals(empty, restored)
        assertTrue(restored.header.isEmpty())
        assertTrue(restored.positions.isEmpty())
        assertTrue(restored.customFields.isEmpty())
    }

    @Test
    fun highPrecisionQuantityIsNotTruncated() {
        val document = StructuredDocument(
            header = emptyMap(),
            positions = listOf(
                StructuredPosition(
                    "big",
                    BigDecimal("1234567890.123456789012345678"),
                    18,
                    null,
                    emptyList(),
                    emptyList()
                )
            ),
            customFields = emptyList()
        )

        val restored = StructuredDocumentJson.fromJson(StructuredDocumentJson.toJson(document))

        assertEquals(18, restored.positions[0].quantity.scale())
        assertEquals("1234567890.123456789012345678", restored.positions[0].quantity.toString())
    }

    @Test
    fun headerKeyOrderIsPreserved() {
        val document = StructuredDocument(
            header = linkedMapOf("first" to "1", "second" to "2", "third" to "3"),
            positions = emptyList(),
            customFields = emptyList()
        )

        val restored = StructuredDocumentJson.fromJson(StructuredDocumentJson.toJson(document))

        assertEquals(listOf("first", "second", "third"), restored.header.keys.toList())
    }

    @Test
    fun jsonContainsNoUnexpectedTopLevelKeys() {
        val root = parse(StructuredDocumentJson.toJson(sampleDocument()))

        assertEquals(
            setOf("header", "positions", "customFields"),
            root.keySet()
        )
        val position = root.getAsJsonArray("positions")[0].asJsonObject
        assertEquals(
            setOf("name", "quantity", "measurePrecision", "productUuid", "subPositions", "customFields"),
            position.keySet()
        )
        assertEquals(
            setOf("key", "title", "value"),
            root.getAsJsonArray("customFields")[0].asJsonObject.keySet()
        )
        assertFalse(root.has("opaquePayload"))
    }

    // ───────── D19: protocolVersion переехал из тела в конверт Intent ─────────

    @Test
    fun toJsonDoesNotContainProtocolVersion() {
        val root = parse(StructuredDocumentJson.toJson(sampleDocument()))

        assertFalse("protocolVersion must not be written into the body", root.has("protocolVersion"))
        assertEquals(setOf("header", "positions", "customFields"), root.keySet())
    }

    @Test
    fun fromJsonAcceptsBodyWithoutProtocolVersion() {
        val document = StructuredDocumentJson.fromJson(
            """{"header":{},"positions":[{"name":"Espresso","quantity":"1.000"}],"customFields":[]}"""
        )

        assertEquals(1, document.positions.size)
        assertEquals("Espresso", document.positions[0].name)
        assertEquals(BigDecimal("1.000"), document.positions[0].quantity)
    }

    @Test
    fun legacyBodyWithProtocolVersionKeyIsStillParsed() {
        // Тело старого продьюсера может ещё содержать ключ: он неизвестен кодеку,
        // игнорируется и не мешает разбору (forward-compatibility, D19).
        val document = StructuredDocumentJson.fromJson(
            """{"protocolVersion":7,"positions":[{"name":"Espresso","quantity":"1.000"}]}"""
        )

        assertEquals(1, document.positions.size)
        assertEquals("Espresso", document.positions[0].name)
    }

    // ───────── W1: отсутствующий measurePrecision берётся из scale количества ─────────

    private fun documentWith(positionJson: String): String =
        """{"header":{},"positions":[{$positionJson}],"customFields":[]}"""

    private fun positionWithoutPrecision(quantity: String = "\"2.500\""): String =
        "\"name\":\"Espresso\",\"quantity\":$quantity,\"productUuid\":null," +
            "\"subPositions\":[],\"customFields\":[]"

    @Test
    fun missingMeasurePrecisionFallsBackToQuantityScale() {
        val json = documentWith(positionWithoutPrecision(quantity = "\"2.500\""))

        val position = StructuredDocumentJson.fromJson(json).positions.single()

        assertEquals(BigDecimal("2.500"), position.quantity)
        assertEquals(3, position.measurePrecision)
    }

    @Test
    fun missingMeasurePrecisionUsesScaleOfTheGivenQuantity() {
        assertEquals(
            2,
            StructuredDocumentJson.fromJson(documentWith(positionWithoutPrecision("\"1.50\"")))
                .positions.single().measurePrecision
        )
        assertEquals(
            0,
            StructuredDocumentJson.fromJson(documentWith(positionWithoutPrecision("7")))
                .positions.single().measurePrecision
        )
    }

    @Test
    fun explicitZeroMeasurePrecisionIsNotOverriddenByScale() {
        // Явный 0 (штуки) обязан побеждать scale: иначе количество перестанет
        // печататься целым.
        val json = documentWith(positionWithoutPrecision() + ",\"measurePrecision\":0")

        val position = StructuredDocumentJson.fromJson(json).positions.single()

        assertEquals(0, position.measurePrecision)
        assertEquals(BigDecimal("2.500"), position.quantity)
    }

    @Test
    fun nullMeasurePrecisionIsTreatedAsAbsent() {
        val json = documentWith(positionWithoutPrecision() + ",\"measurePrecision\":null")

        assertEquals(3, StructuredDocumentJson.fromJson(json).positions.single().measurePrecision)
    }

    @Test
    fun bothQuantityAndMeasurePrecisionAbsentGiveZero() {
        val json = documentWith(
            "\"name\":\"Espresso\",\"productUuid\":null,\"subPositions\":[],\"customFields\":[]"
        )

        val position = StructuredDocumentJson.fromJson(json).positions.single()

        assertEquals(BigDecimal.ZERO, position.quantity)
        assertEquals(0, position.measurePrecision)
    }

    // ───────── W3: диапазон 0..18, fail-fast ─────────

    @Test
    fun measurePrecisionBoundsAreAccepted() {
        assertEquals(
            0,
            StructuredDocumentJson.fromJson(documentWith(positionWithoutPrecision() + ",\"measurePrecision\":0"))
                .positions.single().measurePrecision
        )
        assertEquals(
            18,
            StructuredDocumentJson.fromJson(documentWith(positionWithoutPrecision() + ",\"measurePrecision\":18"))
                .positions.single().measurePrecision
        )
    }

    @Test
    fun measurePrecisionOutsideBoundsIsRejected() {
        for (value in listOf("\"19\"", "\"100\"", "\"-1\"", "\"-999999999999\"")) {
            val json = documentWith(positionWithoutPrecision() + ",\"measurePrecision\":$value")
            try {
                StructuredDocumentJson.fromJson(json)
                fail("measurePrecision=$value должна была быть отклонена")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("measurePrecision"))
            }
        }
    }

    @Test
    fun measurePrecisionOverflowIsRejectedInsteadOfWrapping() {
        // JsonPrimitive.getAsInt молча переполняется: 999999999999 -> -727379969,
        // и отрицательная точность уже в рендере взрывает String.format.
        // Разбор обязан это отловить, а не пропустить как валидное -727379969.
        val json = documentWith(positionWithoutPrecision() + ",\"measurePrecision\":999999999999")

        try {
            StructuredDocumentJson.fromJson(json)
            fail("Переполнение measurePrecision должно быть отклонено")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("measurePrecision"))
        }
    }

    @Test
    fun nonIntegerMeasurePrecisionIsRejected() {
        val json = documentWith(positionWithoutPrecision() + ",\"measurePrecision\":true")

        try {
            StructuredDocumentJson.fromJson(json)
            fail("Не-целое measurePrecision должно быть отклонено")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("measurePrecision"))
        }
    }
}
