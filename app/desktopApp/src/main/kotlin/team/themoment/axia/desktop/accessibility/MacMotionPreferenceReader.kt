package team.themoment.axia.desktop.accessibility

import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.lang.ref.Reference

internal class MacMotionPreferenceReader(
    private val api: MacMotionApi = JnaMacMotionApi(),
) : DesktopMotionPreferenceReader {
    override fun readReducedMotion(): Boolean =
        nativeMotionCall("read macOS reduced motion") {
            val poolClass = api.findClass("NSAutoreleasePool") ?: throw DesktopMotionPreferenceException("find autorelease pool")
            val pool = api.sendObject(poolClass, api.selector("new")) ?: throw DesktopMotionPreferenceException("create autorelease pool")
            // The polling caller may be a JVM worker thread without an AppKit autorelease pool.
            try {
                val workspaceClass = api.findClass("NSWorkspace") ?: throw DesktopMotionPreferenceException("find NSWorkspace")
                val workspace =
                    api.sendObject(workspaceClass, api.selector("sharedWorkspace"))
                        ?: throw DesktopMotionPreferenceException("read shared NSWorkspace")
                val reducedMotion = api.selector("accessibilityDisplayShouldReduceMotion")
                if (!api.sendBoolean(workspace, api.selector("respondsToSelector:"), reducedMotion)) {
                    throw DesktopMotionPreferenceException("unsupported macOS reduced-motion selector")
                }
                api.sendBoolean(workspace, reducedMotion)
            } finally {
                api.sendVoid(pool, api.selector("drain"))
            }
        }
}

internal interface MacMotionApi {
    fun findClass(name: String): Pointer?

    fun selector(name: String): Pointer

    fun sendObject(
        receiver: Pointer,
        selector: Pointer,
    ): Pointer?

    fun sendBoolean(
        receiver: Pointer,
        selector: Pointer,
        argument: Pointer? = null,
    ): Boolean

    fun sendVoid(
        receiver: Pointer,
        selector: Pointer,
    )
}

private class JnaMacMotionApi : MacMotionApi {
    private val appKit = NativeLibrary.getInstance("/System/Library/Frameworks/AppKit.framework/AppKit")
    private val runtime = NativeLibrary.getInstance("/usr/lib/libobjc.A.dylib")
    private val getClass = runtime.getFunction("objc_getClass")
    private val registerSelector = runtime.getFunction("sel_registerName")
    private val sendMessage = runtime.getFunction("objc_msgSend")

    override fun findClass(name: String): Pointer? =
        try {
            getClass.invokePointer(arrayOf(name))
        } finally {
            // Retain AppKit for this reader's lifetime; class lookup alone does not retain the library.
            Reference.reachabilityFence(appKit)
        }

    override fun selector(name: String): Pointer =
        registerSelector.invokePointer(arrayOf(name)) ?: throw DesktopMotionPreferenceException("resolve Objective-C selector")

    override fun sendObject(
        receiver: Pointer,
        selector: Pointer,
    ): Pointer? = sendMessage.invokePointer(arrayOf(receiver, selector))

    override fun sendBoolean(
        receiver: Pointer,
        selector: Pointer,
        argument: Pointer?,
    ): Boolean {
        val arguments = if (argument == null) arrayOf(receiver, selector) else arrayOf(receiver, selector, argument)
        // Objective-C BOOL is one byte on the supported macOS ABI, unlike Win32/JNA's four-byte BOOL.
        return (sendMessage.invoke(Byte::class.javaPrimitiveType, arguments) as Byte).toInt() != 0
    }

    override fun sendVoid(
        receiver: Pointer,
        selector: Pointer,
    ) {
        sendMessage.invokeVoid(arrayOf(receiver, selector))
    }
}
