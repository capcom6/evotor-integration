package ru.softc.evotor.integration.print

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.math.BigDecimal

/**
 * Кодек структурированного печатного документа: JSON <-> типизированная модель.
 *
 * ## Почему tree API, а не reflection (D11)
 *
 * Кодек работает с деревом Gson ([JsonObject], [JsonArray], [JsonPrimitive]) и
 * **не использует reflection**: нет вызовов `Gson().toJson(model)` и нет
 * `@SerializedName`. Reflection потребовала бы правил ProGuard на каждое поле
 * в каждом из 25 приложений-потребителей библиотеки; tree API даёт полный
 * контроль над записью значений и не создаёт такой зависимости.
 *
 * Приложениям запрещено собирать JSON вручную: у JSON нет compile-time схемы,
 * опечатка в имени ключа приводит к тихой потере поля. Единственная точка
 * сериализации - этот кодек.
 *
 * ## Правила формата
 *
 * Ключи документа: `protocolVersion`, `header`, `positions`, `customFields`.
 * Ключи позиции: `name`, `quantity`, `measurePrecision`, `productUuid`,
 * `subPositions`, `customFields`. Ключи произвольного поля: `key`, `title`,
 * `value`.
 *
 * - `quantity` записывается **строкой** ("2.500"): так scale [BigDecimal]
 *   сохраняется гарантированно. `measurePrecision` идёт отдельным целым числом.
 *   Читатель принимает и строку, и число: берётся строковое представление
 *   значения и оно парсится в [BigDecimal].
 * - `protocolVersion` - диагностическое поле: несовпадение версий **не**
 *   отменяет чтение и не отменяет печать.
 * - Неизвестные ключи при чтении игнорируются - это и есть forward-совместимость.
 * - Отсутствующие опциональные поля дают значения по умолчанию, а не ошибку:
 *   `header` - пустая карта, `positions`/`customFields`/`subPositions` - пустые
 *   списки, `productUuid` - null, `name` - пустая строка,
 *   `quantity` - [BigDecimal.ZERO], `protocolVersion` - 0 (версия неизвестна).
 * - `measurePrecision` - единственное исключение из дефолтов: при отсутствии
 *   ключа берётся scale количества, а не 0. Иначе `quantity=2.500` печатался
 *   бы как `"3"`, потому что форматтер `%.0f` при точности 0 округляет до
 *   целого. Присутствующий ключ (включая явный 0 - штуки) читается как есть.
 * - Точность измерения обязана попадать в диапазон 0..18 в обоих случаях;
 *   нарушение - [IllegalArgumentException], а не тихое переполнение. См.
 *   [readMeasurePrecision].
 * - Значение поля, равное `null`, читается как пустая строка - null вне
 *   типизированного контракта (`Map<String, String>`), и чтение не должно
 *   прерываться из-за чужих данных.
 *
 * ## Два уровня представления
 *
 * Дерево JSON хранит документ без потерь (включая неизвестные ключи), а
 * типизированная модель - с потерями: неизвестные ключи в неё не попадают
 * (это by design: получатель печатает то, что понял). Поэтому relay обязан
 * работать с деревом, а [splitByPositions] ниже - единственная операция,
 * которая делит документ, не теряя неизвестные ключи.
 */
object StructuredDocumentJson {

    private const val KEY_PROTOCOL_VERSION = "protocolVersion"
    private const val KEY_HEADER = "header"
    private const val KEY_POSITIONS = "positions"
    private const val KEY_CUSTOM_FIELDS = "customFields"
    private const val KEY_NAME = "name"
    private const val KEY_QUANTITY = "quantity"
    private const val KEY_MEASURE_PRECISION = "measurePrecision"
    private const val KEY_PRODUCT_UUID = "productUuid"
    private const val KEY_SUB_POSITIONS = "subPositions"
    private const val KEY_KEY = "key"
    private const val KEY_TITLE = "title"
    private const val KEY_VALUE = "value"

    /**
     * Допустимый диапазон точности измерения.
     *
     * Нижняя граница 0 - штуки. Верхняя 18 - это одновременно:
     * (а) максимальный scale, который контракт документирует как сохраняемый
     * (см. проверку high-precision-количество), поэтому проверка не отвергает
     * данные, которые сам контракт обязан переносить;
     * (б) порядок, глубже которого в реальных отсчётах Evotor точность не
     * бывает (штуки 0, граммы 1, килограммы 3);
     * (в) гарантия, что `String.format("%.Nf")` не выдаёт строку шире печатной.
     * Всё, что за границей, считается битыми данными и отклоняется на разборе,
     * а не на печати.
     */
    private const val MIN_MEASURE_PRECISION = 0
    private const val MAX_MEASURE_PRECISION = 18

