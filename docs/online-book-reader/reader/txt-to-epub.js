import { ZipWriter, BlobWriter, TextReader, configure } from './foliate/vendor/zip-writer.js'

// Convert a plain-text book (File/Blob/URL) into a minimal in-memory EPUB
// so foliate-js can paginate it. Chapters are split on 第N章/节/回 headings.
// Writing uses the same zip.js library foliate reads with, for compatibility.

configure({ useWebWorkers: false })

const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

function splitChapters(text) {
  const lines = text.replace(/\r\n?/g, '\n').split('\n')
  const chapters = []
  let cur = { title: '开始', lines: [] }
  const heading = /^\s*(第[一二三四五六七八九十百千0-9零〇两]+[章节回卷部篇][^\n]{0,40}|序章|楔子|前言|后记|尾声|番外[^\n]{0,30})\s*$/
  for (const line of lines) {
    if (heading.test(line)) {
      if (cur.lines.length) chapters.push(cur)
      cur = { title: line.trim(), lines: [] }
    } else {
      cur.lines.push(line)
    }
  }
  if (cur.lines.length || chapters.length === 0) chapters.push(cur)
  return chapters
}

const xhtml = (title, body) =>
  '<?xml version="1.0" encoding="utf-8"?>'
  + '<html xmlns="http://www.w3.org/1999/xhtml"><head><meta charset="utf-8"><title>' + esc(title) + '</title>'
  + '<style>body{margin:1em;line-height:1.7}h2{margin:1.2em 0 .8em}</style></head>'
  + '<body><h2>' + esc(title) + '</h2>' + body + '</body></html>'

export async function txtToEpubBlob(source) {
  const text = typeof source === 'string'
    ? await (await fetch(source)).text()
    : await source.text()

  const chapters = splitChapters(text)
  const manifest = [], spine = [], navLis = []
  const contentFiles = []

  chapters.forEach((ch, i) => {
    const id = 'ch' + (i + 1)
    const href = id + '.xhtml'
    const body = ch.lines.map(l => l.trim()
      ? '<p>' + esc(l) + '</p>'
      : '').join('\n') || '<p>&#160;</p>'
    contentFiles.push(['OEBPS/' + href, xhtml(ch.title, body)])
    manifest.push('<item id="' + id + '" href="' + href + '" media-type="application/xhtml+xml"/>')
    spine.push('<itemref idref="' + id + '"/>')
    navLis.push('<li><a href="' + href + '">' + esc(ch.title) + '</a></li>')
  })

  const containerXml = '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>'
  const opf = '<?xml version="1.0" encoding="utf-8"?>'
    + '<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bid">'
    + '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">'
    + '<dc:identifier id="bid">urn:uuid:howread-txt-reader</dc:identifier>'
    + '<dc:title>Text book</dc:title>'
    + '<dc:language>zh</dc:language><meta property="dcterms:modified">2026-01-01T00:00:00Z</meta>'
    + '</metadata>'
    + '<manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
    + manifest.join('') + '</manifest>'
    + '<spine>' + spine.join('') + '</spine></package>'
  const nav = '<?xml version="1.0" encoding="utf-8"?>'
    + '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">'
    + '<head><title>nav</title></head><body><nav epub:type="toc"><ol>' + navLis.join('') + '</ol></nav></body></html>'

  const blobPromise = new Promise((resolve, reject) => {
    const writer = new ZipWriter(new BlobWriter('application/epub+zip'))
    const entries = [
      ['mimetype', 'application/epub+zip', { level: 0 }],
      ['META-INF/container.xml', containerXml],
      ['OEBPS/content.opf', opf],
      ['OEBPS/nav.xhtml', nav],
      ...contentFiles,
    ]
    // TextReader (not a stream) so zip.js records uncompressedSize —
    // foliate uses it for section sizes; streamed input leaves it undefined (→ blank pages)
    const adds = entries.map(([name, content, options]) =>
      writer.add(name, new TextReader(content), options).catch(reject))
    Promise.all(adds).then(() => writer.close()).then(resolve).catch(reject)
  })
  const blob = await blobPromise
  // foliate inspects file.name for format detection — plain Blobs have none
  const srcName = typeof source === 'string'
    ? decodeURIComponent((source.split('/').pop() || '').split('?')[0])
    : (source.name || 'book.txt')
  blob.name = srcName.replace(/\.[^.]+$/, '') + '.epub'
  return blob
}
