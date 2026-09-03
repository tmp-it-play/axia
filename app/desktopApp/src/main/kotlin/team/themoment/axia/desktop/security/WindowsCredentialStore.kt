@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package team.themoment.axia.desktop.security

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

/** CRED_TYPE_GENERIC credential, persisted for the current user on this machine. */
class WindowsCredentialStore(
    private val target: String,
) : SecureCredentialStore {
    private val api = Native.load("Advapi32", CredentialApi::class.java)

    override fun read(): String? {
        val output = PointerByReference()
        if (!api.CredReadW(WString(target), GENERIC, 0, output)) {
            val error = Native.getLastError()
            if (error == NOT_FOUND) return null
            throw CredentialStoreException("read", error)
        }
        val pointer = output.value ?: throw CredentialStoreException("read")
        try {
            val credential = Credential(pointer)
            if (credential.CredentialBlobSize !in 1..MAXIMUM_CREDENTIAL_BYTES) throw CredentialStoreException("read")
            val blob = credential.CredentialBlob ?: throw CredentialStoreException("read")
            val bytes = blob.getByteArray(0, credential.CredentialBlobSize)
            return try {
                bytes.toString(Charsets.UTF_8)
            } finally {
                bytes.fill(0)
            }
        } finally {
            api.CredFree(pointer)
        }
    }

    override fun write(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..MAXIMUM_CREDENTIAL_BYTES) throw CredentialStoreException("write")
        val memory = Memory(bytes.size.toLong())
        try {
            memory.write(0, bytes, 0, bytes.size)
            val credential =
                Credential().apply {
                    Type = GENERIC
                    TargetName = WString(target)
                    CredentialBlobSize = bytes.size
                    CredentialBlob = memory
                    Persist = LOCAL_MACHINE
                    UserName = WString("Axia Desktop")
                    write()
                }
            if (!api.CredWriteW(credential, 0)) throw CredentialStoreException("write", Native.getLastError())
        } finally {
            bytes.fill(0)
            memory.clear()
            memory.close()
        }
    }

    override fun delete() {
        if (!api.CredDeleteW(WString(target), GENERIC, 0)) {
            val error = Native.getLastError()
            if (error != NOT_FOUND) throw CredentialStoreException("delete", error)
        }
    }

    private interface CredentialApi : StdCallLibrary {
        fun CredReadW(
            targetName: WString,
            type: Int,
            flags: Int,
            credential: PointerByReference,
        ): Boolean

        fun CredWriteW(
            credential: Credential,
            flags: Int,
        ): Boolean

        fun CredDeleteW(
            targetName: WString,
            type: Int,
            flags: Int,
        ): Boolean

        fun CredFree(buffer: Pointer)
    }

    @Suppress("PropertyName", "ktlint:standard:property-naming")
    @Structure.FieldOrder(
        "Flags",
        "Type",
        "TargetName",
        "Comment",
        "LastWritten",
        "CredentialBlobSize",
        "CredentialBlob",
        "Persist",
        "AttributeCount",
        "Attributes",
        "TargetAlias",
        "UserName",
    )
    class Credential : Structure {
        @JvmField var Flags: Int = 0

        @JvmField var Type: Int = 0

        @JvmField var TargetName: WString? = null

        @JvmField var Comment: WString? = null

        @JvmField var LastWritten: WinBase.FILETIME = WinBase.FILETIME()

        @JvmField var CredentialBlobSize: Int = 0

        @JvmField var CredentialBlob: Pointer? = null

        @JvmField var Persist: Int = 0

        @JvmField var AttributeCount: Int = 0

        @JvmField var Attributes: Pointer? = null

        @JvmField var TargetAlias: WString? = null

        @JvmField var UserName: WString? = null

        constructor() : super()

        constructor(pointer: Pointer) : super(pointer) {
            read()
        }
    }

    private companion object {
        const val GENERIC = 1
        const val LOCAL_MACHINE = 2
        const val NOT_FOUND = 1168
        const val MAXIMUM_CREDENTIAL_BYTES = 2560
    }
}
