package id.studyflow.platform

/** SHA-256 dari platform (Android dan desktop sama-sama JVM: java.security.MessageDigest). */
expect fun sha256(data: ByteArray): ByteArray
