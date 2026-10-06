package pv.formats

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/**
 * "Encrypt with Password" for an .xlsx: ECMA-376 Agile Encryption ([MS-OFFCRYPTO] 2.3.4.10-2.3.4.15)
 * with the parameters Excel 2013 and later write: SHA-512, 100,000 spins, AES-256-CBC, 4096-byte
 * segments, HMAC-SHA512 data integrity. The result is a Compound File Binary container ([MS-CFB]
 * version 3, 512-byte sectors) holding EncryptionInfo, EncryptedPackage and the \u0006DataSpaces
 * storage, the same eleven directory entries Excel writes.
 */
object AgileEncryption {
    const val SPIN_COUNT = 100_000
    private const val SEGMENT = 4096
    private val BK_VERIFIER_INPUT = hex("fea7d2763b4b9e79")
    private val BK_VERIFIER_VALUE = hex("d7aa0f6d3061344e")
    private val BK_KEY_VALUE = hex("146e0be7abacd0d6")
    private val BK_HMAC_KEY = hex("5fb2ad010cb9e1f6")
    private val BK_HMAC_VALUE = hex("a0677f02b22c8433")

    fun encrypt(packageBytes: ByteArray, password: String, rnd: SecureRandom = SecureRandom()): ByteArray {
        require(password.isNotEmpty() && password.length <= 255) { "Excel accepts 1 to 255 characters" }
        fun random(n: Int) = ByteArray(n).also(rnd::nextBytes)
        val keyDataSalt = random(16); val passwordSalt = random(16)
        val secretKey = random(32); val verifierInput = random(16); val hmacKey = random(64)
        try {
            // EncryptedPackage: u64 LE plaintext size, then each 4096-byte segment encrypted with the
            // secret key and IV = SHA-512(keyData salt || u32 LE segment index)[0..16); the last segment is
            // zero-padded to the 16-byte block.
            val pkg = ByteArrayOutputStream(packageBytes.size + 64)
            pkg.write(le64(packageBytes.size.toLong()))
            var off = 0; var index = 0
            while (off < packageBytes.size) {
                val len = minOf(SEGMENT, packageBytes.size - off)
                val block = packageBytes.copyOfRange(off, off + len).let { if (len % 16 == 0) it else it.copyOf(len + 16 - len % 16) }
                pkg.write(aesCbc(secretKey, sha512(keyDataSalt, le32(index)).copyOf(16), block))
                off += len; index++
            }
            val encryptedPackage = pkg.toByteArray()

            // dataIntegrity: random 64-byte HMAC key; HMAC-SHA512 over the whole EncryptedPackage stream,
            // size field included; both encrypted with the secret key under block-key-derived IVs.
            val hmac = Mac.getInstance("HmacSHA512").run { init(SecretKeySpec(hmacKey, "HmacSHA512")); doFinal(encryptedPackage) }
            val encHmacKey = aesCbc(secretKey, sha512(keyDataSalt, BK_HMAC_KEY).copyOf(16), hmacKey)
            val encHmacValue = aesCbc(secretKey, sha512(keyDataSalt, BK_HMAC_VALUE).copyOf(16), hmac)

            // Password key encryptor: H0 = SHA-512(salt || UTF-16LE password), Hn = SHA-512(u32 LE n || Hn-1)
            // for n = 0..spinCount-1, key_b = SHA-512(Hfinal || blockKey_b)[0..32), IV = the salt.
            val pw = password.toByteArray(Charsets.UTF_16LE)
            var h = sha512(passwordSalt, pw)
            pw.fill(0)
            val md = MessageDigest.getInstance("SHA-512"); val counter = ByteArray(4)
            for (n in 0 until SPIN_COUNT) {
                counter[0] = n.toByte(); counter[1] = (n ushr 8).toByte(); counter[2] = (n ushr 16).toByte(); counter[3] = (n ushr 24).toByte()
                md.update(counter); md.update(h); h = md.digest()
            }
            fun key(blockKey: ByteArray) = sha512(h, blockKey).copyOf(32)
            val k1 = key(BK_VERIFIER_INPUT); val k2 = key(BK_VERIFIER_VALUE); val k3 = key(BK_KEY_VALUE)
            val encVerifierInput = aesCbc(k1, passwordSalt, verifierInput)
            val encVerifierHash = aesCbc(k2, passwordSalt, sha512(verifierInput))
            val encKeyValue = aesCbc(k3, passwordSalt, secretKey)
            listOf(h, k1, k2, k3).forEach { it.fill(0) }

            fun b64(b: ByteArray) = Base64.Default.encode(b)
            val params = "saltSize=\"16\" blockSize=\"16\" keyBits=\"256\" hashSize=\"64\" cipherAlgorithm=\"AES\" " +
                "cipherChaining=\"ChainingModeCBC\" hashAlgorithm=\"SHA512\""
            val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\r\n" +
                "<encryption xmlns=\"http://schemas.microsoft.com/office/2006/encryption\" " +
                "xmlns:p=\"http://schemas.microsoft.com/office/2006/keyEncryptor/password\" " +
                "xmlns:c=\"http://schemas.microsoft.com/office/2006/keyEncryptor/certificate\">" +
                "<keyData $params saltValue=\"${b64(keyDataSalt)}\"/>" +
                "<dataIntegrity encryptedHmacKey=\"${b64(encHmacKey)}\" encryptedHmacValue=\"${b64(encHmacValue)}\"/>" +
                "<keyEncryptors><keyEncryptor uri=\"http://schemas.microsoft.com/office/2006/keyEncryptor/password\">" +
                "<p:encryptedKey spinCount=\"$SPIN_COUNT\" $params saltValue=\"${b64(passwordSalt)}\" " +
                "encryptedVerifierHashInput=\"${b64(encVerifierInput)}\" encryptedVerifierHashValue=\"${b64(encVerifierHash)}\" " +
                "encryptedKeyValue=\"${b64(encKeyValue)}\"/></keyEncryptor></keyEncryptors></encryption>"
            // EncryptionInfo: version 4.4 (agile), reserved flags 0x40, then the XML.
            val encryptionInfo = byteArrayOf(4, 0, 4, 0, 0x40, 0, 0, 0) + xml.toByteArray(Charsets.UTF_8)

            return Cfb.write(listOf(
                Cfb.Node("EncryptionInfo", encryptionInfo),
                Cfb.Node("EncryptedPackage", encryptedPackage),
                Cfb.Node("\u0006DataSpaces", children = listOf(
                    Cfb.Node("Version", DS_VERSION),
                    Cfb.Node("DataSpaceMap", DS_MAP),
                    Cfb.Node("DataSpaceInfo", children = listOf(Cfb.Node("StrongEncryptionDataSpace", DS_INFO))),
                    Cfb.Node("TransformInfo", children = listOf(Cfb.Node("StrongEncryptionTransform",
                        children = listOf(Cfb.Node("\u0006Primary", DS_PRIMARY))))),
                )),
            ))
        } finally {
            secretKey.fill(0); hmacKey.fill(0)
        }
    }

