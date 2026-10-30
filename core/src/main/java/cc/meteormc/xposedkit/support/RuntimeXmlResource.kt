package cc.meteormc.xposedkit.support

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.content.res.StringBlock
import android.content.res.XmlBlock
import android.content.res.XmlResourceParser
import android.os.Build
import android.util.TypedValue
import cc.meteormc.xposedkit.allocate
import cc.meteormc.xposedkit.call
import cc.meteormc.xposedkit.nativelib.NativeBridge
import cc.meteormc.xposedkit.new
import cc.meteormc.xposedkit.put
import cc.meteormc.xposedkit.reflect
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Node
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.roundToInt

object RuntimeXmlResource {
    private const val TAG = "RuntimeXmlResource"

    const val RESOURCE_NS_PREFIX = "http://schemas.android.com/apk/res"
    const val ANDROID_NS = "${RESOURCE_NS_PREFIX}/android"
    const val RES_AUTO_NS = "${RESOURCE_NS_PREFIX}-auto"

    private val isSdk372 = Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.CINNAMON_BUN_2
    private val unitMapping = listOf(
        "px" to TypedValue.COMPLEX_UNIT_PX,
        "dp" to TypedValue.COMPLEX_UNIT_DIP,
        "dip" to TypedValue.COMPLEX_UNIT_DIP,
        "sp" to TypedValue.COMPLEX_UNIT_SP,
        "pt" to TypedValue.COMPLEX_UNIT_PT,
        "in" to TypedValue.COMPLEX_UNIT_IN,
        "mm" to TypedValue.COMPLEX_UNIT_MM
    ).sortedByDescending { it.first.length }.toMap(HashMap())

    private val attrsMethod: Method
    private val bagValueMethod: Method

    private val xmlBlockClass: Class<*>
    private val nativeStateClass: Class<*>?
    private val stringBlockMethod: Method
    private val openCountField: Field
    private val stateField: Field?
    private val stringsField: Field

    init {
        AssetManager::class.reflect {
            attrsMethod = method("getStyleAttributes")!!
            bagValueMethod = method("getResourceBagText")!!
        }
        XmlBlock::class.reflect {
            xmlBlockClass = type
            stringBlockMethod = method("nativeGetStringBlock")!!
            stringsField = field("mStrings")!!

            if (isSdk372) {
                nestedClass("NativeState")!!.reflect {
                    nativeStateClass = type
                    openCountField = field("mOpenCount")!!
                }
                stateField = field("mState")
            } else {
                nativeStateClass = null
                openCountField = field("mOpenCount")!!
                stateField = null
            }
        }
    }

    @ConsistentCopyVisibility
    data class XmlAttribute @PublishedApi internal constructor(
        var namespace: String? = null,
        var name: String? = null,
        var value: String? = null,
        var attributeId: Int = 0/*Resources.ID_NULL*/,
        var valueData: Int? = null,
        var valueType: Int? = null
    ) {
        fun resolve(context: Context) {
            if (name == null || value == null) {
                throw IllegalStateException("Name and value must be set before resolving!")
            }

            if (attributeId == 0) {
                val ns = namespace
                val packageName = if (ns != null && ns.startsWith("$RESOURCE_NS_PREFIX/")) {
                    ns.removePrefix("$RESOURCE_NS_PREFIX/")
                } else {
                    context.packageName
                }

                attributeId = context.resolveIdentifier(name!!, "attr", packageName)
            }

            if (valueData == null || valueType == null) {
                val compiledValue = compileValue(context, attributeId)
                valueData = compiledValue.data
                valueType = compiledValue.type
            }
        }

        fun check() {
            require(name != null) { "Attribute name must not be null" }
            require(value != null) { "Attribute value must not be null" }
            require(attributeId != 0) { "Attribute ID must not be 0" }
            require(valueData != null) { "Value data must not be null" }
            require(valueType != null) { "Value type must not be null" }
        }

        internal fun writeTo(out: JSONObject) {
            check()
            out.put("namespace", namespace)
            out.put("name", name)
            out.put("value", value)
            out.put("attributeId", attributeId)
            out.put("valueData", valueData)
            out.put("valueType", valueType)
        }
    }

