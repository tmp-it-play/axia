@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package team.themoment.axia.desktop.security

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/** Generic-password items in the user's local, encrypted login Keychain. */
class MacKeychainCredentialStore(
    private val service: String,
) : SecureCredentialStore {
    private val security = Native.load(SECURITY_FRAMEWORK, SecurityApi::class.java)
    private val core = Native.load(CORE_FRAMEWORK, CoreApi::class.java)
    private val securityLibrary = NativeLibrary.getInstance(SECURITY_FRAMEWORK)
    private val coreLibrary = NativeLibrary.getInstance(CORE_FRAMEWORK)

    override fun read(): String? =
        withQuery { query, owned ->
            core.CFDictionarySetValue(query, constant("kSecReturnData"), coreConstant("kCFBooleanTrue"))
            core.CFDictionarySetValue(query, constant("kSecMatchLimit"), constant("kSecMatchLimitOne"))
            val output = PointerByReference()
            when (val status = security.SecItemCopyMatching(query, output)) {
                ITEM_NOT_FOUND -> {
                    null
                }

                SUCCESS -> {
                    val data = output.value ?: throw CredentialStoreException("read")
                    owned += data
                    val length = core.CFDataGetLength(data).toLong()
                    if (length !in 1..MAXIMUM_CREDENTIAL_BYTES) throw CredentialStoreException("read")
                    val bytes = core.CFDataGetBytePtr(data).getByteArray(0, length.toInt())
                    try {
                        bytes.toString(Charsets.UTF_8)
                    } finally {
                        bytes.fill(0)
                    }
                }

                else -> {
                    throw CredentialStoreException("read", status)
                }
            }
        }

    override fun write(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..MAXIMUM_CREDENTIAL_BYTES) throw CredentialStoreException("write")
        try {
            withQuery { query, owned ->
                val attributes = dictionary().also { owned += it }
                val data = core.CFDataCreate(null, bytes, NativeLong(bytes.size.toLong())).also { owned += it }
                core.CFDictionarySetValue(attributes, constant("kSecValueData"), data)
                val updated = security.SecItemUpdate(query, attributes)
                if (updated == ITEM_NOT_FOUND) {
                    core.CFDictionarySetValue(query, constant("kSecValueData"), data)
                    // Local login Keychain only; never synchronize a Desktop session with iCloud.
                    core.CFDictionarySetValue(query, constant("kSecAttrSynchronizable"), coreConstant("kCFBooleanFalse"))
                    val added = security.SecItemAdd(query, null)
                    if (added != SUCCESS) throw CredentialStoreException("write", added)
                } else if (updated != SUCCESS) {
                    throw CredentialStoreException("write", updated)
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    override fun delete() {
        withQuery { query, _ ->
            val status = security.SecItemDelete(query)
            if (status != SUCCESS && status != ITEM_NOT_FOUND) throw CredentialStoreException("delete", status)
        }
    }

    private fun <T> withQuery(block: (Pointer, MutableList<Pointer>) -> T): T {
        val owned = mutableListOf<Pointer>()
        try {
            val query = dictionary().also { owned += it }
            val serviceName = string(service).also { owned += it }
            val accountName = string("desktop-session").also { owned += it }
            core.CFDictionarySetValue(query, constant("kSecClass"), constant("kSecClassGenericPassword"))
            core.CFDictionarySetValue(query, constant("kSecAttrService"), serviceName)
            core.CFDictionarySetValue(query, constant("kSecAttrAccount"), accountName)
            return block(query, owned)
        } finally {
            owned.asReversed().forEach(core::CFRelease)
        }
    }

    private fun dictionary(): Pointer =
        core.CFDictionaryCreateMutable(
            null,
            NativeLong(0),
            coreLibrary.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"),
            coreLibrary.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks"),
        )

    private fun string(value: String): Pointer {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return core.CFStringCreateWithBytes(null, bytes, NativeLong(bytes.size.toLong()), UTF8_ENCODING, false)
    }

    private fun constant(name: String): Pointer = securityLibrary.getGlobalVariableAddress(name).getPointer(0)

    private fun coreConstant(name: String): Pointer = coreLibrary.getGlobalVariableAddress(name).getPointer(0)

    private interface SecurityApi : Library {
        fun SecItemCopyMatching(
            query: Pointer,
            result: PointerByReference,
        ): Int

        fun SecItemUpdate(
            query: Pointer,
            attributes: Pointer,
        ): Int

        fun SecItemAdd(
            attributes: Pointer,
            result: PointerByReference?,
        ): Int

        fun SecItemDelete(query: Pointer): Int
    }

    private interface CoreApi : Library {
        fun CFDictionaryCreateMutable(
            allocator: Pointer?,
            capacity: NativeLong,
            keyCallbacks: Pointer,
            valueCallbacks: Pointer,
        ): Pointer

        fun CFDictionarySetValue(
            dictionary: Pointer,
            key: Pointer,
            value: Pointer,
        )

        fun CFStringCreateWithBytes(
            allocator: Pointer?,
            bytes: ByteArray,
            length: NativeLong,
            encoding: Int,
            external: Boolean,
        ): Pointer

        fun CFDataCreate(
            allocator: Pointer?,
            bytes: ByteArray,
            length: NativeLong,
        ): Pointer

        fun CFDataGetLength(data: Pointer): NativeLong

        fun CFDataGetBytePtr(data: Pointer): Pointer

        fun CFRelease(value: Pointer)
    }

    private companion object {
        const val SECURITY_FRAMEWORK = "/System/Library/Frameworks/Security.framework/Security"
        const val CORE_FRAMEWORK = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
        const val UTF8_ENCODING = 0x08000100
        const val SUCCESS = 0
        const val ITEM_NOT_FOUND = -25300
        const val MAXIMUM_CREDENTIAL_BYTES = 2560L
    }
}