    /**
     * Сериализует документ в JSON-строку.
     *
     * @param document Документ для сериализации.
     * @return JSON-строка документа.
     */
    @JvmStatic
    fun toJson(document: StructuredDocument): String {
        return writeDocument(document).toString()
    }

    /**
     * Разбирает JSON-строку в типизированный документ.
     *
     * Неизвестные ключи игнорируются, отсутствующие опциональные поля дают
     * значения по умолчанию. `protocolVersion` не гейтит разбор: документ любой
     * версии читается полностью, получатель сам решает, что с ним делать.
     *
     * @param json JSON-строка документа.
     * @return Разобранный документ.
     * @throws IllegalArgumentException Если строка не является корректным JSON-объектом
     *  либо содержит значение, которое нельзя прочитать (например, `quantity`,
     *  не являющийся десятичным числом).
     */
    @JvmStatic
    fun fromJson(json: String): StructuredDocument {
        val root = parseRoot(json)
        return readDocument(root)
    }

    /**
     * Делит документ на копии по принтерам, не теряя неизвестные ключи.
     *
     * Работает с **деревом JSON**, а не с типизированной моделью: в каждой копии
     * заменяется **только** массив `positions`, всё остальное копируется как есть -
     * включая неизвестные ключи документа и неизвестные ключи внутри позиций.
     * Позиции попадают в копию как сырые [JsonObject]-узлы, а не пересобираются
     * из типизированной модели.
     *
     * @param json JSON-строка документа.
     * @param partition Принтеры, которым печатается позиция. Одна позиция может
     *  уйти на несколько принтеров; пустой набор означает, что позиция не
     *  печатается нигде.
     * @return JSON-строки документов - по одной на принтер, которому досталась
     *  хотя бы одна позиция.
     * @throws IllegalArgumentException Если строка не является корректным
     *  JSON-объектом документа.
     */
    @JvmStatic
    fun splitByPositions(
        json: String,
        partition: (StructuredPosition) -> Collection<Long>
    ): Map<Long, String> {
        val root = parseRoot(json)
        val rawPositions = readRawPositions(root.get(KEY_POSITIONS))

        val byPrinter = LinkedHashMap<Long, MutableList<JsonElement>>()
        for (raw in rawPositions) {
            val position = readPosition(asObject(raw, KEY_POSITIONS))
            val printers = LinkedHashSet(partition(position))
            for (printerId in printers) {
                byPrinter.getOrPut(printerId) { mutableListOf() }.add(raw)
            }
        }

        val result = LinkedHashMap<Long, String>()
        for ((printerId, nodes) in byPrinter) {
            val positions = JsonArray()
            for (node in nodes) {
                positions.add(node.deepCopy())
            }
            // Копия документа: заменяется ТОЛЬКО массив positions, остальные ключи
            // (включая неизвестные) переносятся как есть, в том же порядке.
            val copy = JsonObject()
            for ((key, value) in root.entrySet()) {
                if (key == KEY_POSITIONS) {
                    copy.add(KEY_POSITIONS, positions)
                } else {
                    copy.add(key, value.deepCopy())
                }
            }
            result[printerId] = copy.toString()
        }
        return result
    }

    // ─────────────────────────── запись ───────────────────────────

    private fun writeDocument(document: StructuredDocument): JsonObject {
        val root = JsonObject()
        root.addProperty(KEY_PROTOCOL_VERSION, document.protocolVersion)

        val header = JsonObject()
        for ((key, value) in document.header) {
            header.addProperty(key, value)
        }
        root.add(KEY_HEADER, header)

        val positions = JsonArray()
        for (position in document.positions) {
            positions.add(writePosition(position))
        }
        root.add(KEY_POSITIONS, positions)

        val customFields = JsonArray()
        for (field in document.customFields) {
            customFields.add(writeCustomField(field))
        }
        root.add(KEY_CUSTOM_FIELDS, customFields)

        return root
    }

    private fun writePosition(position: StructuredPosition): JsonObject {
        val node = JsonObject()
        node.addProperty(KEY_NAME, position.name)
        // Количество пишется строкой: scale BigDecimal сохраняется гарантированно.
        node.addProperty(KEY_QUANTITY, position.quantity.toString())
        node.addProperty(KEY_MEASURE_PRECISION, position.measurePrecision)
        node.add(
            KEY_PRODUCT_UUID,
            position.productUuid?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE
        )

        val subPositions = JsonArray()
        for (sub in position.subPositions) {
            subPositions.add(writePosition(sub))
        }
        node.add(KEY_SUB_POSITIONS, subPositions)

        val customFields = JsonArray()
        for (field in position.customFields) {
            customFields.add(writeCustomField(field))
        }
        node.add(KEY_CUSTOM_FIELDS, customFields)

        return node
    }

