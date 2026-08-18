/**
 * Text utilities shared by TTS reading (sprint D) and speed reading RSVP.
 * - normalize: collapse whitespace, strip control chars
 * - splitSentences: split into sentences keeping trailing punctuation
 * - splitTokens: CJK-aware tokenizer for RSVP word flashes
 */

export class TextUtils {
  /** Collapse whitespace runs into single spaces and strip control chars. */
  static normalize(text: string): string {
    let out = '';
    let pendingSpace = false;
    for (let i = 0; i < text.length; i++) {
      const code = text.charCodeAt(i);
      if (code === 10 || code === 13 || code === 9 || code === 32) {
        pendingSpace = true;
      } else if (code < 32) {
        /* strip other control characters */
      } else {
        if (pendingSpace && out.length > 0) {
          out += ' ';
        }
        pendingSpace = false;
        out += text.charAt(i);
      }
    }
    return out;
  }

  /** Split text into sentences, keeping the trailing punctuation. */
  static splitSentences(text: string): string[] {
    const sentences: string[] = [];
    let buf = '';
    for (let i = 0; i < text.length; i++) {
      const ch = text.charAt(i);
      buf += ch;
      const code = text.charCodeAt(i);
      if (code === 0x3002 || code === 0xff01 || code === 0xff1f || /* 。！？ */
        code === 0x2026 || code === 0xff1b ||                          /* …； */
        code === 0x002e || code === 0x0021 || code === 0x003f ||       /* .!? */
        code === 0x003b) {                                             /* ; */
        const s = buf.trim();
        if (s.length > 0) {
          sentences.push(s);
        }
        buf = '';
      }
    }
    const tail = buf.trim();
    if (tail.length > 0) {
      sentences.push(tail);
    }
    return sentences;
  }

  /**
   * CJK-aware tokenizer: one RSVP flash per CJK ideograph (leading CJK
   * punctuation attaches to it) and one flash per Latin word.
   */
  static splitTokens(text: string): string[] {
    const tokens: string[] = [];
    let word = '';
    const flushWord = (): void => {
      const w = word.trim();
      if (w.length > 0) {
        tokens.push(w);
      }
      word = '';
    };
    for (let i = 0; i < text.length; i++) {
      const code = text.charCodeAt(i);
      const ch = text.charAt(i);
      if (code >= 0x4e00 && code <= 0x9fff) {
        /* CJK ideograph: attach pending CJK punctuation, then emit */
        if (word.length > 0 && TextUtils.isCjkPunct(word.charCodeAt(0))) {
          tokens.push(word + ch);
          word = '';
        } else {
          flushWord();
          tokens.push(ch);
        }
      } else if (TextUtils.isCjkPunct(code)) {
        /* hold punctuation to attach to the next ideograph */
        word += ch;
      } else if (code === 32 || code === 9 || code === 10 || code === 13) {
        flushWord();
      } else {
        word += ch;
      }
    }
    flushWord();
    return tokens;
  }

  private static isCjkPunct(code: number): boolean {
    return (code >= 0x3000 && code <= 0x303f) ||
      (code >= 0xff00 && code <= 0xffef) ||
      code === 0x2018 || code === 0x2019 ||
      code === 0x201c || code === 0x201d;
  }
}
