package ru.softc.evotor.integration.print;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.BadParcelableException;
import android.os.Parcel;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Round-trip проверка ручной реализации Parcelable (N1) и сохранения scale у BigDecimal (N2).
 */
public class StructuredDocumentParcelableTest {

    private static StructuredDocument roundTrip(StructuredDocument source) {
        Parcel parcel = Parcel.obtain();
        try {
            source.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            StructuredDocument restored = StructuredDocument.CREATOR.createFromParcel(parcel);
            assertNotNull(restored);
            return restored;
        } finally {
            parcel.recycle();
        }
    }

    /**
     * Читает payload конверта словами по 4 байта: позиция данных Parcel
     * выравнивается по 4 байта, поэтому побайтовое сравнение возможно только
     * по границам слов.
     */
    private static int[] readPayloadWords(Parcel parcel, int length) {
        int[] words = new int[length / 4];
        for (int index = 0; index < words.length; index++) {
            words[index] = parcel.readInt();
        }
        return words;
    }

    private static StructuredPosition samplePosition() {
        StructuredPosition sub = new StructuredPosition(
                "sub",
                new BigDecimal("1.50"),
                2,
                "product-sub",
                Arrays.<StructuredPosition>asList()
        );
        return new StructuredPosition(
                "Espresso",
                new BigDecimal("2.500"),
                3,
                "product-1",
                Arrays.asList(sub)
        );
    }

    private static StructuredDocument sampleDocument() {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("receiptNumber", "42");
        header.put("cashier", "Ivanov");
        return new StructuredDocument(
                1,
                header,
                Arrays.asList(samplePosition()),
                Arrays.asList(new PrintCustomField("total", "Итого", "1250.00"))
        );
    }

    @Test
    public void documentRoundTripPreservesAllFields() {
        StructuredDocument restored = roundTrip(sampleDocument());

        assertEquals(1, restored.getProtocolVersion());
        assertEquals(2, restored.getHeader().size());
        assertEquals("42", restored.getHeader().get("receiptNumber"));
        assertEquals("Ivanov", restored.getHeader().get("cashier"));
        assertEquals(1, restored.getPositions().size());
        assertEquals(1, restored.getCustomFields().size());
        assertEquals("Итого", restored.getCustomFields().get(0).getTitle());
        assertEquals("2.500", restored.getPositions().get(0).getQuantity().toString());
    }

    @Test
    public void bigDecimalKeepsScaleAndPrecision() {
        StructuredPosition restored = roundTrip(sampleDocument()).getPositions().get(0);

        assertEquals(3, restored.getQuantity().scale());
        assertEquals("2.500", restored.getQuantity().toString());
        assertEquals(3, restored.getMeasurePrecision());
        assertEquals("product-1", restored.getProductUuid());
    }

    @Test
    public void subPositionsAndEmptyCollectionsSurvive() {
        StructuredPosition position = roundTrip(sampleDocument()).getPositions().get(0);

        assertEquals(1, position.getSubPositions().size());
        assertEquals("sub", position.getSubPositions().get(0).getName());
        assertEquals(2, position.getSubPositions().get(0).getQuantity().scale());
    }

    @Test
    public void emptyDocumentRoundTrip() {
        StructuredDocument empty = new StructuredDocument(
                1,
                new LinkedHashMap<String, String>(),
                Arrays.<StructuredPosition>asList(),
                Arrays.<PrintCustomField>asList()
        );

        StructuredDocument restored = roundTrip(empty);

        assertEquals(1, restored.getProtocolVersion());
        assertTrue(restored.getHeader().isEmpty());
        assertTrue(restored.getPositions().isEmpty());
        assertTrue(restored.getCustomFields().isEmpty());
    }