    @ConsistentCopyVisibility
    data class XmlElement @PublishedApi internal constructor(
        var name: String? = null,
        val attributes: MutableList<XmlAttribute> = mutableListOf(),
        val children: MutableList<XmlElement> = mutableListOf()
    ) {
        inline fun attribute(block: XmlAttribute.() -> Unit) {
            val attr = XmlAttribute().apply(block)
            attr.check()
            attributes.add(attr)
        }

        inline fun child(block: XmlElement.() -> Unit) {
            val child = XmlElement().apply(block)
            child.check()
            children.add(child)
        }

        fun check() {
            require(name != null) { "Element name must not be null" }
        }

        internal fun writeTo(out: JSONObject) {
            check()
            out.put("name", name)
            out.put("attributes", JSONArray().apply {
                for (attr in attributes) {
                    put(JSONObject().apply { attr.writeTo(this) })
                }
            })
            out.put("children", JSONArray().apply {
                for (child in children) {
                    put(JSONObject().apply { child.writeTo(this) })
                }
            })
        }
    }

    fun create(context: Context, document: Document): XmlResourceParser {
        fun XmlElement.fromDom(node: Node) {
            name = node.localName

            val attrs = node.attributes
            for (i in 0 until attrs.length) {
                val attr = attrs.item(i)
                attribute {
                    namespace = attr.namespaceURI
                    name = attr.localName
                    value = attr.nodeValue
                    resolve(context)
                }
            }

            val childNodes = node.childNodes
            for (i in 0 until childNodes.length) {
                val child = childNodes.item(i)
                child { fromDom(child) }
            }
        }

        return create { fromDom(document.documentElement) }
    }

    fun create(element: XmlElement.() -> Unit): XmlResourceParser {
        val xml = XmlElement().apply(element)
        val json = JSONObject().apply { xml.writeTo(this) }
        val parseState = NativeBridge.CreateXmlParseState(json.toString())
        val xmlBlock = xmlBlockClass.allocate().apply {
            val stringBlock = StringBlock(stringBlockMethod.call(null, parseState), false)
            stringsField.put(this, stringBlock)

            // 当这个值归零后会触发 XmlBlock.close() 的内部逻辑
            // 并且因为 XmlBlock 是一个未初始化的不稳定对象 导致这个操作必定会引发崩溃
            // 由于每次创建 XmlBlock.Parser 时都会累加 mOpenCount
            // 为了防止这种情况 我们可以为这个字段设置一个很小的值来避免
            if (isSdk372) {
                val state = nativeStateClass!!.allocate()
                openCountField.put(state, Int.MIN_VALUE)
                stateField!!.put(this, state)
            } else {
                openCountField.put(this, Int.MIN_VALUE)
            }
        }
        return XmlBlock.Parser::class.reflect {
            constructor(
                xmlBlockClass,
                Long::class.java,
                xmlBlockClass
            )!!.new<XmlBlock.Parser>(
                xmlBlock,
                parseState,
                xmlBlock
            )
        }
    }

    @SuppressLint("DiscouragedApi")
    private fun Context.resolveIdentifier(name: String, type: String?, pkg: String?): Int {
        val id = this.resources.getIdentifier(name, type, pkg)
        if (id == 0) {
            val message = buildString {
                append("Cannot resolve attribute $name")
                if (type != null) append(" of type $type")
                if (pkg != null) append(" in package $pkg")
            }
            throw IllegalArgumentException(message)
        }

        return id
    }

    private data class CompiledValue(val data: Int, val type: Int)

    private infix fun Int.compileTo(type: Int): CompiledValue {
        return CompiledValue(this, type)
    }

