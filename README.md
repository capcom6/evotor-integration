# Evotor Integration

Библиотека предназначена для интеграции сторонних приложений для смарт-терминала Эвотор с приложениями разработки компании СОФТ-Центр.

## Драйвер принтера чеков

Разработчики могут использовать драйвер принтера чеков в своих приложениях. Для этого приложение должно обладать разрешением ru.softc.permission.receiptprinter.PRINT.

```xml

<uses-permission android:name="ru.softc.permission.receiptprinter.PRINT" />

```

Подключите библиотеку интеграции: https://jitpack.io/#capcom6/evotor-integration к своему приложению. Библиотека содержит класс ReceiptPrinter, предназначенный для упрощения работы с драйвером. Класс предоставляет следующие статические методы:

* boolean isDriverInstalled(Context context) - проверяет наличие драйвера на устройстве, если возвращено false, то другие методы не должны использоваться;
* ReceiptPrinter[] getPrinters(Context context) - возвращает массив всех настроенных принтеров;
* ReceiptPrinter getPrinter(Context context, long id) - возвращает описание принтера с идентификатором id;
* void printDocument(Context context, long printerId, PrinterDocument document) - выполняет печать документа на принтере с указанным идентификатором, если указан идентификатор -1, то печать производится на встроенном принтере.

Экземпляры класса ReceiptPrinter также содержат методы:

* long getId() - возвращает идентификатор принтера;
* String getName() - возвращает заданное пользователем имя принтера;
* int getAllowableSymbolsLineLength() - возвращает ширину печати в символах стандартного шрифта, может быть изменена пользователем.

### Печать в асинхронном режиме

При печати в асинхронном режиме приложение не может узнать об ошибках печати, но в случае ошибки сообщение будет выведено пользователю. Также есть ограничение на максимальный размер документа, не рекомендуется использовать при печати графики.

Пример работы с драйвером, выполняет печать на всех доступных принтерах в асинхронном режиме:

```java
if (ReceiptPrinter.isDriverInstalled(getApplicationContext())) {
    for (ReceiptPrinter printer : ReceiptPrinter.getPrinters(getApplicationContext())) {
        ReceiptPrinter.printDocument(getApplicationContext(), item.getId(), new PrinterDocument(
            new PrintableText("Первая строка"),
            new PrintableText("<!s>Мелкая строка"),
            new PrintableText("<!e>Жирная строка"),
            new PrintableText("<!hw>Крупная строка"),
            new PrintableText("<!u>Подчеркнутая строка"),
            new PrintableText("Довольно длинный текст, помещающийся лишь на несколько строк"),
            new PrintableAlignedText(PrintableAlignedText.Align.CENTER, "По центру"),
            new PrintableAlignedText(PrintableAlignedText.Align.RIGHT, "Справа"),
            new PrintableImage(BitmapFactory.decodeResource(getResources(), R.drawable.img_evotor, options)),
            new PrintableBarcode("EVOTOR", PrintableBarcode.BarcodeType.CODE39)
        ));
    }
}

```

### Печать в синхронном режиме

Печать в синхронном режиме осуществвляется по аналогии с использованием встроенного в Эвотор принтера. Обращаем Ваше внимание, что работы в синхронном режиме должна выполняться в потоке, отличном от UI.

```java
// создаем объект-помощник
final ReceiptPrinterService receiptPrinterService = new ReceiptPrinterService();
// инициализируем подключение к сервису
receiptPrinterService.startInitConnection(context, false);
// ожидаем подключения
receiptPrinterService.waitInitService(context);
// выполняем печать
receiptPrinterService.printDocument(printerId, document);

```

### Форматирование текста

Драйвер поддерживает несколько команд форматирования текста, необходимый формат определяется тегом в начале строки вида *<!флаги>*. В качестве флагов могут использоваться:

* s - мелкий шрифт;
* e - жирный шрифт;
* h - двойная высота шрифта;
* w - двойная ширина шрифта;
* u - подчеркнутый шрифт.