    @Test
    public void highPrecisionQuantityIsNotTruncated() {
        StructuredPosition position = new StructuredPosition(
                "big",
                new BigDecimal("1234567890.123456789012345678"),
                18,
                null,
                Arrays.<StructuredPosition>asList()
        );

        StructuredPosition restored = roundTrip(
                new StructuredDocument(1, new LinkedHashMap<String, String>(), Arrays.asList(position), Arrays.<PrintCustomField>asList())
        ).getPositions().get(0);

        assertEquals(18, restored.getQuantity().scale());
        assertEquals("1234567890.123456789012345678", restored.getQuantity().toString());
        assertEquals(null, restored.getProductUuid());
    }

    @Test
    public void protocolVersionConstantIsPublishedAndUnknownVersionRoundTrips() {
        assertEquals(1, StructuredDocument.PROTOCOL_VERSION_CURRENT);

        // Неизвестная версия (2) переживает round-trip без интерпретации: транспорт
        // сохраняет значение. Отказ от документа - ответственность получателя (T3/T4),
        // библиотека намеренно не реализует проверку.
        StructuredDocument future = new StructuredDocument(
                2,
                new LinkedHashMap<String, String>(),
                Arrays.<StructuredPosition>asList(),
                Arrays.<PrintCustomField>asList()
        );

        StructuredDocument restored = roundTrip(future);

        assertEquals(2, restored.getProtocolVersion());
        assertNotEquals(
                "Receiver detects mismatch by comparing against the published constant",
                StructuredDocument.PROTOCOL_VERSION_CURRENT,
                restored.getProtocolVersion()
        );
        // Документ собран вручную, поэтому записан он в layout текущей версии,
        // а прочитан - как чужой payload и сохранён побайтово.
        assertNotNull(restored.getOpaquePayload());
    }

    @Test
    public void unknownHeaderAndCustomFieldKeysSurviveIntermediateCopy() {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("delivery.address", "Moscow");
        header.put("some.context.key", "value-with-\u0440\u0443\u0441");
        header.put("zzz.last.inserted", "1");

        List<PrintCustomField> customFields = Arrays.asList(
                new PrintCustomField("delivery.address", "\u0410\u0434\u0440\u0435\u0441", "\u041c\u043e\u0441\u043a\u0432\u0430"),
                new PrintCustomField("some.context.key", "Title B", "Value B"),
                new PrintCustomField("unknown.third", "Title C", "Value C")
        );

        // Промежуточная копия: документ пересоздаётся получателем из разобранного
        // состояния - так же, как это делает relay-слой, пересобирая документ.
        StructuredDocument original = new StructuredDocument(
                StructuredDocument.PROTOCOL_VERSION_CURRENT,
                header,
                Arrays.<StructuredPosition>asList(),
                customFields
        );
        StructuredDocument intermediate = new StructuredDocument(
                roundTrip(original).getProtocolVersion(),
                roundTrip(original).getHeader(),
                roundTrip(original).getPositions(),
                roundTrip(original).getCustomFields()
        );

        StructuredDocument restored = roundTrip(intermediate);

        // Ни один ключ не отфильтрован, не переименован, не переупорядочен.
        assertEquals(3, restored.getHeader().size());
        List<String> headerKeys = new ArrayList<String>(restored.getHeader().keySet());
        assertEquals("delivery.address", headerKeys.get(0));
        assertEquals("some.context.key", headerKeys.get(1));
        assertEquals("zzz.last.inserted", headerKeys.get(2));
        assertEquals("Moscow", restored.getHeader().get("delivery.address"));
        assertEquals("value-with-\u0440\u0443\u0441", restored.getHeader().get("some.context.key"));

        assertEquals(3, restored.getCustomFields().size());
        assertEquals("delivery.address", restored.getCustomFields().get(0).getKey());
        assertEquals("\u0410\u0434\u0440\u0435\u0441", restored.getCustomFields().get(0).getTitle());
        assertEquals("\u041c\u043e\u0441\u043a\u0432\u0430", restored.getCustomFields().get(0).getValue());
        assertEquals("some.context.key", restored.getCustomFields().get(1).getKey());
        assertEquals("Title B", restored.getCustomFields().get(1).getTitle());
        assertEquals("Value B", restored.getCustomFields().get(1).getValue());
        assertEquals("unknown.third", restored.getCustomFields().get(2).getKey());
        assertEquals("Title C", restored.getCustomFields().get(2).getTitle());
        assertEquals("Value C", restored.getCustomFields().get(2).getValue());
    }