    private fun encodeComplex(value: Float, unit: Int): Int {
        require(value.isFinite()) {
            "Complex value must be finite: $value"
        }
        require(unit in 0..TypedValue.COMPLEX_UNIT_MASK) {
            "Invalid complex unit: $unit"
        }

        val limit = 1 shl 23
        if (value < -limit - 0.5f || value >= limit - 0.5f) {
            throw IllegalArgumentException("Complex value is out of range: $value")
        }

        fun pack(mantissa: Int, radix: Int): Int {
            require(mantissa < limit && mantissa >= -limit) {
                "Mantissa is out of range: $mantissa"
            }
            require(radix in 0..TypedValue.COMPLEX_RADIX_MASK) {
                "Invalid complex radix: $radix"
            }
            return ((mantissa and TypedValue.COMPLEX_MANTISSA_MASK) shl TypedValue.COMPLEX_MANTISSA_SHIFT) or
                    (radix shl TypedValue.COMPLEX_RADIX_SHIFT) or
                    unit
        }

        // If there's no fraction, use integer representation, as that's clearer
        if (value == value.toInt().toFloat()) {
            return pack(value.toInt(), TypedValue.COMPLEX_RADIX_23p0)
        }

        val magnitude = abs(value)
        // If the magnitude is 0, we don't need any magnitude digits
        if (magnitude < 1f) {
            return pack((value * (1 shl 23)).roundToInt(), TypedValue.COMPLEX_RADIX_0p23)
        }

        // If the magnitude is less than 2^8, use 8 magnitude digits
        if (magnitude < 1 shl 8) {
            return pack((value * (1 shl 15)).roundToInt(), TypedValue.COMPLEX_RADIX_8p15)
        }

        // If the magnitude is less than 2^16, use 16 magnitude digits
        if (magnitude < 1 shl 16) {
            return pack((value * (1 shl 7)).roundToInt(), TypedValue.COMPLEX_RADIX_16p7)
        }

        // The magnitude requires all 23 digits
        return pack(value.roundToInt(), TypedValue.COMPLEX_RADIX_23p0)
    }

    private fun XmlAttribute.compileValue(context: Context, attributeId: Int): CompiledValue {
        val value = value!!.trim()
        parseReference(context, value)?.let { return it }
        parseEnum(context, attributeId, value)?.let { return it }
        parseBoolean(value)?.let { return it }
        parseColor(value)?.let { return it }
        parseDimension(value)?.let { return it }
        parseFraction(value)?.let { return it }
        parseInt(value)?.let { return it }
        parseFloat(value)?.let { return it }
        parseHex(value)?.let { return it }
        return 0 compileTo TypedValue.TYPE_STRING
    }

    private fun parseReference(context: Context, value: String): CompiledValue? {
        if (value.length <= 1) return null
        val isAttr = if (value.startsWith("@")) {
            if (value.startsWith("@+")) {
                throw IllegalArgumentException("Resource creation is not supported at runtime: $value")
            }

            false
        } else if (value.startsWith("?")) {
            true
        } else {
            return null
        }

        val token = value.substring(1)
        if (token == "null") return 0 compileTo TypedValue.TYPE_NULL

        val colon = token.indexOf(':')
        var resource = token
        var packageName: String? = null
        if (colon >= 0) {
            resource = token.substring(colon + 1)
            packageName = token.substring(0, colon)
        }

        val slash = resource.indexOf('/')
        val resType: String
        val dataType: Int
        if (isAttr) {
            resType = "attr"
            dataType = TypedValue.TYPE_ATTRIBUTE
        } else {
            if (slash !in 1..resource.lastIndex) return null
            resType = resource.substring(0, slash)
            dataType = TypedValue.TYPE_REFERENCE
        }

        val name = resource.substring(slash + 1)
        val id = context.resolveIdentifier(name, resType, packageName)
        return id compileTo dataType
    }

