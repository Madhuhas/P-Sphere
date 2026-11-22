package com.psphere.desktop.engine

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.nio.charset.StandardCharsets.UTF_16LE

/**
 * The standard Windows folder picker (the one File Explorer uses), called directly through
 * Windows' COM interface with Java's foreign-function support, so no extra library is needed.
 *
 * [pick] returns the chosen folder, or null if the user cancelled. It throws if the picker can't
 * be shown at all (not Windows, a Java runtime without this support, any COM error), so callers
 * can fall back to the Swing chooser.
 */
object WindowsFolderPicker {
    private const val CLSCTX_INPROC_SERVER = 1
    private const val COINIT_APARTMENTTHREADED = 2
    private const val FOS_PICKFOLDERS = 0x20
    private const val FOS_FORCEFILESYSTEM = 0x40
    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()
    private const val ERROR_CANCELLED = 0x800704C7.toInt()

    // IUnknown 0-2, IModalWindow.Show 3, then IFileDialog's methods in declaration order.
    private const val RELEASE = 2
    private const val SHOW = 3
    private const val SET_OPTIONS = 9
    private const val GET_OPTIONS = 10
    private const val SET_FOLDER = 12
    private const val SET_TITLE = 17
    private const val GET_RESULT = 20
    private const val ITEM_GET_DISPLAY_NAME = 5

    fun pick(title: String, startAt: String?): String? {
        check(System.getProperty("os.name").startsWith("Windows")) { "Not Windows" }
        // COM wants a thread of its own (single-threaded apartment); the dialog runs its own message loop.
        var result: Result<String?> = Result.failure(IllegalStateException("no result"))
        val t = Thread({ result = runCatching { pickHere(title, startAt) } }, "psphere-folder-picker")
        t.isDaemon = true
        t.start()
        waitKeepingUiAlive(t)
        return result.getOrThrow()
    }

    /** Waits for [t]; on the UI thread, keeps repainting the app meanwhile, like a modal dialog would. */
    private fun waitKeepingUiAlive(t: Thread) {
        if (!java.awt.EventQueue.isDispatchThread()) { t.join(); return }
        val loop = java.awt.Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        Thread({ t.join(); loop.exit() }, "psphere-folder-picker-wait").apply { isDaemon = true }.start()
        loop.enter()
    }

