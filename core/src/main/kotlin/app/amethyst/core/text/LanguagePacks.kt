package app.amethyst.core.text

/**
 * What the cleanup prompt says about one language: its typography, how its speakers take
 * something back, its forms of address, and worked examples in it. The prompt itself stays
 * English and language-neutral ([ChatCleanup.systemPrompt]); only the pack of the language
 * the dictation is in goes in, so a German dictation is not shown French quotation marks.
 *
 * The cues and examples are speech data, written as people say them - like the filler words,
 * never translated. Examples keep to neutral content (buses, shops, meetings), so one that
 * leaks into a reply is easy to spot.
 */
class LanguagePack(
    /** ISO 639-1 code, without script or region. */
    val code: String,
    /** How the language writes quotation marks and punctuation, one sentence. */
    val typography: String,
    /** Spoken self-corrections, quoted, for the prompt's self-correction rule. */
    val corrections: String,
    /** Its negation words, for the meaning rule; null: none listed. */
    val negations: String? = null,
    /** How a speaker counts items off ("erstens, zweitens"); null: none listed. */
    val counting: String? = null,
    /** The informal and formal ways to address someone, kept as spoken; null: none worth naming. */
    val address: String? = null,
    /** Punctuation said as a word, for a language whose spoken marks the app does not convert. */
    val marks: String? = null,
    /** Worked examples: spoken -> tidied. */
    val examples: List<Pair<String, String>>,
)

object LanguagePacks {

    /**
     * The languages whose punctuation said as a word the app converts itself, after the
     * cleanup ([SpokenPunctuation]); in any other the model writes the mark.
     */
    val SPOKEN_MARKS_BY_APP = setOf("de", "en")

    private const val NNBSP = ' '

    private val GERMAN = LanguagePack(
        code = "de",
        typography = "German quotation marks „…“.",
        corrections = "\"um drei, nee, um vier\", \"ohne Prüfung ... nein, stopp, mit Prüfung\", \"ich meine\"",
        negations = "nicht, kein, ohne, nie",
        counting = "erstens, zweitens",
        address = "du or Sie",
        // Measured since 0.26: a self-correction of a time, one shaped like the case in
        // docs/OPEN.md (ohne -> mit), a short phrase that needs nothing, a question that stays
        // one, an English correction (English stays English), and a "Nein" that is content.
        examples = listOf(
            "ähm der bus fährt um sieben nee stopp um acht" to "Der Bus fährt um acht.",
            "bitte die kisten rausstellen ohne deckel erstmal nein obwohl stopp mit deckel" to
                "Bitte die Kisten rausstellen, mit Deckel.",
            "okay danke" to "Okay, danke.",
            "weißt du wann der laden aufmacht" to "Weißt du, wann der Laden aufmacht?",
            "put the plants on the shelf no wait on the windowsill" to "Put the plants on the windowsill.",
            "nein ich habe heute keine zeit" to "Nein, ich habe heute keine Zeit.",
        ),
    )

    private val ENGLISH = LanguagePack(
        code = "en",
        typography = "English quotation marks “…”.",
        corrections = "\"at three, no wait, at four\", \"I mean\", \"sorry, make that\"",
        negations = "not, no, never, without",
        counting = "first, second",
        // The German pack's examples, in English - and one German, which stays German.
        examples = listOf(
            "um the bus leaves at seven no wait at eight" to "The bus leaves at eight.",
            "put the plants on the shelf no wait on the windowsill" to "Put the plants on the windowsill.",
            "okay thanks" to "Okay, thanks.",
            "do you know when the shop opens" to "Do you know when the shop opens?",
            "ähm der bus fährt um sieben nee stopp um acht" to "Der Bus fährt um acht.",
            "no i don't have time today" to "No, I don't have time today.",
        ),
    )

