package com.hexkeys.keyboard

enum class Kind { CHAR, PUNCT, SPACE, ENTER, TOGGLE, SHIFT, BACKSPACE }

/**
 * x   = center position in units of key width
 * up  = output on quick swipe up (null = none)
 * hold = popup choices on long press, holdStart = index of the base char inside it
 */
data class Key(
    val kind: Kind,
    val x: Float,
    val row: Int,
    val main: String = "",
    val up: String? = null,
    val hold: List<String> = emptyList(),
    val holdStart: Int = 0,
    val hint: String = "",
    val showUp: Boolean = false,
    val holdLabels: List<String> = emptyList()
)

object Layouts {
    private val accents = mapOf(
        "a" to listOf("á", "à", "ä", "â", "ã", "å"),
        "e" to listOf("é", "è", "ë", "ê"),
        "i" to listOf("í", "ì", "ï", "î"),
        "o" to listOf("ó", "ò", "ö", "ô", "õ", "ø"),
        "u" to listOf("ú", "ù", "ü", "û"),
        "y" to listOf("ý", "ÿ"),
        "c" to listOf("ç", "ć", "č"),
        "n" to listOf("ñ", "ń"),
        "s" to listOf("ß", "š", "ś"),
        "z" to listOf("ž", "ź", "ż"),
        "l" to listOf("ł"),
        "g" to listOf("ğ"),
        "d" to listOf("ð")
    )

    // Rows 0, 2, 4 are shifted by half a key; rows 1, 3 are full (7 keys).
    private fun xShort(i: Int) = 1.1f + i
    private fun xLong(i: Int) = 0.6f + i

    private fun letter(c: String, x: Float, row: Int): Key {
        val acc = accents[c]
        if (acc == null) return Key(Kind.CHAR, x, row, c, c.uppercase())
        val k = acc.size / 2
        val hold = acc.take(k) + c + acc.drop(k)
        return Key(Kind.CHAR, x, row, c, c.uppercase(), hold, k)
    }

    private fun sym(c: String, x: Float, row: Int, up: String? = null) =
        Key(Kind.CHAR, x, row, c, up)

    private fun punct1(x: Float) = Key(
        Kind.PUNCT, x, 2, main = ".", up = ",",
        hold = listOf(",", ".", ":", ";", "-", "…"), holdStart = 1, hint = ":"
    )

    private fun punct2(x: Float) = Key(
        Kind.PUNCT, x, 2, main = "?", up = "!",
        hold = listOf("!", "?", "'", "\"", "¿", "¡"), holdStart = 1, hint = "'"
    )

    private fun space(x: Float) = Key(Kind.SPACE, x, 2)

    private fun fill(chars: String, row: Int, long: Boolean): List<Key> =
        chars.split(" ").mapIndexed { i, c ->
            letter(c, if (long) xLong(i) else xShort(i), row)
        }

    val letters: List<Key> = buildList {
        addAll(fill("w e t y i o", 0, false))
        addAll(fill("q a r g u l p", 1, true))
        add(punct1(xShort(0)))
        add(space(xShort(1)))
        add(letter("f", xShort(2), 2))
        add(letter("h", xShort(3), 2))
        add(space(xShort(4)))
        add(punct2(xShort(5)))
        addAll(fill("z s d n m j k", 3, true))
        addAll(fill("x c v b", 4, false))
        add(Key(Kind.TOGGLE, xShort(4), 4, main = "123"))
        add(Key(Kind.ENTER, xShort(5), 4))
        add(Key(Kind.SHIFT, 0.35f, 2))
        add(Key(Kind.BACKSPACE, 6.82f, 2))
    }

    val symbols: List<Key> = buildList {
        "1 2 3 4 5 6".split(" ").forEachIndexed { i, c -> add(sym(c, xShort(i), 0)) }
        add(sym("7", xLong(0), 1))
        add(sym("8", xLong(1), 1, "*"))
        add(sym("9", xLong(2), 1))
        add(sym("0", xLong(3), 1))
        add(sym("(", xLong(4), 1, "["))
        add(sym(")", xLong(5), 1, "]"))
        add(sym("/", xLong(6), 1, "\\"))
        add(punct1(xShort(0)))
        add(space(xShort(1)))
        add(sym("=", xShort(2), 2, "|"))
        add(sym("+", xShort(3), 2))
        add(space(xShort(4)))
        add(punct2(xShort(5)))
        "@ # $ % & * -".split(" ").forEachIndexed { i, c -> add(sym(c, xLong(i), 3)) }
        add(sym("_", xShort(0), 4))
        add(sym("\"", xShort(1), 4))
        add(sym("<", xShort(2), 4, "{"))
        add(sym(">", xShort(3), 4, "}"))
        add(Key(Kind.TOGGLE, xShort(4), 4, main = "ABC"))
        add(Key(Kind.ENTER, xShort(5), 4))
        add(Key(Kind.BACKSPACE, 6.82f, 2))
    }

    // ------------------------------------------------------------ Arabic

    /** Arabic letters ordered by frequency; the most frequent get the most central keys. */
    private val arabicOrder =
        "ا ل ي م و ن ر ب ت ع ه د ك س ف ق ح ج ش ص ط ز خ ذ ث ض غ ظ".split(" ")

    private val arabicVariants = mapOf(
        "ا" to listOf("أ", "إ", "آ", "ء"),
        "و" to listOf("ؤ"),
        "ي" to listOf("ى", "ئ"),
        "ه" to listOf("ة")
    )

    private val harakat = listOf("\u064E", "\u064F", "\u064B", " ", "\u0650", "\u0651", "\u0652")
    private val harakatLabels = listOf("ـَ", "ـُ", "ـً", "_", "ـِ", "ـّ", "ـْ")

    private fun arabicKey(c: String, src: Key): Key {
        if (src.kind == Kind.PUNCT) {
            return if (src.main == ".") {
                Key(Kind.CHAR, src.x, src.row, c, "،",
                    listOf(".", "،", c, ":", "؛"), 2, showUp = true)
            } else {
                Key(Kind.CHAR, src.x, src.row, c, "؟",
                    listOf("!", "؟", c, "'", "\""), 2, showUp = true)
            }
        }
        val acc = arabicVariants[c] ?: return Key(Kind.CHAR, src.x, src.row, c)
        val k = acc.size / 2
        return Key(Kind.CHAR, src.x, src.row, c, acc[0],
            acc.take(k) + c + acc.drop(k), k, showUp = true)
    }

    val arabic: List<Key> = run {
        val slots = letters.filter { it.kind == Kind.CHAR || it.kind == Kind.PUNCT }
            .sortedBy {
                val dx = it.x - 3.6f
                val dy = it.row * 0.866f - 1.732f
                dx * dx + dy * dy
            }
        letters.filter { it.kind != Kind.SHIFT }.map { key ->
            when (key.kind) {
                Kind.CHAR, Kind.PUNCT -> arabicKey(arabicOrder[slots.indexOf(key)], key)
                Kind.SPACE -> Key(Kind.SPACE, key.x, key.row, up = ".",
                    hold = harakat, holdStart = 3, holdLabels = harakatLabels)
                else -> key
            }
        }
    }
}