    // [MS-OFFCRYPTO] 2.1.5-2.1.9 fixed streams; byte-identical to an Excel-written file (msoffcrypto-tool
    // tests/inputs/example_password.xlsx, read with olefile 2026-10-06).
    private fun lp(s: String): ByteArray { // UNICODE-LP-P4: u32 byte length, UTF-16LE, pad to 4
        val b = s.toByteArray(Charsets.UTF_16LE)
        return le32(b.size) + b + ByteArray((4 - b.size % 4) % 4)
    }
    private val DS_VERSION = lp("Microsoft.Container.DataSpaces") + le32(1) + le32(1) + le32(1)
    private val DS_MAP = le32(8) + le32(1) + run {
        val body = le32(1) + le32(0) + lp("EncryptedPackage") + lp("StrongEncryptionDataSpace")
        le32(body.size + 4) + body
    }
    private val DS_INFO = le32(8) + le32(1) + lp("StrongEncryptionTransform")
    private val DS_PRIMARY = run {
        // TransformInfoHeader: length of the bytes before TransformName, type 1, ID, name, versions 1.0 x3;
        // then EncryptionTransformInfo: empty name, block size 0, cipher mode 0, reserved 4.
        val id = lp("{FF9A3F03-56EF-4613-BDD5-5A41C1D07246}")
        le32(4 + 4 + id.size) + le32(1) + id + lp("Microsoft.Container.EncryptionTransform") +
            le32(1) + le32(1) + le32(1) + le32(0) + le32(0) + le32(0) + le32(4)
    }