## Структурированная печать

Начиная с версии библиотеки 0.6.0 документ можно передавать получателю печати не как готовый `PrinterDocument`, а как структурированный документ: получатель сам решает, как отрисовать шапку, позиции и произвольные поля.

Модель (пакет `ru.softc.evotor.integration.print`):

* `StructuredDocument(protocolVersion, header, positions, customFields)` - документ;
* `StructuredPosition(name, quantity, measurePrecision, productUuid, subPositions, customFields)` - позиция документа, количество передаётся как `BigDecimal`, чтобы не потерять точность и scale;
* `measurePrecision` - количество знаков после запятой при выводе количества. Если ключ отсутствует (или `null`), берётся scale количества: `quantity=2.500` выводится как `2.500`, а не округляется до `3`. Явный `0` (штуки) сохраняется и не перекрывается scale. Допустимый диапазон - 0..18 в обоих случаях; значение вне диапазона или не целое отклоняется на разборе с `IllegalArgumentException`, а не на печати;
* `PrintCustomField(key, title, value)` - произвольное поле документа.

Точки входа:

* boolean `ServicePrint.printStructured(Context context, StructuredDocument document)` - печать через приложение serviceprint, требует serviceprint версии не ниже 19;
* boolean `ReceiptPrinter.printStructuredDocument(Context context, long printerId, StructuredDocument document)` - печать на конкретном принтере через драйвер, требует драйвер версии не ниже 142.

Оба метода возвращают `false`, если получатель не установлен или его версия ниже порога. При `false` ничего не отправляется - вызывающий код обязан деградировать сам.

```java
StructuredDocument document = new StructuredDocument(
    StructuredDocument.PROTOCOL_VERSION_CURRENT,
    Collections.singletonMap("receiptNumber", "42"),
    Collections.singletonList(new StructuredPosition(
        "Espresso", new BigDecimal("2.500"), 3, "product-1",
        Collections.<StructuredPosition>emptyList(),
        Collections.<PrintCustomField>emptyList()
    )),
    Collections.singletonList(new PrintCustomField("total", "Итого", "1250.00"))
);

if (!ReceiptPrinter.printStructuredDocument(context, printerId, document)) {
    // деградация: печатаем обычным документом через ReceiptPrinter.printDocument
}

```

### Версия протокола и wire-format

`StructuredDocument.protocolVersion` - версия wire-layout контракта, а не версия библиотеки. Поддерживаемая версия публикуется как `StructuredDocument.PROTOCOL_VERSION_CURRENT`.

Документ передаётся JSON-строкой. Сериализация выполняется только кодеком `StructuredDocumentJson`: приложениям запрещено собирать JSON вручную - у JSON нет compile-time схемы, и опечатка в имени ключа приводит к тихой потере поля. Ключи документа: `protocolVersion`, `header`, `positions`, `customFields`.

Правило получателя: несовпадение `protocolVersion` с `PROTOCOL_VERSION_CURRENT` не является отказом. Получатель печатает известные поля и логирует расхождение - неизвестный layout неотличим от известного с новыми ключами, поэтому версия не может служить основанием для отказа от печати.

Неизвестные ключи при чтении игнорируются - это forward-совместимость: документ с новыми ключами читается полностью, а получатель печатает то, что понял.

### Правило проксирования

Промежуточные приложения (relay, любой слой между вызывающим приложением и получателем печати) обязаны передавать `header` и `customFields` получателю как есть: без фильтрации, переименования, интерпретации и переупорядочивания. Ключи не имеют семантики для промежуточного слоя - значение приходит от вызывающего приложения, отображение определяет получатель.

Relay обязан работать с JSON-деревом через `StructuredDocumentJson.splitByPositions`, а не с типизированной моделью: разбор в модель отбрасывает неизвестные ключи. При делении документа заменяется только массив `positions`, остальные ключи документа и позиций (включая неизвестные) переносятся как есть.
