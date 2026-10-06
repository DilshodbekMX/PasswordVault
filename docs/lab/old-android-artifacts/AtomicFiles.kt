package com.example.pvlab.old

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream

/** Crash-safe replacement of a small file: write temp, fsync it, rename over target, fsync dir. */
object AtomicFiles {
    fun write(dir: File, name: String, bytes: ByteArray) {
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync() // fsync(2) of the file data
        }
        Os.rename(tmp.path, target.path) // rename(2): atomically replaces an existing target
        fsyncDir(dir) // makes the rename itself durable
    }

    fun fsyncDir(dir: File) {
        val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }
}