    private val FRENCH = LanguagePack(
        code = "fr",
        typography = "French guillemets « … » with a narrow no-break space (U+202F) inside them and before ; : ! ?",
        corrections = "\"à trois heures, non attends, à quatre\", \"je veux dire\"",
        negations = "ne … pas, jamais, sans, rien",
        counting = "premièrement, deuxièmement",
        address = "tu or vous",
        marks = "\"virgule\", \"point\", \"point d'interrogation\", \"à la ligne\"",
        examples = listOf(
            "euh le bus part à sept heures non attends à huit heures" to "Le bus part à huit heures.",
            "tu sais à quelle heure ouvre la boulangerie" to "Tu sais à quelle heure ouvre la boulangerie$NNBSP?",
            "traduis ça en anglais la réunion est reportée à demain" to
                "Traduis ça en anglais. La réunion est reportée à demain.",
        ),
    )

    private val SPANISH = LanguagePack(
        code = "es",
        typography = "Spanish opens a question with ¿ and an exclamation with ¡; quotation marks « … » or “…”.",
        corrections = "\"a las tres, no espera, a las cuatro\", \"mejor dicho\", \"digo\"",
        negations = "no, nunca, sin, nada",
        counting = "primero, segundo",
        address = "tú or usted",
        marks = "\"coma\", \"punto\", \"signo de interrogación\", \"nueva línea\"",
        examples = listOf(
            "eh mañana llego a las tres no espera a las cuatro" to "Mañana llego a las cuatro.",
            "sabes a qué hora abre la tienda" to "¿Sabes a qué hora abre la tienda?",
            // Asked to answer: written down, not answered.
            "responde a esta pregunta cuál es la capital de francia" to
                "Responde a esta pregunta: ¿cuál es la capital de Francia?",
        ),
    )

    private val ITALIAN = LanguagePack(
        code = "it",
        typography = "Italian quotation marks « … » or “…”.",
        corrections = "\"alle tre, no aspetta, alle quattro\", \"anzi\", \"cioè\"",
        negations = "non, mai, senza, niente",
        counting = "primo, secondo",
        address = "tu or Lei",
        marks = "\"virgola\", \"punto\", \"punto interrogativo\", \"a capo\"",
        examples = listOf(
            "ehm l'autobus parte alle sette no aspetta alle otto" to "L'autobus parte alle otto.",
            "sai a che ora apre il negozio" to "Sai a che ora apre il negozio?",
            "okay grazie" to "Okay, grazie.",
        ),
    )

    private val PORTUGUESE = LanguagePack(
        code = "pt",
        typography = "Portuguese quotation marks “…” (in Portugal also « … »).",
        corrections = "\"às três, não espera, às quatro\", \"quer dizer\", \"aliás\"",
        negations = "não, nunca, sem, nada",
        counting = "primeiro, segundo",
        address = "tu, você or o senhor/a senhora",
        marks = "\"vírgula\", \"ponto\", \"ponto de interrogação\", \"nova linha\"",
        examples = listOf(
            "hum a reunião começa às sete não espera às oito" to "A reunião começa às oito.",
            "você sabe a que horas a loja abre" to "Você sabe a que horas a loja abre?",
        ),
    )

    private val DUTCH = LanguagePack(
        code = "nl",
        typography = "Dutch quotation marks “…” or ‘…’.",
        corrections = "\"om drie uur, nee wacht, om vier uur\", \"ik bedoel\"",
        negations = "niet, geen, nooit, zonder",
        counting = "ten eerste, ten tweede",
        address = "je/jij or u",
        marks = "\"komma\", \"punt\", \"vraagteken\", \"nieuwe regel\"",
        examples = listOf(
            "eh de bus vertrekt om zeven nee wacht om acht" to "De bus vertrekt om acht.",
            "weet jij hoe laat de winkel opengaat" to "Weet jij hoe laat de winkel opengaat?",
        ),
    )