    private fun aesCbc(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(data) }
    private fun sha512(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").run { parts.forEach(::update); digest() }
    internal fun le32(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
    private fun le64(v: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    internal fun fixedStreams() = mapOf("Version" to DS_VERSION, "DataSpaceMap" to DS_MAP, "StrongEncryptionDataSpace" to DS_INFO, "Primary" to DS_PRIMARY)
}

/**
 * Minimal Compound File Binary writer ([MS-CFB] version 3): 512-byte sectors, streams under 4096 bytes
 * in the mini stream (64-byte mini sectors), FAT plus DIFAT for any size, every sibling set as a
 * balanced binary search tree with all nodes black ("the simplest implementation", MS-CFB 2.6.4).
 */
object Cfb {
    class Node(val name: String, val data: ByteArray? = null, val children: List<Node> = emptyList())

    private const val SECTOR = 512
    private const val MINI = 64
    private const val CUTOFF = 4096
    private const val FREESECT = -1          // 0xFFFFFFFF, also NOSTREAM
    private const val ENDOFCHAIN = -2        // 0xFFFFFFFE
    private const val FATSECT = -3           // 0xFFFFFFFD
    private const val DIFSECT = -4           // 0xFFFFFFFC

    private class Dir(val name: String, val type: Int, val data: ByteArray?) {
        var left = FREESECT; var right = FREESECT; var child = FREESECT; var start = 0; var size = 0L
    }

    /** Sort order of [MS-CFB] 2.6.4: shorter names first, then UTF-16 code units compared upper-cased. */
    private val ORDER = Comparator<String> { a, b ->
        if (a.length != b.length) a.length - b.length
        else { var r = 0; for (i in a.indices) { r = a[i].uppercaseChar().compareTo(b[i].uppercaseChar()); if (r != 0) break }; r }
    }

    fun write(rootChildren: List<Node>): ByteArray {
        val dirs = ArrayList<Dir>()
        dirs.add(Dir("Root Entry", 5, null))
        fun addChildren(parent: Int, kids: List<Node>) {
            require(kids.map { it.name.uppercase() }.toSet().size == kids.size) { "duplicate names" }
            val sorted = kids.sortedWith { x, y -> ORDER.compare(x.name, y.name) }
            val ids = sorted.map { k ->
                require(k.name.length in 1..31)
                dirs.add(Dir(k.name, if (k.data == null) 1 else 2, k.data)); dirs.size - 1
            }
            fun tree(lo: Int, hi: Int): Int { // balanced BST over ids[lo, hi)
                if (lo >= hi) return FREESECT
                val mid = (lo + hi) / 2
                dirs[ids[mid]].left = tree(lo, mid); dirs[ids[mid]].right = tree(mid + 1, hi)
                return ids[mid]
            }
            dirs[parent].child = tree(0, ids.size)
            sorted.forEachIndexed { i, k -> if (k.data == null) addChildren(ids[i], k.children) }
        }
        addChildren(0, rootChildren)

        // Mini stream: streams shorter than the cutoff, each starting on a 64-byte boundary.
        val miniFat = ArrayList<Int>()
        val mini = ByteArrayOutputStream()
        val big = ArrayList<Dir>()
        for (d in dirs) {
            val data = d.data ?: continue
            d.size = data.size.toLong()
            if (data.isEmpty()) { d.start = ENDOFCHAIN; continue }
            if (data.size < CUTOFF) {
                val n = (data.size + MINI - 1) / MINI
                d.start = miniFat.size
                for (k in 0 until n) miniFat.add(if (k == n - 1) ENDOFCHAIN else miniFat.size + 1)
                mini.write(data); mini.write(ByteArray(n * MINI - data.size))
            } else big.add(d)
        }
        val miniBytes = mini.toByteArray()

        // Regular sectors: big streams, mini stream container, mini FAT, directory, FAT, DIFAT.
        val fat = ArrayList<Int>()
        val body = ByteArrayOutputStream()
        fun chain(bytes: ByteArray): Int {
            if (bytes.isEmpty()) return ENDOFCHAIN
            val first = fat.size
            val n = (bytes.size + SECTOR - 1) / SECTOR
            for (k in 0 until n) fat.add(if (k == n - 1) ENDOFCHAIN else fat.size + 1)
            body.write(bytes); body.write(ByteArray(n * SECTOR - bytes.size))
            return first
        }
        for (d in big) d.start = chain(d.data!!)
        dirs[0].start = if (miniBytes.isEmpty()) ENDOFCHAIN else chain(miniBytes)
        dirs[0].size = miniBytes.size.toLong()
        val miniFatBytes = ints(miniFat, padTo = SECTOR)
        val firstMiniFat = if (miniFat.isEmpty()) ENDOFCHAIN else chain(miniFatBytes)
        val dirBytes = ByteArrayOutputStream()
        for (d in dirs) dirBytes.write(entry(d))
        repeat((4 - dirs.size % 4) % 4) { dirBytes.write(entry(null)) }
        val firstDir = chain(dirBytes.toByteArray())

        var nFat = 0; var nDifat = 0
        while (true) {
            val total = fat.size + nFat + nDifat
            val needFat = (total + 127) / 128
            val needDifat = if (needFat <= 109) 0 else (needFat - 109 + 126) / 127
            if (needFat == nFat && needDifat == nDifat) break
            nFat = needFat; nDifat = needDifat
        }
        val fatStart = fat.size
        repeat(nFat) { fat.add(FATSECT) }
        val difatStart = fat.size
        repeat(nDifat) { fat.add(DIFSECT) }
        val fatSectors = (0 until nFat).map { fatStart + it }
        body.write(ints(fat, padTo = nFat * SECTOR))
        for (k in 0 until nDifat) { // 127 FAT sector numbers, then the next DIFAT sector
            val slice = fatSectors.drop(109 + k * 127).take(127)
            val ints = ArrayList(slice); while (ints.size < 127) ints.add(FREESECT)
            ints.add(if (k == nDifat - 1) ENDOFCHAIN else difatStart + k + 1)
            body.write(ints(ints, padTo = SECTOR))
        }

        val header = ByteBuffer.allocate(SECTOR).order(ByteOrder.LITTLE_ENDIAN)
        header.put(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
        header.put(ByteArray(16))                       // CLSID
        header.putShort(0x003E); header.putShort(3)     // minor, major version
        header.putShort(0xFFFE.toShort()); header.putShort(9); header.putShort(6) // byte order, sector shift, mini shift
        header.put(ByteArray(6))                        // reserved
        header.putInt(0); header.putInt(nFat); header.putInt(firstDir); header.putInt(0) // dir sectors (v3: 0), FAT count, first dir, transaction
        header.putInt(CUTOFF); header.putInt(firstMiniFat); header.putInt((miniFatBytes.size / SECTOR).takeIf { miniFat.isNotEmpty() } ?: 0)
        header.putInt(if (nDifat == 0) ENDOFCHAIN else difatStart); header.putInt(nDifat)
        for (k in 0 until 109) header.putInt(fatSectors.getOrElse(k) { FREESECT })
        return header.array() + body.toByteArray()
    }

    private fun entry(d: Dir?): ByteArray {
        val b = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        if (d == null) { b.position(68); b.putInt(FREESECT); b.putInt(FREESECT); b.putInt(FREESECT); return b.array() }
        val name = d.name.toByteArray(Charsets.UTF_16LE)
        b.put(name); b.position(64); b.putShort((name.size + 2).toShort())
        b.put(d.type.toByte()); b.put(1)                // object type, colour black
        b.putInt(d.left); b.putInt(d.right); b.putInt(d.child)
        b.position(116)                                 // CLSID, state bits, times: zero
        b.putInt(if (d.type == 1) 0 else d.start); b.putLong(if (d.type == 1) 0 else d.size)
        return b.array()
    }

    private fun ints(v: List<Int>, padTo: Int): ByteArray {
        val size = maxOf(padTo, (v.size * 4 + SECTOR - 1) / SECTOR * SECTOR)
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putInt(it) }
        while (b.hasRemaining()) b.putInt(FREESECT)
        return b.array()
    }
}
