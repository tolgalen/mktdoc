package com.tolgalen.mktdoc.pdf

import android.content.Context
import android.net.Uri
import com.tolgalen.mktdoc.data.ASSET_PREFIX
import com.tolgalen.mktdoc.data.CHANGELOG
import com.tolgalen.mktdoc.data.CrawlOptions
import com.tolgalen.mktdoc.data.Crawler
import com.tolgalen.mktdoc.data.Node
import com.tolgalen.mktdoc.data.PageMeta
import com.tolgalen.mktdoc.data.Reporter
import com.tolgalen.mktdoc.data.Store
import com.tolgalen.mktdoc.data.Structure
import com.tolgalen.mktdoc.data.esc
import com.tolgalen.mktdoc.data.normPath
import com.tolgalen.mktdoc.net.Http
import com.tolgalen.mktdoc.data.writeAtomic
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** Where each generated PDF is written. */
fun interface PdfSink { fun open(fileName: String): OutputStream }

class PdfGroup(val title: String, val fileName: String, val roots: List<Node>)

/**
 * Exports the online version to PDF: downloads the chosen pages (cached), assembles them into HTML blocks,
 * prints them with the WebView and merges the blocks, adding a cover, a contents page with page numbers,
 * bookmarks and a footer with the section and the page number.
 */