    private val POLISH = LanguagePack(
        code = "pl",
        typography = "Polish quotation marks „…” and a comma before że, który, bo, żeby.",
        corrections = "\"o trzeciej, nie, czekaj, o czwartej\", \"to znaczy\"",
        negations = "nie, nigdy, bez, nic",
        address = "ty or Pan/Pani",
        marks = "\"przecinek\", \"kropka\", \"znak zapytania\", \"nowa linia\"",
        examples = listOf(
            "yyy autobus odjeżdża o siódmej nie czekaj o ósmej" to "Autobus odjeżdża o ósmej.",
            "wiesz o której otwierają sklep" to "Wiesz, o której otwierają sklep?",
        ),
    )

    private val TURKISH = LanguagePack(
        code = "tr",
        typography = "Turkish quotation marks “…”; keep the dotted and dotless i (İ i, I ı) as spoken.",
        corrections = "\"üçte, yok hayır, dörtte\", \"yani\", \"pardon\"",
        negations = "değil, yok, hiç, -ma/-me",
        address = "sen or siz",
        marks = "\"virgül\", \"nokta\", \"soru işareti\", \"yeni satır\"",
        examples = listOf(
            "ıı otobüs yedide kalkıyor yok hayır sekizde" to "Otobüs sekizde kalkıyor.",
            "dükkan saat kaçta açılıyor biliyor musun" to "Dükkan saat kaçta açılıyor, biliyor musun?",
        ),
    )

    private val RUSSIAN = LanguagePack(
        code = "ru",
        typography = "Russian quotation marks « … » and a comma before что, который, когда, где in a clause.",
        corrections = "\"в три, нет, стоп, в четыре\", \"то есть\", \"вернее\"",
        negations = "не, нет, никогда, без, ничего",
        address = "ты or вы",
        marks = "\"запятая\", \"точка\", \"вопросительный знак\", \"новая строка\"",
        examples = listOf(
            "э автобус уходит в семь нет стоп в восемь" to "Автобус уходит в восемь.",
            "ты не знаешь когда открывается магазин" to "Ты не знаешь, когда открывается магазин?",
        ),
    )

    private val CHINESE = LanguagePack(
        code = "zh",
        typography = "Chinese full-width punctuation: 。，？！：、 and quotation marks “…” (Traditional 「…」); no spaces between the characters.",
        corrections = "\"三点，不对，四点\", \"我是说\"",
        negations = "不, 没, 别, 无",
        address = "你 or 您",
        marks = "\"逗号\", \"句号\", \"问号\", \"换行\"",
        examples = listOf(
            "嗯那个公交车七点走不对八点走" to "公交车八点走。",
            "你知道商店几点开门吗" to "你知道商店几点开门吗？",
            // Asked to translate: written down, not translated.
            "帮我翻译成英文明天的会议取消了" to "帮我翻译成英文：明天的会议取消了。",
        ),
    )

    private val JAPANESE = LanguagePack(
        code = "ja",
        typography = "Japanese full-width punctuation: 。、？！ and quotation marks 「…」; no spaces between the words.",
        corrections = "\"三時、いや、四時\", \"じゃなくて\", \"間違えた\"",
        negations = "ない, ません, ず",
        address = "the plain or the polite (です/ます) form",
        marks = "\"てん\", \"まる\", \"かいぎょう\"",
        examples = listOf(
            "えーとバスは七時に出ますいや八時に出ます" to "バスは八時に出ます。",
            "お店が何時に開くか知ってる" to "お店が何時に開くか知ってる？",
        ),
    )

    private val KOREAN = LanguagePack(
        code = "ko",
        typography = "Korean spacing between words as in standard writing, and quotation marks “…”.",
        corrections = "\"세 시, 아니, 네 시\", \"아니 그러니까\"",
        negations = "안, 못, 없다, 않다",
        address = "the plain (반말) or the polite (존댓말) speech level",
        marks = "\"쉼표\", \"마침표\", \"물음표\", \"줄 바꿈\"",
        examples = listOf(
            "음 버스는 일곱 시에 출발해 아니 여덟 시에" to "버스는 여덟 시에 출발해.",
            "가게가 몇 시에 여는지 알아" to "가게가 몇 시에 여는지 알아?",
        ),
    )