    @Test
    public void documentIsActuallyParcelableAndContextIsAvailable() {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeParcelable(sampleDocument(), 0);
            parcel.setDataPosition(0);
            StructuredDocument restored = parcel.readParcelable(StructuredDocument.class.getClassLoader());
            assertEquals("2.500", restored.getPositions().get(0).getQuantity().toString());
        } finally {
            parcel.recycle();
        }

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNotNull(context);
    }

    @Test
    public void unknownVersionPayloadIsSkippedWithoutConsumingFollowingValue() {
        Parcel parcel = Parcel.obtain();
        try {
            // Конверт совпадает (версия + длина payload), но payload написан
            // чужим layout'ом будущей версии 2 и не разбирается библиотекой.
            parcel.writeInt(2);
            int lengthPosition = parcel.dataPosition();
            parcel.writeInt(0);
            int payloadStart = parcel.dataPosition();
            parcel.writeInt(999); // поля, которых нет в layout версии 1
            parcel.writeString("v2-layout");
            int payloadEnd = parcel.dataPosition();
            parcel.setDataPosition(lengthPosition);
            parcel.writeInt(payloadEnd - payloadStart);
            parcel.setDataPosition(payloadEnd);
            parcel.writeInt(0xDEADBEEF); // следующее значение за документом

            parcel.setDataPosition(0);
            StructuredDocument restored = StructuredDocument.CREATOR.createFromParcel(parcel);

            assertEquals(2, restored.getProtocolVersion());
            assertTrue(restored.getHeader().isEmpty());
            assertTrue(restored.getPositions().isEmpty());
            assertTrue(restored.getCustomFields().isEmpty());
            // Чужой layout не разбирается, но и не теряется: payload сохраняется
            // без изменения, чтобы пересылка не подменила документ пустым.
            assertNotNull(restored.getOpaquePayload());
            assertEquals(payloadEnd - payloadStart, restored.getOpaquePayload().getSize());
            // Чтение payload не потребляет следующее значение в Parcel.
            assertEquals(0xDEADBEEF, parcel.readInt());
        } finally {
            parcel.recycle();
        }
    }

    @Test
    public void unknownVersionPayloadIsReEmittedUnchanged() {
        Parcel source = Parcel.obtain();
        Parcel reemitted = Parcel.obtain();
        try {
            source.writeInt(2);
            int lengthPosition = source.dataPosition();
            source.writeInt(0);
            int payloadStart = source.dataPosition();
            source.writeInt(999); // чужой layout будущей версии 2
            source.writeString("v2-layout");
            int payloadEnd = source.dataPosition();
            source.setDataPosition(lengthPosition);
            source.writeInt(payloadEnd - payloadStart);
            source.setDataPosition(payloadEnd);

            source.setDataPosition(0);
            StructuredDocument restored = StructuredDocument.CREATOR.createFromParcel(source);

            restored.writeToParcel(reemitted, 0);

            // Конверт переписан без изменения: та же версия, та же длина payload.
            reemitted.setDataPosition(0);
            assertEquals(2, reemitted.readInt());
            int reemittedLength = reemitted.readInt();
            assertEquals(payloadEnd - payloadStart, reemittedLength);

            // Payload переписан без изменения: те же слова в том же порядке.
            source.setDataPosition(payloadStart);
            int[] expected = readPayloadWords(source, reemittedLength);
            int[] actual = readPayloadWords(reemitted, reemittedLength);
            assertArrayEquals("Opaque payload must survive forwarding unchanged", expected, actual);
        } finally {
            source.recycle();
            reemitted.recycle();
        }
    }

    @Test
    public void opaquePayloadSurvivesCopy() {
        Parcel source = Parcel.obtain();
        Parcel reemitted = Parcel.obtain();
        try {
            source.writeInt(2);
            int lengthPosition = source.dataPosition();
            source.writeInt(0);
            int payloadStart = source.dataPosition();
            source.writeInt(999);
            source.writeString("v2-layout");
            int payloadEnd = source.dataPosition();
            source.setDataPosition(lengthPosition);
            source.writeInt(payloadEnd - payloadStart);
            source.setDataPosition(payloadEnd);

            source.setDataPosition(0);
            StructuredDocument restored = StructuredDocument.CREATOR.createFromParcel(source);

            // Промежуточный слой может пересобрать документ через copy() -
            // payload обязан остаться при документе.
            StructuredDocument copy = restored.copy(
                    restored.getProtocolVersion(),
                    restored.getHeader(),
                    restored.getPositions(),
                    restored.getCustomFields(),
                    restored.getOpaquePayload()
            );
            assertNotNull(copy.getOpaquePayload());
            assertEquals(restored.getOpaquePayload().getSize(), copy.getOpaquePayload().getSize());

            copy.writeToParcel(reemitted, 0);

            reemitted.setDataPosition(0);
            assertEquals(2, reemitted.readInt());
            int reemittedLength = reemitted.readInt();
            assertEquals(payloadEnd - payloadStart, reemittedLength);

            source.setDataPosition(payloadStart);
            int[] expected = readPayloadWords(source, reemittedLength);
            int[] actual = readPayloadWords(reemitted, reemittedLength);
            assertArrayEquals("copy() must not drop the opaque payload", expected, actual);
        } finally {
            source.recycle();
            reemitted.recycle();
        }
    }

    @Test
    public void currentVersionDocumentCarriesNoOpaquePayload() {
        StructuredDocument restored = roundTrip(sampleDocument());

        assertNull("Document of the current version must not carry an opaque payload",
                restored.getOpaquePayload());
    }

    @Test
    public void invalidPayloadLengthThrowsBadParcelableException() {
        Parcel negative = Parcel.obtain();
        try {
            negative.writeInt(StructuredDocument.PROTOCOL_VERSION_CURRENT);
            negative.writeInt(-1);
            negative.setDataPosition(0);
            try {
                StructuredDocument.CREATOR.createFromParcel(negative);
                fail("Expected BadParcelableException for negative payload length");
            } catch (BadParcelableException expected) {
                // ожидаемый отказ конверта
            }
        } finally {
            negative.recycle();
        }

        Parcel oversized = Parcel.obtain();
        try {
            oversized.writeInt(StructuredDocument.PROTOCOL_VERSION_CURRENT);
            oversized.writeInt(Integer.MAX_VALUE);
            oversized.setDataPosition(0);
            try {
                StructuredDocument.CREATOR.createFromParcel(oversized);
                fail("Expected BadParcelableException for oversized payload length");
            } catch (BadParcelableException expected) {
                // ожидаемый отказ конверта
            }
        } finally {
            oversized.recycle();
        }

        // Длина payload обязана быть кратна 4: позиция данных Parcel выравнивается
        // по 4 байта, иначе границы payload не попадают на границу слова.
        Parcel unaligned = Parcel.obtain();
        try {
            unaligned.writeInt(StructuredDocument.PROTOCOL_VERSION_CURRENT);
            unaligned.writeInt(6);
            unaligned.writeInt(0);
            unaligned.writeInt(0);
            unaligned.setDataPosition(0);
            try {
                StructuredDocument.CREATOR.createFromParcel(unaligned);
                fail("Expected BadParcelableException for unaligned payload length");
            } catch (BadParcelableException expected) {
                // ожидаемый отказ конверта
            }
        } finally {
            unaligned.recycle();
        }
    }
}