package com.example.auto_2048

object GameConfig {
    // Колір порожньої клітинки (фон поля)
    val BOARD_BACKGROUND_RGB = intArrayOf(22, 21, 37)
    const val COLOR_TOLERANCE = 15

    // Межі пошуку ігрового поля на екрані (у відсотках)
    // SCAN_Y_END піднято до 0.95, бо ftband 2048 розміщує дошку ближче до низу екрана (~85-90%)
    const val SCAN_Y_START = 0.25
    const val SCAN_Y_END = 0.95

    // Мінімальна затримка після свайпу, яка дозволяє грі завершити
    // анімацію (рух плиток + злиття + поява нової). Анімація займає
    // ~300-500мс залежно від швидкості телефону. Якщо зчитувати раніше,
    // VisionProcessor бачить плитки в проміжних позиціях і повертає
    // невизначеність (або -1), що призводить до хибних рішень солвера.
    const val MIN_POST_SWIPE_DELAY_MS = 550L
    // Кількість повторних спроб зчитування, якщо після свайпу поле
    // не співпадає з прогнозованим. Дозволяє дочекатися завершення
    // анімації без втрати темпу солвера.
    const val POST_SWIPE_RETRY_LIMIT = 2
    // Затримка між повторними спробами зчитування
    const val POST_SWIPE_RETRY_DELAY_MS = 180L

    // Налаштування плиток
    const val MAX_COLOR_DISTANCE = 80.0
    // Поріг кількості голосів для прийняття кольорового рішення (6 точок семплінгу)
    const val DEFAULT_VOTE_THRESHOLD = 4
    // Підвищений поріг для плиток середнього діапазону, які часто плутають
    const val STRICT_VOTE_THRESHOLD = 5
    // Відступ точок семплінгу від краю плитки (у частках від розміру).
    // 20% достатньо, щоб уникнути антиаліасингу країв плитки та
    // проміжків між клітинками (~5% від розміру) на будь-якому екрані.
    const val SAMPLE_INSET_FRACTION = 0.20f
    // Slightly larger inset for the bottom row only - keeps the rendered sample
    // dots visibly inside the calibration frame even when the detected board
    // edge sits flush against the actual game board edge.
    const val BOTTOM_SAMPLE_INSET_FRACTION = 0.25f
    // Pause/resume gate. The MediaProjection captures every visible surface,
    // including auth popups and system dialogs that overlay the 2048 game
    // with a darkened background. When the average sampled colour is this
    // dim or darker the screen is almost certainly not the game; we treat
    // the frame as "game not visible" and skip analysis.
    const val NON_GAME_BRIGHTNESS_THRESHOLD = 40
    // A genuine 2048 board is full of vibrant tiles - at least a few cells
    // (spawned tiles, surrounding chrome) sit well above the threshold.
    // Requiring N bright cells avoids false-pausing on legitimate dark tiles.
    const val NON_GAME_MIN_BRIGHT_CELLS = 2
    // Плитки середнього діапазону, які потребують підвищеної точності через схожість відтінків.
    // Включає 512: його колір (237,194,46) збігається з 2048 і лише ~74 одиниці
    // від 256 (245,121,58), що робить колірну класифікацію ненадійною.
    val MID_RANGE_TILES = setOf(64, 128, 256, 512, 1024)
    // Найменші плитки (2/4): їх кольори наближені до фону або затінення
    // клітинки (антиаліасинг країв, лапа кота, ґратер). Колірна класифікація
    // може хибно зарахувати порожню клітинку до «2» або «4», тому для цих
    // значень теж потрібна верифікація через OCR перед комітом.
    val SMALL_TILES = setOf(2, 4)
    // Поріг відстані, нижче якого колір вважається надійним для прийняття без OCR
    const val RELIABLE_COLOR_DISTANCE = 60.0
    // Максимальне відхилення для визнання "порожньої" клітинки
    const val EMPTY_CELL_MAX_DISTANCE = 25.0

    val TILE_COLORS = mapOf(
        0 to intArrayOf(22, 21, 37),
        2 to intArrayOf(59, 58, 72),
        4 to intArrayOf(69, 71, 110),
        8 to intArrayOf(38, 131, 224),
        16 to intArrayOf(14, 157, 156),
        32 to intArrayOf(29, 174, 93),
        64 to intArrayOf(33, 108, 180),
        128 to intArrayOf(253, 130, 14),
        256 to intArrayOf(245, 121, 58),
        512 to intArrayOf(237, 194, 46),
        1024 to intArrayOf(237, 207, 114),
        2048 to intArrayOf(237, 194, 46)
    )

    // Якщо true — бот не шукає поле автоматично
    @Volatile
    var isManualMode = false

    // Поточні межі (зберігаються або вираховуються)
    @Volatile var detectedYStart = 0
    @Volatile var detectedYEnd = 0
    @Volatile var detectedXStart = 0
    @Volatile var detectedXEnd = 0

    // Реальні розміри екрана (для масштабування координат у bitmap)
    @Volatile var screenWidth = 0
    @Volatile var screenHeight = 0
}