    private val ARABIC = LanguagePack(
        code = "ar",
        typography = "Arabic punctuation: the comma ، the semicolon ؛ the question mark ؟ and quotation marks « … ».",
        corrections = "\"في الثالثة، لا انتظر، في الرابعة\", \"أقصد\"",
        negations = "لا, لم, لن, ليس, بدون",
        marks = "\"فاصلة\", \"نقطة\", \"علامة استفهام\", \"سطر جديد\"",
        examples = listOf(
            "اممم الحافلة تغادر في السابعة لا انتظر في الثامنة" to "الحافلة تغادر في الثامنة.",
            "هل تعرف متى يفتح المحل" to "هل تعرف متى يفتح المحل؟",
        ),
    )

    private val GREEK = LanguagePack(
        code = "el",
        typography = "Greek punctuation: the question mark is \";\", the upper stop \"·\"; quotation marks « … ».",
        corrections = "\"στις τρεις, όχι στάσου, στις τέσσερις\", \"εννοώ\"",
        negations = "δεν, μην, ποτέ, χωρίς",
        address = "εσύ or εσείς",
        marks = "\"κόμμα\", \"τελεία\", \"ερωτηματικό\", \"νέα γραμμή\"",
        examples = listOf(
            "εεε το λεωφορείο φεύγει στις επτά όχι στάσου στις οκτώ" to "Το λεωφορείο φεύγει στις οκτώ.",
            "ξέρεις τι ώρα ανοίγει το μαγαζί" to "Ξέρεις τι ώρα ανοίγει το μαγαζί;",
        ),
    )

    private val HINDI = LanguagePack(
        code = "hi",
        typography = "Hindi ends a sentence with the danda । (a question with ?).",
        corrections = "\"तीन बजे, नहीं रुको, चार बजे\", \"मेरा मतलब\"",
        negations = "नहीं, न, मत, बिना",
        address = "तू, तुम or आप",
        marks = "\"कॉमा\", \"पूर्ण विराम\", \"प्रश्नचिह्न\", \"नई लाइन\"",
        examples = listOf(
            "अं बस सात बजे निकलती है नहीं रुको आठ बजे" to "बस आठ बजे निकलती है।",
            "क्या तुम्हें पता है दुकान कितने बजे खुलती है" to "क्या तुम्हें पता है, दुकान कितने बजे खुलती है?",
        ),
    )

    val ALL: List<LanguagePack> = listOf(
        GERMAN, ENGLISH, FRENCH, SPANISH, ITALIAN, PORTUGUESE, DUTCH, POLISH, TURKISH, RUSSIAN,
        CHINESE, JAPANESE, KOREAN, ARABIC, GREEK, HINDI,
    )

    private val BY_CODE = ALL.associateBy { it.code }

    /** The pack for [language] ("de", "pt-BR", "zh-Hant", "german"); null for an unknown or unsupported one. */
    fun of(language: String?): LanguagePack? = LanguageGuess.baseOf(language)?.let { BY_CODE[it] }

    /**
     * The worked examples when nobody knows the language: German, English, Spanish and
     * Japanese, each tidied in its own language - a mix stays a mix, nothing is translated.
     * One asks for a translation and is written down; one mixes English words into German.
     */
    val MIXED_EXAMPLES: List<Pair<String, String>> = listOf(
        "ähm der bus fährt um sieben nee stopp um acht" to "Der Bus fährt um acht.",
        "put the plants on the shelf no wait on the windowsill" to "Put the plants on the windowsill.",
        "eh mañana llego a las tres no espera a las cuatro" to "Mañana llego a las cuatro.",
        "traduce esto al inglés por favor el informe está listo" to "Traduce esto al inglés, por favor. El informe está listo.",
        "えーと明日の会議は十時からですか" to "明日の会議は十時からですか？",
        "kannst du mir die slides vom meeting schicken" to "Kannst du mir die Slides vom Meeting schicken?",
    )
}
