package ru.softc.evotor.integration.print

import java.math.BigDecimal

/**
 * Позиция структурированного печатного документа.
 *
 * Wire-format - JSON, ключи позиции: `name`, `quantity`, `measurePrecision`,
 * `productUuid`, `subPositions`, `customFields`.
 *
 * @param quantity Количество. Передаётся как [BigDecimal], потому что примитив теряет
 *   точность и scale: 2.500 и 2.5 - разные значения для получателя. В JSON
 *   записывается **строкой** ("2.500"), поэтому scale сохраняется гарантированно.
 * @param measurePrecision Количество знаков после запятой из framework Position.measurePrecision.
 *   Нужен получателю для форматирования количества.
 * @param customFields Произвольные поля позиции (D10). Пустой список допустим.
 */
data class StructuredPosition(
    /**
     * Наименование позиции
     */
    val name: String,
    /**
     * Количество. Строковое представление определяет конечный получатель (D4).
     */
    val quantity: BigDecimal,
    /**
     * Количество знаков после запятой при отображении количества (N3)
     */
    val measurePrecision: Int,
    /**
     * Идентификатор товара в учётной системе. null, если товар не привязан.
     */
    val productUuid: String?,
    /**
     * Подпозиции. Пустой список для позиции без подпозиций.
     */
    val subPositions: List<StructuredPosition>,
    /**
     * Произвольные поля позиции. Пустой список допустим.
     */
    val customFields: List<PrintCustomField>
)