    private fun parseEnum(context: Context, attributeId: Int, value: String): CompiledValue? {
        val resources = context.resources
        val assets = resources.assets
        // 使用隐藏API来查找bag中的枚举项定义
        // 注意此方法本身是用于获取主题中的属性值
        // 但在这里也能够用来读取枚举列表
        val keys = attrsMethod.call<IntArray>(assets, attributeId)

        val attrs = mutableMapOf<String, Int>()
        for (key in keys) {
            val name = try {
                resources.getResourceEntryName(key)
            } catch (_: Exception) {
                continue
            }
            val value = bagValueMethod.call<CharSequence>(
                assets,
                attributeId,
                key
            ).toString().toIntOrNull() ?: continue
            attrs[name] = value
        }
        if (attrs.isEmpty()) {
            return null
        }

        val data = value.split('|').fold(0) { acc, part ->
            val enumValue = attrs[part.trim()]
                ?: throw IllegalArgumentException("Unknown enum value '$part' for attribute ID $attributeId")
            acc or enumValue
        }
        return data compileTo TypedValue.TYPE_INT_DEC
    }

    private fun parseBoolean(value: String): CompiledValue? {
        val booleanValue = value.lowercase().toBooleanStrictOrNull() ?: return null
        val data = if (booleanValue) 1 else 0
        return data compileTo TypedValue.TYPE_INT_BOOLEAN
    }

    private fun parseColor(value: String): CompiledValue? {
        if (!value.startsWith('#')) return null
        val digits = value.substring(1)
            .map { it.digitToIntOrNull(16) ?: return null }
            .toIntArray()

        fun Int.expandHex() = (this shl 4) or this

        fun IntArray.toIntHex(): Int {
            var hex = 0L
            for (digit in this) {
                hex = (hex shl 4) or digit.toLong()
            }
            return hex.toInt()
        }

        val data = when (digits.size) {
            3 -> (0xFF shl 24) or
                    (digits[0].expandHex() shl 16) or
                    (digits[1].expandHex() shl 8) or
                    (digits[2].expandHex())

            4 -> (digits[0].expandHex() shl 24) or
                    (digits[1].expandHex() shl 16) or
                    (digits[2].expandHex() shl 8) or
                    (digits[3].expandHex())

            6 -> 0xFF000000.toInt() or digits.toIntHex()

            8 -> digits.toIntHex()

            else -> return null
        }
        return data compileTo TypedValue.TYPE_INT_COLOR_ARGB8
    }

    private fun parseDimension(value: String): CompiledValue? {
        val (suffix, unit) = unitMapping.entries
            .firstOrNull { value.endsWith(it.key) }
            ?: return null
        val number = value.dropLast(suffix.length)
            .trim()
            .toFloatOrNull()
            ?.takeIf { it.isFinite() }
            ?: return null
        val complex = encodeComplex(number, unit)
        return complex compileTo TypedValue.TYPE_DIMENSION
    }

    private fun parseFraction(value: String): CompiledValue? {
        val unit: Int
        val number = when {
            value.endsWith("%") -> {
                unit = TypedValue.COMPLEX_UNIT_FRACTION
                value.dropLast(1)
            }
            value.endsWith("%p") -> {
                unit = TypedValue.COMPLEX_UNIT_FRACTION_PARENT
                value.dropLast(2)
            }
            else -> return null
        }
        val percentage = number.trim()
            .toFloatOrNull()
            ?.takeIf { it.isFinite() }
            ?: return null

        // 应将百分数存储为比例数值
        // 因此50%将被编码为0.5 而不是50
        val ratio = percentage / 100f
        val complex = encodeComplex(ratio, unit)
        return complex compileTo TypedValue.TYPE_FRACTION
    }

    private fun parseInt(value: String): CompiledValue? {
        val intValue = value.toIntOrNull() ?: return null
        return intValue compileTo TypedValue.TYPE_INT_DEC
    }

    private fun parseFloat(value: String): CompiledValue? {
        val floatValue = value.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        return floatValue.toRawBits() compileTo TypedValue.TYPE_FLOAT
    }

    private fun parseHex(value: String): CompiledValue? {
        if (!value.startsWith("0x", true)) {
            return null
        }

        val longValue = value.lowercase()
            .drop(2)
            .toLongOrNull(16)
            ?: return null
        return longValue.toInt() compileTo TypedValue.TYPE_INT_HEX
    }
}