    private fun pickHere(title: String, startAt: String?): String? {
        val linker = Linker.nativeLinker()
        Arena.ofConfined().use { arena ->
            val ole32 = SymbolLookup.libraryLookup("ole32", arena)
            val shell32 = SymbolLookup.libraryLookup("shell32", arena)
            val user32 = SymbolLookup.libraryLookup("user32", arena)
            fun fn(lib: SymbolLookup, name: String, d: FunctionDescriptor): MethodHandle = linker.downcallHandle(lib.find(name).orElseThrow(), d)
            val coInitializeEx = fn(ole32, "CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
            val coUninitialize = fn(ole32, "CoUninitialize", FunctionDescriptor.ofVoid())
            val coCreateInstance = fn(ole32, "CoCreateInstance", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS))
            val coTaskMemFree = fn(ole32, "CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS))
            val shCreateItem = fn(shell32, "SHCreateItemFromParsingName", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
            val getForegroundWindow = fn(user32, "GetForegroundWindow", FunctionDescriptor.of(ADDRESS))
            // The window the user just clicked in (PSphere's): the picker opens in front of it and blocks it, like any dialog.
            val owner = getForegroundWindow.invokeWithArguments() as MemorySegment

            val init = coInitializeEx.invokeWithArguments(MemorySegment.NULL, COINIT_APARTMENTTHREADED) as Int
            try {
                val out = arena.allocate(ADDRESS)
                hr(coCreateInstance.invokeWithArguments(
                    guid(arena, 0xDC1C5A9C.toInt(), 0xE88A, 0x4DDE, 0xA5, 0xA1, 0x60, 0xF8, 0x2A, 0x20, 0xAE, 0xF7), // CLSID_FileOpenDialog
                    MemorySegment.NULL, CLSCTX_INPROC_SERVER,
                    guid(arena, 0xD57C7288.toInt(), 0xD4AD, 0x4768, 0xBE, 0x02, 0x9D, 0x96, 0x95, 0x32, 0xD9, 0x60), // IID_IFileOpenDialog
                    out,
                ) as Int, "CoCreateInstance")
                val dialog = out.get(ADDRESS, 0)
                try {
                    val opts = arena.allocate(JAVA_INT)
                    hr(call(linker, dialog, GET_OPTIONS, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), opts), "GetOptions")
                    hr(call(linker, dialog, SET_OPTIONS, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), opts.get(JAVA_INT, 0) or FOS_PICKFOLDERS or FOS_FORCEFILESYSTEM), "SetOptions")
                    call(linker, dialog, SET_TITLE, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), arena.allocateFrom(title, UTF_16LE))
                    if (startAt != null && java.io.File(startAt).isDirectory) {
                        val itemOut = arena.allocate(ADDRESS)
                        val iidShellItem = guid(arena, 0x43826D1E, 0xE718, 0x42EE, 0xBC, 0x55, 0xA1, 0xE2, 0x61, 0xC3, 0x7B, 0xFE)
                        if (shCreateItem.invokeWithArguments(arena.allocateFrom(startAt, UTF_16LE), MemorySegment.NULL, iidShellItem, itemOut) as Int == 0) {
                            val folder = itemOut.get(ADDRESS, 0)
                            call(linker, dialog, SET_FOLDER, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), folder)
                            release(linker, folder)
                        }
                    }
                    val shown = call(linker, dialog, SHOW, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), owner)
                    if (shown == ERROR_CANCELLED) return null
                    hr(shown, "Show")
                    hr(call(linker, dialog, GET_RESULT, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), out), "GetResult")
                    val item = out.get(ADDRESS, 0)
                    try {
                        val name = arena.allocate(ADDRESS)
                        hr(call(linker, item, ITEM_GET_DISPLAY_NAME, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS), SIGDN_FILESYSPATH, name), "GetDisplayName")
                        val text = name.get(ADDRESS, 0)
                        try {
                            return text.reinterpret(Long.MAX_VALUE).getString(0, UTF_16LE)
                        } finally {
                            coTaskMemFree.invokeWithArguments(text)
                        }
                    } finally {
                        release(linker, item)
                    }
                } finally {
                    release(linker, dialog)
                }
            } finally {
                if (init >= 0) coUninitialize.invokeWithArguments()
            }
        }
    }

    /** Calls method number [index] of the COM object [obj] (its vtable), passing [obj] first. */
    private fun call(linker: Linker, obj: MemorySegment, index: Int, d: FunctionDescriptor, vararg args: Any): Int {
        val vtable = obj.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0).reinterpret(ADDRESS.byteSize() * (index + 1))
        val fn = vtable.getAtIndex(ADDRESS, index.toLong())
        return linker.downcallHandle(fn, d).invokeWithArguments(obj, *args) as Int
    }

    private fun release(linker: Linker, obj: MemorySegment) {
        runCatching {
            val vtable = obj.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0).reinterpret(ADDRESS.byteSize() * (RELEASE + 1))
            linker.downcallHandle(vtable.getAtIndex(ADDRESS, RELEASE.toLong()), FunctionDescriptor.of(JAVA_INT, ADDRESS)).invokeWithArguments(obj)
        }
    }

    private fun hr(code: Int, what: String) { if (code < 0) throw IllegalStateException("$what failed (0x%08X)".format(code)) }

    private fun guid(arena: Arena, d1: Int, d2: Int, d3: Int, vararg d4: Int): MemorySegment {
        val g = arena.allocate(16, 8)
        g.set(JAVA_INT, 0, d1)
        g.set(JAVA_SHORT, 4, d2.toShort())
        g.set(JAVA_SHORT, 6, d3.toShort())
        d4.forEachIndexed { i, b -> g.set(JAVA_BYTE, 8L + i, b.toByte()) }
        return g
    }
}