class PdfExporter(
    private val ctx: Context, private val st: Structure, private val opts: PdfOptions, private val rep: Reporter,
    /** The offline copy to print from. When null the pages are downloaded from the website (and cached). */
    private val localStore: Store? = null,
) {
    private val label = st.version.label
    private val work = File(ctx.filesDir, "pdfcache/$label")
    private val store = localStore ?: Store(work, File(work, "assets"))
    /** Where optimized images are written (never inside the offline copy). */
    private val optRoot = if (localStore != null) File(work, "assets-opt") else store.assets
    private val printer = PdfPrinter(ctx)

    private fun s(es: String, en: String) = if (opts.lang == "es") es else en

    private fun groups(selected: List<Node>) = groupsFor(label, opts.layout, selected)

    suspend fun export(selected: List<Node>, sink: PdfSink) {
        PDFBoxResourceLoader.init(ctx)
        val groups = groups(selected)
        val roots = groups.flatMap { it.roots }
        // 1. pages: from the offline copy, or downloaded (cached for 7 days)
        if (localStore == null) {
            store.load()
            rep.log(s("Descargando las páginas…", "Downloading pages…"))
            val res = Crawler(st, roots, store, store, CrawlOptions(maxAgeSec = 7 * 86400), rep.log, progress = { d, t -> rep.progress("pages", d, t) }).run()
            store.manifest.putAll(res.pages); store.save()
            if (res.failed.isNotEmpty()) rep.log(s("${res.failed.size} páginas no se pudieron descargar y se omiten.", "${res.failed.size} pages could not be downloaded and are skipped."))
        } else rep.log(s("Usando la copia sin conexión.", "Using the offline copy."))
        // 2. images
        rep.progress("images", 0, 0)
        val imgMap = withContext(Dispatchers.IO) { ImageOptimizer.optimize(store.assets, opts.imageQuality, optRoot) { false } }
        val imgDir = ImageOptimizer.dirName(opts.imageQuality)
        // 3. one PDF per group
        groups.forEachIndexed { gi, g ->
            coroutineContext.ensureActive()
            rep.log(s("Generando ${g.fileName}…", "Building ${g.fileName}…"))
            val tmp = File(work, "out-$gi.pdf")
            buildGroup(g, gi, groups.size, imgMap, imgDir, tmp)
            withContext(Dispatchers.IO) { sink.open(g.fileName).use { o -> tmp.inputStream().use { it.copyTo(o) } }; tmp.delete() }
        }
        withContext(Dispatchers.IO) { File(work, "blocks").deleteRecursively() }
    }

    // ------------------------------------------------------------------ HTML
    private class Entry(val node: Node, val meta: PageMeta, val depth: Int, val brk: Boolean, val section: String)

    private fun entriesOf(g: PdfGroup): List<Entry> {
        val out = ArrayList<Entry>()
        var lastTop: Node? = null; var lastSub: Node? = null
        for (root in g.roots) {
            val rootDepth = root.depth()
            for (n in root.pages()) {
                val meta = store.manifest[n.key] ?: continue
                val chain = n.ancestors() + n
                val top = chain.first(); val sub = chain.getOrNull(1) ?: n
                val first = out.isEmpty()
                val brk = !first && when (opts.breaks) {
                    PageBreaks.CONTINUOUS -> false
                    PageBreaks.SECTION -> top !== lastTop
                    PageBreaks.SUBSECTION -> top !== lastTop || sub !== lastSub
                    PageBreaks.PAGE -> true
                }
                lastTop = top; lastSub = sub
                out.add(Entry(n, meta, n.depth() - rootDepth, brk, top.title.ifEmpty { g.title }))
            }
        }
        return out
    }

    private fun fileUrl(f: File) = "file://" + f.absolutePath

    private fun pageBody(e: Entry, idx: Int, imgMap: Map<String, String>, imgDir: String): String {
        var html = store.readRaw(e.meta)
        html = Regex("""@ASSETS@/img/([^"'\s)]+)""").replace(html) { m ->
            val name = m.groupValues[1]
            val mapped = imgMap[name]
            fileUrl(if (mapped != null) File(optRoot, "$imgDir/$mapped") else File(store.assets, "img/$name"))
        }
        html = html.replace(ASSET_PREFIX, fileUrl(store.assets) + "/")
        val cls = "mk-page" + if (e.brk) " mk-break" else ""
        // The marker lives inside the first heading, so it moves to the next sheet together with it when the
        // heading does not fit at the bottom of one (a separate block would be left behind: off-by-one in the contents).
        val marker = "<span class=\"mk-self\">MKP${idx}X</span>"
        val h1 = Regex("<h1\\b[^>]*>").find(html)
        return if (h1 != null) "<section class=\"$cls\">" + html.substring(0, h1.range.last + 1) + marker + html.substring(h1.range.last + 1) + "</section>"
        else "<section class=\"$cls\">${marker.replace("mk-self", "mk-self mk-blk")}$html</section>"
    }

    private fun docHtml(body: String, newSheet: Boolean): String =
        "<!doctype html><html data-theme=\"light\"><head><meta charset=\"utf-8\"><link rel=\"stylesheet\" href=\"${fileUrl(File(store.assets, "site.css"))}\">" +
            "<style>${pdfCss(opts.textSize.zoom)}</style></head><body class=\"mk-pdf${if (newSheet) " mk-newsheet" else ""}\"><div class=\"mk-wrap\">$body</div></body></html>"

    private fun coverHtml(g: PdfGroup): String {
        val title = opts.coverTitle.ifBlank { g.title }
        val date = DateFormat.getDateInstance(DateFormat.LONG, Locale(opts.lang)).format(Date())
        val img = opts.coverImage?.let { copyCoverImage(it) }
        val banner = if (img != null) "<img class=\"cv-img\" src=\"${fileUrl(img)}\">"
        else "<div class=\"cv-banner\"><span>MikroTik</span> RouterOS</div>"
        return "<div class=\"cover\">$banner<h1>${esc(title)}</h1><h2>${if (label == CHANGELOG) "" else esc(s("Versión", "Version")) + " " + label}</h2>" +
            "<p>${esc(s("Copia en PDF de manual.mikrotik.com", "PDF copy of manual.mikrotik.com"))}<br>${esc(s("Impreso el", "Printed on"))} ${esc(date)}</p></div>"
    }

    private fun copyCoverImage(uri: Uri): File? = try {
        val f = File(work, "cover-image")
        ctx.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } }
        f.takeIf { it.length() > 0 }
    } catch (_: Exception) { null }

    private fun tocHtml(entries: List<Entry>, numbers: List<String>): String {
        val sb = StringBuilder("<div class=\"mk-toc\"><h1>${esc(s("Contenido", "Contents"))}</h1><ul>")
        entries.forEachIndexed { i, e ->
            sb.append("<li style=\"margin-left:${e.depth.coerceAtMost(6) * 12}px\"><span class=\"t\">${esc(e.meta.title)}</span><span class=\"n\">${numbers[i]}</span></li>")
        }
        return sb.append("</ul></div>").toString()
    }

    // ------------------------------------------------------------------ one PDF
    private suspend fun buildGroup(g: PdfGroup, gi: Int, gn: Int, imgMap: Map<String, String>, imgDir: String, outFile: File) {
        val entries = entriesOf(g)
        if (entries.isEmpty()) throw IllegalStateException(s("No hay páginas descargadas para ${g.title}", "No pages downloaded for ${g.title}"))
        val blocksDir = File(work, "blocks/$gi").also { it.deleteRecursively(); it.mkdirs() }
        val newSheet = false

        // blocks: they start at every sheet break and are split by size
        val blocks = ArrayList<MutableList<Int>>()
        for (i in entries.indices) {
            if (blocks.isEmpty() || entries[i].brk || blocks.last().size >= MAX_PAGES_PER_BLOCK) blocks.add(ArrayList())
            blocks.last().add(i)
        }

        // cover
        val coverFile = File(blocksDir, "cover.pdf")
        withContext(Dispatchers.IO) { writeAtomic(File(blocksDir, "cover.html"), docHtml(coverHtml(g), false)) }
        printer.print(File(blocksDir, "cover.html"), coverFile)

        // content blocks
        val starts = IntArray(entries.size)               // sheet (within the content, 0-based) where each page starts
        val blockFiles = ArrayList<File>()
        var sheets = 0
        blocks.forEachIndexed { bi, idxs ->
            coroutineContext.ensureActive()
            rep.progress("print", gi * 1000 + bi, gn * 1000 + blocks.size)
            rep.log(s("Imprimiendo bloque ${bi + 1}/${blocks.size} (${g.fileName})", "Printing block ${bi + 1}/${blocks.size} (${g.fileName})"))
            val html = withContext(Dispatchers.IO) { docHtml(idxs.joinToString("") { pageBody(entries[it], it, imgMap, imgDir) }, newSheet) }
            val hf = File(blocksDir, "b$bi.html"); val pf = File(blocksDir, "b$bi.pdf")
            withContext(Dispatchers.IO) { writeAtomic(hf, html) }
            printer.print(hf, pf)
            withContext(Dispatchers.IO) { hf.delete() }
            val found = withContext(Dispatchers.IO) { locateMarkers(pf, idxs) }
            val count = found.second
            idxs.forEach { starts[it] = sheets + (found.first[it] ?: 0) }
            sheets += count
            blockFiles.add(pf)
        }

        // contents: first pass with placeholder numbers to learn how many sheets it takes
        val coverSheets = pageCount(coverFile)
        suspend fun printToc(numbers: List<String>): Pair<File, Int> {
            val hf = File(blocksDir, "toc.html"); val pf = File(blocksDir, "toc.pdf")
            withContext(Dispatchers.IO) { writeAtomic(hf, docHtml(tocHtml(entries, numbers), false)) }
            printer.print(hf, pf)
            return pf to pageCount(pf)
        }
        var (tocFile, tocSheets) = printToc(List(entries.size) { "0000" })
        for (attempt in 0 until 2) {
            val nums = entries.indices.map { (coverSheets + tocSheets + starts[it] + 1).toString().padStart(4, '\u2007') }
            val (f, n) = printToc(nums)
            tocFile = f
            if (n == tocSheets) break
            tocSheets = n
        }

        // merge + bookmarks + footer
        rep.progress("merge", 0, 0)
        rep.log(s("Uniendo y numerando páginas…", "Merging and numbering pages…"))
        withContext(Dispatchers.IO) {
            val merged = File(blocksDir, "merged.pdf")
            PDFMergerUtility().apply {
                addSource(coverFile); addSource(tocFile); blockFiles.forEach { addSource(it) }
                destinationFileName = merged.absolutePath
            }.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
            decorate(merged, outFile, entries, starts, coverSheets + tocSheets)
            merged.delete()
        }
    }

    private fun pageCount(f: File): Int = PDDocument.load(f).use { it.numberOfPages }

    /** Sheet (0-based, within the block) where each page's marker appears, and the block's total sheets. */
    private fun locateMarkers(pdf: File, idxs: List<Int>): Pair<Map<Int, Int>, Int> {
        val found = HashMap<Int, Int>()
        PDDocument.load(pdf, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val n = doc.numberOfPages
            if (idxs.size > 1) {
                val re = Regex("""M\s*K\s*P\s*(\d+)\s*X""")
                val stripper = PDFTextStripper()
                for (p in 1..n) {
                    stripper.startPage = p; stripper.endPage = p
                    for (m in re.findAll(stripper.getText(doc))) {
                        val id = m.groupValues[1].toIntOrNull() ?: continue
                        if (id !in found) found[id] = p - 1
                    }
                    if (found.size == idxs.size) break
                }
            }
            return found to n
        }
    }

    /** Index of the exported page a website link points to (null if it is not part of this PDF). */
    private fun internalTarget(uri: String, byRel: Map<String, Int>): Int? = linkTarget(uri, st.version.prefix, byRel)

    private fun latin(sIn: String): String = sIn.filter { it.code in 32..126 || it.code in 161..255 }

    private fun decorate(src: File, dst: File, entries: List<Entry>, starts: IntArray, frontSheets: Int) {
        PDDocument.load(src, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val total = doc.numberOfPages
            // section of each content sheet
            val sectionOf = arrayOfNulls<String>(total)
            var ei = 0
            for (sheet in 0 until total - frontSheets) {
                while (ei + 1 < entries.size && starts[ei + 1] <= sheet) ei++
                sectionOf[frontSheets + sheet] = entries[ei].section
            }
            val font = PDType1Font.HELVETICA
            for (pi in 1 until total) {
                val page = doc.getPage(pi)
                val box = page.mediaBox ?: PDRectangle.A4
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    cs.setNonStrokingColor(0.35f)
                    cs.beginText(); cs.setFont(font, 8f)
                    val num = (pi + 1).toString()
                    cs.newLineAtOffset(box.upperRightX - 42f - font.getStringWidth(num) / 1000f * 8f, 28f)
                    cs.showText(num); cs.endText()
                    val sec = sectionOf[pi]
                    if (sec != null && pi >= frontSheets) {
                        cs.beginText(); cs.setFont(font, 8f); cs.newLineAtOffset(42f, 28f)
                        cs.showText(latin(sec).take(80)); cs.endText()
                    }
                }
            }
            // links to other exported pages jump inside the PDF instead of opening the website
            val byRel = HashMap<String, Int>()
            entries.forEachIndexed { i, e -> e.node.key?.let { byRel[it.removePrefix(st.version.prefix).ifEmpty { "/" }] = i } }
            var internal = 0
            for (pi in frontSheets until total) {
                val links = try { doc.getPage(pi).annotations.filterIsInstance<PDAnnotationLink>() } catch (_: Exception) { continue }
                for (link in links) {
                    val uri = (link.action as? PDActionURI)?.uri ?: continue
                    val idx = internalTarget(uri, byRel) ?: continue
                    link.action = PDActionGoTo().also { g ->
                        g.destination = PDPageFitDestination().also { it.page = doc.getPage((frontSheets + starts[idx]).coerceIn(0, total - 1)) }
                    }
                    internal++
                }
            }
            rep.log(s("$internal enlaces internos.", "$internal internal links."))
            // hierarchical bookmarks
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            val stack = ArrayList<Pair<Int, PDOutlineItem>>()
            entries.forEachIndexed { i, e ->
                val item = PDOutlineItem()
                item.title = e.meta.title
                item.destination = PDPageFitDestination().also { it.page = doc.getPage((frontSheets + starts[i]).coerceIn(0, total - 1)) }
                while (stack.isNotEmpty() && stack.last().first >= e.depth) stack.removeAt(stack.size - 1)
                if (stack.isEmpty()) outline.addLast(item) else stack.last().second.addLast(item)
                stack.add(e.depth to item)
            }
            outline.openNode()
            doc.save(dst)
        }
    }

    companion object {
        const val MAX_PAGES_PER_BLOCK = 80

        /**
         * Index of the exported page a website link points to, or null when it is external or not part of the PDF.
         * [byRel] maps each exported page's path (relative to the version [prefix]) to its index.
         */
        fun linkTarget(uri: String, prefix: String, byRel: Map<String, Int>): Int? {
            val u = try { java.net.URI(uri) } catch (_: Exception) { return null }
            if (u.host != Http.HOST) return null
            val path = normPath(u.rawPath ?: return null)
            for (p in listOf(prefix, "/docs")) if (path == p || path.startsWith("$p/")) return byRel[path.removePrefix(p).ifEmpty { "/" }]
            return null
        }

        /** Drops the selected sections that are already inside another selected one. */
        fun normalize(selected: List<Node>): List<Node> =
            selected.filter { n -> n.ancestors().none { a -> selected.any { it === a } } }

        private fun slug(t: String) = t.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').lowercase().ifEmpty { "x" }

        /** A single file with everything, or one file per chosen section. */
        fun groupsFor(label: String, layout: PdfLayout, selected: List<Node>): List<PdfGroup> {
            val sel = normalize(selected)
            val base = if (label == CHANGELOG) "MikroTik-docs-changelog" else "MikroTik-RouterOS-$label"
            val title = if (label == CHANGELOG) "MikroTik documentation changelog" else "MikroTik RouterOS $label"
            return if (layout == PdfLayout.SINGLE) listOf(PdfGroup(title, "$base.pdf", sel))
            else sel.map { PdfGroup(it.title, "$base-${slug(it.title)}.pdf", listOf(it)) }
        }

        fun pdfCss(zoom: Double): String = """
@page{size:A4;margin:18mm 15mm 22mm 15mm}
body.mk-pdf{background:#fff;color:#1c1e21;margin:0;font-family:sans-serif;line-height:1.45}
.mk-pdf .mk-wrap{max-width:none;padding:0;margin:0}
.mk-pdf .mk-page{zoom:$zoom;overflow-x:clip}
.mk-pdf .mk-page+.mk-page{margin-top:1.3em;padding-top:1em;border-top:1px solid #c9ccd1}
.mk-pdf .mk-page p,.mk-pdf .mk-page ul,.mk-pdf .mk-page ol,.mk-pdf .mk-page table,.mk-pdf .mk-page pre,.mk-pdf .mk-page .admonition,.mk-pdf .mk-page blockquote{margin-bottom:.6rem!important}
.mk-pdf .mk-page h1{margin:.2rem 0 .5rem!important}
.mk-pdf .mk-page h2{margin:1rem 0 .4rem!important}
.mk-pdf .mk-page h3,.mk-pdf .mk-page h4{margin:.75rem 0 .3rem!important}
.mk-pdf .mk-page li{margin-top:.15rem}
.mk-pdf .mk-page li>p{margin-bottom:.2rem!important}
.mk-pdf .mk-page .admonition{padding:.45rem .8rem}
.mk-pdf .mk-page td,.mk-pdf .mk-page th{padding:.3rem .5rem}
.mk-pdf .mk-page hr{margin:.5rem 0}
.mk-pdf .mk-page img{max-height:195mm;object-fit:contain}
.mk-pdf *{box-shadow:none!important;text-shadow:none!important}
.mk-pdf .mk-page *{max-width:100%;box-sizing:border-box}
.mk-pdf pre,.mk-pdf pre code,.mk-pdf [class*=codeBlock],.mk-pdf .token-line,.mk-pdf .token{white-space:break-spaces!important;overflow:visible!important;overflow-wrap:anywhere;word-break:break-word}
.mk-pdf .row{margin-left:0!important;margin-right:0!important}
.mk-pdf .row>.col{padding-left:0!important;padding-right:0!important}
.mk-pdf table{display:table;table-layout:auto;width:100%}
.mk-pdf td,.mk-pdf th{overflow-wrap:break-word;word-break:normal}
.mk-pdf a,.mk-pdf code{overflow-wrap:anywhere}
.mk-pdf img,.mk-pdf svg,.mk-pdf video{max-width:100%;height:auto}
.mk-self{display:inline-block;width:0;white-space:nowrap;font-size:1px;line-height:1px;color:#fefefe}
.mk-self.mk-blk{display:block;width:auto;height:2px;overflow:hidden;break-after:avoid}
.mk-pdf .mk-page.mk-break{break-before:page;margin-top:0!important;padding-top:0!important;border-top:none!important}
.mk-pdf .mk-page:first-child{break-before:auto}
.mk-pdf h1,.mk-pdf h2,.mk-pdf h3,.mk-pdf h4{break-after:avoid}
.mk-pdf tr,.mk-pdf img{break-inside:avoid}
.mk-toc ul{list-style:none;padding:0;line-height:1.35;font-size:10pt;margin:0}
.mk-toc li{display:flex;gap:6px;margin:1px 0}
.mk-toc .t{flex:1;min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}
.mk-toc .n{white-space:pre;font-variant-numeric:tabular-nums}
.cover{text-align:center;padding-top:60mm}
.cover h1{font-size:30pt;margin:30mm 0 6mm}
.cover h2{font-size:18pt;font-weight:400;color:#444;margin:0 0 20mm}
.cover p{color:#666;font-size:11pt}
.cv-banner{display:inline-block;background:#293239;color:#fff;font-size:26pt;padding:14mm 20mm;border-radius:6mm;letter-spacing:1px}
.cv-banner span{color:#eb5f27;font-weight:700}
.cv-img{max-width:150mm;max-height:80mm}
"""
    }
}
