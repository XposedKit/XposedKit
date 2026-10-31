package cc.meteormc.xposedkit.support

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetManager
import android.content.res.StringBlock
import android.content.res.XmlBlock
import android.content.res.XmlResourceParser
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import androidx.annotation.CallSuper
import cc.meteormc.xposedkit.XLog
import cc.meteormc.xposedkit.allocate
import cc.meteormc.xposedkit.call
import cc.meteormc.xposedkit.new
import cc.meteormc.xposedkit.put
import cc.meteormc.xposedkit.reflect
import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalUnsignedTypes::class)
object RuntimeXmlResource {
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

    private val nativeStateClass: Class<*>?
    private val xmlBlockClass: Class<*>
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
                stateField = field("mState")
                nestedClass("NativeState")!!.reflect {
                    nativeStateClass = type
                    openCountField = field("mOpenCount")!!
                }
            } else {
                stateField = null
                nativeStateClass = null
                openCountField = field("mOpenCount")!!
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
                val parsedValue = parseValue(context, attributeId)
                valueData = parsedValue.data
                valueType = parsedValue.type
            }
        }

        fun check() {
            require(name != null) { "Attribute name must not be null" }
            require(value != null) { "Attribute value must not be null" }
            require(attributeId != 0) { "Attribute ID must not be 0" }
            require(valueData != null) { "Value data must not be null" }
            require(valueType != null) { "Value type must not be null" }
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
        xml.check()

        val axml = compileAXML(xml)
        XLog.d("RuntimeXmlResource", axml.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) })
        val parseState = /*NativeBridge.CreateXmlParseState(axml)*/0L
        val xmlBlock = xmlBlockClass.allocate().apply {
            val stringBlock = StringBlock(stringBlockMethod.call(null, parseState), false)
            stringsField.put(this, stringBlock)

            // 当 openCount 归零后会触发 XmlBlock.close() 的内部逻辑
            // 并且因为 XmlBlock 是一个未初始化的不稳定对象 导致这个操作必定会引发崩溃
            // 由于每次创建 XmlBlock.Parser 时都会累加 mOpenCount 字段
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

    fun XmlResourceParser.asAttributeSet(): AttributeSet {
        do {
            if (eventType != XmlResourceParser.START_TAG) continue
            return this
        } while (next() != XmlResourceParser.END_DOCUMENT)
        throw IllegalStateException("No start tag found in the XML parser")
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

    private data class ParsedValue(val data: Int, val type: Int)

    private infix fun Int.parseTo(type: Int): ParsedValue {
        return ParsedValue(this, type)
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

    private fun XmlAttribute.parseValue(context: Context, attributeId: Int): ParsedValue {
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
        return 0 parseTo TypedValue.TYPE_STRING
    }

    private fun parseReference(context: Context, value: String): ParsedValue? {
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
        if (token == "null") return 0 parseTo TypedValue.TYPE_NULL

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
        return id parseTo dataType
    }

    private fun parseEnum(context: Context, attributeId: Int, value: String): ParsedValue? {
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
        return data parseTo TypedValue.TYPE_INT_DEC
    }

    private fun parseBoolean(value: String): ParsedValue? {
        val booleanValue = value.lowercase().toBooleanStrictOrNull() ?: return null
        val data = if (booleanValue) 1 else 0
        return data parseTo TypedValue.TYPE_INT_BOOLEAN
    }

    private fun parseColor(value: String): ParsedValue? {
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
        return data parseTo TypedValue.TYPE_INT_COLOR_ARGB8
    }

    private fun parseDimension(value: String): ParsedValue? {
        val (suffix, unit) = unitMapping.entries
            .firstOrNull { value.endsWith(it.key) }
            ?: return null
        val number = value.dropLast(suffix.length)
            .trim()
            .toFloatOrNull()
            ?.takeIf { it.isFinite() }
            ?: return null
        val complex = encodeComplex(number, unit)
        return complex parseTo TypedValue.TYPE_DIMENSION
    }

    private fun parseFraction(value: String): ParsedValue? {
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
        return complex parseTo TypedValue.TYPE_FRACTION
    }

    private fun parseInt(value: String): ParsedValue? {
        val intValue = value.toIntOrNull() ?: return null
        return intValue parseTo TypedValue.TYPE_INT_DEC
    }

    private fun parseFloat(value: String): ParsedValue? {
        val floatValue = value.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        return floatValue.toRawBits() parseTo TypedValue.TYPE_FLOAT
    }

    private fun parseHex(value: String): ParsedValue? {
        if (!value.startsWith("0x", true)) {
            return null
        }

        val longValue = value.lowercase()
            .drop(2)
            .toLongOrNull(16)
            ?: return null
        return longValue.toInt() parseTo TypedValue.TYPE_INT_HEX
    }

    private val EMPTY_REF = ResStringPool.Ref((-1).toUInt())
    private const val BASE_HEADER_SIZE = 2 + 2 + 4

    private interface Serializer {
        val bodySize: Int

        fun serialize(out: DataOutputStream)

        fun DataOutputStream.align4() {
            while (size() % 4 != 0) { write(0) }
        }

        fun DataOutputStream.writeUByte(value: UByte) {
            writeByte(value.toInt())
        }

        fun DataOutputStream.writeUShort(value: UShort) {
            writeShort(value.toInt())
        }

        fun DataOutputStream.writeUInt(value: UInt) {
            writeInt(value.toInt())
        }

        fun DataOutputStream.writeULong(value: ULong) {
            writeLong(value.toLong())
        }
    }

    private class ResValue(
        val dataType: UByte, // uint8_t
        val data: UInt // uint32_t
    ) : Serializer {
        override val bodySize: Int
            get() = UShort.SIZE_BYTES + UInt.SIZE_BYTES

        override fun serialize(out: DataOutputStream) {
            out.writeUShort(8u) // size=2+1+1+4
            out.writeUByte(0.toUByte()) // Always set to 0
            out.writeUByte(dataType)
            out.writeUInt(data)
        }
    }

    private open class ResChunkHeader(
        val type: UShort, // uint16_t
        val headerSize: UShort, // uint16_t
        var size: UInt = 0u // uint32_t
    ) : Serializer {
        override val bodySize: Int
            get() = throw UnsupportedOperationException("ResChunkHeader does not have a body size")

        companion object {
            const val RES_NULL_TYPE: UShort = 0x0000u
            const val RES_STRING_POOL_TYPE: UShort = 0x0001u
            const val RES_TABLE_TYPE: UShort = 0x0002u
            const val RES_XML_TYPE: UShort = 0x0003u

            const val RES_XML_FIRST_CHUNK_TYPE: UShort = 0x0100u
            const val RES_XML_START_NAMESPACE_TYPE: UShort = 0x0100u
            const val RES_XML_END_NAMESPACE_TYPE: UShort = 0x0101u
            const val RES_XML_START_ELEMENT_TYPE: UShort = 0x0102u
            const val RES_XML_END_ELEMENT_TYPE: UShort = 0x0103u
            const val RES_XML_CDATA_TYPE: UShort = 0x0104u
            const val RES_XML_LAST_CHUNK_TYPE: UShort = 0x017fu
            const val RES_XML_RESOURCE_MAP_TYPE: UShort = 0x0180u

            const val RES_TABLE_PACKAGE_TYPE: UShort = 0x0200u
            const val RES_TABLE_TYPE_TYPE: UShort = 0x0201u
            const val RES_TABLE_TYPE_SPEC_TYPE: UShort = 0x0202u
            const val RES_TABLE_LIBRARY_TYPE: UShort = 0x0203u
            const val RES_TABLE_OVERLAYABLE_TYPE: UShort = 0x0204u
            const val RES_TABLE_OVERLAYABLE_POLICY_TYPE: UShort = 0x0205u
            const val RES_TABLE_STAGED_ALIAS_TYPE: UShort = 0x0206u
        }

        @CallSuper
        override fun serialize(out: DataOutputStream) {
            out.writeUShort(type)
            out.writeUShort(headerSize)
            out.writeUInt(size)
        }

        fun resize(size: Int) {
            this.size = headerSize + size.toUInt()
        }
    }

    private class ResXMLTree(
        val header: Header,
        val strings: ResStringPool,
        val ids: ResIdPool,
        val nodes: Array<ResXMLTree.Node>
    ) : Serializer {
        override val bodySize: Int
            get() {
                var result = 0
                result += strings.bodySize
                result += ids.bodySize
                result += nodes.sumOf {
                    result = (result + 3) and -4
                    it.bodySize
                }
                return result
            }

        override fun serialize(out: DataOutputStream) {
            header.resize(bodySize)
            header.serialize(out)
            strings.serialize(out)
            ids.serialize(out)
            nodes.forEach {
                out.align4()
                it.serialize(out)
            }
        }

        class Header : ResChunkHeader(RES_XML_TYPE, BASE_HEADER_SIZE.toUShort())

        interface Ext : Serializer

        class Node(
            type: UShort,
            val comment: ResStringPool.Ref,
            val ext: Ext
        ) : Serializer {
            // 这里的headerSize包含Node中除Ext以外的所有数据的大小
            private val header = ResChunkHeader(type, (BASE_HEADER_SIZE + 4 + 4).toUShort())

            override val bodySize: Int
                get() = comment.bodySize + ext.bodySize

            override fun serialize(out: DataOutputStream) {
                header.resize(bodySize)
                header.serialize(out)
                out.writeUInt(0u) // lineNumber=0
                comment.serialize(out)
                ext.serialize(out)
            }
        }

        class NamespaceExt(
            val prefix: ResStringPool.Ref,
            val uri: ResStringPool.Ref
        ) : Ext {
            override val bodySize: Int
                get() = prefix.bodySize + uri.bodySize

            override fun serialize(out: DataOutputStream) {
                prefix.serialize(out)
                uri.serialize(out)
            }
        }

        class StartElementExt(
            val namespace: ResStringPool.Ref,
            val name: ResStringPool.Ref,
            val attributeCount: UShort, // uint16_t
            val idIndex: UShort, // uint16_t
            val classIndex: UShort, // uint16_t
            val styleIndex: UShort, // uint16_t
            val attributes: Array<Attribute>
        ) : Ext {
            override val bodySize: Int
                get() = namespace.bodySize + name.bodySize + UShort.SIZE_BYTES * 4 + attributes.sumOf { it.bodySize }

            override fun serialize(out: DataOutputStream) {
                namespace.serialize(out)
                name.serialize(out)
                out.writeUShort(20u) // attributeStart=4*2+2*6
                out.writeUShort(20u) // attributeSize=4*3+(2+1+1+4)
                out.writeUShort(attributeCount)
                out.writeUShort(idIndex)
                out.writeUShort(classIndex)
                out.writeUShort(styleIndex)
                attributes.forEach { it.serialize(out) }
            }
        }

        class EndElementExt(
            val namespace: ResStringPool.Ref,
            val name: ResStringPool.Ref
        ) : Ext {
            override val bodySize: Int
                get() = namespace.bodySize + name.bodySize

            override fun serialize(out: DataOutputStream) {
                namespace.serialize(out)
                name.serialize(out)
            }
        }

        class CdataExt(
            val data: ResStringPool.Ref,
            val typedData: ResValue
        ) : Ext {
            override val bodySize: Int
                get() = data.bodySize + typedData.bodySize

            override fun serialize(out: DataOutputStream) {
                data.serialize(out)
                typedData.serialize(out)
            }
        }

        class Attribute(
            val namespace: ResStringPool.Ref,
            val name: ResStringPool.Ref,
            val rawValue: ResStringPool.Ref,
            val typedValue: ResValue
        ) : Serializer {
            override val bodySize: Int
                get() {
                    var result = 0
                    result += namespace.bodySize
                    result += name.bodySize
                    result += rawValue.bodySize
                    result += typedValue.bodySize
                    return result
                }

            override fun serialize(out: DataOutputStream) {
                namespace.serialize(out)
                name.serialize(out)
                rawValue.serialize(out)
                typedValue.serialize(out)
            }
        }
    }

    private class ResStringPool(
        val header: Header,
        val stringOffsets: UIntArray, // uint32_t
        val styleOffsets: UIntArray, // uint32_t
        val strings: Array<StringData>,
        val styles: Array<StyleData>
    ) : Serializer {
        private var cachedSize = 0
        override val bodySize: Int
            get() = cachedSize.takeIf { it > 0 } ?: calculateLayout()

        override fun serialize(out: DataOutputStream) {
            header.resize(bodySize)
            header.serialize(out)
            stringOffsets.forEach { out.writeUInt(it) }
            styleOffsets.forEach { out.writeUInt(it) }
            strings.forEach { it.serialize(out) }
            out.align4()
            styles.forEach { it.serialize(out) }
            out.align4()
        }

        private fun calculateLayout(): Int {
            var size = 0
            val headerSize = header.headerSize
            fun align() {
                size = (size + 3) and -4
            }

            size += stringOffsets.size * UInt.SIZE_BYTES
            size += styleOffsets.size * UInt.SIZE_BYTES
            header.stringsStart = headerSize + size.toUInt()
            size += strings.sumOf { it.bodySize }
            align()
            header.stylesStart = headerSize + size.toUInt()
            size += styles.sumOf { it.bodySize }
            align()
            cachedSize = size
            return size
        }

        class Header(
            val stringCount: UInt, // uint32_t
            val styleCount: UInt, // uint32_t
            val flags: UInt, // uint32_t
            var stringsStart: UInt = 0u, // uint32_t
            var stylesStart: UInt = 0u // uint32_t
        ) : ResChunkHeader(RES_STRING_POOL_TYPE, (BASE_HEADER_SIZE + 4 * 5).toUShort()) {
            override fun serialize(out: DataOutputStream) {
                super.serialize(out)
                out.writeUInt(stringCount)
                out.writeUInt(styleCount)
                out.writeUInt(flags)
                out.writeUInt(stringsStart)
                out.writeUInt(stylesStart)
            }

            object Flags {
                const val SORTED_FLAG = 1u // 1 << 0
                const val UTF8_FLAG = 256u // 1 << 8
            }
        }

        class Ref(
            val index: UInt // uint32_t
        ) : Serializer {
            override val bodySize: Int
                get() = UInt.SIZE_BYTES

            override fun serialize(out: DataOutputStream) {
                out.writeUInt(index)
            }
        }

        class StringData(value: String) : Serializer {
            private val data: ByteArray

            override val bodySize: Int
                get() = data.size

            init {
                val utf8 = value.toByteArray(Charsets.UTF_8)
                val len16 = writeLength(value.length) // UTF-16 length
                val len8 = writeLength(utf8.size) // UTF-8 length

                val offset1 = len16.size
                val offset2 = offset1 + len8.size
                val offset3 = offset2 + utf8.size
                data = ByteArray(offset3 + 1)
                len16.copyInto(data)
                len8.copyInto(data, offset1)
                utf8.copyInto(data, offset2)
                data[data.lastIndex] = 0x00
            }

            override fun serialize(out: DataOutputStream) {
                out.write(data)
            }

            private fun writeLength(length: Int): ByteArray {
                require(length in 0..0x7FFF) {
                    "Length out of range: $length"
                }

                return if (length < 0x80) {
                    byteArrayOf(length.toByte())
                } else {
                    byteArrayOf(
                        ((length shr 8) or 0x80).toByte(),
                        (length and 0xFF).toByte()
                    )
                }
            }
        }

        class StyleData : Serializer {
            override val bodySize: Int
                get() = 0

            override fun serialize(out: DataOutputStream) {

            }
        }
    }

    private class ResIdPool(
        val header: Header,
        val resourceIds: UIntArray // uint32_t
    ) : Serializer {
        override val bodySize: Int
            get() = resourceIds.size * UInt.SIZE_BYTES

        override fun serialize(out: DataOutputStream) {
            header.resize(bodySize)
            header.serialize(out)
            resourceIds.forEach { out.writeUInt(it) }
        }

        class Header : ResChunkHeader(RES_XML_RESOURCE_MAP_TYPE, BASE_HEADER_SIZE.toUShort())
    }

    private fun compileAXML(xml: XmlElement): ByteArray {
        val strings = arrayListOf<ResStringPool.StringData>()
        val stringIndex = HashMap<String, ResStringPool.Ref>()
        fun compileString(str: String): ResStringPool.Ref {
            return stringIndex.getOrPut(str) {
                val idx = strings.size
                strings.add(ResStringPool.StringData(str))
                ResStringPool.Ref(idx.toUInt())
            }
        }

        val resources = arrayListOf<UInt>()
        fun compileResource(name: String, id: UInt): ResStringPool.Ref {
            val ref = compileString(name)
            val idx = ref.index.toInt()
            while (resources.size <= idx) { resources.add(0u) }
            resources[idx] = id
            return ref
        }

        val namespaces = arrayListOf<ResXMLTree.NamespaceExt>()
        val namespaceIndex = HashMap<String, ResStringPool.Ref>()
        fun compileNamespace(ns: String): ResStringPool.Ref {
            return namespaceIndex.getOrPut(ns) {
                val prefix = "ns${namespaces.size}"
                val ref = compileString(prefix)
                namespaces.add(ResXMLTree.NamespaceExt(ref, compileString(ns)))
                ref
            }
        }

        val nodes = arrayListOf<ResXMLTree.Node>()
        fun handleElement(element: XmlElement) {
            val name = compileString(element.name!!)
            val attributes = element.attributes.map {
                val namespace = it.namespace?.let { ns -> compileNamespace(ns) }
                val dataType = it.valueType!!
                val data = it.valueData!!
                ResXMLTree.Attribute(
                    namespace = namespace ?: EMPTY_REF,
                    name = compileResource(it.name!!, it.attributeId.toUInt()),
                    rawValue = compileString(it.value!!),
                    typedValue = ResValue(dataType.toUByte(), data.toUInt())
                )
            }.toTypedArray()
            nodes += ResXMLTree.Node(
                type = ResChunkHeader.RES_XML_START_ELEMENT_TYPE,
                comment = EMPTY_REF,
                ext = ResXMLTree.StartElementExt(
                    namespace = EMPTY_REF,
                    name = name,
                    attributeCount = attributes.size.toUShort(),
                    idIndex = 0u,
                    classIndex = 0u,
                    styleIndex = 0u,
                    attributes = attributes
                )
            )

            element.children.forEach(::handleElement)
            nodes += ResXMLTree.Node(
                type = ResChunkHeader.RES_XML_END_ELEMENT_TYPE,
                comment = EMPTY_REF,
                ext = ResXMLTree.EndElementExt(
                    namespace = EMPTY_REF,
                    name = name
                )
            )
        }
        handleElement(xml)

        var stringOffset = 0u
        val tree = ResXMLTree(
            header = ResXMLTree.Header(),
            strings = ResStringPool(
                ResStringPool.Header(
                    flags = ResStringPool.Header.Flags.UTF8_FLAG,
                    stringCount = strings.size.toUInt(),
                    styleCount = 0u
                ),
                stringOffsets = UIntArray(strings.size) {
                    val off = stringOffset
                    stringOffset += strings[it].bodySize.toUInt()
                    off
                },
                styleOffsets = uintArrayOf(),
                strings = strings.toTypedArray(),
                styles = emptyArray()
            ),
            ids = ResIdPool(
                header = ResIdPool.Header(),
                resourceIds = resources.toUIntArray()
            ),
            nodes = buildList {
                namespaces.forEach {
                    ResXMLTree.Node(
                        type = ResChunkHeader.RES_XML_START_NAMESPACE_TYPE,
                        comment = EMPTY_REF,
                        ext = it
                    )
                }
                addAll(nodes)
                namespaces.asReversed().forEach {
                    ResXMLTree.Node(
                        type = ResChunkHeader.RES_XML_END_NAMESPACE_TYPE,
                        comment = EMPTY_REF,
                        ext = it
                    )
                }
            }.toTypedArray()
        )
        return ByteArrayOutputStream().use {
            val out = DataOutputStream(it)
            tree.serialize(out)
            it.toByteArray()
        }
    }
}