    private fun writeCustomField(field: PrintCustomField): JsonObject {
        val node = JsonObject()
        node.addProperty(KEY_KEY, field.key)
        node.addProperty(KEY_TITLE, field.title)
        node.addProperty(KEY_VALUE, field.value)
        return node
    }

    // ─────────────────────────── чтение ───────────────────────────

    private fun parseRoot(json: String): JsonObject {
        val element = try {
            parseTree(json)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Документ не является корректным JSON", error)
        }
        if (!element.isJsonObject) {
            throw IllegalArgumentException("Документ должен быть JSON-объектом")
        }
        return element.asJsonObject
    }

    /**
     * Разбирает строку в дерево JSON.
     *
     * Используется экземплярный `JsonParser.parse`, а не статический
     * `JsonParser.parseString`: статический метод появился только в Gson 2.8.6,
     * а опубликованный POM библиотеки не содержит секции `dependencies`, поэтому
     * версия Gson у потребителя определяется его собственным графом зависимостей.
     * Часть потребителей разрешает gson 2.8.5 (integration-library v0.4.45/v0.4.48),
     * где `parseString` отсутствует, и вызов кода привёл бы к `NoSuchMethodError`
     * в пути печати. Экземплярный `parse` присутствует во всех версиях Gson.
     */
    @Suppress("DEPRECATION")
    private fun parseTree(json: String): JsonElement {
        return JsonParser().parse(json)
    }

    private fun readDocument(root: JsonObject): StructuredDocument {
        val protocolVersion = readInt(root.get(KEY_PROTOCOL_VERSION), KEY_PROTOCOL_VERSION, 0)
        val header = readHeader(root.get(KEY_HEADER))
        val positions = readPositions(root.get(KEY_POSITIONS), KEY_POSITIONS)
        val customFields = readCustomFields(root.get(KEY_CUSTOM_FIELDS), KEY_CUSTOM_FIELDS)
        return StructuredDocument(protocolVersion, header, positions, customFields)
    }

    private fun readHeader(element: JsonElement?): Map<String, String> {
        if (element == null || element.isJsonNull) {
            return emptyMap()
        }
        if (!element.isJsonObject) {
            throw IllegalArgumentException("'$KEY_HEADER' должен быть JSON-объектом")
        }
        val header = LinkedHashMap<String, String>()
        for ((key, value) in element.asJsonObject.entrySet()) {
            header[key] = readString(value, "$KEY_HEADER.$key")
        }
        return header
    }

    private fun readPositions(element: JsonElement?, path: String): List<StructuredPosition> {
        val raw = readRawPositions(element)
        val positions = mutableListOf<StructuredPosition>()
        for (node in raw) {
            positions.add(readPosition(asObject(node, path)))
        }
        return positions
    }

    private fun readRawPositions(element: JsonElement?): List<JsonElement> {
        if (element == null || element.isJsonNull) {
            return emptyList()
        }
        if (!element.isJsonArray) {
            throw IllegalArgumentException("'$KEY_POSITIONS' должен быть JSON-массивом")
        }
        return element.asJsonArray.toList()
    }

    private fun readPosition(node: JsonObject): StructuredPosition {
        val name = readString(node.get(KEY_NAME), KEY_NAME)
        val quantity = readQuantity(node.get(KEY_QUANTITY))
        val measurePrecision = readMeasurePrecision(node.get(KEY_MEASURE_PRECISION), quantity)
        val productUuid = readNullableString(node.get(KEY_PRODUCT_UUID), KEY_PRODUCT_UUID)
        val subPositions = readPositions(node.get(KEY_SUB_POSITIONS), KEY_SUB_POSITIONS)
        val customFields = readCustomFields(node.get(KEY_CUSTOM_FIELDS), KEY_CUSTOM_FIELDS)
        return StructuredPosition(
            name,
            quantity,
            measurePrecision,
            productUuid,
            subPositions,
            customFields
        )
    }

    private fun readCustomFields(element: JsonElement?, path: String): List<PrintCustomField> {
        if (element == null || element.isJsonNull) {
            return emptyList()
        }
        if (!element.isJsonArray) {
            throw IllegalArgumentException("'$path' должен быть JSON-массивом")
        }
        val fields = mutableListOf<PrintCustomField>()
        val array = element.asJsonArray
        for (index in 0 until array.size()) {
            val node = asObject(array.get(index), "$path[$index]")
            fields.add(
                PrintCustomField(
                    readString(node.get(KEY_KEY), "$path[$index].$KEY_KEY"),
                    readString(node.get(KEY_TITLE), "$path[$index].$KEY_TITLE"),
                    readString(node.get(KEY_VALUE), "$path[$index].$KEY_VALUE")
                )
            )
        }
        return fields
    }

