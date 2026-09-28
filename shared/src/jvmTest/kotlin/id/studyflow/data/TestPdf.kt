package id.studyflow.data

import java.io.ByteArrayOutputStream

/** PDF teks minimal (ASCII, Helvetica) untuk tes unggah. Setara dengan backend/tests/pdf_factory.py. */
fun testPdf(pages: List<String>): ByteArray {
    val objects = mutableListOf<String>()
    fun add(body: String): Int {
        objects += body
        return objects.size
    }
    val catalogId = add("")
    val pagesId = add("")
    val fontId = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    val pageIds = pages.map { text ->
        val lines = text.chunked(90).map { it.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") }
        val stream = "BT /F1 11 Tf 14 TL 40 800 Td " + lines.joinToString(" ") { "($it) Tj T*" } + " ET"
        val contentId = add("<< /Length ${stream.length} >>\nstream\n$stream\nendstream")
        add("<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 $fontId 0 R >> >> /Contents $contentId 0 R >>")
    }
    objects[pagesId - 1] = "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>"
    objects[catalogId - 1] = "<< /Type /Catalog /Pages $pagesId 0 R >>"

    val out = ByteArrayOutputStream()
    fun write(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))
    write("%PDF-1.4\n")
    val offsets = objects.mapIndexed { index, body ->
        val offset = out.size()
        write("${index + 1} 0 obj\n$body\nendobj\n")
        offset
    }
    val xref = out.size()
    write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
    offsets.forEach { write("%010d 00000 n \n".format(it)) }
    write("trailer\n<< /Size ${objects.size + 1} /Root $catalogId 0 R >>\nstartxref\n$xref\n%%EOF\n")
    return out.toByteArray()
}