    private fun readQuantity(element: JsonElement?): BigDecimal {
        if (element == null || element.isJsonNull) {
            return BigDecimal.ZERO
        }
        if (!element.isJsonPrimitive) {
            throw IllegalArgumentException("'$KEY_QUANTITY' должен быть числом или строкой")
        }
        // Читатель принимает и строку, и число: берётся строковое представление
        // значения, поэтому scale сохраняется в обоих случаях.
        val raw = element.asString
        return try {
            BigDecimal(raw)
        } catch (error: NumberFormatException) {
            throw IllegalArgumentException("'$KEY_QUANTITY' не является десятичным числом: '$raw'", error)
        }
    }

    private fun readString(element: JsonElement?, path: String): String {
        if (element == null || element.isJsonNull) {
            return ""
        }
        if (!element.isJsonPrimitive) {
            throw IllegalArgumentException("'$path' должен быть строкой")
        }
        return element.asString
    }

    private fun readNullableString(element: JsonElement?, path: String): String? {
        if (element == null || element.isJsonNull) {
            return null
        }
        if (!element.isJsonPrimitive) {
            throw IllegalArgumentException("'$path' должен быть строкой")
        }
        return element.asString
    }

    /**
     * Читает точность измерения позиции.
     *
     * Правила чтения:
     *
     * - **Ключ отсутствует** (или `null`) - берётся scale количества. Раньше
     *   здесь стоял дефолт 0, и `quantity=2.500` печатался бы как `"3"`:
     *   форматтер `ru.softc.helpers.formatDecimal` делает `%.0f` при точности 0.
     *   Scale количества сохраняет ровно то, что передал отправитель (W1).
     * - **Ключ присутствует** - читается как целое, включая явный 0 (штуки).
     *   Отсутствующий ключ и явный 0 теперь различимы.
     * - **Диапазон 0..18 обязателен для обоих путей.** Значение разбирается
     *   через строку и сверяется с диапазоном как `Long` **до** приведения к
     *   `Int`: `JsonPrimitive.getAsInt` молча переполняет
     *   (999999999999 -> -727379969), а отрицательная точность взрывает
     *   `String.format` уже в рендере - отказ поздний и неочевидный. Здесь
     *   ошибка ловится на разборе и обрабатывается получателем как битый
     *   документ (W3, AC-DR-10 / AC-SP-8).
     *
     * @throws IllegalArgumentException если значение вне диапазона 0..18, не
     *  является целым числом либо не является примитивом.
     */
    private fun readMeasurePrecision(element: JsonElement?, quantity: BigDecimal): Int {
        if (element == null || element.isJsonNull) {
            return checkMeasurePrecision(quantity.scale().toLong(), "как scale количества")
        }
        if (!element.isJsonPrimitive) {
            throw IllegalArgumentException("'$KEY_MEASURE_PRECISION' должен быть целым числом")
        }

        val raw = element.asString
        val parsed = raw.toLongOrNull()
            ?: throw IllegalArgumentException(
                "'$KEY_MEASURE_PRECISION' не является целым числом: '$raw'"
            )
        return checkMeasurePrecision(parsed, "='$raw'")
    }

    /**
     * Проверяет точность на диапазон и приводит к `Int`.
     *
     * Проверка выполняется на [Long]: так ловятся и отрицательные значения,
     * и выходящие за `Int` числа, иначе приведение молча завернуло бы их.
     */
    private fun checkMeasurePrecision(value: Long, source: String): Int {
        if (value < MIN_MEASURE_PRECISION || value > MAX_MEASURE_PRECISION) {
            throw IllegalArgumentException(
                "'$KEY_MEASURE_PRECISION' вне допустимого диапазона " +
                    "$MIN_MEASURE_PRECISION..$MAX_MEASURE_PRECISION $source"
            )
        }
        return value.toInt()
    }

    private fun readInt(element: JsonElement?, path: String, default: Int): Int {
        if (element == null || element.isJsonNull) {
            return default
        }
        if (!element.isJsonPrimitive) {
            throw IllegalArgumentException("'$path' должен быть числом")
        }
        return try {
            element.asInt
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("'$path' должен быть целым числом", error)
        }
    }

    private fun asObject(element: JsonElement, path: String): JsonObject {
        if (!element.isJsonObject) {
            throw IllegalArgumentException("'$path' должен быть JSON-объектом")
        }
        return element.asJsonObject
    }
}
